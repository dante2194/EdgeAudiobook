package com.dante.edgeaudiobook

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Minimal client for the same websocket service Microsoft Edge's "Read aloud" uses
 * (the protocol implemented by the open-source edge-tts projects on GitHub).
 * Output is MP3 (24 kHz, 48 kbps, mono).
 */
object EdgeTts {
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val BASE = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"

    /** Editable in the app (Voice tab -> advanced) in case Microsoft starts rejecting it. */
    @Volatile var chromium = "143.0.3650.75"
    @Volatile private var skewSeconds = 0L
    @Volatile private var skewSynced = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val FALLBACK = listOf(
        Voice("en-US-AriaNeural", "en-US", "Female", "Aria (US)"),
        Voice("en-US-GuyNeural", "en-US", "Male", "Guy (US)"),
        Voice("en-US-JennyNeural", "en-US", "Female", "Jenny (US)"),
        Voice("en-US-AndrewNeural", "en-US", "Male", "Andrew (US)"),
        Voice("en-US-EmmaNeural", "en-US", "Female", "Emma (US)"),
        Voice("en-US-BrianNeural", "en-US", "Male", "Brian (US)"),
        Voice("en-GB-SoniaNeural", "en-GB", "Female", "Sonia (UK)"),
        Voice("en-GB-RyanNeural", "en-GB", "Male", "Ryan (UK)"),
        Voice("en-AU-NatashaNeural", "en-AU", "Female", "Natasha (AU)"),
        Voice("fr-FR-DeniseNeural", "fr-FR", "Female", "Denise (FR)"),
        Voice("de-DE-KatjaNeural", "de-DE", "Female", "Katja (DE)"),
        Voice("es-ES-ElviraNeural", "es-ES", "Female", "Elvira (ES)"),
    )

    fun fallbackVoices(): List<Voice> = FALLBACK

    private fun major() = chromium.substringBefore('.')

    private fun userAgent() =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/${major()}.0.0.0 Safari/537.36 Edg/${major()}.0.0.0"

    private const val SHA_M = 0xFFFFFFFFL

