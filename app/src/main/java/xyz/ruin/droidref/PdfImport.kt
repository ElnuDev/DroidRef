package xyz.ruin.droidref

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.xiaopo.flying.sticker.ImageLoader
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A PDF whose pages are being turned into images. A page that is just one
 * picture gets that picture's own pixels, copied out of the file; any other
 * page that is only pictures is rendered at the pictures' resolution; pages
 * with text or drawings are rendered as sharp as the board can show them.
 * [page] may be called from several threads.
 */
class PdfImport(private val file: File) : Closeable {
    private val idle = ConcurrentLinkedQueue<PdfRenderer>()
    private val all = ArrayList<PdfRenderer>()
    private var closed = false

    val pageCount: Int
    private val pages: List<PdfScanner.Page?>

    init {
        val renderer = open()
        pageCount = renderer.pageCount
        idle.add(renderer)
        pages = try {
            PdfScanner.scan(file).takeIf { it.size == pageCount }
        } catch (e: Exception) {
            Timber.w(e, "Could not scan PDF; rendering every page")
            null
        } ?: List(pageCount) { null }
    }

    private fun open(): PdfRenderer {
        val renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        synchronized(all) {
            all.add(renderer)
        }
        return renderer
    }

    /** Encoded image data for page [index]. */
    fun page(index: Int): ByteArray {
        val page = pages[index]
        copied(page)?.let { return it }

        val renderer = idle.poll() ?: open()
        try {
            renderer.openPage(index).use { pdfPage ->
                val width = pdfPage.width.toDouble()
                val height = pdfPage.height.toDouble()
                val plan = plan(page, width, height)
                val (left, top, right, bottom) = plan.region
                val pixels = (right - left) * (bottom - top) * plan.scale * plan.scale
                val scale = min(plan.scale, sqrt(MAX_RENDER_PIXELS / max(1.0, pixels)) * plan.scale)
                val bitmap = Bitmap.createBitmap(
                    max(1, ceil((right - left) * scale - 0.01).toInt()),
                    max(1, ceil((bottom - top) * scale - 0.01).toInt()),
                    Bitmap.Config.ARGB_8888
                )
                // Paper is white; PdfRenderer leaves unpainted areas transparent.
                bitmap.eraseColor(Color.WHITE)
                val transform = Matrix().apply {
                    setTranslate(-left.toFloat(), -top.toFloat())
                    postScale(scale.toFloat(), scale.toFloat())
                }
                pdfPage.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = ByteArrayOutputStream()
                if (plan.photo) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bitmap.recycle()
                return out.toByteArray()
            }
        } finally {
            release(renderer)
        }
    }

    /**
     * The page's picture copied out of the file, if the page shows exactly
     * that and nothing else: a JPEG as is, other pixels as PNG.
     */
    private fun copied(page: PdfScanner.Page?): ByteArray? {
        if (page == null || page.hasOtherContent || page.rotate != 0) return null
        val image = page.images.singleOrNull() ?: return null
        if (!image.upright || !page.cropBox.contains(image.bounds, 1.0)) return null
        val bytes = RandomAccessFile(file, "r").use { raf ->
            when (val copy = image.copy) {
                is PdfScanner.JpegCopy -> ByteArray(copy.length).also {
                    raf.seek(copy.offset)
                    raf.readFully(it)
                }
                is PdfScanner.PixelCopy -> pixelsToPng(raf, copy, image.width, image.height)
                null -> null
            }
        }
        // Anything Android decodes differently from how the PDF shows it gets rendered instead.
        return bytes?.takeIf { ImageLoader.size(it) == image.width to image.height }
    }

    private fun pixelsToPng(raf: RandomAccessFile, copy: PdfScanner.PixelCopy, width: Int, height: Int): ByteArray? {
        val channels = copy.channels
        val samples = PdfScanner.inflate(raf, copy.samples)
        if (samples.size < width.toLong() * height * channels) return null
        copy.mask?.let {
            val mask = PdfScanner.inflate(raf, it)
            if (mask.size < width.toLong() * height) return null
            // Onto white paper, as PdfRenderer would show it.
            for (i in 0 until width * height) {
                val alpha = mask[i].toInt() and 0xff
                if (alpha == 255) continue
                for (c in 0 until channels) {
                    val k = i * channels + c
                    samples[k] = (((samples[k].toInt() and 0xff) * alpha + 255 * (255 - alpha) + 127) / 255).toByte()
                }
            }
        }
        return Png.encode(width, height, channels, samples)
    }

    private class Plan(val region: DoubleArray, val scale: Double, val photo: Boolean)

    /** What to render, in PdfRenderer's page coordinates, and at how many pixels per point. */
    private fun plan(page: PdfScanner.Page?, width: Double, height: Double): Plan {
        val whole = doubleArrayOf(0.0, 0.0, width, height)
        val fit = sqrt(ImageLoader.MAX_PIXELS / (width * height))
        // Only trust the scan if it agrees with PdfRenderer about the page.
        if (page == null || abs(page.displayWidth - width) > 1 || abs(page.displayHeight - height) > 1) {
            return Plan(whole, fit, false)
        }
        if (!page.hasOtherContent && page.images.isNotEmpty()) {
            // Only pictures: crop to them and keep their resolution.
            val union = page.images.map { it.bounds }.reduce { a, b -> a.union(b) }.intersect(page.cropBox)
            val region = if (union.width > 1 && union.height > 1) page.toDisplay(union) else whole
            return Plan(region, page.images.maxOf { it.scale }, page.images.all { it.isLossy })
        }
        // Text or drawings: as sharp as the board can show them. JPEG only
        // if the page is mostly photo anyway.
        val photo = page.images.filter { it.isLossy }.sumOf { it.bounds.width * it.bounds.height }
        return Plan(whole, fit, photo >= width * height * 0.5)
    }

    private fun release(renderer: PdfRenderer) {
        synchronized(all) {
            if (!closed) {
                idle.add(renderer)
                return
            }
        }
        renderer.close()
    }

    /** Closes the renderers not in use (the others close when their page is done) and deletes the file. */
    override fun close() {
        synchronized(all) {
            closed = true
        }
        while (true) {
            (idle.poll() ?: break).close()
        }
        file.delete()
    }

    companion object {
        /** Cap on a rendered page, so huge scans don't run out of memory. */
        private const val MAX_RENDER_PIXELS = 16_000_000.0
    }
}
