package com.dante.edgeaudiobook

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.debounce
import java.io.File

/** Plain class: compared by identity so Compose does not deep-compare thousands of matches. */
class SearchState(val ranges: List<IntRange>)

class Vm(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)

    // ----- book / reader -----
    var book by mutableStateOf<Book?>(null)
    var blocks by mutableStateOf(IntArray(0))
    val listState = LazyListState()
    var jumpOffset by mutableStateOf<Int?>(null)
    var selStart by mutableStateOf<Int?>(null)
    var selEnd by mutableStateOf<Int?>(null)

    // ----- search -----
    var searchQuery by mutableStateOf("")
    var matches by mutableStateOf(SearchState(emptyList()))
    var matchIdx by mutableIntStateOf(0)
    private var searchJob: Job? = null
    var loading by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    // ----- chapters -----
    val chapters = mutableStateListOf<Chapter>()
    var draftStart by mutableStateOf<Int?>(null)
    var draftEnd by mutableStateOf<Int?>(null)
    var draftName by mutableStateOf("")

    // ----- voice settings (persisted) -----
    var voices by mutableStateOf<List<Voice>>(emptyList())
    var voicesLoading by mutableStateOf(false)
    var previewing by mutableStateOf(false)
    var sample by mutableStateOf("Hello! This is how your audiobook will sound with this voice.")
    var voice by mutableStateOf(prefs.voice)
    var rate by mutableIntStateOf(prefs.rate)
    var pitch by mutableIntStateOf(prefs.pitch)
    var readTitle by mutableStateOf(prefs.readTitle)
    var chromium by mutableStateOf(prefs.chromium)

    // ----- pronunciation rules (persisted) -----
    val rules = mutableStateListOf<PronRule>()
    var builtinRefs by mutableStateOf(prefs.builtinRefs)
    var skipRefs by mutableStateOf(prefs.skipRefs)
    var skipSuper by mutableStateOf(prefs.skipSuper)

    // ----- emphasis (persisted) -----
    var emph by mutableStateOf(EmphStore.fromJson(prefs.emphJson))

    // ----- export settings (persisted) -----
    var treeUri by mutableStateOf(prefs.treeUri)
    var folder by mutableStateOf(prefs.folder)
    var template by mutableStateOf(prefs.template)
    var ext by mutableStateOf(prefs.ext)

    // ----- album photo + tags (persisted) -----
    var artist by mutableStateOf(prefs.artist)
    var album by mutableStateOf(prefs.album)
    var useEpubCover by mutableStateOf(prefs.useEpubCover)
    var parallel by mutableIntStateOf(prefs.parallel)
    var coverBitmap by mutableStateOf<ImageBitmap?>(null)
    private var coverJpeg: ByteArray? = null
    var bookCover: ByteArray? = null

    private var player: MediaPlayer? = null

    init {
        EdgeTts.chromium = prefs.chromium
        rules.addAll(PronStore.fromJson(prefs.rulesJson))
        val f = File(app.filesDir, "cover.jpg")
        if (f.exists()) applyCover(try { f.readBytes() } catch (e: Exception) { null })
        loadVoices()
        restoreSession()
    }

    // ---------- saved session ----------

    /** Brings back the book, chapters, draft, scroll position and generation queue from last time. */
    private fun restoreSession() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                class Loaded(val saved: SavedBook, val snap: SessionSnap, val blocks: IntArray)
                val loaded = withContext(Dispatchers.IO) {
                    JobStore.restore(app)
                    ChunkCache.purgeOld(app, 7L * 24 * 3600 * 1000)
                    SessionStore.loadBook(app)?.let {
                        Loaded(it, SessionStore.loadChapters(app), TextOps.blocks(it.book.text))
                    }
                }
                if (loaded != null && book == null) {
                    book = loaded.saved.book
                    blocks = loaded.blocks
                    bookCover = loaded.saved.cover
                    chapters.clear()
                    chapters.addAll(loaded.snap.chapters)
                    IdGen.bump(loaded.snap.chapters.maxOfOrNull { it.id } ?: 0L)
                    draftStart = loaded.snap.draftStart
                    draftEnd = loaded.snap.draftEnd
                    draftName = loaded.snap.draftName
                    jumpOffset = prefs.scrollOffset.coerceIn(0, loaded.saved.book.text.length)
                    message = "Restored your last session"
                }
            } catch (e: Exception) {
                message = "Could not restore the last session: ${e.message}"
            }
            startAutosave()
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startAutosave() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            snapshotFlow { SessionSnap(chapters.toList(), draftStart, draftEnd, draftName) }
                .debounce(400)
                .collect { s ->
                    withContext(Dispatchers.IO) { try { SessionStore.saveChapters(app, s) } catch (_: Exception) {} }
                }
        }
        viewModelScope.launch {
            snapshotFlow { listState.firstVisibleItemIndex }
                .debounce(600)
                .collect { i ->
                    val b = blocks
                    if (book != null && jumpOffset == null && i in b.indices) prefs.scrollOffset = b[i]
                }
        }
    }

    // ---------- album photo ----------

    private fun applyCover(jpg: ByteArray?) {
        coverJpeg = jpg
        coverBitmap = jpg?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }

    fun pickCover(uri: Uri) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val raw = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                val jpg = raw?.let { withContext(Dispatchers.Default) { Cover.prepare(it) } }
                    ?: error("Could not read that image")
                withContext(Dispatchers.IO) { File(app.filesDir, "cover.jpg").writeBytes(jpg) }
                applyCover(jpg)
            } catch (e: Exception) {
                message = "Photo failed: ${e.message}"
            }
        }
    }

    fun clearCover() {
        File(getApplication<Application>().filesDir, "cover.jpg").delete()
        applyCover(null)
    }

    // ---------- pronunciation rules ----------

    private fun saveRules() {
        prefs.rulesJson = PronStore.toJson(rules)
        prefs.builtinRefs = builtinRefs
        prefs.skipRefs = skipRefs
        prefs.skipSuper = skipSuper
    }

    fun setSkip(refs: Boolean? = null, sup: Boolean? = null) {
        if (refs != null) skipRefs = refs
        if (sup != null) skipSuper = sup
        saveRules()
    }

    fun addSkipNotesRule() {
        rules.add(
            PronRule(
                IdGen.next(),
                """^(?:notes|footnotes|endnotes|references|bibliography)\s*:?$""",
                "", PronRule.SECTION
            )
        )
        saveRules()
    }

    fun setBuiltin(v: Boolean) { builtinRefs = v; saveRules() }

    fun updateEmph(e: Emph) {
        emph = e
        prefs.emphJson = EmphStore.toJson(e)
    }

    fun upsertRule(r: PronRule) {
        val i = rules.indexOfFirst { it.id == r.id }
        if (i >= 0) rules[i] = r else rules.add(r)
        saveRules()
    }

    fun deleteRule(id: Long) { rules.removeAll { it.id == id }; saveRules() }

    fun addExampleRules() {
        // Order matters: the range form must come before the single-verse form.
        rules.add(PronRule(IdGen.next(), "#:#-#", "chapter #, verses # to #", PronRule.PATTERN))
        rules.add(PronRule(IdGen.next(), "#:#", "chapter #, verse #", PronRule.PATTERN))
        saveRules()
    }

    /** Run [block] to change settings, then persist everything. */
    fun update(block: Vm.() -> Unit) {
        block()
        prefs.voice = voice
        prefs.rate = rate
        prefs.pitch = pitch
        prefs.readTitle = readTitle
        prefs.chromium = chromium
        prefs.treeUri = treeUri
        prefs.folder = folder
        prefs.template = template
        prefs.ext = ext
        prefs.artist = artist
        prefs.album = album
        prefs.useEpubCover = useEpubCover
        prefs.parallel = parallel
        EdgeTts.chromium = chromium.ifBlank { "143.0.3650.75" }
    }

    // ---------- book ----------

    fun loadBook(uri: Uri) {
        loading = true
        viewModelScope.launch {
            try {
                val b = withContext(Dispatchers.IO) { BookLoader.load(getApplication<Application>(), uri) }
                if (b.text.isBlank()) error("The file has no readable text")
                book = b
                blocks = TextOps.blocks(b.text)
                chapters.clear()
                draftStart = null
                draftEnd = null
                draftName = ""
                selStart = null
                selEnd = null
                searchJob?.cancel()
                searchQuery = ""
                matches = SearchState(emptyList())
                matchIdx = 0
                jumpOffset = 0
                bookCover = b.coverRaw?.let { raw -> withContext(Dispatchers.Default) { Cover.prepare(raw) } }
                prefs.scrollOffset = 0
                try {
                    withContext(Dispatchers.IO) { SessionStore.saveBook(getApplication<Application>(), b, bookCover) }
                } catch (e: Exception) {
                    message = "Opened, but could not save it for next time: ${e.message}"
                }
                message = "Loaded ${b.words} words" +
                    (if (b.toc.isNotEmpty()) " · table of contents found (${b.toc.size} entries)" else "")
            } catch (e: Exception) {
                message = "Could not open file: ${e.message}"
            }
            loading = false
        }
    }

    fun snippet(off: Int?, forward: Boolean = true): String {
        val t = book?.text ?: return ""
        if (off == null) return "—"
        val o = off.coerceIn(0, t.length)
        val s = if (forward) t.substring(o, minOf(t.length, o + 50)) else t.substring(maxOf(0, o - 50), o)
        return (if (forward) "“$s…”" else "“…$s”").replace('\n', ' ')
    }

    // ---------- chapters ----------

    private fun sortChapters() { chapters.sortBy { it.start } }

    fun edit(id: Long, f: (Chapter) -> Chapter) {
        val i = chapters.indexOfFirst { it.id == id }
        if (i >= 0) chapters[i] = f(chapters[i])
    }

    fun remove(id: Long) { chapters.removeAll { it.id == id } }

    fun addDraft() {
        val b = book ?: return
        val s = draftStart ?: return
        val e = draftEnd?.takeIf { it > s }
        chapters.add(
            Chapter(
                IdGen.next(),
                draftName.ifBlank { "Chapter ${chapters.size + 1}" },
                s.coerceIn(0, b.text.length),
                e
            )
        )
        sortChapters()
        draftStart = null
        draftEnd = null
        draftName = ""
    }

    fun addByPhrases(name: String, startPhrase: String, endPhrase: String): Boolean {
        val b = book ?: return false
        val t = b.text
        val from = chapters.maxOfOrNull { it.end ?: (it.start + 1) } ?: 0
        val s = TextOps.find(t, startPhrase, from) ?: TextOps.find(t, startPhrase, 0)
        if (s == null) {
            message = "Start text not found"
            return false
        }
        var endOff: Int? = null
        if (endPhrase.isNotBlank()) {
            val e = TextOps.find(t, endPhrase, s.last + 1)
            if (e == null) {
                message = "End text not found after the start"
                return false
            }
            endOff = e.last + 1
        }
        chapters.add(Chapter(IdGen.next(), name.ifBlank { "Chapter ${chapters.size + 1}" }, s.first, endOff))
        sortChapters()
        return true
    }

    fun tocLevels(): Int = book?.toc?.maxOfOrNull { it.depth } ?: 0

    fun tocPreview(level: Int, minWords: Int): List<Chapter> {
        val b = book ?: return emptyList()
        return TocFinder.toChapters(b, level, minWords)
    }

    fun applyToc(level: Int, minWords: Int, replace: Boolean) {
        val found = tocPreview(level, minWords)
        if (found.isEmpty()) {
            message = "Nothing found at this level"
            return
        }
        if (replace) chapters.clear()
        chapters.addAll(found)
        sortChapters()
        message = "Created ${found.size} chapters from the table of contents"
    }

    /** Advanced: pattern scan instead of the table of contents. */
    fun autoDetect(pattern: String, minWords: Int, replace: Boolean) {
        val b = book ?: return
        try {
            val found = TextOps.detect(b.text, pattern, minWords)
            if (replace) chapters.clear()
            chapters.addAll(found)
            sortChapters()
            message = "Found ${found.size} sections"
        } catch (e: Exception) {
            message = "Invalid pattern: ${e.message}"
        }
    }

    // ---------- search ----------

    fun setQuery(q: String) {
        searchQuery = q
        searchJob?.cancel()
        val text = book?.text ?: return
        if (q.trim().length < 2) {
            matches = SearchState(emptyList())
            matchIdx = 0
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            val found = withContext(Dispatchers.Default) { TextOps.findAll(text, q, 3000) }
            matches = SearchState(found)
            matchIdx = 0
            if (found.isNotEmpty()) jumpOffset = found[0].first
        }
    }

    fun stepMatch(d: Int) {
        val r = matches.ranges
        if (r.isEmpty()) return
        matchIdx = (matchIdx + d + r.size) % r.size
        jumpOffset = r[matchIdx].first
    }

    // ---------- voices ----------

    fun loadVoices() {
        if (voicesLoading) return
        voicesLoading = true
        viewModelScope.launch {
            try {
                val json = EdgeTts.fetchVoicesJson()
                val list = EdgeTts.parseVoices(json)
                if (list.isNotEmpty()) {
                    voices = list
                    prefs.voicesJson = json
                }
            } catch (e: Exception) {
                val cached = try { EdgeTts.parseVoices(prefs.voicesJson) } catch (_: Exception) { emptyList() }
                voices = cached.ifEmpty { EdgeTts.fallbackVoices() }
                message = "Couldn't refresh voice list (${e.message}) — using saved/built-in list"
            }
            voicesLoading = false
        }
    }

    /** [method]: null = current settings, -1 = no emphasis, 0/1/2 = force that method. */
    fun preview(text: String? = null, method: Int? = null) {
        if (previewing) return
        previewing = true
        viewModelScope.launch {
            try {
                val src = (text ?: sample).ifBlank { "Hello." }
                val e = when (method) {
                    null -> emph
                    -1 -> emph.copy(enabled = false)
                    else -> emph.copy(enabled = true, method = method)
                }
                val paras = TextOps.speechParagraphs(src, e.skipForeign).ifEmpty { listOf(src) }
                val spoken = Pronouncer(rules.toList(), builtinRefs, skipRefs, skipSuper).apply(paras.joinToString(" "))
                val bytes = EdgeTts.speak(spoken, voice, rate, pitch, e, tries = 2)
                if (bytes.isEmpty()) error("Nothing to speak")
                val f = File(getApplication<Application>().cacheDir, "preview.mp3")
                withContext(Dispatchers.IO) { f.writeBytes(bytes) }
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    setOnCompletionListener { previewing = false }
                    setOnErrorListener { _, _, _ -> previewing = false; true }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                message = "Preview failed: ${e.message}"
                previewing = false
            }
        }
    }

    // ---------- export ----------

    fun startExport(ctx: Context) {
        val b = book ?: run { message = "Open a book first"; return }
        if (Jobs.running.value) { message = "Already generating"; return }
        val spans = TextOps.spans(chapters, b.text.length).filter { it.ch.include && it.end > it.start }
        if (spans.isEmpty()) { message = "No chapters selected"; return }

        val width = maxOf(2, spans.size.toString().length)
        val extension = ext.trim().trimStart('.').ifBlank { "m4a" }
        val items = spans.mapIndexed { i, s ->
            val base = TextOps.safe(
                template.ifBlank { "{n} - {title}" }
                    .replace("{n}", (i + 1).toString().padStart(width, '0'))
                    .replace("{title}", s.ch.name)
                    .replace("{book}", b.title)
            )
            JobItem(s.ch.name, "$base.$extension", b.text.substring(s.start, s.end))
        }
        val cover = coverJpeg ?: (if (useEpubCover) bookCover else null)
        Jobs.submit(
            items,
            JobConfig(
                voice, rate, pitch, readTitle, treeUri, folder.ifBlank { b.title },
                rules.toList(), builtinRefs, emph, skipRefs, skipSuper, cover, artist, album, parallel
            )
        )
        ContextCompat.startForegroundService(ctx, Intent(ctx, TtsService::class.java))
    }

    /** Re-queues failed / interrupted chapters. Finished pieces come from the cache. */
    fun retryFailed(ctx: Context) {
        if (Jobs.running.value) { message = "Already generating"; return }
        if (Jobs.config == null) { message = "Nothing to retry"; return }
        if (Jobs.requeueFailed() == 0) { message = "Nothing to retry"; return }
        ContextCompat.startForegroundService(ctx, Intent(ctx, TtsService::class.java))
    }

    override fun onCleared() {
        player?.release()
        super.onCleared()
    }
}
