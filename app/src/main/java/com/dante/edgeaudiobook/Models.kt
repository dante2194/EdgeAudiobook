package com.dante.edgeaudiobook

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.select.Elements
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

data class TocEntry(val label: String, val start: Int, val depth: Int)

/** Plain class on purpose: compared by identity, so Compose never deep-compares a huge text. */
class Book(
    val title: String,
    val text: String,
    val words: Int,
    val toc: List<TocEntry>,
    val tocSource: String,
    val coverRaw: ByteArray? = null
)

/** [end] == null means "until the next chapter starts (or the end of the text)". */
data class Chapter(val id: Long, val name: String, val start: Int, val end: Int?, val include: Boolean = true)

data class Span(val ch: Chapter, val start: Int, val end: Int)

data class Voice(val shortName: String, val locale: String, val gender: String, val friendly: String)

object IdGen {
    private val n = AtomicLong(1)
    fun next(): Long = n.getAndIncrement()
    /** After restoring saved items, make sure new ids never collide with them. */
    fun bump(atLeast: Long) { n.updateAndGet { maxOf(it, atLeast + 1) } }
}

object TextOps {
    /** One heading per line. Case-insensitive + multiline. Editable in the app. */
    val DEFAULT_HEADING_REGEX = """^[ \t]{0,3}[*_]{0,3}(?:#{1,3}[ \t]+\S.*|(?:chapter|part|book|section)[ \t]+(?:\d{1,4}|[ivxlcdm]{1,8}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)\b.{0,70}|(?:prologue|epilogue|preface|introduction|foreword|afterword)\b.{0,60}|(?:\d{1,3}|[ivxlc]{2,8})[.)]?)[*_]{0,3}[ \t]*$"""

    private val WS = Regex("""\s+""")
    private val BAD_FILE_CHARS = Regex("""[\\/:*?"<>|\r\n\t]""")
    private val SEPARATOR = Regex("""[-*_=~#\s]{3,}""")
    private val MD_IMG = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val MD_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val MD_EMPH = Regex("""[*_`~]+""")
    private val SENT_SPLIT = Regex("""(?<=[.!?…]["'”’)\uE001\uE003]?)\s+""")

    fun normalize(s: String): String =
        s.replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u00A0', ' ')
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** Start offsets of reader pages (about [size] chars each, cut at whitespace). */
    fun pages(t: String, size: Int = 3000): List<Int> {
        val r = arrayListOf(0)
        var start = 0
        while (true) {
            val target = start + size
            var q = target
            if (q >= t.length) break
            while (q < t.length && q - target < 300 && !t[q].isWhitespace()) q++
            if (q >= t.length) break
            start = q + 1
            r.add(start)
        }
        return r
    }

    /** Whitespace-tolerant, case-insensitive, smart-quote-tolerant phrase matcher. */
    fun phraseRegex(phrase: String): Regex? {
        val words = phrase.trim().split(WS).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val pattern = words.joinToString("""\s+""") { w ->
            w.map { ch ->
                when (ch) {
                    '\'', '’', '‘' -> "['’‘]"
                    '"', '“', '”' -> "[\"“”]"
                    else -> Regex.escape(ch.toString())
                }
            }.joinToString("")
        }
        return Regex(pattern, RegexOption.IGNORE_CASE)
    }

    fun find(text: String, phrase: String, from: Int): IntRange? =
        phraseRegex(phrase)?.find(text, from.coerceIn(0, text.length))?.range

    fun findAll(text: String, phrase: String, limit: Int): List<IntRange> =
        phraseRegex(phrase)?.findAll(text)?.take(limit)?.map { it.range }?.toList() ?: emptyList()

    /** Pattern scan (advanced). Sections shorter than [minWords] are dropped. */
    fun detect(text: String, pattern: String, minWords: Int): List<Chapter> {
        val rx = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        val hits = rx.findAll(text).map { it.range.first to it.value.trim() }.toList()
        val kept = hits.filterIndexed { i, h ->
            countWords(text, h.first, hits.getOrNull(i + 1)?.first ?: text.length) >= minWords
        }
        return kept.mapIndexed { i, h ->
            Chapter(
                IdGen.next(),
                h.second.trim('#', ' ', '*', '_').take(60).ifBlank { "Section ${i + 1}" },
                h.first,
                null
            )
        }
    }

