package com.dante.edgeaudiobook

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private fun writeAtomic(f: File, bytes: ByteArray) {
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, f.name + "." + System.nanoTime() + ".tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(f)) {
        f.delete()
        if (!tmp.renameTo(f)) { tmp.delete(); error("Could not save ${f.name}") }
    }
}

/**
 * Finished MP3 pieces, one file per chunk of text. The name is a hash of the text AND the
 * voice / speed / pitch / emphasis settings, so a changed setting can never reuse an old piece.
 */
object ChunkCache {
    private fun dir(ctx: Context) = File(ctx.filesDir, "chunks").apply { mkdirs() }

    fun key(chunk: String, cfg: JobConfig): String {
        val raw = "v1|${cfg.voice}|${cfg.rate}|${cfg.pitch}|${EmphStore.toJson(cfg.emph)}|$chunk"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun has(ctx: Context, key: String): Boolean =
        File(dir(ctx), "$key.mp3").let { it.isFile && it.length() > 0 }

    fun get(ctx: Context, key: String): ByteArray? {
        val f = File(dir(ctx), "$key.mp3")
        if (!f.isFile || f.length() == 0L) return null
        return try { f.readBytes() } catch (_: Exception) { null }
    }

    fun put(ctx: Context, key: String, data: ByteArray) {
        if (data.isEmpty()) return
        try { writeAtomic(File(dir(ctx), "$key.mp3"), data) } catch (_: Exception) {}
    }

    fun remove(ctx: Context, keys: Collection<String>) {
        for (k in keys) try { File(dir(ctx), "$k.mp3").delete() } catch (_: Exception) {}
    }

    fun size(ctx: Context): Long =
        (dir(ctx).listFiles() ?: emptyArray()).filter { it.name.endsWith(".mp3") }.sumOf { it.length() }

    fun clear(ctx: Context) {
        (dir(ctx).listFiles() ?: emptyArray()).forEach { try { it.delete() } catch (_: Exception) {} }
    }

    /** Housekeeping: drop pieces nobody came back for, and leftover temp files. */
    fun purgeOld(ctx: Context, maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        (dir(ctx).listFiles() ?: emptyArray()).forEach {
            if (now - it.lastModified() > maxAgeMs || it.name.endsWith(".tmp")) {
                try { it.delete() } catch (_: Exception) {}
            }
        }
    }
}

/** Saves the generation queue so it survives the app being killed. */
object JobStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "session").apply { mkdirs() }

    /** The queue itself (names, file names, text, settings). Written once per submit. */
    @Synchronized
    fun saveDef(ctx: Context, items: List<JobItem>, cfg: JobConfig) {
        val a = JSONArray()
        for (it in items) a.put(JSONObject().put("name", it.name).put("file", it.fileName).put("text", it.text))
        val c = JSONObject()
            .put("voice", cfg.voice).put("rate", cfg.rate).put("pitch", cfg.pitch)
            .put("readTitle", cfg.readTitle)
            .put("treeUri", cfg.treeUri ?: JSONObject.NULL)
            .put("folder", cfg.folder)
            .put("rules", PronStore.toJson(cfg.rules))
            .put("builtinRefs", cfg.builtinRefs)
            .put("emph", EmphStore.toJson(cfg.emph))
            .put("skipRefs", cfg.skipRefs).put("skipSuper", cfg.skipSuper)
            .put("artist", cfg.artist).put("album", cfg.album).put("parallel", cfg.parallel)
        val cover = File(dir(ctx), "job_cover.jpg")
        if (cfg.cover != null) writeAtomic(cover, cfg.cover) else cover.delete()
        writeAtomic(File(dir(ctx), "job_def.json"), JSONObject().put("cfg", c).put("items", a).toString().toByteArray())
        writeAtomic(File(dir(ctx), "job_state.json"), "[]".toByteArray())
    }

    /** Status of each chapter. Small, written often. */
    @Synchronized
    fun saveState(ctx: Context, items: List<JobItem>) {
        if (items.isEmpty()) return
        val a = JSONArray()
        for (it in items) a.put(
            JSONObject().put("s", it.status.name).put("note", it.note).put("uri", it.outUri ?: JSONObject.NULL)
        )
        writeAtomic(File(dir(ctx), "job_state.json"), a.toString().toByteArray())
    }

    /** Loads the saved queue into [Jobs] — unless a run is alive in this process already. */
    @Synchronized
    fun restore(ctx: Context) {
        if (Jobs.running.value || Jobs.items.value.isNotEmpty()) return
        val defFile = File(dir(ctx), "job_def.json")
        if (!defFile.exists()) return
        try {
            val o = JSONObject(defFile.readText())
            val c = o.getJSONObject("cfg")
            val coverFile = File(dir(ctx), "job_cover.jpg")
            val cfg = JobConfig(
                voice = c.getString("voice"), rate = c.getInt("rate"), pitch = c.getInt("pitch"),
                readTitle = c.optBoolean("readTitle", true),
                treeUri = if (c.isNull("treeUri")) null else c.getString("treeUri"),
                folder = c.optString("folder"),
                rules = PronStore.fromJson(c.optString("rules")),
                builtinRefs = c.optBoolean("builtinRefs", true),
                emph = EmphStore.fromJson(c.optString("emph")),
                skipRefs = c.optBoolean("skipRefs"), skipSuper = c.optBoolean("skipSuper"),
                cover = if (coverFile.exists()) coverFile.readBytes() else null,
                artist = c.optString("artist"), album = c.optString("album"),
                parallel = c.optInt("parallel", 3)
            )
            val arr = o.getJSONArray("items")
            val stFile = File(dir(ctx), "job_state.json")
            val st = if (stFile.exists()) JSONArray(stFile.readText()) else JSONArray()
            val list = (0 until arr.length()).map { i ->
                val it = arr.getJSONObject(i)
                val s = st.optJSONObject(i)
                var status = s?.optString("s")?.let { n -> St.values().firstOrNull { v -> v.name == n } } ?: St.QUEUED
                var note = s?.optString("note") ?: ""
                val uri = if (s == null || s.isNull("uri")) null else s.optString("uri").ifBlank { null }
                if (status == St.QUEUED || status == St.RUNNING) {
                    status = St.CANCELLED
                    note = "Interrupted — tap Retry to continue"
                }
                JobItem(
                    it.getString("name"), it.getString("file"), it.getString("text"),
                    status, if (status == St.DONE) 1f else 0f, if (status == St.DONE) "" else note, uri
                )
            }
            Jobs.config = cfg
            Jobs.items.value = list
        } catch (_: Exception) {
        }
    }
}

