package com.nuvio.tv.openseek

import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure parts of the OpenSeek client (no network, no Android): address/invite parsing, the OSP1 pairing
 * derivation, request bodies and VTT parsing. Contract: OpenSeek `docs/api/CONTRACT-v1.md`.
 */
internal object OpenSeekProtocol {

    const val DEFAULT_PORT = 8787
    const val MIN_ITERATIONS = 200_000
    const val MAX_ITERATIONS = 5_000_000
    private const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val INVITE_TAG = 4
    private const val INVITE_SECRET = 12
    private const val CODE_LEN = 8
    private val HEADERS_ALLOWED = listOf("User-Agent", "Referer", "Origin")

    private val hostRegex = Regex("^[A-Za-z0-9.-]{1,120}$")
    private val ipv6Regex = Regex("^\\[[0-9A-Fa-f:.]{2,60}]$")
    private val pinRegex = Regex("^sha256//[A-Za-z0-9+/]{43}=$")
    private val hexRegex = Regex("^[0-9a-f]+$")

    /** "nas.local", "192.168.1.5:8787", "https://nas:8787/" -> "https://host:port"; null when refused (http://, user@, path, ...). */
    fun normalizeAddress(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.length > 200) return null
        if (s.startsWith("https://", ignoreCase = true)) s = s.substring(8)
        else if (s.contains("://")) return null
        if (s.endsWith("/")) s = s.dropLast(1)
        if (s.any { it == '/' || it == '@' || it == '?' || it == '#' || it == ' ' }) return null
        val host: String
        val port: Int
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            host = s.substring(0, end + 1)
            if (!ipv6Regex.matches(host)) return null
            val rest = s.substring(end + 1)
            port = if (rest.isEmpty()) DEFAULT_PORT else rest.removePrefix(":").toIntOrNull() ?: return null
        } else {
            val colon = s.lastIndexOf(':')
            if (colon >= 0) {
                host = s.substring(0, colon)
                port = s.substring(colon + 1).toIntOrNull() ?: return null
            } else {
                host = s
                port = DEFAULT_PORT
            }
            if (!hostRegex.matches(host)) return null
        }
        if (port !in 1..65535) return null
        return "https://$host:$port"
    }

    /** True when [url] is "<base>/..." (same scheme, host and port). */
    fun sameOrigin(url: String, base: String): Boolean = url.startsWith("$base/")

    fun pinValid(pin: String): Boolean = pinRegex.matches(pin)

    enum class Form { OwnerCode, Invite }

    /** What the person typed or pasted when joining a library. */
    data class JoinInput(
        val form: Form,
        val secret: String,            // 8-symbol owner code, or the 12-symbol invite secret
        val invite: String = "",       // invite id (32 hex) or the 4-letter label
        val base: String? = null,      // from h= in an invitation link
        val pin: String? = null        // from p= in an invitation link
    )

    private fun symbols(text: String, want: Int): String? {
        val out = StringBuilder()
        for (ch in text) {
            val c = ch.uppercaseChar()
            if (c == ' ' || c == '-') continue
            if (CODE_ALPHABET.indexOf(c) < 0 || out.length >= want) return null
            out.append(c)
        }
        return if (out.length == want) out.toString() else null
    }

    private fun queryValue(query: String, key: String): String? {
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq) == key) {
                return try { java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8") } catch (_: Exception) { null }
            }
        }
        return null
    }

    fun parseJoinInput(raw: String): JoinInput? {
        val text = raw.trim()
        val prefix = "openseek://join/1?"
        if (text.startsWith(prefix)) {
            val q = text.substring(prefix.length)
            if (q.length > 600) return null
            val id = queryValue(q, "i")?.lowercase() ?: return null
            if (id.length != 32 || !hexRegex.matches(id)) return null
            val secret = symbols(queryValue(q, "c") ?: return null, INVITE_SECRET) ?: return null
            val base = queryValue(q, "h")?.takeIf { it.isNotBlank() }?.let { normalizeAddress(it) ?: return null }
            val pin = queryValue(q, "p")?.takeIf { it.isNotBlank() }?.also { if (!pinValid(it)) return null }
            return JoinInput(Form.Invite, secret, id, base, pin)
        }
        symbols(text, INVITE_TAG + INVITE_SECRET)?.let {
            return JoinInput(Form.Invite, it.substring(INVITE_TAG), it.substring(0, INVITE_TAG))
        }
        symbols(text, CODE_LEN)?.let { return JoinInput(Form.OwnerCode, it) }
        return null
    }

    // ---- OSP1 -----------------------------------------------------------------------------------

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    fun hex(b: ByteArray): String {
        val h = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) { sb.append(h[(x.toInt() shr 4) and 15]); sb.append(h[x.toInt() and 15]) }
        return sb.toString()
    }

    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        try {
            val spec = PBEKeySpec(String(password, Charsets.UTF_8).toCharArray(), salt, iterations, 256)
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } catch (_: Exception) {
            // API 24/25 have no PBKDF2WithHmacSHA256: single-block PBKDF2 by hand (dkLen = hLen = 32).
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return t
    }

    fun deriveKey(secret: String, pairId: String, salt: String, pin: String, iterations: Int): ByteArray =
        pbkdf2(secret.toByteArray(), ("OSP1$pairId$salt$pin").toByteArray(), iterations)

    fun clientProof(k: ByteArray, pairId: String, deviceName: String): String =
        hex(hmac(k, "client".toByteArray(), pairId.toByteArray(), deviceName.toByteArray()))

    fun serverProof(k: ByteArray, pairId: String, token: String): String {
        val tokenHash = hex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
        return hex(hmac(k, "server".toByteArray(), pairId.toByteArray(), tokenHash.toByteArray()))
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].lowercaseChar().code xor b[i].lowercaseChar().code)
        return d == 0
    }

    fun spkiPin(spkiDer: ByteArray): String =
        "sha256//" + android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(spkiDer), android.util.Base64.NO_WRAP)

    // ---- Request bodies -------------------------------------------------------------------------

    fun allowedHeaders(headers: Map<String, String>): JSONObject {
        val o = JSONObject()
        for (name in HEADERS_ALLOWED) {
            val v = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()
            if (!v.isNullOrEmpty()) o.put(name, v)
        }
        return o
    }

    private fun imdbValid(id: String?): Boolean = id != null && Regex("^tt\\d{5,12}$").matches(id)

    fun previewBody(url: String, headers: Map<String, String>, durationMs: Long, imdb: String?, season: Int?, episode: Int?,
                    sizeBytes: Long?, playheadMs: Long): String {
        val source = JSONObject().put("url", url).put("headers", allowedHeaders(headers))
        if (durationMs > 0) source.put("duration_ms", durationMs)
        val hints = JSONObject()
        if (imdbValid(imdb)) {
            hints.put("imdb_id", imdb)
            hints.put("season", (season ?: 0).coerceAtLeast(0))
            hints.put("episode", (episode ?: 0).coerceAtLeast(0))
        }
        if (sizeBytes != null && sizeBytes > 0) hints.put("video_size", sizeBytes)
        return JSONObject().put("source", source).put("hints", hints).put("layout", "hybrid")
            .put("at_ms", JSONObject.NULL).put("playhead_ms", playheadMs.coerceAtLeast(0)).toString()
    }

    /** POST /v1/lookup body (ids and duration only, never the video link). Null without a title id or duration. */
    fun lookupBody(imdb: String?, season: Int?, episode: Int?, durationMs: Long, sizeBytes: Long?): String? {
        if (!imdbValid(imdb) || durationMs <= 0) return null
        val o = JSONObject().put("imdb_id", imdb).put("season", (season ?: 0).coerceAtLeast(0))
            .put("episode", (episode ?: 0).coerceAtLeast(0)).put("duration_ms", durationMs)
        if (sizeBytes != null && sizeBytes > 0) o.put("size_bytes", sizeBytes)
        return o.put("layout", "hybrid").toString()
    }

    /** POST /v1/contribute body, for a guest the owner has allowed to contribute. Null without a title id, https link or duration. */
    fun contributeBody(url: String, headers: Map<String, String>, durationMs: Long, imdb: String?, season: Int?, episode: Int?): String? {
        if (!url.startsWith("https://") || !imdbValid(imdb) || durationMs < 60_000) return null
        val source = JSONObject().put("url", url).put("headers", allowedHeaders(headers))
        return JSONObject().put("source", source).put("imdb_id", imdb).put("season", (season ?: 0).coerceAtLeast(0))
            .put("episode", (episode ?: 0).coerceAtLeast(0)).put("duration_ms", durationMs).toString()
    }

    fun priorityBody(atMs: Long?, playheadMs: Long): String =
        JSONObject().put("at_ms", atMs ?: JSONObject.NULL).put("playhead_ms", playheadMs.coerceAtLeast(0)).toString()

    // ---- Responses ------------------------------------------------------------------------------

    enum class JobState { Preparing, Ready, Complete, Failed }

    data class Preview(
        val id: String,
        val state: JobState,
        val error: String?,
        val vttUrl: String,
        val vttVersion: Long,
        val refreshAfterMs: Long,
        val exp: Long,
    )

    fun parsePreview(json: String, base: String): Preview? = try {
        val o = JSONObject(json)
        val id = o.getString("preview_id")
        val vtt = o.getString("vtt_url")
        if (id.length != 32 || !hexRegex.matches(id) || !sameOrigin(vtt, base)) null else Preview(
            id = id,
            state = when (o.optString("state")) {
                "ready" -> JobState.Ready; "complete" -> JobState.Complete; "failed" -> JobState.Failed; else -> JobState.Preparing
            },
            error = o.optString("error").takeIf { it.isNotEmpty() && it != "null" },
            vttUrl = vtt,
            vttVersion = o.optLong("vtt_version", 0),
            refreshAfterMs = o.optLong("refresh_after_ms", 1000).coerceIn(250, 15_000),
            exp = urlExp(vtt),
        )
    } catch (_: Exception) { null }

    fun urlExp(url: String): Long = Regex("[?&]exp=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    data class Shared(val preview: Preview, val kind: String, val verified: Boolean, val approximate: Boolean, val a: Double, val b: Double)

    /** First candidate of a POST /v1/lookup 200 response. Approximate unless the server proves otherwise. */
    fun parseLookup(json: String, base: String): Shared? = try {
        val c = JSONObject(json).getJSONArray("candidates").getJSONObject(0)
        val id = c.getString("preview_id")
        val vtt = c.getString("vtt_url")
        val mapping = c.optJSONObject("mapping")
        val a = mapping?.optDouble("a", 1.0) ?: 1.0
        val b = mapping?.optDouble("b", 0.0) ?: 0.0
        if (id.length != 32 || !hexRegex.matches(id) || !sameOrigin(vtt, base) || !(a > 0.5 && a < 2.0) || b < -2.2e7 || b > 2.2e7) null
        else Shared(
            Preview(id, JobState.Complete, null, vtt, 0, 1000, urlExp(vtt)),
            c.optString("kind"), c.optBoolean("verified", false), c.optBoolean("approximate", true), a, b,
        )
    } catch (_: Exception) { null }

    // ---- WebVTT ---------------------------------------------------------------------------------

    class Cue(val startMs: Long, val endMs: Long, val imageUrl: String, val x: Int, val y: Int, val w: Int, val h: Int, val labelMs: Long)

    private fun parseTimestamp(s: String): Long? {
        val p = s.trim().split(':')
        if (p.size !in 2..3) return null
        return try {
            val secs = p.last().toDouble()
            val mins = p[p.size - 2].toLong()
            val hours = if (p.size == 3) p[0].toLong() else 0L
            ((hours * 3600 + mins * 60) * 1000 + Math.round(secs * 1000))
        } catch (_: NumberFormatException) { null }
    }

    /** Cue key: the image URL without t=, exp= and the fragment, so renewed capabilities keep cached images. */
    fun imageKey(url: String): String {
        val noFrag = url.substringBefore('#')
        val path = noFrag.substringBefore('?')
        val query = noFrag.substringAfter('?', "").split('&').filter { it.isNotEmpty() && !it.startsWith("t=") && !it.startsWith("exp=") }
        return if (query.isEmpty()) path else "$path?${query.joinToString("&")}"
    }

    fun parseVtt(text: String, base: String): List<Cue> {
        val out = ArrayList<Cue>()
        val lines = text.lineSequence().iterator()
        while (lines.hasNext()) {
            val line = lines.next()
            val arrow = line.indexOf("-->")
            if (arrow < 0) continue
            val start = parseTimestamp(line.substring(0, arrow)) ?: continue
            val end = parseTimestamp(line.substring(arrow + 3).trim().substringBefore(' ')) ?: continue
            if (!lines.hasNext()) break
            val target = lines.next().trim()
            if (!sameOrigin(target, base)) continue
            val frag = target.substringAfter("#xywh=", "")
            val xywh = frag.split(',').mapNotNull { it.toIntOrNull() }
            if (xywh.size != 4 || xywh[2] <= 0 || xywh[3] <= 0 || xywh[2] > 3200 || xywh[3] > 1800) continue
            val label = Regex("[?&]t=(\\d+)").find(target.substringBefore('#'))?.groupValues?.get(1)?.toLongOrNull() ?: start
            out.add(Cue(start, end, target.substringBefore('#'), xywh[0], xywh[1], xywh[2], xywh[3], label))
        }
        out.sortBy { it.startMs }
        return out
    }

    private const val SPARSE_TOLERANCE_MS = 35_000L

    /** The cue shown for [posMs]: the one containing it, else the nearest within the sparse-grid tolerance. */
    fun cueAt(cues: List<Cue>, posMs: Long): Cue? {
        if (cues.isEmpty()) return null
        var lo = 0
        var hi = cues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cues[mid]
            when {
                posMs < c.startMs -> hi = mid - 1
                posMs >= c.endMs -> lo = mid + 1
                else -> return c
            }
        }
        var best: Cue? = null
        var bestD = Long.MAX_VALUE
        for (i in (lo - 2).coerceAtLeast(0)..(lo + 1).coerceAtMost(cues.size - 1)) {
            val c = cues[i]
            val d = kotlin.math.abs(c.labelMs - posMs)
            if (d < bestD) { bestD = d; best = c }
        }
        return if (bestD <= SPARSE_TOLERANCE_MS) best else null
    }
}
