package com.dante.edgeaudiobook

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("edge_audiobook", Context.MODE_PRIVATE)

    var voice: String
        get() = sp.getString("voice", "en-US-AriaNeural") ?: "en-US-AriaNeural"
        set(v) = sp.edit().putString("voice", v).apply()
    var rate: Int
        get() = sp.getInt("rate", 0)
        set(v) = sp.edit().putInt("rate", v).apply()
    var pitch: Int
        get() = sp.getInt("pitch", 0)
        set(v) = sp.edit().putInt("pitch", v).apply()
    var readTitle: Boolean
        get() = sp.getBoolean("readTitle", true)
        set(v) = sp.edit().putBoolean("readTitle", v).apply()
    var treeUri: String?
        get() = sp.getString("treeUri", null)
        set(v) = sp.edit().putString("treeUri", v).apply()
    var folder: String
        get() = sp.getString("folder", "") ?: ""
        set(v) = sp.edit().putString("folder", v).apply()
    var template: String
        get() = sp.getString("template", "{n} - {title}") ?: "{n} - {title}"
        set(v) = sp.edit().putString("template", v).apply()
    var ext: String
        get() = sp.getString("ext", "m4a") ?: "m4a"
        set(v) = sp.edit().putString("ext", v).apply()
    var chromium: String
        get() = sp.getString("chromium", "143.0.3650.75") ?: "143.0.3650.75"
        set(v) = sp.edit().putString("chromium", v).apply()
    var voicesJson: String
        get() = sp.getString("voicesJson", "") ?: ""
        set(v) = sp.edit().putString("voicesJson", v).apply()
    var rulesJson: String
        get() = sp.getString("rulesJson", "") ?: ""
        set(v) = sp.edit().putString("rulesJson", v).apply()
    var builtinRefs: Boolean
        get() = sp.getBoolean("builtinRefs", true)
        set(v) = sp.edit().putBoolean("builtinRefs", v).apply()
    var emphJson: String
        get() = sp.getString("emphJson", "") ?: ""
        set(v) = sp.edit().putString("emphJson", v).apply()
    var skipRefs: Boolean
        get() = sp.getBoolean("skipRefs", false)
        set(v) = sp.edit().putBoolean("skipRefs", v).apply()
    var skipSuper: Boolean
        get() = sp.getBoolean("skipSuper", false)
        set(v) = sp.edit().putBoolean("skipSuper", v).apply()
    var artist: String
        get() = sp.getString("artist", "") ?: ""
        set(v) = sp.edit().putString("artist", v).apply()
    var album: String
        get() = sp.getString("album", "") ?: ""
        set(v) = sp.edit().putString("album", v).apply()
    var useEpubCover: Boolean
        get() = sp.getBoolean("useEpubCover", true)
        set(v) = sp.edit().putBoolean("useEpubCover", v).apply()
    var parallel: Int
        get() = sp.getInt("parallel", 3)
        set(v) = sp.edit().putInt("parallel", v).apply()
    var tab: Int
        get() = sp.getInt("tab", 0)
        set(v) = sp.edit().putInt("tab", v).apply()
    var scrollOffset: Int
        get() = sp.getInt("scrollOffset", 0)
        set(v) = sp.edit().putInt("scrollOffset", v).apply()
}

class OutputSink(val stream: OutputStream, val uri: Uri?, private val onClose: (Boolean) -> Unit) {
    fun close(success: Boolean) {
        try { stream.close() } catch (_: Exception) {}
        onClose(success)
    }
}

object Sinks {
    /** Removes a half-written output file left behind by a crash or a killed app. */
    fun deleteQuietly(ctx: Context, uriStr: String) {
        val u = try { Uri.parse(uriStr) } catch (_: Exception) { return }
        try { if (ctx.contentResolver.delete(u, null, null) > 0) return } catch (_: Exception) {}
        try { DocumentFile.fromSingleUri(ctx, u)?.delete() } catch (_: Exception) {}
    }

    /**
     * Creates the output file. The data written is MP3; the name just ends in [name]'s extension.
     * The MIME type is derived from that extension so Android keeps the name unchanged.
     */
    fun open(ctx: Context, treeUri: String?, folder: String, name: String): OutputSink {
        val ext = name.substringAfterLast('.', "m4a").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "audio/mp4"

        if (!treeUri.isNullOrBlank()) {
            val root = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri))
                ?: error("Output folder is unavailable — pick it again")
            val f = root.createFile(mime, name) ?: error("Could not create $name in the chosen folder")
            val os = ctx.contentResolver.openOutputStream(f.uri) ?: error("Cannot open output file")
            return OutputSink(os, f.uri) { ok -> if (!ok) f.delete() }
        }

        val resolver = ctx.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + TextOps.safe(folder))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: error("Could not create $name in Downloads")
        val os = resolver.openOutputStream(uri) ?: error("Cannot open output file")
        return OutputSink(os, uri) { ok ->
            if (ok) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } else {
                resolver.delete(uri, null, null)
            }
        }
    }
}