data class SessionSnap(
    val chapters: List<Chapter>,
    val draftStart: Int?,
    val draftEnd: Int?,
    val draftName: String
)

class SavedBook(val book: Book, val cover: ByteArray?)

/** Saves the opened book and the chapter list so the app reopens exactly as it was left. */
object SessionStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "session").apply { mkdirs() }

    fun saveBook(ctx: Context, b: Book, cover: ByteArray?) {
        val toc = JSONArray()
        for (e in b.toc) toc.put(JSONArray().put(e.label).put(e.start).put(e.depth))
        val meta = JSONObject()
            .put("title", b.title).put("words", b.words)
            .put("tocSource", b.tocSource).put("toc", toc)
        writeAtomic(File(dir(ctx), "book.txt"), b.text.toByteArray(Charsets.UTF_8))
        writeAtomic(File(dir(ctx), "book.json"), meta.toString().toByteArray())
        val cf = File(dir(ctx), "book_cover.jpg")
        if (cover != null) writeAtomic(cf, cover) else cf.delete()
    }

    fun loadBook(ctx: Context): SavedBook? {
        val tf = File(dir(ctx), "book.txt")
        val mf = File(dir(ctx), "book.json")
        if (!tf.exists() || !mf.exists()) return null
        return try {
            val m = JSONObject(mf.readText())
            val tocArr = m.optJSONArray("toc") ?: JSONArray()
            val toc = (0 until tocArr.length()).map { i ->
                val e = tocArr.getJSONArray(i)
                TocEntry(e.getString(0), e.getInt(1), e.getInt(2))
            }
            val cf = File(dir(ctx), "book_cover.jpg")
            SavedBook(
                Book(
                    m.getString("title"), tf.readText(Charsets.UTF_8), m.optInt("words"),
                    toc, m.optString("tocSource"), null
                ),
                if (cf.exists()) cf.readBytes() else null
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveChapters(ctx: Context, s: SessionSnap) {
        val a = JSONArray()
        for (c in s.chapters) a.put(
            JSONObject().put("id", c.id).put("name", c.name).put("start", c.start)
                .put("end", c.end ?: JSONObject.NULL).put("inc", c.include)
        )
        val o = JSONObject().put("chapters", a)
            .put("ds", s.draftStart ?: -1).put("de", s.draftEnd ?: -1).put("dn", s.draftName)
        writeAtomic(File(dir(ctx), "chapters.json"), o.toString().toByteArray())
    }

    fun loadChapters(ctx: Context): SessionSnap {
        val f = File(dir(ctx), "chapters.json")
        if (!f.exists()) return SessionSnap(emptyList(), null, null, "")
        return try {
            val o = JSONObject(f.readText())
            val a = o.getJSONArray("chapters")
            val list = (0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                Chapter(
                    c.getLong("id"), c.getString("name"), c.getInt("start"),
                    if (c.isNull("end")) null else c.getInt("end"), c.optBoolean("inc", true)
                )
            }
            SessionSnap(
                list,
                o.optInt("ds", -1).takeIf { it >= 0 },
                o.optInt("de", -1).takeIf { it >= 0 },
                o.optString("dn")
            )
        } catch (_: Exception) {
            SessionSnap(emptyList(), null, null, "")
        }
    }
}