    fun spans(chapters: List<Chapter>, len: Int): List<Span> {
        val sorted = chapters.sortedBy { it.start }
        return sorted.mapIndexed { i, c ->
            val s = c.start.coerceIn(0, len)
            val e = c.end ?: (sorted.getOrNull(i + 1)?.start ?: len)
            Span(c, s, e.coerceIn(s, len))
        }
    }

    /** Audio minutes for a word count (about 150 words per minute). */
    fun minutes(words: Int): Int = maxOf(1, words / 150)

    fun countWords(s: CharSequence, from: Int = 0, to: Int = s.length): Int {
        var n = 0
        var inWord = false
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(s.length)) {
            val ws = s[i].isWhitespace()
            if (!ws && !inWord) n++
            inWord = !ws
        }
        return n
    }

    /** Start offsets of reader blocks (~[target] chars, cut at paragraph ends where possible). */
    fun blocks(t: String, target: Int = 1800): IntArray {
        val r = ArrayList<Int>()
        r.add(0)
        var start = 0
        while (true) {
            val aim = start + target
            if (aim >= t.length) break
            var q = t.indexOf('\n', aim)
            if (q < 0 || q - aim > 3000) {
                q = t.indexOf(' ', aim)
                if (q < 0) break
            }
            start = q + 1
            if (start >= t.length) break
            r.add(start)
        }
        return r.toIntArray()
    }

    fun blockIndex(blocks: IntArray, off: Int): Int {
        var lo = 0
        var hi = blocks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (blocks[mid] <= off) lo = mid else hi = mid - 1
        }
        return lo.coerceAtLeast(0)
    }

    fun safe(s: String): String =
        s.replace(BAD_FILE_CHARS, "_").trim().trim('.').take(80).ifBlank { "untitled" }

    // ---------- speech preparation ----------

    private val BULLET = Regex("""^[-+*][ \t]+""")
    private val MD_FN = Regex("""\[(\d{1,3}|[\u00B9\u00B2\u00B3\u2070\u2074-\u2079]+)]\(#[^)]*\)""")
    private val HTML_SUP = Regex("""<sup>([^<]{1,12})</sup>""", RegexOption.IGNORE_CASE)
    private val MD_BI = Regex("""\*\*\*(?=\S)(.+?)(?<=\S)\*\*\*""")
    private val MD_B = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*""")
    private val MD_B2 = Regex("""(?<![\w])__(?=\S)(.+?)(?<=\S)__(?![\w])""")
    private val MD_I = Regex("""(?<![\w*])\*(?=[^\s*])(.+?)(?<=[^\s*])\*(?![\w*])""")
    private val MD_I2 = Regex("""(?<![\w])_(?=[^\s_])(.+?)(?<=[^\s_])_(?![\w])""")
    private val HEAD_MARKS = Regex("""[*_]+""")
    private val MULTI_SPACE = Regex("""[ \t]{2,}""")

    private fun isForeign(cp: Int): Boolean {
        val sc = Character.UnicodeScript.of(cp)
        return sc != Character.UnicodeScript.LATIN && sc != Character.UnicodeScript.COMMON &&
            sc != Character.UnicodeScript.INHERITED && sc != Character.UnicodeScript.UNKNOWN
    }

    /** null = the paragraph is mostly non-Latin script (Hebrew, Greek, Arabic…) and is dropped. */
    private fun dropForeign(s: String): String? {
        var letters = 0
        var foreign = 0
        s.codePoints().forEach { cp ->
            if (Character.isLetter(cp)) {
                letters++
                if (isForeign(cp)) foreign++
            }
        }
        if (letters > 0 && foreign * 2 > letters) return null
        if (foreign == 0) return s
        val sb = StringBuilder()
        s.codePoints().forEach { cp -> if (!isForeign(cp)) sb.appendCodePoint(cp) }
        return sb.toString().replace(MULTI_SPACE, " ").trim()
    }

    /**
     * Turns raw (txt / md / epub-derived) text into clean spoken paragraphs.
     * Bold/italic become [Marks] markers; blockquote and bullet marks are removed.
     */
    fun speechParagraphs(text: String, skipForeign: Boolean = true): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotBlank()) out.add(cur.toString().trim())
            cur.setLength(0)
        }
        for (raw in text.lines()) {
            var line = raw.trim()
            if (line.startsWith(">")) line = line.trimStart('>', ' ', '\t')
            if (line.isEmpty()) { flush(); continue }
            if (SEPARATOR.matches(line)) { flush(); continue }
            if (line.startsWith("#")) {
                flush()
                line = line.trimStart('#', ' ').replace(HEAD_MARKS, "")
                if (line.isNotBlank()) out.add(line)
                continue
            }
            line = line.replace(BULLET, "")
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(line)
        }
        flush()
        var paras = out.map { cleanInline(it) }.filter { Marks.strip(it).isNotBlank() }
        if (skipForeign) {
            paras = paras.mapNotNull { dropForeign(it) }.filter { Marks.strip(it).isNotBlank() }
        }
        return paras.map { ensureEnd(it) }
    }

    private fun cleanInline(s: String): String =
        s.replace(MD_IMG, "")
            .replace(MD_FN, "^$1^")
            .replace(HTML_SUP, "^$1^")
            .replace(MD_LINK, "$1")
            .replace(MD_BI, "\uE002\uE000$1\uE001\uE003")
            .replace(MD_B, "\uE002$1\uE003")
            .replace(MD_B2, "\uE002$1\uE003")
            .replace(MD_I, "\uE000$1\uE001")
            .replace(MD_I2, "\uE000$1\uE001")
            .replace(MD_EMPH, "")
            .replace(WS, " ")
            .trim()

    private fun ensureEnd(s: String): String {
        val core = s.trimEnd(*Marks.CHARS)
        return if (core.isNotEmpty() && core.last().isLetterOrDigit()) "$s." else s
    }

    /** Re-opens / closes bold & italic at chunk boundaries so no span is left dangling. */
    fun balance(chunks: List<String>): List<String> {
        var b = 0
        var i = 0
        return chunks.map { c ->
            val pre = StringBuilder()
            repeat(b) { pre.append(Marks.BS) }
            repeat(i) { pre.append(Marks.IS) }
            for (ch in c) {
                when (ch) {
                    Marks.BS -> b++
                    Marks.BE -> if (b > 0) b--
                    Marks.IS -> i++
                    Marks.IE -> if (i > 0) i--
                }
            }
            val post = StringBuilder()
            repeat(i) { post.append(Marks.IE) }
            repeat(b) { post.append(Marks.BE) }
            pre.toString() + c + post
        }
    }

    /** Packs paragraphs into chunks small enough for one Edge request. */
    fun chunkText(paras: List<String>, max: Int = 2200): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            val t = sb.toString().trim()
            if (t.any { it.isLetterOrDigit() }) out.add(t)
            sb.setLength(0)
        }
        for (p in paras) {
            for (s in p.split(SENT_SPLIT)) {
                var piece = s.trim()
                if (piece.isEmpty()) continue
                while (piece.length > max) {
                    var cut = piece.lastIndexOf(' ', max)
                    if (cut < max / 2) cut = max
                    if (sb.isNotEmpty()) flush()
                    sb.append(piece, 0, cut)
                    flush()
                    piece = piece.substring(cut).trim()
                }
                if (sb.length + piece.length + 1 > max) flush()
                sb.append(piece).append(' ')
            }
        }
        flush()
        return out
    }
}

object BookLoader {
    private class Epub(val text: String, val toc: List<TocEntry>, val cover: ByteArray?)
    private class RawToc(val label: String, val path: String, val frag: String, val depth: Int)

    private const val ANCHOR_START = '\uE100'
    private const val ANCHOR_END = '\uE101'

    fun load(ctx: Context, uri: Uri): Book {
        val cr = ctx.contentResolver
        var name = "book"
        cr.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
        }
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read the file")
        val isEpub = name.lowercase().endsWith(".epub") ||
            (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())

        val text: String
        var toc: List<TocEntry>
        var source: String
        var cover: ByteArray? = null
        if (isEpub) {
            val ep = epub(bytes)
            text = ep.text
            toc = ep.toc
            source = "EPUB table of contents"
            cover = ep.cover
            if (toc.size < 2) {
                val h = TocFinder.fromHeadings(text)
                if (h.size >= 2) {
                    toc = h
                    source = "EPUB headings (no table of contents found)"
                } else {
                    toc = emptyList()
                    source = ""
                }
            }
        } else {
            text = TextOps.normalize(String(bytes, Charsets.UTF_8))
            val block = TocFinder.fromContentsBlock(text)
            val heads = TocFinder.fromHeadings(text)
            if (block.size >= 3) {
                toc = block
                source = "Contents list in the text"
            } else if (heads.size >= 2) {
                toc = heads
                source = "Markdown headings"
            } else {
                toc = emptyList()
                source = ""
            }
        }
        return Book(name.substringBeforeLast('.'), text, TextOps.countWords(text), toc, source, cover)
    }

    private fun epub(bytes: ByteArray): Epub {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) files[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        val container = files["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            ?: error("Not a valid EPUB (no container.xml)")
        val opfPath = Jsoup.parse(container, "", Parser.xmlParser())
            .selectFirst("rootfile")?.attr("full-path")
            ?: error("EPUB has no package file")
        val opf = Jsoup.parse(
            String(files[opfPath] ?: error("EPUB package file missing"), Charsets.UTF_8),
            "", Parser.xmlParser()
        )
        val dir = opfPath.substringBeforeLast('/', "")
        val items = opf.select("manifest > item")
        val manifest = items.associate { it.attr("id") to it.attr("href") }
        val spine = opf.select("spine > itemref").mapNotNull { manifest[it.attr("idref")] }
        val paths = if (spine.isNotEmpty()) spine.map { resolve(dir, it) }
        else files.keys.filter {
            val l = it.lowercase()
            l.endsWith(".xhtml") || l.endsWith(".html") || l.endsWith(".htm")
        }.sorted()

        val rawToc = readToc(files, opf, items, dir)

        val sb = StringBuilder()
        for (p in paths) {
            val b = files[p] ?: continue
            val anchors = rawToc.mapIndexedNotNull { i, r -> if (r.path == p) i to r.frag else null }
            sb.append(htmlToText(String(b, Charsets.UTF_8), anchors)).append("\n\n")
        }
        val normalized = TextOps.normalize(sb.toString())

        // Remove the anchor markers and remember where each table-of-contents entry landed.
        val out = StringBuilder(normalized.length)
        val pos = HashMap<Int, Int>()
        var i = 0
        while (i < normalized.length) {
            val c = normalized[i]
            if (c == ANCHOR_START) {
                val j = normalized.indexOf(ANCHOR_END, i)
                if (j > i) {
                    normalized.substring(i + 1, j).toIntOrNull()?.let { id -> if (!pos.containsKey(id)) pos[id] = out.length }
                    i = j + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
        val toc = rawToc.mapIndexedNotNull { idx, r -> pos[idx]?.let { TocEntry(r.label, it, r.depth) } }
            .sortedBy { it.start }
            .distinctBy { it.start }

        val coverHref = items.firstOrNull { (" " + it.attr("properties") + " ").contains(" cover-image ") }?.attr("href")
            ?: opf.selectFirst("meta[name=cover]")?.attr("content")?.let { id ->
                items.firstOrNull { it.attr("id") == id }?.attr("href")
            }
        val cover = coverHref?.let { files[resolve(dir, it)] }
        return Epub(out.toString(), toc, cover)
    }

    // ---------- table of contents (EPUB 3 nav, EPUB 2 NCX) ----------

    private fun readToc(files: Map<String, ByteArray>, opf: Document, items: Elements, dir: String): List<RawToc> {
        val navItem = items.firstOrNull { (" " + it.attr("properties") + " ").contains(" nav ") }
        if (navItem != null) {
            val navPath = resolve(dir, navItem.attr("href"))
            val nb = files[navPath]
            if (nb != null) {
                val r = parseNav(String(nb, Charsets.UTF_8), navPath.substringBeforeLast('/', ""))
                if (r.size >= 2) return r
            }
        }
        val ncxId = opf.selectFirst("spine")?.attr("toc").orEmpty()
        val ncxItem = items.firstOrNull { ncxId.isNotEmpty() && it.attr("id") == ncxId }
            ?: items.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
        if (ncxItem != null) {
            val ncxPath = resolve(dir, ncxItem.attr("href"))
            val nb = files[ncxPath]
            if (nb != null) {
                val r = parseNcx(String(nb, Charsets.UTF_8), ncxPath.substringBeforeLast('/', ""))
                if (r.size >= 2) return r
            }
        }
        return emptyList()
    }

    private fun parseNav(html: String, baseDir: String): List<RawToc> {
        val doc = Jsoup.parse(html)
        val navs = doc.select("nav")
        val nav = navs.firstOrNull { it.attr("epub:type").contains("toc") } ?: navs.firstOrNull()
            ?: return emptyList()
        val ol = nav.selectFirst("ol") ?: return emptyList()
        val out = ArrayList<RawToc>()
        walkNav(ol, 1, baseDir, out)
        return out
    }

    private fun walkNav(ol: Element, depth: Int, baseDir: String, out: MutableList<RawToc>) {
        for (li in ol.children()) {
            if (li.tagName() != "li") continue
            val a = li.children().firstOrNull { it.tagName() == "a" || it.tagName() == "span" }
            if (a != null) {
                val href = a.attr("href")
                val label = a.text().trim()
                val filePart = href.substringBefore('#')
                if (filePart.isNotEmpty() && label.isNotEmpty()) {
                    out.add(RawToc(label, resolve(baseDir, filePart), href.substringAfter('#', ""), depth))
                }
            }
            for (sub in li.children()) if (sub.tagName() == "ol") walkNav(sub, depth + 1, baseDir, out)
        }
    }

    private fun parseNcx(xml: String, baseDir: String): List<RawToc> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val map = doc.selectFirst("navMap") ?: return emptyList()
        val out = ArrayList<RawToc>()
        walkNcx(map, 1, baseDir, out)
        return out
    }

    private fun walkNcx(parent: Element, depth: Int, baseDir: String, out: MutableList<RawToc>) {
        for (np in parent.children()) {
            if (np.tagName() != "navPoint") continue
            val label = np.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
            val src = np.selectFirst("content")?.attr("src").orEmpty()
            val filePart = src.substringBefore('#')
            if (label.isNotEmpty() && filePart.isNotEmpty()) {
                out.add(RawToc(label, resolve(baseDir, filePart), src.substringAfter('#', ""), depth))
            }
            walkNcx(np, depth + 1, baseDir, out)
        }
    }

    private fun resolve(dir: String, href: String): String {
        val clean = URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8")
        val parts = ArrayList<String>()
        for (seg in (if (dir.isEmpty()) clean else "$dir/$clean").split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun htmlToText(html: String, anchors: List<Pair<Int, String>>): String {
        val doc = Jsoup.parse(html)
        doc.select("script,style,nav,svg,img").remove()
        for (el in doc.select("strong,b,em,i").reversed()) {
            val w = if (el.tagName() == "strong" || el.tagName() == "b") "**" else "*"
            val inner = el.html()
            if (inner.isBlank()) continue
            val lead = inner.takeWhile { it.isWhitespace() }
            val trail = inner.takeLastWhile { it.isWhitespace() }
            el.html(lead + w + inner.trim() + w + trail)
        }
        // footnote / superscript numbers become ^1^ so they can be skipped or read plainly
        for (el in doc.select("sup").reversed()) {
            val t = el.text().trim()
            if (t.isNotEmpty() && t.length <= 12) el.html("^$t^")
        }
        for (a in doc.select("a")) {
            if (a.attr("epub:type").contains("noteref")) {
                val t = a.text().trim()
                if (t.isNotEmpty() && t.length <= 12 && !t.startsWith("^")) a.html("^$t^")
            }
        }
        for ((idx, frag) in anchors) {
            val marker = "$ANCHOR_START$idx$ANCHOR_END"
            val target = if (frag.isNotEmpty()) doc.getElementById(frag) else null
            (target ?: doc.body())?.prepend(marker)
        }
        doc.select("h1,h2,h3").prepend("\\n\\n## ")
        doc.select("br").append("\\n")
        doc.select("p,div,li,h1,h2,h3,h4,h5,h6,blockquote,tr").append("\\n\\n")
        return (doc.body()?.text() ?: "").replace("\\n", "\n")
    }
}

/** Builds a table of contents for plain text / markdown, and turns entries into chapters. */
object TocFinder {
    private val HEAD = Regex("""^(#{1,6})[ \t]+(.+?)[ \t#]*$""")
    private val CONTENTS = Regex(
        """^[ \t#*_]*(?:table of contents|contents|index of chapters|toc)[ \t*_:.]*$""",
        RegexOption.IGNORE_CASE
    )
    private val LEADER = Regex("""(?:[ \t]*[.·…]{2,}|\t+|[ \t]{3,})[ \t]*[0-9ivxlcIVXLC]{1,6}$""")
    private val BULLET = Regex("""^[-*+•][ \t]+""")
    private val MDLINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val EMPH = Regex("""[*_`~]+""")
    private val SPACES = Regex("""\s+""")
    private val NONWORD = Regex("""[^\p{L}\p{N}]+""")

    private fun clean(s: String): String =
        s.replace(MDLINK, "$1").replace(EMPH, "").replace(SPACES, " ").trim()

    private fun shift(l: List<TocEntry>): List<TocEntry> {
        val min = l.minOfOrNull { it.depth } ?: return l
        return l.map { it.copy(depth = it.depth - min + 1) }
    }

    /** The heading outline (# … ######) used as the table of contents. */
    fun fromHeadings(text: String): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        var off = 0
        for (line in text.split('\n')) {
            val m = HEAD.matchEntire(line)
            if (m != null) {
                val label = clean(m.groupValues[2])
                if (label.isNotBlank()) out.add(TocEntry(label.take(120), off, m.groupValues[1].length))
            }
            off += line.length + 1
        }
        return shift(out)
    }

    private fun tocLabel(raw: String): String {
        var s = raw.trim().replace(BULLET, "")
        s = s.replace(LEADER, "")
        return clean(s)
    }

    /** A "Contents" list inside the text; each entry is located later in the text, in order. */
    fun fromContentsBlock(text: String): List<TocEntry> {
        val lines = text.split('\n')
        val offs = IntArray(lines.size)
        var o = 0
        for (i in lines.indices) {
            offs[i] = o
            o += lines[i].length + 1
        }
        val ci = lines.indexOfFirst { CONTENTS.matches(it.trim()) }
        if (ci < 0) return emptyList()

        val labels = ArrayList<String>()
        var i = ci + 1
        var blanks = 0
        while (i < lines.size && labels.size < 300) {
            val raw = lines[i]
            if (raw.isBlank()) {
                blanks++
                if (blanks >= 2 && labels.isNotEmpty()) break
                i++
                continue
            }
            if (raw.length > 120) break
            blanks = 0
            val label = tocLabel(raw)
            if (label.isNotBlank()) labels.add(label)
            i++
        }
        if (labels.size < 3) return emptyList()

        val cache = arrayOfNulls<String>(lines.size)
        var cursor = i
        val out = ArrayList<TocEntry>()
        for (label in labels) {
            val key = label.lowercase().replace(NONWORD, " ").trim()
            if (key.isEmpty()) continue
            var j = cursor
            while (j < lines.size) {
                val ln = lines[j]
                if (ln.isNotBlank() && ln.length <= label.length + 40) {
                    val n = cache[j] ?: ln.lowercase().replace(NONWORD, " ").trim().also { cache[j] = it }
                    if (n.contains(key)) {
                        out.add(TocEntry(label.take(120), offs[j], 1))
                        cursor = j + 1
                        break
                    }
                }
                j++
            }
        }
        return if (out.size >= 3) out else emptyList()
    }

    /** Chapters for every entry up to [level]; sections with fewer than [minWords] words are left out. */
    fun toChapters(book: Book, level: Int, minWords: Int): List<Chapter> {
        val ents = book.toc.filter { it.depth <= level }.sortedBy { it.start }.distinctBy { it.start }
        val res = ArrayList<Chapter>()
        for ((i, e) in ents.withIndex()) {
            val next = ents.getOrNull(i + 1)?.start
            val endOff = next ?: book.text.length
            if (TextOps.countWords(book.text, e.start, endOff) < minWords) continue
            res.add(Chapter(IdGen.next(), e.label.take(80), e.start, next))
        }
        return res
    }
}
