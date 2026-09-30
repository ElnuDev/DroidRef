package com.xiaopo.flying.sticker

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Decoded pixels for every image on the board, at the resolution it is shown.
 *
 * Each image keeps a small mip chain in memory for good (at most [RESIDENT_SIDE]
 * pixels on the long side, halving down to [MIN_SIDE]), so anything can always
 * be drawn. Sharper levels (the original sampled by 1/2, 1/4, ... or decoded in
 * full) are loaded in the background for images that are shown large, and live
 * in an LRU cache with a byte budget.
 *
 * Levels are hardware bitmaps where available: they go to the GPU once, when
 * decoded, instead of being uploaded again by the renderer whenever too many
 * images are on screen for its texture cache.
 */
object ImageCache {
    private const val TAG = "ImageCache"
    private const val RESIDENT_SIDE = 512
    private const val MIN_SIDE = 16
    /** Load a sharper level a little before it is needed, so zooming in rarely catches up with it. */
    private const val PREFETCH = 1.3f
    private const val STALE_MILLIS = 3000L
    private const val WORKERS = 2

    /** An image's original size and its resident mip chain, sharpest first. */
    private class Pyramid(val width: Int, val height: Int, val residentSample: Int, val mips: List<Bitmap>) {
        /** Fraction of the original resolution the full (capped) decode has. */
        val fullScale = ImageLoader.canonicalSize(width, height).first.toFloat() / width

        fun scaleOf(bitmap: Bitmap) = bitmap.width.toFloat() / width

        /** Nominal scale of a sharper level; sample 1 is the full decode. */
        fun scaleOf(sample: Int) = if (sample == 1) fullScale else 1f / sample

        /** Samples of the sharper levels, smallest image first. */
        val sharpSamples: List<Int> = generateSequence(residentSample / 2) { it / 2 }
            .takeWhile { it >= 1 }
            // A sampled level bigger than the full decode would exceed its pixel cap.
            .filter { it == 1 || 1f / it < fullScale }
            .toList()
    }

    private data class Level(val key: String, val sample: Int)

    /** Level sample meaning "build the resident mips". */
    private const val RESIDENT = 0

    private val pyramids = ConcurrentHashMap<String, Pyramid>()
    private var cache = newCache(128L shl 20)
    private val queue = LinkedBlockingDeque<Level>()
    private val wanted = ConcurrentHashMap<Level, Long>()
    private val listeners = CopyOnWriteArraySet<Runnable>()
    private val main = Handler(Looper.getMainLooper())
    private val notifyPending = AtomicBoolean()
    private var started = false

    private fun newCache(bytes: Long) = object : LruCache<Level, Bitmap>(bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
        override fun sizeOf(key: Level, value: Bitmap) = value.allocationByteCount
    }