    private val SHA_INIT = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
        0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L
    )
    private val SHA_STATE0 = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
        0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L
    )
    private val SHA_SCHED = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL, 0xA622EE8CL,
        0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L, 0xF0C31573L, 0x7058CE4EL,
        0x7188E606L, 0x00E5AB33L, 0xD258B060L, 0x72766564L
    )
    private val SHA_K = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L, 0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
        0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L, 0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
        0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL, 0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L, 0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
        0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L, 0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
        0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L, 0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L, 0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L, 0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L
    )
    private val SHA_KB = SHA_K.copyOf().also { it[14] = 0xC9DC06A7L }

    private fun rol16(v: Long): Long = ((v shl 16) or (v ushr 16)) and SHA_M
    private fun sg0(v: Long): Long =
        ((v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)) and SHA_M
    private fun sg1(v: Long): Long =
        ((v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)) and SHA_M

    private fun shaRounds(st: LongArray, w: List<Long>, first: Int): LongArray {
        var a = st[0]; var b = st[1]; var c = st[2]; var d = st[3]
        var e = st[4]; var f = st[5]; var g = st[6]; var h = st[7]
        val kk = if (first == 14) SHA_KB else SHA_K
        val aligned = minOf(64, ((first + 7) / 8) * 8)
        var i = first
        while (i < aligned) {
            val rotE = e * 0x100000001L
            val rotA = a * 0x100000001L
            val t1 = (h + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (g xor (e and (f xor g))) + kk[i] + w[i]) and SHA_M
            val t2 = (((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((a and b) or (c and (a or b)))) and SHA_M
            val na = (t1 + t2) and SHA_M
            val nd = (d + t1) and SHA_M
            h = g; g = f; f = e; e = nd; d = c; c = b; b = a; a = na
            i++
        }
        i = aligned
        while (i < 64) {
            var rotE = e * 0x100000001L
            var rotA = a * 0x100000001L
            var t1 = (h + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (g xor (e and (f xor g))) + kk[i] + w[i]) and SHA_M
            d = (d + t1) and SHA_M
            h = (t1 + (((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((a and b) or (c and (a or b))))) and SHA_M
            rotE = d * 0x100000001L
            rotA = h * 0x100000001L
            t1 = (g + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (f xor (d and (e xor f))) + kk[i + 1] + w[i + 1]) and SHA_M
            c = (c + t1) and SHA_M
            g = (t1 + (((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((h and a) or (b and (h or a))))) and SHA_M
            rotE = c * 0x100000001L
            rotA = g * 0x100000001L
            t1 = (f + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (e xor (c and (d xor e))) + kk[i + 2] + w[i + 2]) and SHA_M
            b = (b + t1) and SHA_M
            f = (t1 + (((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((g and h) or (a and (g or h))))) and SHA_M
            rotE = b * 0x100000001L
            rotA = f * 0x100000001L
            t1 = (e + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (d xor (b and (c xor d))) + kk[i + 3] + w[i + 3]) and SHA_M
            a = (a + t1) and SHA_M
            e = (t1 + (((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((f and g) or (h and (f or g))))) and SHA_M
            rotE = a * 0x100000001L
            rotA = e * 0x100000001L
            t1 = (d + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (c xor (a and (b xor c))) + kk[i + 4] + w[i + 4]) and SHA_M
            h = (h + t1) and SHA_M
            d = (t1 + (((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((e and f) or (g and (e or f))))) and SHA_M
            rotE = h * 0x100000001L
            rotA = d * 0x100000001L
            t1 = (c + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (b xor (h and (a xor b))) + kk[i + 5] + w[i + 5]) and SHA_M
            g = (g + t1) and SHA_M
            c = (t1 + (((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((d and e) or (f and (d or e))))) and SHA_M
            rotE = g * 0x100000001L
            rotA = c * 0x100000001L
            t1 = (b + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (a xor (g and (h xor a))) + kk[i + 6] + w[i + 6]) and SHA_M
            f = (f + t1) and SHA_M
            b = (t1 + (((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((c and d) or (e and (c or d))))) and SHA_M
            rotE = f * 0x100000001L
            rotA = b * 0x100000001L
            t1 = (a + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (h xor (f and (g xor h))) + kk[i + 7] + w[i + 7]) and SHA_M
            e = (e + t1) and SHA_M
            a = (t1 + (((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((b and c) or (d and (b or c))))) and SHA_M
            i += 8
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSchedule(w: MutableList<Long>, firstWord: Int) {
        for (i in firstWord until 64) {
            val x = w[i - 2]; val y = w[i - 15]
            val s1 = (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)
            val s0 = (y ushr 7) xor (y shl 25) xor (y ushr 18) xor (y shl 14) xor (y ushr 3)
            w.add((s1 + w[i - 7] + s0 + w[i - 16]) and SHA_M)
        }
    }

    private fun sha256Init(data7: ByteArray): LongArray {
        val w = ArrayList<Long>(64)
        repeat(14) { w.add(0L) }
        w.add(((data7[0].toLong() and 0xFF) shl 16) or
            ((data7[1].toLong() and 0xFF) shl 8) or (data7[2].toLong() and 0xFF))
        w.add(((data7[3].toLong() and 0xFF) shl 24) or
            ((data7[4].toLong() and 0xFF) shl 16) or
            ((data7[5].toLong() and 0xFF) shl 8) or (data7[6].toLong() and 0xFF))
        for (i in 16 until 30) {
            var value = SHA_SCHED[i - 16]
            if (i - 2 >= 14) value += if (i == 16) (sg1(w[i - 2]) xor 0x000B92C0L) else sg1(w[i - 2])
            if (i - 7 >= 14) value += w[i - 7]
            if (i - 15 >= 14) value += if (i == 29) (sg0(w[i - 15]) xor 0x059C0B80L) else sg0(w[i - 15])
            w.add(value and SHA_M)
        }
        w.add((sg1(w[28]) + w[23] + sg0(w[15]) + w[14] +
            0xC9DC06A7L - SHA_K[14]) and SHA_M)
        expandSchedule(w, 31)
        val working = shaRounds(SHA_STATE0, w, 14)
        return LongArray(8) { (SHA_INIT[it] + working[it]) and SHA_M }
    }

    private fun drmSha256(data: ByteArray): ByteArray {
        val length = 57 + data.size
        val tail = data.copyOfRange(7, data.size)
        var remPad = (55 - length) % 64
        if (remPad < 0) remPad += 64
        val lenBytes = ByteArray(8)
        var bits = length * 8L
        for (i in 7 downTo 0) {
            lenBytes[i] = (bits and 0xFF).toByte()
            bits = bits ushr 8
        }
        val all = tail + byteArrayOf(0x80.toByte()) + ByteArray(remPad) + lenBytes
        var state = sha256Init(data)
        var off = 0
        while (off < all.size) {
            val w = ArrayList<Long>(64)
            for (j in 0 until 16) {
                var v = 0L
                for (b in 0 until 4) v = (v shl 8) or (all[off + j * 4 + b].toLong() and 0xFF)
                w.add(v)
            }
            expandSchedule(w, 16)
            val working = shaRounds(state, w, 0)
            state = LongArray(8) { (state[it] + working[it]) and SHA_M }
            off += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            var v = state[i]
            for (b in 3 downTo 0) {
                out[i * 4 + b] = (v and 0xFF).toByte()
                v = v ushr 8
            }
        }
        return out
    }

    private fun drmCs64(data: ByteArray, digest: ByteArray): LongArray {
        var keyA = (digest[0].toLong() and 0xFF) or ((digest[1].toLong() and 0xFF) shl 8) or
            ((digest[2].toLong() and 0xFF) shl 16) or ((digest[3].toLong() and 0xFF) shl 24)
        var keyB = (digest[4].toLong() and 0xFF) or ((digest[5].toLong() and 0xFF) shl 8) or
            ((digest[6].toLong() and 0xFF) shl 16) or ((digest[7].toLong() and 0xFF) shl 24)
        keyA = keyA or 1
        keyB = keyB or 1
        val fa = (keyA * 0x82893933L) and SHA_M
        val fb = (keyB * 0x82893933L) and SHA_M
        val coeff = longArrayOf(
            0xE82E91B1L, 0x44DE24C6L, 0x3BEB65BCL, 0xC89C5933L, 0x8D1EC211L, 0x314EF5D5L, 0xE92491B1L,
            0xF0A5B9D5L, 0x06064CF3L, 0xDA79FA60L, 0xBED20560L, 0x89F9C9F8L, 0x35FA38C6L, 0xCB49E3C6L
        )
        val coff = longArrayOf(
            0xB32700D0L, 0x88DFF51DL, 0x29514038L, 0xB328CBDFL, 0xD405B4D5L, 0x26E55F3CL, 0x607801C6L,
            0x82B2C1FBL, 0xFA51A3DAL, 0x207CC464L, 0x3C3C74F3L, 0xD2D14B61L, 0xBB04A2C1L, 0xC5626167L
        )
        val terms = ArrayList<Long>(40)
        val offs = ArrayList<Long>(40)
        for (i in 0 until 14) {
            terms.add((coeff[i] * (if (i % 2 == 0) fa else fb)) and SHA_M)
            offs.add(coff[i])
        }
        val buf = ByteArray(1 + data.size)
        System.arraycopy(data, 0, buf, 1, data.size)
        var nWords = (buf.size + 3) / 4
        if (nWords % 2 != 0) nWords++
        for (w in 0 until nWords) {
            var word = 0L
            for (b in 0 until 4) {
                val idx = w * 4 + b
                val v = if (idx < buf.size) buf[idx].toLong() and 0xFF else 0L
                word = word or (v shl (8 * b))
            }
            var term = word * (if (w % 2 == 0) keyA else keyB)
            var off = rol16(word) * (if (w % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (w == 0) {
                term += 0xF2F90B1AL * fa
                off += 0x0B1A0000L
            }
            terms.add(term and SHA_M)
            offs.add(off and SHA_M)
        }
        var swapAcc = 0L; var swapLast = 0L; var state = 0L; var acc = 0L
        var i = 0
        while (i < terms.size) {
            val x = terms[i]; val y = terms[i + 1]
            var t = rol16((offs[i] + x) and SHA_M)
            var q = (t * 0x76F50F34L) and SHA_M
            swapAcc += ((rol16(q) * 0xC3EFEA97L + q) and SHA_M)
            t = rol16((offs[i + 1] + y) and SHA_M)
            q = (t * 0x1881CE3CL) and SHA_M
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and SHA_M
            swapAcc = (swapAcc + swapLast) and SHA_M
            state = (state * keyA + x) and SHA_M
            state = (rol16(state) * 0xCF98B111L) and SHA_M
            state = (rol16(state) * 0x87085B9FL) and SHA_M
            state = (rol16(state) * 0x12CEB96DL) and SHA_M
            state = (rol16(state) * 0x257E1D83L) and SHA_M
            acc += state
            t = (state * keyB + y) and SHA_M
            t = (rol16(t) * 0xA27416F5L) and SHA_M
            t = (rol16(t) * 0xD38396FFL) and SHA_M
            state = (rol16((rol16(t) * 0x7C932B89L) and SHA_M) * 0xBFA49F69L) and SHA_M
            acc = (acc + state) and SHA_M
            i += 2
        }
        return longArrayOf((swapLast xor state) and SHA_M, (swapAcc xor acc) and SHA_M)
    }

    private fun leHex(v: Long): String {
        val sb = StringBuilder(8)
        for (i in 0 until 4) sb.append("%02X".format((v shr (8 * i)) and 0xFF))
        return sb.toString()
    }

    /** Sec-MS-GEC v2: "<CS64hex>-<custom-SHA256hex>" over 5-min-rounded Windows ticks. */
    private fun gec(): String {
        var s = System.currentTimeMillis() / 1000 + skewSeconds + 11_644_473_600L
        s -= s % 300
        val data = ("${s * 10_000_000L}$TOKEN").toByteArray(Charsets.US_ASCII)
        val digest = drmSha256(data)
        val cs = drmCs64(data, digest)
        return leHex(cs[0]) + leHex(cs[1]) + "-" +
            digest.joinToString("") { "%02X".format(it) }
    }

    private fun stamp(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date()) + " GMT+0000 (Coordinated Universal Time)"
    }

    private fun muid(): String =
        UUID.randomUUID().toString().replace("-", "").uppercase()

    private fun Request.Builder.edge(): Request.Builder = this
        .header("Pragma", "no-cache")
        .header("Cache-Control", "no-cache")
        .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
        .header("User-Agent", userAgent())
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Cookie", "muid=${muid()};")

    private fun fullVoiceName(short: String): String {
        val parts = short.split("-")
        if (parts.size < 3) return short
        val lang = parts[0]
        val region = parts.subList(1, parts.size - 1).joinToString("-")
        return "Microsoft Server Speech Text to Speech Voice ($lang-$region, ${parts.last()})"
    }

    private fun esc(s: String): String =
        s.replace(Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F]"""), " ")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun applySkew(r: Response?) {
        val d = r?.headers?.getDate("Date") ?: return
        skewSeconds = d.time / 1000 - System.currentTimeMillis() / 1000
    }

    /** Learn the server clock (Date header) before the first TTS request. */
    private suspend fun syncSkew(force: Boolean = false) {
        if (skewSynced && !force) return
        withContext(Dispatchers.IO) {
            try {
                client.newCall(
                    Request.Builder().url("https://speech.platform.bing.com/").build()
                ).execute().use { applySkew(it) }
            } catch (_: Exception) {
            }
            skewSynced = true
        }
    }

    /** rate in percent (-50..100), pitch in Hz (-50..50). Returns MP3 bytes. */
    private suspend fun synthesizeInner(inner: String, voice: String, rate: Int, pitch: Int, volume: Int): ByteArray =
        suspendCancellableCoroutine { cont ->
            val out = ByteArrayOutputStream()
            val id = UUID.randomUUID().toString().replace("-", "")
            val url = "wss://$BASE/edge/v1?TrustedClientToken=$TOKEN" +
                "&Sec-MS-GEC=${gec()}&Sec-MS-GEC-Version=2-$chromium&ConnectionId=$id"
            val req = Request.Builder().url(url).edge().build()

            val ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        "X-Timestamp:${stamp()}\r\nContent-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" +
                            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}""" +
                            "\r\n"
                    )
                    val r = String.format(Locale.US, "%+d%%", rate)
                    val p = String.format(Locale.US, "%+dHz", pitch)
                    val vol = String.format(Locale.US, "%+d%%", volume)
                    webSocket.send(
                        "X-RequestId:$id\r\nContent-Type:application/ssml+xml\r\n" +
                            "X-Timestamp:${stamp()}Z\r\nPath:ssml\r\n\r\n" +
                            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                            "<voice name='${fullVoiceName(voice)}'>" +
                            "<prosody pitch='$p' rate='$r' volume='$vol'>$inner</prosody>" +
                            "</voice></speak>"
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        webSocket.close(1000, null)
                        finish()
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val b = bytes.toByteArray()
                    if (b.size < 2) return
                    val headerLen = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                    val start = 2 + headerLen
                    if (start > b.size) return
                    val header = String(b, 2, headerLen, Charsets.UTF_8)
                    if (header.contains("Path:audio") && b.size > start) {
                        out.write(b, start, b.size - start)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    applySkew(response)
                    if (cont.isActive) {
                        val code = response?.code?.let { "HTTP $it: " } ?: ""
                        cont.resumeWithException(IOException(code + (t.message ?: "connection failed"), t))
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish()

                private fun finish() {
                    if (!cont.isActive) return
                    val b = out.toByteArray()
                    if (b.isEmpty()) cont.resumeWithException(IOException("No audio returned"))
                    else cont.resume(b)
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }

    private suspend fun <T> retrying(tries: Int, block: suspend () -> T): T {
        var last: Exception? = null
        for (attempt in 1..tries) {
            try {
                return kotlinx.coroutines.withTimeout(90_000L) { block() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                last = IOException("Timed out waiting for the server")
                delay(1500L * attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (e.message?.contains("HTTP 403") == true) {
                    try {
                        syncSkew(force = true)
                    } catch (_: Exception) {
                    }
                }
                delay(1500L * attempt)
            }
        }
        throw last ?: IOException("Synthesis failed")
    }

    suspend fun synthesizeRetry(
        text: String, voice: String, rate: Int, pitch: Int, volume: Int = 0, tries: Int = 3
    ): ByteArray {
        syncSkew()
        return retrying(tries) { synthesizeInner(esc(text), voice, rate, pitch, volume) }
    }

    private fun hasWords(s: String) = s.any { it.isLetterOrDigit() }

    /** Speaks [text]. Bold/italic markers ([Marks]) become emphasis according to [e]. */
    suspend fun speak(text: String, voice: String, rate: Int, pitch: Int, e: Emph, tries: Int = 3): ByteArray {
        if (!Marks.has(text)) {
            if (!hasWords(text)) return ByteArray(0)
            return synthesizeRetry(text, voice, rate, pitch, 0, tries)
        }
        val segs = EmphOps.visible(text, e)
        val plain = segs.joinToString("") { it.text }
        if (!hasWords(plain)) return ByteArray(0)
        if (!e.enabled || segs.none { EmphOps.delta(it, e) != null }) {
            return synthesizeRetry(plain, voice, rate, pitch, 0, tries)
        }
        if (e.method == 2) return speakSplit(segs, voice, rate, pitch, e, tries)
        val inner = ssmlBody(segs, e)
        return retrying(tries) { synthesizeInner(inner, voice, rate, pitch, 0) }
    }

    private fun ssmlBody(segs: List<Marks.Seg>, e: Emph): String {
        val sb = StringBuilder()
        for (seg in segs) {
            val t = esc(seg.text)
            val d = EmphOps.delta(seg, e)
            if (d == null) {
                sb.append(t)
            } else if (e.method == 1) {
                sb.append("<emphasis level='").append(if (d.strong) "strong" else "moderate").append("'>")
                    .append(t).append("</emphasis>")
            } else {
                sb.append("<prosody rate='").append(String.format(Locale.US, "%+d", d.rate))
                    .append("%' pitch='").append(String.format(Locale.US, "%+d", d.pitch))
                    .append("Hz' volume='").append(String.format(Locale.US, "%+d", d.vol))
                    .append("%'>").append(t).append("</prosody>")
            }
        }
        return sb.toString()
    }

    private class Part(var text: String, val rate: Int, val pitch: Int, val vol: Int)

    /** Emphasised words are synthesised as their own requests and the MP3 pieces are joined. */
    private suspend fun speakSplit(
        segs: List<Marks.Seg>, voice: String, rate: Int, pitch: Int, e: Emph, tries: Int
    ): ByteArray {
        val parts = ArrayList<Part>()
        for (seg in segs) {
            val d = EmphOps.delta(seg, e)
            val r = d?.rate ?: 0
            val p = d?.pitch ?: 0
            val v = d?.vol ?: 0
            val last = parts.lastOrNull()
            if (last != null && ((last.rate == r && last.pitch == p && last.vol == v) || !hasWords(seg.text))) {
                last.text += seg.text
            } else {
                parts.add(Part(seg.text, r, p, v))
            }
        }
        val out = ByteArrayOutputStream()
        for (part in parts) {
            if (!hasWords(part.text)) continue
            out.write(
                synthesizeRetry(
                    part.text.trim(), voice,
                    (rate + part.rate).coerceIn(-60, 100), (pitch + part.pitch).coerceIn(-50, 50),
                    part.vol, tries
                )
            )
        }
        return out.toByteArray()
    }

    suspend fun fetchVoicesJson(): String = withContext(Dispatchers.IO) {
        syncSkew()
        var last: Exception? = null
        for (attempt in 1..2) {
            try {
                val url = "https://$BASE/voices/list?trustedclienttoken=$TOKEN" +
                    "&Sec-MS-GEC=${gec()}&Sec-MS-GEC-Version=2-$chromium"
                val req = Request.Builder().url(url).edge()
                    .header("Accept", "*/*")
                    .header("Sec-CH-UA", "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"${major()}\", \"Chromium\";v=\"${major()}\"")
                    .header("Sec-CH-UA-Mobile", "?0")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        applySkew(r)
                        throw IOException("HTTP ${r.code}")
                    }
                    return@withContext r.body?.string() ?: throw IOException("Empty response")
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Could not load voices")
    }

    fun parseVoices(json: String): List<Voice> {
        val arr = JSONArray(json)
        val list = ArrayList<Voice>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val short = o.optString("ShortName")
            if (short.isEmpty()) continue
            list.add(Voice(short, o.optString("Locale"), o.optString("Gender"), o.optString("FriendlyName")))
        }
        return list.sortedWith(compareBy({ it.locale }, { it.shortName }))
    }
}
