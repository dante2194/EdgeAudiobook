package com.dante.edgeaudiobook

import org.json.JSONArray
import org.json.JSONObject

/**
 * A user pronunciation rule.
 * WORD    – whole word/phrase replacement (also acts as a book name before "2:5-7" style references)
 * PATTERN – "#" matches a number; the spoken text can use "#" (in order) or "#1", "#2"...
 * REGEX   – full regular expression; the spoken text can use $1, $2...
 */
data class PronRule(
    val id: Long,
    val find: String,
    val say: String,
    val mode: Int = 0,
    val matchCase: Boolean = false,
    val enabled: Boolean = true
) {
    companion object {
        const val WORD = 0
        const val PATTERN = 1
        const val REGEX = 2
        const val SKIP = 3      // remove text matching the regex
        const val SECTION = 4   // skip paragraphs from a start regex up to an end regex
    }
}

class CompiledRule(val rx: Regex, val raw: String?, val fn: ((MatchResult) -> String)?) {
    fun run(t: String): String =
        if (raw != null) rx.replace(t, raw) else rx.replace(t) { fn!!(it) }
}

object PronStore {
    fun toJson(list: List<PronRule>): String {
        val a = JSONArray()
        for (r in list) {
            a.put(
                JSONObject()
                    .put("find", r.find)
                    .put("say", r.say)
                    .put("mode", r.mode)
                    .put("case", r.matchCase)
                    .put("on", r.enabled)
            )
        }
        return a.toString()
    }

