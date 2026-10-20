package com.nuvio.tv.openseek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSeekProtocolTest {

    // Vectors from OpenSeek backend/tests/vectors/osp1.json (shared with the server and the other clients).
    private val pin = "sha256///xu1gU6gaNOLG3vZ7lgNZOSZ3FzCPOxmXaxK7+KHEPE="
    private val token = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8"

    @Test
    fun osp1ProductionVector() {
        val k = OpenSeekProtocol.deriveKey("K7QFM2XD", "000102030405060708090a0b0c0d0e0f", "101112131415161718191a1b1c1d1e1f", pin, 600_000)
        assertEquals("31ae960890b3c475a4a4045d815c00cdf91f00acec5c861ebaf9929bb5004e90", OpenSeekProtocol.hex(k))
        assertEquals("246b0d42f45974edf2ea73cccd57ba9881f16f398aa81e987eb2f5ae29e5d249",
            OpenSeekProtocol.clientProof(k, "000102030405060708090a0b0c0d0e0f", "Shield"))
        assertEquals("508fcea036d1d1c2282630b2b5d55cd44c65a2bf57e95a723c0bd887ea705c3e",
            OpenSeekProtocol.serverProof(k, "000102030405060708090a0b0c0d0e0f", token))
    }

    @Test
    fun osp1Utf8DeviceName() {
        val k = OpenSeekProtocol.deriveKey("K7QFM2XD", "000102030405060708090a0b0c0d0e0f", "101112131415161718191a1b1c1d1e1f", pin, 1000)
        assertEquals("5788e18431f3acf6e9d059406463b63db51fb6d28b46aa6b06cf4be039e0d487", OpenSeekProtocol.hex(k))
        assertEquals("f3c92e80d1ef5e1c61425f546dde50a2f0fa3a138bd49cbe39a901f2790a8b7a",
            OpenSeekProtocol.clientProof(k, "000102030405060708090a0b0c0d0e0f", "Wohnzimmer TV ü"))
    }

    @Test
    fun pbkdf2ManualMatchesProvider() {
        // The hand-written single-block PBKDF2 (used on API 24/25) must equal the provider result.
        val pw = "K7QFM2XD".toByteArray()
        val salt = "OSP1abcdef".toByteArray()
        val viaProvider = OpenSeekProtocol.pbkdf2(pw, salt, 1000)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(pw, "HmacSHA256")) }
        mac.update(salt); mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal(); val t = u.copyOf()
        repeat(999) { u = mac.doFinal(u); for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte() }
        assertEquals(OpenSeekProtocol.hex(t), OpenSeekProtocol.hex(viaProvider))
    }

    @Test
    fun addresses() {
        assertEquals("https://192.168.1.20:8787", OpenSeekProtocol.normalizeAddress("192.168.1.20"))
        assertEquals("https://nas.local:9000", OpenSeekProtocol.normalizeAddress("https://nas.local:9000/"))
        assertEquals("https://[fe80::1]:9000", OpenSeekProtocol.normalizeAddress("[fe80::1]:9000"))
        assertNull(OpenSeekProtocol.normalizeAddress("http://nas"))
        assertNull(OpenSeekProtocol.normalizeAddress("user@nas"))
        assertNull(OpenSeekProtocol.normalizeAddress("nas/path"))
        assertNull(OpenSeekProtocol.normalizeAddress("nas:99999"))
    }

    @Test
    fun joinInputs() {
        val owner = OpenSeekProtocol.parseJoinInput("k7qf-m2xd")!!
        assertEquals(OpenSeekProtocol.Form.OwnerCode, owner.form)
        assertEquals("K7QFM2XD", owner.secret)
        val typed = OpenSeekProtocol.parseJoinInput("ABCD-EFGH-JKMN-PQRS")!!
        assertEquals(OpenSeekProtocol.Form.Invite, typed.form)
        assertEquals("ABCD", typed.invite)
        assertEquals("EFGHJKMNPQRS", typed.secret)
        val link = OpenSeekProtocol.parseJoinInput("openseek://join/1?i=000102030405060708090a0b0c0d0e0f&c=EFGH-JKMN-PQRS&h=192.168.1.20%3A8787")!!
        assertEquals("https://192.168.1.20:8787", link.base)
        assertEquals("000102030405060708090a0b0c0d0e0f", link.invite)
        assertNull(OpenSeekProtocol.parseJoinInput("hello"))
        assertNull(OpenSeekProtocol.parseJoinInput("0123-4567"))     // 0, 1 are not in the alphabet
    }

    @Test
    fun vttParsingAndLookup() {
        val base = "https://nas:8787"
        val vtt = """
            WEBVTT

            00:00:00.000 --> 00:00:05.000
            $base/v1/c/cap/id/tiles/0.jpg?exp=100&t=0#xywh=0,0,320,180

            00:79:57.000 --> 00:80:03.500
            $base/v1/c/cap/id/sheets/4.jpg?exp=100&t=4797960#xywh=320,180,320,180

            00:00:07.000 --> 00:00:09.000
            https://evil.example/x.jpg#xywh=0,0,320,180
        """.trimIndent()
        val cues = OpenSeekProtocol.parseVtt(vtt, base)
        assertEquals(2, cues.size)                                  // the foreign origin is dropped
        assertEquals(4_797_000L, cues[1].startMs)                   // minutes may exceed 59
        assertEquals(4_803_500L, cues[1].endMs)
        assertEquals(4_797_960L, cues[1].labelMs)
        assertEquals("$base/v1/c/cap/id/sheets/4.jpg", OpenSeekProtocol.imageKey(cues[1].imageUrl + "&exp=999"))
        assertNotNull(OpenSeekProtocol.cueAt(cues, 2_000))
        assertEquals(4_797_960L, OpenSeekProtocol.cueAt(cues, 4_800_000)!!.labelMs)
        assertNull(OpenSeekProtocol.cueAt(cues, 1_000_000))         // far from any cue: no thumbnail
        assertTrue(OpenSeekProtocol.sameOrigin("$base/x", base) && !OpenSeekProtocol.sameOrigin("$base.evil/x", base))
    }
}
