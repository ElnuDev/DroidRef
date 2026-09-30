package com.xiaopo.flying.sticker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Original image data, kept on disk and keyed by SHA-256 so that only decoded
 * bitmaps live in memory. Boards embed these bytes when saved.
 */
object BlobStore {
    private var dir: File? = null

    fun init(directory: File) {
        directory.mkdirs()
        dir = directory
    }

    /** Drop blobs left over from a previous process; call before loading anything. */
    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
    }

    fun file(key: String) = File(checkNotNull(dir) { "BlobStore not initialized" }, key)

    fun put(bytes: ByteArray): String {
        val key = sha256(bytes)
        val f = file(key)
        if (!f.exists()) {
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
        }
        return key
    }

    fun putBitmap(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return put(stream.toByteArray())
    }

    fun read(key: String): ByteArray = file(key).readBytes()

    fun open(key: String): InputStream = file(key).inputStream()

    fun size(key: String): Long = file(key).length()

    fun copyTo(key: String, out: OutputStream) {
        open(key).use { it.copyTo(out) }
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        digest.forEach { sb.append("%02x".format(it)) }
        return sb.toString()
    }

    /** File extension matching the image data's magic number. */
    fun extensionOf(key: String): String {
        val header = ByteArray(12)
        val n = open(key).use { it.read(header) }
        return when {
            n >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() -> "jpg"
            n >= 8 && header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte() -> "png"
            n >= 6 && String(header, 0, 3) == "GIF" -> "gif"
            n >= 12 && String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WEBP" -> "webp"
            n >= 2 && String(header, 0, 2) == "BM" -> "bmp"
            else -> "png"
        }
    }
}

object ImageLoader {
    /** Cap on decoded pixels per image; larger images are downsampled for display. */
    const val MAX_PIXELS = 2560 * 2560

    /** Pixel size of image data without decoding it, or null if it isn't an image. */
    fun size(bytes: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    /**
     * Size of the coordinate space an image is placed and cropped in: its own
     * size, scaled down to at most [MAX_PIXELS]. Only [ImageCache] decides which
     * pixels actually get drawn into it.
     */
    fun canonicalSize(width: Int, height: Int): Pair<Int, Int> {
        val total = width.toLong() * height
        if (total <= MAX_PIXELS) {
            return width to height
        }
        val scale = Math.sqrt(MAX_PIXELS.toDouble() / total)
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    /** Decodes image data at full resolution, downsampling huge images to about [MAX_PIXELS]. */
    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        var sample = 1
        while ((bounds.outWidth / (sample * 2)).toLong() * (bounds.outHeight / (sample * 2)) >= MAX_PIXELS) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return fit(bitmap)
    }

    fun fit(bitmap: Bitmap): Bitmap {
        val total = bitmap.width.toLong() * bitmap.height
        if (total <= MAX_PIXELS) {
            return bitmap
        }
        val scale = Math.sqrt(MAX_PIXELS.toDouble() / total)
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) {
            bitmap.recycle()
        }
        return scaled
    }
}
