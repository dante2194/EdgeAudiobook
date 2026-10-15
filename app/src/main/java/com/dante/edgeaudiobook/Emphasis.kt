package com.dante.edgeaudiobook

import org.json.JSONObject

/**
 * Bold / italic spans travel through the pipeline as private-use marker characters,
 * so text rules (references, abbreviations) and chunking can run on plain text.
 */
object Marks {
    const val IS = '\uE000'   // italic start
    const val IE = '\uE001'   // italic end
    const val BS = '\uE002'   // bold start
    const val BE = '\uE003'   // bold end
    const val ALL = "\uE000\uE001\uE002\uE003"
    val CHARS = charArrayOf(IS, IE, BS, BE)

    class Seg(val text: String, val bold: Boolean, val italic: Boolean)

    fun has(s: String): Boolean = s.any { it in ALL }

    fun strip(s: String): String = s.filter { it !in ALL }

    fun parse(s: String): List<Seg> {
        val out = ArrayList<Seg>()
        val sb = StringBuilder()
        var b = 0
        var i = 0
        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(Seg(sb.toString(), b > 0, i > 0))
                sb.setLength(0)
            }
        }
        for (ch in s) {
            when (ch) {
                BS -> { flush(); b++ }
                BE -> { flush(); if (b > 0) b-- }
                IS -> { flush(); i++ }
                IE -> { flush(); if (i > 0) i-- }
                else -> sb.append(ch)
            }
        }
        flush()
        return out
    }
}

/** Emphasis settings. method: 0 = nested prosody tags, 1 = <emphasis> tag, 2 = separate requests. */
data class Emph(
    val enabled: Boolean = true,
    val method: Int = 0,
    val boldRate: Int = -10,
    val boldPitch: Int = 8,
    val boldVol: Int = 15,
    val italicOn: Boolean = true,
    val italicRate: Int = -5,
    val italicPitch: Int = 4,
    val italicVol: Int = 5,
    val maxWords: Int = 4,
    val numberSpans: Int = 0,      // 0 = read normally, 1 = skip bold/italic numbers (verse numbers)
    val skipForeign: Boolean = true
)

class Delta(val rate: Int, val pitch: Int, val vol: Int, val strong: Boolean)

object EmphOps {
    private val NUMONLY = Regex("""[\d\s.,;:\-–—]+""")
    private val WS = Regex("""\s+""")

    fun isNumberSpan(t: String): Boolean = t.any { it.isDigit() } && NUMONLY.matches(t.trim())

    /** Segments to actually speak (numbers-only spans may be dropped). */
    fun visible(text: String, e: Emph): List<Marks.Seg> =
        Marks.parse(text).filter { !(e.numberSpans == 1 && (it.bold || it.italic) && isNumberSpan(it.text)) }

    /** null = speak this segment normally. */
    fun delta(seg: Marks.Seg, e: Emph): Delta? {
        if (!e.enabled) return null
        val useItalic = seg.italic && e.italicOn
        if (!seg.bold && !useItalic) return null
        if (seg.text.trim().split(WS).size > e.maxWords) return null
        if (isNumberSpan(seg.text)) return null
        var r = 0
        var p = 0
        var v = 0
        if (seg.bold) { r += e.boldRate; p += e.boldPitch; v += e.boldVol }
        if (useItalic) { r += e.italicRate; p += e.italicPitch; v += e.italicVol }
        return Delta(r.coerceIn(-40, 40), p.coerceIn(-30, 30), v.coerceIn(-30, 50), seg.bold)
    }
}

object EmphStore {
    fun toJson(e: Emph): String = JSONObject()
        .put("enabled", e.enabled).put("method", e.method)
        .put("bR", e.boldRate).put("bP", e.boldPitch).put("bV", e.boldVol)
        .put("iOn", e.italicOn).put("iR", e.italicRate).put("iP", e.italicPitch).put("iV", e.italicVol)
        .put("max", e.maxWords).put("num", e.numberSpans).put("foreign", e.skipForeign)
        .toString()

    fun fromJson(s: String): Emph {
        if (s.isBlank()) return Emph()
        return try {
            val o = JSONObject(s)
            val d = Emph()
            Emph(
                o.optBoolean("enabled", d.enabled), o.optInt("method", d.method),
                o.optInt("bR", d.boldRate), o.optInt("bP", d.boldPitch), o.optInt("bV", d.boldVol),
                o.optBoolean("iOn", d.italicOn), o.optInt("iR", d.italicRate),
                o.optInt("iP", d.italicPitch), o.optInt("iV", d.italicVol),
                o.optInt("max", d.maxWords), o.optInt("num", d.numberSpans),
                o.optBoolean("foreign", d.skipForeign)
            )
        } catch (ex: Exception) {
            Emph()
        }
    }
}
