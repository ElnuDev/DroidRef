package xyz.ruin.droidref

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.xiaopo.flying.sticker.BlobStore
import com.xiaopo.flying.sticker.DrawableSticker
import com.xiaopo.flying.sticker.ImageLoader
import com.xiaopo.flying.sticker.Sticker
import com.xiaopo.flying.sticker.StickerViewSerializer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * File and network I/O for boards and images. Everything here blocks, so call
 * it off the main thread.
 */
class BoardIO(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val resources get() = context.resources

    // region Importing

    fun displayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        return cursor.getString(0)
                    }
                }
            } catch (e: Exception) {
                // Some providers don't support queries; fall back to the path.
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    /** Decodes an image into an item named after its file, or null if it isn't one. */
    fun loadImage(uri: Uri): Sticker? {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return imageFromBytes(bytes, displayName(uri)?.substringBeforeLast('.'))
    }

    fun imageFromBytes(bytes: ByteArray, name: String?, source: String? = null): Sticker? {
        val bitmap = ImageLoader.decode(bytes) ?: return null
        val key = BlobStore.put(bytes)
        return DrawableSticker(BitmapDrawable(resources, bitmap), key).also {
            it.name = name
            it.source = source
        }
    }

    fun imageFromBitmap(bitmap: Bitmap, name: String?): Sticker {
        val fitted = ImageLoader.fit(bitmap)
        return DrawableSticker(BitmapDrawable(resources, fitted), BlobStore.putBitmap(fitted)).also { it.name = name }
    }

    /** Images directly inside a folder picked with OpenDocumentTree, sorted by name. */
    fun imagesInTree(tree: Uri): List<Uri> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val found = ArrayList<Pair<String, Uri>>()
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val mime = cursor.getString(2) ?: continue
                if (mime.startsWith("image/")) {
                    found.add(cursor.getString(1) to DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)))
                }
            }
        }
        return found.sortedWith(compareBy(com.xiaopo.flying.sticker.NaturalOrder) { it.first }).map { it.second }
    }

    /** Downloads an image; throws with a readable message on failure. */
    fun download(url: String): Sticker {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("Server responded $code")
            }
            val type = connection.contentType ?: ""
            if (type.isNotEmpty() && !type.startsWith("image/")) {
                throw IOException("Link is not an image ($type)")
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            val name = Uri.parse(url).lastPathSegment?.substringBeforeLast('.')
            return imageFromBytes(bytes, name, url) ?: throw IOException("Could not decode image")
        } finally {
            connection.disconnect()
        }
    }

    // endregion

    // region Boards

    fun readBoard(uri: Uri): StickerViewSerializer.Board =
        resolver.openInputStream(uri)?.use { StickerViewSerializer().read(it, resources) }
            ?: throw IOException("Could not open $uri")

    fun readBoard(file: File): StickerViewSerializer.Board =
        file.inputStream().use { StickerViewSerializer().read(it, resources) }

    fun writeBoard(uri: Uri, canvasMatrix: Matrix, stickers: List<Sticker>) {
        // Write fully to memory first so a failure can't truncate the existing file.
        val buffer = ByteArrayOutputStream()
        StickerViewSerializer().write(buffer, canvasMatrix, stickers)
        (resolver.openOutputStream(uri, "wt") ?: throw IOException("Could not write $uri")).use {
            buffer.writeTo(it)
        }
    }

    fun writeBoard(file: File, canvasMatrix: Matrix, stickers: List<Sticker>) {
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { StickerViewSerializer().write(it, canvasMatrix, stickers) }
        if (!tmp.renameTo(file)) {
            throw IOException("Could not replace $file")
        }
    }

    // endregion

    // region Exporting

    /**
     * Saves each image separately: the original file if uncropped, otherwise the
     * cropped area as PNG. Returns the number written.
     */
    fun exportImages(stickers: List<Sticker>, folder: String): Int {
        var written = 0
        val used = HashSet<String>()
        stickers.filterIsInstance<DrawableSticker>().forEachIndexed { i, sticker ->
            var base = (sticker.name ?: "image").replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "image" }
            if (!used.add(base)) base = "${base}_${i + 1}"
            if (sticker.isCropped) {
                val bitmap = sticker.croppedBitmap
                savePicture("$base.png", "image/png", folder) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } else {
                val ext = BlobStore.extensionOf(sticker.blobKey)
                savePicture("$base.$ext", mimeFor(ext), folder) { BlobStore.copyTo(sticker.blobKey, it) }
            }
            written++
        }
        return written
    }

    /** Renders items (in stacking order) into one PNG. */
    fun exportScene(stickers: List<Sticker>, background: Int, grayscale: Boolean, name: String): String {
        if (stickers.isEmpty()) throw IOException("Nothing to export")
        val bitmap = render(stickers, background, grayscale)
        return savePicture("$name.png", "image/png", EXPORT_FOLDER) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    fun render(stickers: List<Sticker>, background: Int, grayscale: Boolean): Bitmap {
        val bounds = RectF(stickers[0].worldBounds)
        stickers.forEach { bounds.union(it.worldBounds) }
        val pixels = bounds.width() * bounds.height()
        val scale = min(
            min(1f, sqrt(MAX_EXPORT_PIXELS / max(1f, pixels))),
            MAX_EXPORT_SIDE / max(bounds.width(), bounds.height())
        )
        val width = max(1, (bounds.width() * scale).toInt())
        val height = max(1, (bounds.height() * scale).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val camera = Matrix().apply {
            setTranslate(-bounds.left, -bounds.top)
            postScale(scale, scale)
        }
        val layer = if (grayscale) {
            canvas.saveLayer(null, Paint().apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            })
        } else -1
        for (sticker in stickers) {
            // Copies, so the live items' camera is untouched.
            val copy = sticker.copy(true)
            copy.setCanvasMatrix(camera)
            copy.draw(canvas)
        }
        if (layer >= 0) canvas.restoreToCount(layer)
        return bitmap
    }

    /** Writes into Pictures/<folder> and returns a readable location. */
    private fun savePicture(fileName: String, mime: String, folder: String, write: (OutputStream) -> Unit): String {
        val relative = "${Environment.DIRECTORY_PICTURES}/$folder"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, relative)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create $fileName")
            try {
                resolver.openOutputStream(uri)!!.use(write)
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), folder)
            dir.mkdirs()
            val file = File(dir, fileName)
            file.outputStream().use(write)
        }
        return "$relative/$fileName"
    }

    private fun mimeFor(ext: String) = when (ext) {
        "jpg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "image/png"
    }

    /** A PNG of the item for the clipboard, shared through FileProvider. */
    fun clipboardImage(sticker: DrawableSticker): File {
        val dir = File(context.cacheDir, "clipboard")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "${(sticker.name ?: "image").replace(Regex("[^A-Za-z0-9._-]"), "_")}.png")
        file.outputStream().use { sticker.croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    // endregion

    companion object {
        const val EXPORT_FOLDER = "DroidRef"
        private const val MAX_EXPORT_PIXELS = 36_000_000f
        private const val MAX_EXPORT_SIDE = 12_000f
        private const val USER_AGENT = "Mozilla/5.0 (Android) DroidRef"
    }
}