    fun fromJson(s: String): List<PronRule> {
        if (s.isBlank()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                PronRule(
                    IdGen.next(), o.optString("find"), o.optString("say"),
                    o.optInt("mode"), o.optBoolean("case"), o.optBoolean("on", true)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

object Pron {
    private val ORD = listOf("", "First", "Second", "Third")

    // spoken name, aliases separated by "|", number of numbered volumes (0 = not numbered)
    private val BOOKS: List<Triple<String, String, Int>> = listOf(
        Triple("Genesis", "Gen|Gn|Genesis", 0),
        Triple("Exodus", "Exod|Exo|Ex|Exodus", 0),
        Triple("Leviticus", "Lev|Lv|Leviticus", 0),
        Triple("Numbers", "Num|Nm|Nb|Numbers", 0),
        Triple("Deuteronomy", "Deut|Dt|Deuteronomy", 0),
        Triple("Joshua", "Josh|Jsh|Joshua", 0),
        Triple("Judges", "Judg|Jdg|Jg|Jdgs|Judges", 0),
        Triple("Ruth", "Rth|Ruth", 0),
        Triple("Samuel", "Sam|Sm|Samuel", 2),
        Triple("Kings", "Kgs|Ki|Kings", 2),
        Triple("Chronicles", "Chron|Chr|Chronicles", 2),
        Triple("Ezra", "Ezr|Ezra", 0),
        Triple("Nehemiah", "Neh|Nehemiah", 0),
        Triple("Esther", "Esth|Est|Esther", 0),
        Triple("Job", "Jb|Job", 0),
        Triple("Psalm", "Psalm", 0),
        Triple("Psalms", "Ps|Pss|Psa|Psm|Pslm|Psalms", 0),
        Triple("Proverbs", "Prov|Prv|Pr|Proverbs", 0),
        Triple("Ecclesiastes", "Eccl|Eccles|Ecc|Qoh|Ecclesiastes", 0),
        Triple("Song of Songs", "Song of Songs|Song of Solomon|Song of Sol|Song|SoS|Canticles", 0),
        Triple("Isaiah", "Isa|Isaiah", 0),
        Triple("Jeremiah", "Jer|Jr|Jeremiah", 0),
        Triple("Lamentations", "Lam|Lamentations", 0),
        Triple("Ezekiel", "Ezek|Eze|Ezk|Ezekiel", 0),
        Triple("Daniel", "Dan|Dn|Daniel", 0),
        Triple("Hosea", "Hos|Hosea", 0),
        Triple("Joel", "Jl|Joel", 0),
        Triple("Amos", "Amos", 0),
        Triple("Obadiah", "Obad|Obadiah", 0),
        Triple("Jonah", "Jon|Jnh|Jonah", 0),
        Triple("Micah", "Mic|Mc|Micah", 0),
        Triple("Nahum", "Nah|Nahum", 0),
        Triple("Habakkuk", "Hab|Hb|Habakkuk", 0),
        Triple("Zephaniah", "Zeph|Zep|Zp|Zephaniah", 0),
        Triple("Haggai", "Hag|Hg|Haggai", 0),
        Triple("Zechariah", "Zech|Zec|Zc|Zechariah", 0),
        Triple("Malachi", "Mal|Ml|Malachi", 0),
        Triple("Matthew", "Matt|Mt|Matthew", 0),
        Triple("Mark", "Mk|Mrk|Mark", 0),
        Triple("Luke", "Lk|Luk|Luke", 0),
        Triple("John", "Jn|Jhn|John", 0),
        Triple("Acts", "Acts", 0),
        Triple("Romans", "Rom|Rm|Romans", 0),
        Triple("Corinthians", "Cor|Corinthians", 2),
        Triple("Galatians", "Gal|Galatians", 0),
        Triple("Ephesians", "Eph|Ephesians", 0),
        Triple("Philippians", "Phil|Php|Philippians", 0),
        Triple("Colossians", "Col|Colossians", 0),
        Triple("Thessalonians", "Thess|Thes|Thessalonians", 2),
        Triple("Timothy", "Tim|Timothy", 2),
        Triple("Titus", "Tit|Titus", 0),
        Triple("Philemon", "Phlm|Phm|Philem|Philemon", 0),
        Triple("Hebrews", "Heb|Hebrews", 0),
        Triple("James", "Jas|Jm|James", 0),
        Triple("Peter", "Pet|Pt|Peter", 2),
        Triple("John", "Jn|Jhn|John", 3),
        Triple("Jude", "Jud|Jude", 0),
        Triple("Revelation", "Rev|Rv|Revelation", 0),
    )

    /** "Isa" -> "Isaiah", "1 sam" -> "First Samuel" (keys are normalised with [normKey]). */
    val BOOK_MAP: Map<String, String> = buildMap {
        for ((spoken, aliases, nums) in BOOKS) {
            for (a in aliases.split("|")) {
                if (nums == 0) put(normKey(a), spoken)
                else for (n in 1..nums) put(normKey("$n $a"), ORD[n] + " " + spoken)
            }
        }
    }

    fun normKey(s: String): String {
        var k = s.lowercase().replace('.', ' ').trim().replace(Regex("\\s+"), " ")
        k = Regex("^(iii|ii|i) ").replace(k) {
            when (it.groupValues[1]) {
                "i" -> "1 "
                "ii" -> "2 "
                else -> "3 "
            }
        }
        k = Regex("^([1-3])(?=[a-z])").replace(k, "$1 ")
        return k
    }

    // ---------------- scripture references ----------------

    private const val NUM_PREFIX = """(?:[1-3]\.?[ \t]*|(?:III|II|I)(?:\.[ \t]*|[ \t]+))"""
    private const val ITEM = """\d{1,3}(?::\d{1,3})?(?:[ \t]?[-–—][ \t]?\d{1,3}(?::\d{1,3})?)?"""
    private const val TAIL =
        """\d{1,3}:\d{1,3}(?:[ \t]?[-–—][ \t]?\d{1,3}(?::\d{1,3})?)?(?!\d)""" +
            """(?:[ \t]?[,;][ \t]?""" + ITEM + """(?!\d|[ \t]*[A-Za-z]))*"""

    private val REF = Regex(
        """(?<![\p{L}\p{N}])(""" + NUM_PREFIX + """)?([A-Z][A-Za-z]*(?:[ \t]+[A-Za-z]+){0,2})\.?[ \t]+(""" + TAIL + """)"""
    )
    private val ITEM_RX = Regex("""(?:(\d{1,3}):)?(\d{1,3})(?:[ \t]?[-–—][ \t]?(?:(\d{1,3}):)?(\d{1,3}))?""")
    private val TAIL_SPLIT = Regex("""[ \t]?[,;][ \t]?""")
    private val SPACES = Regex("""[ \t]+""")

    private fun speakTail(tail: String): String {
        val out = ArrayList<String>()
        for (p in tail.split(TAIL_SPLIT)) {
            val m = ITEM_RX.matchEntire(p.trim()) ?: return tail
            val c1 = m.groupValues[1]
            val v1 = m.groupValues[2]
            val c2 = m.groupValues[3]
            val v2 = m.groupValues[4]
            val sb = StringBuilder()
            if (c1.isNotEmpty()) sb.append("chapter ").append(c1).append(' ')
            when {
                v2.isEmpty() || (c2.isEmpty() && v2 == v1) -> sb.append("verse ").append(v1)
                c2.isNotEmpty() -> sb.append("verse ").append(v1).append(" to chapter ").append(c2)
                    .append(" verse ").append(v2)
                else -> sb.append("verses ").append(v1).append(" to ").append(v2)
            }
            out.add(sb.toString())
        }
        return out.joinToString(", ")
    }

    private fun mapRefs(text: String, books: Map<String, String>, f: (String, String, String) -> String): String =
        REF.replace(text) { m ->
            val prefix = m.groupValues[1]
            val ws = m.groupValues[2].split(SPACES)
            var result: String? = null
            for (k in ws.indices) {
                if (!ws[k][0].isUpperCase()) continue
                val key = normKey((if (k == 0) prefix else "") + ws.drop(k).joinToString(" "))
                val spoken = books[key] ?: continue
                val lead = if (k == 0) "" else prefix + ws.take(k).joinToString(" ") + " "
                result = f(lead, spoken, m.groupValues[3])
                break
            }
            result ?: m.value
        }

    fun scripture(text: String, books: Map<String, String>): String =
        mapRefs(text, books) { lead, spoken, tail -> lead + spoken + " " + speakTail(tail) }

    // ---- skipping references / numbering ----

    private val LEAD_WORDS = Regex(
        """(?i)(?<![\p{L}\p{N}])(?:see(?:[ \t]+also)?|cf\.?|compare|also|esp\.|especially)[ \t]*\uE200"""
    )
    private val CHAIN = Regex("""\uE200(?:[ \t]*(?:[;,]|,?[ \t]*(?:and|or|&))[ \t]*\uE200)+""")
    private val IN_BRACKETS = Regex("""[ \t]*[(\[][ \t]*\uE200[ \t]*[)\]]""")
    private val BEFORE_PUNCT = Regex("""[ \t]*\uE200(?=[ \t]*[.,;:!?)\]])""")
    private val SPACE_RUNS = Regex("""[ \t]{2,}""")
    private val PUNCT_DOT = Regex("""([.!?])[ \t]*\uE200[ \t]*\.""")
    private val DUP_PUNCT = Regex("""([,;])[ \t]*[,;]""")
    private val TRAIL_COMMA = Regex("""[,;:][ \t]*(?=[.!?](?!\.))""")
    private val SPACE_BEFORE_PUNCT = Regex("""[ \t]+([,.;:!?])""")

    /** Removes references such as "Josh 3:20", "(Mal 1:10; 1 John 2:10)" and tidies the leftovers. */
    fun skipScripture(text: String, books: Map<String, String>): String {
        var changed = false
        var t = mapRefs(text, books) { lead, _, _ ->
            changed = true
            lead + "\uE200"
        }
        if (!changed) return text
        t = LEAD_WORDS.replace(t, "\uE200")
        t = CHAIN.replace(t, "\uE200")
        t = IN_BRACKETS.replace(t, "")
        t = PUNCT_DOT.replace(t, "$1")
        t = BEFORE_PUNCT.replace(t, "")
        t = t.replace("\uE200", "")
        t = DUP_PUNCT.replace(t, "$1")
        t = TRAIL_COMMA.replace(t, "")
        return SPACE_RUNS.replace(t, " ").trim()
    }

    private val SUPER_RX = listOf(
        Regex("""[\u00B9\u00B2\u00B3\u2070\u2074-\u207E]+"""),
        Regex("""\^[^^\s]{1,12}\^"""),
        Regex("""\[\^[^\]]{1,20}]"""),
        Regex("""\[\d{1,3}(?:[,;–-][ \t]?\d{1,3})*]"""),
    )
    private val CARET = Regex("""\^([^^\s]{1,12})\^""")

    /** Footnote / superscript numbers: removed when [skip], otherwise just unwrapped (^3^ -> 3). */
    fun superscripts(text: String, skip: Boolean): String {
        if (!skip) return CARET.replace(text, "$1")
        var t = text
        var removed = false
        for (rx in SUPER_RX) {
            val n = rx.replace(t, "")
            if (n != t) removed = true
            t = n
        }
        if (removed) t = SPACE_BEFORE_PUNCT.replace(SPACE_RUNS.replace(t, " "), "$1")
        return t
    }

    /** Drops whole paragraphs between a start regex and an end regex (SECTION rules). */
    fun dropSections(paras: List<String>, rules: List<PronRule>): List<String> {
        val secs = rules.filter { it.enabled && it.mode == PronRule.SECTION && it.find.isNotBlank() }.mapNotNull { r ->
            try {
                val o: Set<RegexOption> = if (r.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex(r.find.trim(), o) to (if (r.say.isBlank()) null else Regex(r.say.trim(), o))
            } catch (e: Exception) {
                null
            }
        }
        if (secs.isEmpty()) return paras
        val out = ArrayList<String>()
        var skipping = false
        var endRx: Regex? = null
        for (p in paras) {
            val plain = Marks.strip(p)
            if (skipping) {
                val er = endRx
                if (er != null && er.containsMatchIn(plain)) {
                    skipping = false
                    endRx = null
                }
                continue
            }
            val hit = secs.firstOrNull { it.first.containsMatchIn(plain) }
            if (hit != null) {
                val er = hit.second
                skipping = !(er != null && er.containsMatchIn(plain))
                endRx = er
                continue
            }
            out.add(p)
        }
        return out
    }

    // ---------------- built-in abbreviations ----------------

    private val NUMLIST = Regex(
        """(?<![\p{L}\p{N}])(verses?|vv?|chapters?|chs?)(\.?)[ \t]*(\d{1,3})(?:[ \t]?[-–—][ \t]?(\d{1,3}))?(?!\d)""",
        RegexOption.IGNORE_CASE
    )

    private val WORDS: List<Pair<Regex, String>> = listOf(
        Regex("""(?<![\p{L}\p{N}])LXX(?![\p{L}\p{N}])""") to "Septuagint",
        Regex("""(?<![\p{L}\p{N}])MT(?![\p{L}\p{N}])""") to "Masoretic Text",
        Regex("""(?<![\p{L}\p{N}])DSS(?![\p{L}\p{N}])""") to "Dead Sea Scrolls",
        Regex("""(?<![\p{L}\p{N}])OT(?![\p{L}\p{N}])""") to "Old Testament",
        Regex("""(?<![\p{L}\p{N}])NT(?![\p{L}\p{N}])""") to "New Testament",
        Regex("""(?<![\p{L}\p{N}])Cf\.""") to "Compare",
        Regex("""(?<![\p{L}\p{N}])cf\.""") to "compare",
        Regex("""(?<![\p{L}\p{N}])e\.g\.""") to "for example",
        Regex("""(?<![\p{L}\p{N}])i\.e\.""") to "that is",
        Regex("""(?<![\p{L}\p{N}])vs\.""") to "versus",
        Regex("""(?<![\p{L}\p{N}])esp\.""") to "especially",
        Regex("""(?<![\p{L}\p{N}])viz\.""") to "namely",
        Regex("""(?<=\d)ff\.?(?![\p{L}])""") to " and following",
    )

    fun misc(text: String): String {
        var t = NUMLIST.replace(text) { m ->
            val w = m.groupValues[1]
            val lw = w.lowercase()
            val abbr = lw == "v" || lw == "vv" || lw == "ch" || lw == "chs"
            if (abbr && m.groupValues[2] != ".") {
                m.value
            } else {
                val a = m.groupValues[3]
                val b = m.groupValues[4]
                val range = b.isNotEmpty() && b != a
                val plural = lw == "verses" || lw == "vv" || lw == "chapters" || lw == "chs" || range
                var word = (if (lw.startsWith("v")) "verse" else "chapter") + (if (plural) "s" else "")
                if (w[0].isUpperCase()) word = word.replaceFirstChar { it.uppercase() }
                if (range) "$word $a to $b" else "$word $a"
            }
        }
        for ((rx, to) in WORDS) t = rx.replace(t, to)
        return t
    }

    // ---------------- user rules ----------------

    private fun edge(find: String, body: String): String {
        val first = find.first()
        val last = find.last()
        val lead = if (first.isLetterOrDigit() || first == '#') """(?<![\p{L}\p{N}])""" else ""
        val trail = if (last.isLetterOrDigit() || last == '#') """(?![\p{L}\p{N}])""" else ""
        return lead + body + trail
    }

    private val PLACEHOLDER = Regex("""#(\d)?""")

    private fun build(r: PronRule): CompiledRule {
        val opts: Set<RegexOption> = if (r.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val find = r.find.trim()
        require(find.isNotEmpty()) { "Nothing to find" }
        return when (r.mode) {
            PronRule.REGEX -> CompiledRule(Regex(find, opts), r.say, null)
            PronRule.SKIP -> CompiledRule(Regex(find, opts), "", null)
            PronRule.SECTION -> throw IllegalArgumentException("Section rules are applied separately")
            PronRule.PATTERN -> {
                val body = find.split("#").joinToString("""(\d+)""") { if (it.isEmpty()) "" else Regex.escape(it) }
                val say = r.say
                CompiledRule(Regex(edge(find, body), opts), null) { m ->
                    var n = 0
                    PLACEHOLDER.replace(say) { p ->
                        val idx = p.groupValues[1].toIntOrNull() ?: ++n
                        m.groupValues.getOrElse(idx) { "" }
                    }
                }
            }
            else -> {
                val say = r.say
                CompiledRule(Regex(edge(find, Regex.escape(find)), opts), null) { say }
            }
        }
    }

    fun compile(r: PronRule): CompiledRule? =
        if (r.mode == PronRule.SECTION) null else try { build(r) } catch (e: Exception) { null }

    /** null = fine, otherwise a short error message. */
    fun validate(r: PronRule): String? = try {
        if (r.mode == PronRule.SECTION) {
            val o: Set<RegexOption> = if (r.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(r.find.trim(), o)
            if (r.say.isNotBlank()) Regex(r.say.trim(), o)
        } else {
            build(r)
        }
        null
    } catch (e: Exception) {
        e.message?.lineSequence()?.firstOrNull() ?: "Invalid rule"
    }
}

class Pronouncer(
    rules: List<PronRule>,
    private val builtin: Boolean,
    private val skipRefs: Boolean = false,
    private val skipSuper: Boolean = false
) {
    private val books = HashMap<String, String>()
    private val compiled = ArrayList<CompiledRule>()

    init {
        if (builtin || skipRefs) books.putAll(Pron.BOOK_MAP)
        for (r in rules) {
            if (!r.enabled || r.find.isBlank()) continue
            // A custom word also works as a book name: "Ma 2:5-7" -> "<say> chapter 2 verses 5 to 7"
            if (r.mode == PronRule.WORD && r.find.any { it.isLetter() }) {
                books[Pron.normKey(r.find)] = r.say
            }
            Pron.compile(r)?.let { compiled.add(it) }
        }
    }

    fun apply(text: String): String {
        var t = Pron.superscripts(text, skipSuper)
        if (books.isNotEmpty()) {
            t = if (skipRefs) Pron.skipScripture(t, books) else Pron.scripture(t, books)
        }
        for (c in compiled) t = c.run(t)
        if (builtin) t = Pron.misc(t)
        return t
    }
}

