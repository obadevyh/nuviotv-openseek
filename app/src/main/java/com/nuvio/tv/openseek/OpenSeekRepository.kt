package com.nuvio.tv.openseek

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private const val TAG = "OpenSeek"

/** What the settings screen shows. Secrets (token, pin) never leave [OpenSeekRepository]. */
data class OpenSeekSettings(
    val enabled: Boolean = false,
    val address: String = "",
    val paired: Boolean = false,
    val role: String = "owner",            // owner | guest
    val deviceName: String = "",
)

enum class OpenSeekConnection { Unknown, Checking, Online, Offline, Revoked, PinChanged }

enum class PairResult { Paired, BadInput, NoConnection, PinMismatch, Refused, Closed, RateLimited, ServerProofFailed }

/** State of the preview session of the video that is playing. */
data class OpenSeekSession(
    val active: Boolean = false,
    val ready: Boolean = false,        // at least one thumbnail is available
    val label: String = "",            // short honest caption, e.g. "Shared thumbnails (approximate)"
    val cues: Int = 0,
)

/**
 * OpenSeek client: pairing, connection status and the preview session of the video being played.
 * Everything here is best-effort: any failure leaves playback untouched and simply yields no thumbnail.
 */
@Singleton
class OpenSeekRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("openseek_client", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = "application/json".toMediaType()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<OpenSeekSettings> = _settings.asStateFlow()
    private val _connection = MutableStateFlow(OpenSeekConnection.Unknown)
    val connection: StateFlow<OpenSeekConnection> = _connection.asStateFlow()
    private val _session = MutableStateFlow(OpenSeekSession())
    val session: StateFlow<OpenSeekSession> = _session.asStateFlow()

    // ---- configuration --------------------------------------------------------------------------

    private fun readSettings() = OpenSeekSettings(
        enabled = prefs.getBoolean("enabled", false),
        address = prefs.getString("base", "") ?: "",
        paired = !prefs.getString("token", "").isNullOrEmpty() && !prefs.getString("pin", "").isNullOrEmpty(),
        role = prefs.getString("role", "owner") ?: "owner",
        deviceName = prefs.getString("device_name", "") ?: "",
    )

    private fun base() = prefs.getString("base", "") ?: ""
    private fun pin() = prefs.getString("pin", "") ?: ""
    private fun token() = prefs.getString("token", "") ?: ""

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        _settings.value = readSettings()
        if (!on) stopSession()
    }

    /** Sets the server address. A different address forgets the pairing (the pin belongs to the old server). */
    fun setAddress(input: String): Boolean {
        val b = OpenSeekProtocol.normalizeAddress(input) ?: return false
        if (b != base()) {
            prefs.edit().putString("base", b).remove("token").remove("pin").remove("device_id").putString("role", "owner").apply()
            _connection.value = OpenSeekConnection.Unknown
        }
        _settings.value = readSettings()
        return true
    }

    fun forget() {
        stopSession()
        val b = base()
        if (b.isNotEmpty() && token().isNotEmpty()) {
            // Best-effort self-revoke so the owner's device list stays tidy; the local pairing is dropped either way.
            val base = b; val pin = pin(); val token = token()
            scope.launch { runCatching { call("DELETE", "$base/v1/pair", token, pin, null, 5) } }
        }
        prefs.edit().remove("token").remove("pin").remove("device_id").putString("role", "owner").apply()
        _settings.value = readSettings()
        _connection.value = OpenSeekConnection.Unknown
    }

    // ---- HTTP (TLS pinned to the server's SPKI) -------------------------------------------------

    private class Reply(val code: Int, val body: String, val retryAfterS: Long = 0, val bytes: ByteArray? = null)

    private val clientCache = HashMap<String, OkHttpClient>()

    private fun client(pin: String?, readSeconds: Long): OkHttpClient = synchronized(clientCache) {
        clientCache.getOrPut("${pin.orEmpty()}|$readSeconds") {
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    // The server's certificate is self-signed: identity is the pinned public key, not a CA chain.
                    // pin == null only for the very first contact (POST /v1/pair/start), which carries no secret.
                    if (pin == null) return
                    if (chain.isEmpty() || !OpenSeekProtocol.constantTimeEquals(OpenSeekProtocol.spkiPin(chain[0].publicKey.encoded), pin)) {
                        throw java.security.cert.CertificateException("pin mismatch")
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, trust)
                .hostnameVerifier(HostnameVerifier { _, _ -> true })
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(readSeconds, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS)
                .build()
        }
    }

    /** Never throws; code -1 = no connection, -2 = pin mismatch. Never logs URLs, tokens or bodies. */
    private fun call(method: String, url: String, token: String?, pin: String?, body: String?, readSeconds: Long = 10, binary: Boolean = false): Reply {
        return try {
            val rb = Request.Builder().url(url)
            if (!token.isNullOrEmpty()) rb.header("Authorization", "Bearer $token")
            rb.method(method, if (body != null) body.toRequestBody(json) else if (method == "POST" || method == "PUT") "".toRequestBody(json) else null)
            client(pin, readSeconds).newCall(rb.build()).execute().use { r ->
                val retry = r.header("Retry-After")?.toLongOrNull() ?: 0
                if (binary) {
                    val bytes = r.body?.source()?.let { s -> s.request(4L * 1024 * 1024 + 1); if (s.buffer.size > 4L * 1024 * 1024) null else s.readByteArray() }
                    Reply(r.code, "", retry, bytes)
                } else Reply(r.code, r.body?.string()?.take(2_000_000) ?: "", retry)
            }
        } catch (e: javax.net.ssl.SSLException) {
            Reply(if (e.cause is java.security.cert.CertificateException || e.message?.contains("pin") == true) -2 else -1, "")
        } catch (_: Exception) {
            Reply(-1, "")
        }
    }

    // ---- connection status ----------------------------------------------------------------------

    fun checkConnection() {
        val b = base(); if (b.isEmpty()) { _connection.value = OpenSeekConnection.Unknown; return }
        _connection.value = OpenSeekConnection.Checking
        val pin = pin(); val token = token()
        scope.launch {
            val r = if (pin.isNotEmpty() && token.isNotEmpty()) call("GET", "$b/v1/pair/status", token, pin, null, 5)
            else call("GET", "$b/v1/health", null, null, null, 5)
            _connection.value = when {
                r.code == 200 -> OpenSeekConnection.Online
                r.code == 401 || r.code == 403 -> OpenSeekConnection.Revoked
                r.code == -2 -> OpenSeekConnection.PinChanged
                else -> OpenSeekConnection.Offline
            }
            if (r.code == 200 && pin.isNotEmpty()) {
                runCatching { JSONObject(r.body).optString("role").takeIf { it == "guest" || it == "owner" } }.getOrNull()
                    ?.let { prefs.edit().putString("role", it).apply(); _settings.value = readSettings() }
            }
        }
    }

    // ---- pairing (OSP1) -------------------------------------------------------------------------

    /** [input]: the 8-symbol code from the OpenSeek window, an invitation link, or a typed 16-symbol invitation code. */
    suspend fun pair(input: String, deviceName: String = defaultDeviceName()): PairResult = withContext(Dispatchers.IO) {
        val join = OpenSeekProtocol.parseJoinInput(input) ?: return@withContext PairResult.BadInput
        var base = join.base ?: base().takeIf { it.isNotEmpty() } ?: return@withContext PairResult.BadInput
        if (join.base != null && join.base != base()) {
            prefs.edit().putString("base", join.base).remove("token").remove("pin").remove("device_id").apply()
        }
        base = join.base ?: base
        val startBody = if (join.form == OpenSeekProtocol.Form.Invite) JSONObject().put("invite", join.invite).toString() else "{}"
        // First contact carries no secret; an invitation link that includes the pin is pinned from the start.
        val start = call("POST", "$base/v1/pair/start", null, join.pin, startBody, 6)
        when {
            start.code == -2 -> return@withContext PairResult.PinMismatch
            start.code == 429 -> return@withContext PairResult.RateLimited
            start.code == 409 -> return@withContext if (join.form == OpenSeekProtocol.Form.Invite) PairResult.Refused else PairResult.Closed
            start.code != 200 -> return@withContext PairResult.NoConnection
        }
        val o = try { JSONObject(start.body) } catch (_: Exception) { return@withContext PairResult.Refused }
        val pairId = o.optString("pair_id"); val salt = o.optString("salt"); val pin = o.optString("spki_pin"); val iter = o.optInt("iterations")
        if (pairId.length != 32 || salt.length != 32 || !OpenSeekProtocol.pinValid(pin)) return@withContext PairResult.Refused
        if (join.pin != null && join.pin != pin) return@withContext PairResult.PinMismatch
        if (iter < OpenSeekProtocol.MIN_ITERATIONS || iter > OpenSeekProtocol.MAX_ITERATIONS) return@withContext PairResult.Refused
        val k = OpenSeekProtocol.deriveKey(join.secret, pairId, salt, pin, iter)
        val claimBody = JSONObject().put("pair_id", pairId).put("device_name", deviceName)
            .put("client_proof", OpenSeekProtocol.clientProof(k, pairId, deviceName)).toString()
        // From here on only with the pin the start call announced.
        val claim = call("POST", "$base/v1/pair/claim", null, pin, claimBody, 8)
        when {
            claim.code == -2 -> return@withContext PairResult.PinMismatch
            claim.code == 429 -> return@withContext PairResult.RateLimited
            claim.code == 403 -> return@withContext PairResult.Refused
            claim.code != 200 -> return@withContext PairResult.NoConnection
        }
        val c = try { JSONObject(claim.body) } catch (_: Exception) { return@withContext PairResult.Refused }
        val token = c.optString("token"); val deviceId = c.optString("device_id"); val proof = c.optString("server_proof")
        val role = c.optString("role").takeIf { it == "guest" } ?: "owner"
        if (token.length !in 32..60 || !OpenSeekProtocol.constantTimeEquals(OpenSeekProtocol.serverProof(k, pairId, token), proof)) {
            return@withContext PairResult.ServerProofFailed       // the server did not prove it knows the code: store nothing
        }
        prefs.edit().putString("base", base).putString("pin", pin).putString("token", token).putString("device_id", deviceId)
            .putString("role", role).putString("device_name", deviceName).apply()
        _settings.value = readSettings()
        _connection.value = OpenSeekConnection.Online
        PairResult.Paired
    }

    private fun defaultDeviceName(): String {
        val m = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android TV"
        return "Nuvio+OpenSeek $m".take(40)
    }

    // ---- preview session ------------------------------------------------------------------------

    data class PlaybackRequest(
        val url: String,
        val headers: Map<String, String>,
        val durationMs: Long,
        val imdb: String?,
        val season: Int?,
        val episode: Int?,
        val sizeBytes: Long?,
    )

    @Volatile private var cues: List<OpenSeekProtocol.Cue> = emptyList()
    @Volatile private var mapA = 1.0
    @Volatile private var mapB = 0.0
    @Volatile private var sessionBase = ""
    @Volatile private var sessionPin = ""
    @Volatile private var sessionToken = ""
    @Volatile private var routePreviewId = ""
    @Volatile private var routeKind = "previews"
    @Volatile private var sessionJob: Job? = null
    @Volatile private var lastPriorityAt = 0L
    private val sheetBytes = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val frames = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun isUsable(): Boolean = settings.value.enabled && settings.value.paired

    fun stopSession() {
        sessionJob?.cancel(); sessionJob = null
        cues = emptyList(); routePreviewId = ""
        _session.value = OpenSeekSession()
    }

    /** Starts (or restarts) the preview session for the video that is playing. No-op when disabled or unpaired. */
    fun startSession(req: PlaybackRequest) {
        stopSession()
        if (!isUsable()) return
        if (!req.url.startsWith("http://") && !req.url.startsWith("https://")) return      // magnet/p2p/local sources: nothing to send
        sessionBase = base(); sessionPin = pin(); sessionToken = token()
        val guest = settings.value.role == "guest"
        _session.value = OpenSeekSession(active = true)
        sessionJob = scope.launch {
            try {
                if (guest) runGuest(req) else runOwner(req)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "session ended: ${e.javaClass.simpleName}")
            }
        }
    }


    private fun noteHttp(code: Int) {
        _connection.value = when (code) {
            -1 -> OpenSeekConnection.Offline
            -2 -> OpenSeekConnection.PinChanged
            401 -> OpenSeekConnection.Revoked
            else -> if (code in 200..299) OpenSeekConnection.Online else _connection.value
        }
    }

    private suspend fun runOwner(req: PlaybackRequest) {
        var preview = createPreview(req, "previews", "/v1/previews", OpenSeekProtocol.previewBody(req.url, req.headers, req.durationMs, req.imdb, req.season, req.episode, req.sizeBytes, 0), 202)
            ?: return
        pollPreview(req, preview, label = "")
    }

    /** POST that creates or attaches to a job; retries a few times on 429/5xx, gives up quietly otherwise. */
    private suspend fun createPreview(req: PlaybackRequest, kind: String, path: String, body: String, okCode: Int): OpenSeekProtocol.Preview? {
        var attempts = 0
        while (currentCoroutineContext().isActive && attempts < 8) {
            val r = call("POST", sessionBase + path, sessionToken, sessionPin, body, 8)
            noteHttp(r.code)
            if (r.code == 200 || r.code == okCode) {
                val p = OpenSeekProtocol.parsePreview(r.body, sessionBase) ?: return null
                routePreviewId = p.id; routeKind = kind
                return p
            }
            if (r.code == 401 || r.code == 400 || r.code == 403 || r.code == 413 || r.code == -2) return null
            delay(if (r.code == 429) (r.retryAfterS.coerceIn(2, 60)) * 1000 else 5000)
            attempts++
        }
        return null
    }

    private suspend fun pollPreview(req: PlaybackRequest, first: OpenSeekProtocol.Preview, label: String) {
        var p = first
        var appliedVersion = -1L
        var renew = false
        var failures = 0
        while (currentCoroutineContext().isActive) {
            if (p.vttVersion != appliedVersion || cues.isEmpty() || renew) {
                val r = call("GET", p.vttUrl, null, sessionPin, null, 10)
                noteHttp(r.code)
                if (r.code == 200) {
                    val parsed = OpenSeekProtocol.parseVtt(r.body, sessionBase)
                    cues = parsed
                    appliedVersion = p.vttVersion; renew = false; failures = 0
                    Log.i(TAG, "vtt cues=${parsed.size} state=${p.state}")
                    _session.value = OpenSeekSession(active = true, ready = parsed.isNotEmpty(), label = label, cues = parsed.size)
                } else if (r.code == 403) renew = true       // capability expired: ask for a fresh VTT url below
                else if (++failures > 6) return
            }
            if (p.state == OpenSeekProtocol.JobState.Failed) return
            if (p.state == OpenSeekProtocol.JobState.Complete && !renew && cues.isNotEmpty()) {
                // Finished: only keep the capability fresh (they last up to 6 h; renew a few minutes early).
                val left = if (p.exp > 0) p.exp * 1000 - System.currentTimeMillis() - 300_000 else 3_600_000
                delay(left.coerceIn(60_000, 3_600_000))
                renew = true
            } else delay(if (p.state == OpenSeekProtocol.JobState.Preparing) p.refreshAfterMs.coerceAtLeast(1000) else p.refreshAfterMs.coerceAtLeast(2000))
            val r = call("GET", "$sessionBase/v1/$routeKind/${p.id}?wait_version=$appliedVersion&wait_ms=2000", sessionToken, sessionPin, null, 8)
            noteHttp(r.code)
            if (r.code == 200 || r.code == 202) {
                OpenSeekProtocol.parsePreview(r.body, sessionBase)?.let { p = it }
            } else if (r.code == 404 || r.code == 403) {
                val again = createPreview(req, routeKind, "/v1/$routeKind", OpenSeekProtocol.previewBody(req.url, req.headers, req.durationMs, req.imdb, req.season, req.episode, req.sizeBytes, 0), 202) ?: return
                p = again; renew = true
            } else if (r.code == 401 || r.code == -2) return
            else delay(5000)
        }
    }

    private suspend fun runGuest(req: PlaybackRequest) {
        val body = OpenSeekProtocol.lookupBody(req.imdb, req.season, req.episode, req.durationMs, req.sizeBytes) ?: return
        var contributed = false
        while (currentCoroutineContext().isActive) {
            val r = call("POST", "$sessionBase/v1/lookup", sessionToken, sessionPin, body, 8)
            noteHttp(r.code)
            if (r.code == 200) {
                val s = OpenSeekProtocol.parseLookup(r.body, sessionBase) ?: return
                mapA = s.a; mapB = s.b
                routePreviewId = s.preview.id; routeKind = "previews"
                val label = when {
                    s.kind == "user_adjusted" -> "Shared thumbnails, adjusted"
                    s.verified && !s.approximate -> "Shared thumbnails"
                    else -> "Shared thumbnails (approximate)"
                }
                pollPreview(req, s.preview, label)
                return
            }
            if (r.code == 401 || r.code == 400 || r.code == -2 || r.code == 403) return
            if (r.code == 404 && !contributed) {
                // Not in the shared library: a guest the owner allowed to contribute hands over the video link once.
                val st = call("GET", "$sessionBase/v1/pair/status", sessionToken, sessionPin, null, 5)
                val may = st.code == 200 && runCatching { JSONObject(st.body).optBoolean("contribute", false) }.getOrDefault(false)
                val cb = if (may) OpenSeekProtocol.contributeBody(req.url, req.headers, req.durationMs, req.imdb, req.season, req.episode) else null
                if (cb != null) {
                    mapA = 1.0; mapB = 0.0
                    val p = createPreview(req, "contribute", "/v1/contribute", cb, 202)
                    if (p != null) { contributed = true; pollPreview(req, p, "Contributing thumbnails"); return }
                }
            }
            delay(if (r.code == 404) 120_000 else 30_000)
        }
    }

    // ---- thumbnails -----------------------------------------------------------------------------

    class Thumb(val bitmap: Bitmap, val labelMs: Long)

    /** Cue for the guest-time position [posMs] (applies the shared-library time mapping). */
    private fun cueFor(posMs: Long): OpenSeekProtocol.Cue? = OpenSeekProtocol.cueAt(cues, Math.round(mapA * posMs + mapB))

    /** Thumbnail for [posMs] if it is already in memory (cheap; safe to call from composition). */
    fun cachedThumb(posMs: Long): Thumb? {
        val c = cueFor(posMs) ?: return null
        return frames.get(frameKey(c))?.let { Thumb(it, c.labelMs) }
    }

    private fun frameKey(c: OpenSeekProtocol.Cue) = "${OpenSeekProtocol.imageKey(c.imageUrl)}#${c.x},${c.y},${c.w},${c.h}"

    /** Fetches and crops the thumbnail for [posMs]; null when there is none. */
    suspend fun loadThumb(posMs: Long): Thumb? = withContext(Dispatchers.IO) {
        val c = cueFor(posMs) ?: return@withContext null
        val key = frameKey(c)
        frames.get(key)?.let { return@withContext Thumb(it, c.labelMs) }
        val imgKey = OpenSeekProtocol.imageKey(c.imageUrl)
        var bytes = sheetBytes.get(imgKey)
        if (bytes == null) {
            val r = call("GET", c.imageUrl, null, sessionPin, null, 10, binary = true)
            if (r.code != 200 || r.bytes == null) { Log.i(TAG, "image fetch http=${r.code}"); return@withContext null }
            bytes = r.bytes
            sheetBytes.put(imgKey, bytes)
        }
        val bmp = try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            if (c.w == 320 && c.h == 180 && c.x == 0 && c.y == 0) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            else {
                @Suppress("DEPRECATION")
                val dec = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                try { dec?.decodeRegion(Rect(c.x, c.y, c.x + c.w, c.y + c.h), opts) } finally { dec?.recycle() }
            }
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
        bmp?.let { frames.put(key, it); Thumb(it, c.labelMs) }
    }

    /** Scrub hint for the server (latest-wins, ≤ 4/s). [atMs] null = scrubbing ended. Never blocks the caller. */
    fun scrubHint(atMs: Long?, playheadMs: Long) {
        val id = routePreviewId; if (id.isEmpty() || !_session.value.active || settings.value.role == "guest") return
        val now = System.currentTimeMillis()
        if (atMs != null && now - lastPriorityAt < 250) return
        lastPriorityAt = now
        val b = sessionBase; val t = sessionToken; val pin = sessionPin
        val body = OpenSeekProtocol.priorityBody(atMs, playheadMs)
        scope.launch { call("POST", "$b/v1/$routeKind/$id/priority", t, pin, body, 4) }
    }
}