    /** Sizes the cache for the device and starts the decoders. Call once at startup. */
    @Synchronized
    fun init(context: Context) {
        if (started) return
        started = true
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        cache = newCache((info.totalMem / 20).coerceIn(96L shl 20, 384L shl 20))
        repeat(WORKERS) { n ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                while (true) {
                    work(queue.takeLast())
                }
            }, "ImageCache-$n").apply { isDaemon = true }.start()
        }
    }

    /** Called on the main thread whenever a level finished loading, so views can redraw. */
    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    // region Preparing

    /**
     * Builds the resident mips for a blob, returning the size to place it at
     * (see [ImageLoader.canonicalSize]), or null if it isn't an image. Blocks.
     */
    fun prepare(key: String, bytes: ByteArray? = null): Pair<Int, Int>? {
        pyramids[key]?.let { return ImageLoader.canonicalSize(it.width, it.height) }
        val data = bytes ?: BlobStore.read(key)
        val (width, height) = ImageLoader.size(data) ?: return null
        var sample = 1
        while (max(width, height) / sample > RESIDENT_SIDE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
        }
        var level = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null
        val mips = ArrayList<Bitmap>()
        while (true) {
            val next = if (max(level.width, level.height) / 2 >= MIN_SIDE) {
                Bitmap.createScaledBitmap(level, max(1, level.width / 2), max(1, level.height / 2), true)
            } else null
            mips.add(toHardware(level))
            level = next ?: break
        }
        pyramids[key] = Pyramid(width, height, sample, mips)
        return ImageLoader.canonicalSize(width, height)
    }

    /** Forgets images that are no longer on the board. */
    fun retain(keys: Set<String>) {
        pyramids.keys.retainAll(keys)
        for (level in cache.snapshot().keys) {
            if (level.key !in keys) {
                cache.remove(level)
            }
        }
    }

    // endregion

    // region Drawing

    /**
     * Draws the image [key] into [dst], picking the smallest loaded level that is
     * at least as sharp as the [onScreenWidth] it covers, and queueing a sharper
     * one if none is.
     */
    fun draw(canvas: Canvas, key: String, onScreenWidth: Float, dst: RectF, paint: Paint) {
        val pyramid = pyramids[key]
        if (pyramid == null) {
            request(Level(key, RESIDENT))
            return
        }
        val need = onScreenWidth / pyramid.width
        if (!canvas.isHardwareAccelerated && Looper.myLooper() != Looper.getMainLooper()) {
            // Exporting: decode exactly what is needed, now.
            val sample = sampleFor(pyramid, need)
            val bitmap = if (sample == RESIDENT) null else decode(key, sample, software = true)
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, dst, paint)
                bitmap.recycle()
            } else {
                drawSoftware(canvas, pick(key, pyramid, need), dst, paint)
            }
            return
        }
        request(key, pyramid, need)
        val bitmap = pick(key, pyramid, need)
        if (canvas.isHardwareAccelerated) {
            canvas.drawBitmap(bitmap, null, dst, paint)
        } else {
            drawSoftware(canvas, bitmap, dst, paint)
        }
    }

    /**
     * Starts loading what drawing at [onScreenWidth] will need, without drawing.
     * Only while the cache is at most half full, so images loaded ahead never
     * push out the ones on screen (which would then load again, and so on).
     */
    fun prefetch(key: String, onScreenWidth: Float) {
        val pyramid = pyramids[key]
        if (pyramid == null) {
            request(Level(key, RESIDENT))
        } else if (cache.size() < cache.maxSize() / 2) {
            request(key, pyramid, onScreenWidth / pyramid.width)
        }
    }

    private fun drawSoftware(canvas: Canvas, bitmap: Bitmap, dst: RectF, paint: Paint) {
        // A software canvas can't read hardware bitmaps.
        val readable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else bitmap
        canvas.drawBitmap(readable, null, dst, paint)
        if (readable !== bitmap) {
            readable.recycle()
        }
    }

    /** Smallest loaded level at least [need] sharp, else the sharpest loaded. */
    private fun pick(key: String, pyramid: Pyramid, need: Float): Bitmap {
        for (i in pyramid.mips.indices.reversed()) {
            val mip = pyramid.mips[i]
            if (pyramid.scaleOf(mip) >= need || i == 0 && pyramid.sharpSamples.isEmpty()) {
                return mip
            }
        }
        var best = pyramid.mips[0]
        for (sample in pyramid.sharpSamples) {
            val level = cache.get(Level(key, sample)) ?: continue
            best = level
            if (pyramid.scaleOf(sample) >= need) {
                break
            }
        }
        return best
    }

    /** Sample of the smallest level at least [need] sharp; [RESIDENT] if the mips are enough. */
    private fun sampleFor(pyramid: Pyramid, need: Float): Int {
        if (pyramid.scaleOf(pyramid.mips[0]) >= need || pyramid.sharpSamples.isEmpty()) {
            return RESIDENT
        }
        return pyramid.sharpSamples.firstOrNull { pyramid.scaleOf(it) >= need } ?: 1
    }

    private fun request(key: String, pyramid: Pyramid, need: Float) {
        val sample = sampleFor(pyramid, need * PREFETCH)
        if (sample != RESIDENT) {
            val level = Level(key, sample)
            if (cache.get(level) == null) {
                request(level)
            }
        }
    }

    private fun request(level: Level) {
        if (wanted.put(level, SystemClock.uptimeMillis()) == null) {
            queue.addLast(level)
        }
    }

    // endregion

    // region Loading

    private fun work(level: Level) {
        val requested = wanted[level] ?: return
        try {
            // Anything still needed was asked for again on the last redraw.
            if (SystemClock.uptimeMillis() - requested > STALE_MILLIS) {
                return
            }
            if (level.sample == RESIDENT) {
                if (pyramids[level.key] == null && BlobStore.file(level.key).exists()) {
                    prepare(level.key)
                }
            } else if (cache.get(level) == null && pyramids.containsKey(level.key)) {
                decode(level.key, level.sample, software = false)?.let { cache.put(level, it) }
            } else {
                return
            }
            notifyListeners()
        } catch (e: Throwable) {
            Log.e(TAG, "Could not load $level", e)
        } finally {
            wanted.remove(level)
        }
    }

    private fun decode(key: String, sample: Int, software: Boolean): Bitmap? {
        val bitmap = if (sample == 1) {
            ImageLoader.decode(BlobStore.read(key))
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inScaled = false
                if (!software && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    inPreferredConfig = Bitmap.Config.HARDWARE
                }
            }
            BitmapFactory.decodeFile(BlobStore.file(key).path, options)
        } ?: return null
        return if (software) bitmap else toHardware(bitmap)
    }

    /**
     * The whole image as a software bitmap of exactly [width] x [height], the
     * size it is placed at, for cropping and exporting. Blocks.
     */
    fun loadFull(key: String, width: Int, height: Int): Bitmap {
        val bitmap = decode(key, 1, software = true)
            ?: throw IllegalStateException("Could not decode image $key")
        if (bitmap.width == width && bitmap.height == height) {
            return bitmap
        }
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        bitmap.recycle()
        return scaled
    }

    private fun toHardware(bitmap: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || bitmap.config == Bitmap.Config.HARDWARE) {
            return bitmap
        }
        val hardware = bitmap.copy(Bitmap.Config.HARDWARE, false) ?: return bitmap
        bitmap.recycle()
        return hardware
    }

    private fun notifyListeners() {
        if (notifyPending.compareAndSet(false, true)) {
            main.post {
                notifyPending.set(false)
                listeners.forEach { it.run() }
            }
        }
    }

    // endregion
}
