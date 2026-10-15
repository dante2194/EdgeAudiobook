package com.dante.edgeaudiobook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

object Cover {
    /** Decodes any image and returns a JPEG no larger than [max] pixels on its long side. */
    fun prepare(bytes: ByteArray, max: Int = 800): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > max * 2 || bounds.outHeight / sample > max * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else bmp
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        return bos.toByteArray()
    }
}

/** Minimal ID3v2.3 writer: title, track, album, artist, genre and front-cover picture. */
object Id3 {
    private fun utf16(s: String): ByteArray =
        byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)

    private fun frame(id: String, data: ByteArray): ByteArray {
        val h = ByteArray(10)
        id.toByteArray(Charsets.US_ASCII).copyInto(h, 0)
        h[4] = (data.size ushr 24).toByte()
        h[5] = (data.size ushr 16).toByte()
        h[6] = (data.size ushr 8).toByte()
        h[7] = data.size.toByte()
        return h + data
    }

    private fun text(id: String, s: String): ByteArray? = if (s.isBlank()) null else frame(id, utf16(s))

    fun build(title: String, album: String, artist: String, track: Int, total: Int, cover: ByteArray?): ByteArray {
        val frames = ArrayList<ByteArray>()
        val textFrames = listOf(
            text("TIT2", title),
            text("TALB", album),
            text("TPE1", artist),
            text("TRCK", "$track/$total"),
            text("TCON", "Audiobook")
        )
        for (f in textFrames) if (f != null) frames.add(f)
        if (cover != null) {
            val mime = "image/jpeg".toByteArray(Charsets.ISO_8859_1)
            // encoding 0, mime, NUL, picture type 3 (front cover), empty description + NUL, image data
            val data = byteArrayOf(0) + mime + byteArrayOf(0, 3, 0) + cover
            frames.add(frame("APIC", data))
        }
        var body = ByteArray(0)
        for (f in frames) body += f
        val size = body.size
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte()
        )
        return header + body
    }
}
