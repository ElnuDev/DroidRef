package com.xiaopo.flying.sticker

import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.core.MessageUnpacker
import java.io.*

fun MessagePacker.packMatrix(matrix: Matrix) {
    val temp = FloatArray(9)
    matrix.getValues(temp)
    temp.forEach { this.packFloat(it) }
}

fun MessageUnpacker.unpackMatrix(): Matrix {
    val temp = FloatArray(9)
    (0..8).forEach { temp[it] = this.unpackFloat() }
    val matrix = Matrix()
    matrix.setValues(temp)
    return matrix
}

fun MessagePacker.packRect(rect: Rect) {
    this.packInt(rect.left)
    this.packInt(rect.top)
    this.packInt(rect.right)
    this.packInt(rect.bottom)
}

fun MessageUnpacker.unpackRect(): Rect {
    val left = this.unpackInt()
    val top = this.unpackInt()
    val right = this.unpackInt()
    val bottom = this.unpackInt()
    return Rect(left, top, right, bottom)
}

fun MessagePacker.packRectF(rectF: RectF) {
    this.packFloat(rectF.left)
    this.packFloat(rectF.top)
    this.packFloat(rectF.right)
    this.packFloat(rectF.bottom)
}

fun MessageUnpacker.unpackRectF(): RectF {
    val left = this.unpackFloat()
    val top = this.unpackFloat()
    val right = this.unpackFloat()
    val bottom = this.unpackFloat()
    return RectF(left, top, right, bottom)
}

/**
 * Reads and writes boards (.ref files).
 *
 * Version 1 grouped image instances by bitmap and so lost the stacking order.
 * Version 2 stores the bitmaps once, then every item in stacking order as a
 * msgpack map, so fields can be added without breaking older files.
 */
class StickerViewSerializer {
    class Board(val canvasMatrix: Matrix, val stickers: List<Sticker>)

    /**
     * Writes a board. Safe off the main thread as long as [stickers] are copies
     * nobody else mutates.
     */
    fun write(out: OutputStream, canvasMatrix: Matrix, stickers: List<Sticker>) {
        MessagePack.newDefaultPacker(BufferedOutputStream(out)).use { p ->
            p.packInt(SERIAL_VERSION)
            p.packLong(System.currentTimeMillis())
            p.packMatrix(canvasMatrix)

            val keys = LinkedHashSet<String>()
            stickers.forEach { if (it is DrawableSticker) keys.add(it.blobKey) }
            p.packArrayHeader(keys.size)
            for (key in keys) {
                p.packString(key)
                val bytes = BlobStore.read(key)
                p.packBinaryHeader(bytes.size)
                p.writePayload(bytes)
            }

            p.packArrayHeader(stickers.size)
            stickers.forEach { writeItem(p, it) }
        }
    }

    private fun writeItem(p: MessagePacker, sticker: Sticker) {
        val fields = ArrayList<Pair<String, MessagePacker.() -> Unit>>()
        fun field(key: String, write: MessagePacker.() -> Unit) = fields.add(key to write)

        when (sticker) {
            is DrawableSticker -> {
                field("type") { packString("image") }
                field("blob") { packString(sticker.blobKey) }
                field("bounds") { packArrayHeader(4); packRect(sticker.realBounds) }
                field("crop") { packArrayHeader(4); packRectF(sticker.croppedBounds) }
            }
            is NoteSticker -> {
                field("type") { packString("note") }
                field("text") { packString(sticker.text) }
                field("textColor") { packInt(sticker.textColor) }
                field("backgroundColor") { packInt(sticker.backgroundColor) }
                field("textSize") { packFloat(sticker.textSize) }
            }
            is DrawingSticker -> {
                field("type") { packString("drawing") }
                field("color") { packInt(sticker.color) }
                field("strokeWidth") { packFloat(sticker.strokeWidth) }
                field("points") {
                    packArrayHeader(sticker.points.size)
                    sticker.points.forEach { packFloat(it) }
                }
            }
            else -> field("type") { packString("unknown") }
        }
        field("matrix") { packArrayHeader(9); packMatrix(sticker.matrix) }
        field("flipH") { packBoolean(sticker.isFlippedHorizontally) }
        field("flipV") { packBoolean(sticker.isFlippedVertically) }
        field("grayscale") { packBoolean(sticker.isGrayscale) }
        field("smooth") { packBoolean(sticker.isSmooth) }
        field("locked") { packBoolean(sticker.isLocked) }
        field("opacity") { packInt(sticker.opacity) }
        field("group") { packLong(sticker.groupId) }
        field("order") { packLong(sticker.addedOrder) }
        sticker.name?.let { field("name") { packString(it) } }
        sticker.comment?.let { field("comment") { packString(it) } }
        sticker.source?.let { field("source") { packString(it) } }

        p.packMapHeader(fields.size)
        for ((key, write) in fields) {
            p.packString(key)
            p.write()
        }
    }

    /**
     * Reads a board, decoding its images. Call off the main thread.
     */
    fun read(input: InputStream, resources: Resources): Board =
        MessagePack.newDefaultUnpacker(BufferedInputStream(input)).use { u ->
            when (val version = u.unpackInt()) {
                1 -> readV1(u, resources)
                2 -> readV2(u, resources)
                else -> throw IOException("Unsupported board version $version")
            }
        }

    private fun readV1(u: MessageUnpacker, resources: Resources): Board {
        u.unpackLong() // date
        val canvasMatrix = u.unpackMatrix()
        val stickers = ArrayList<Sticker>()
        repeat(u.unpackArrayHeader()) {
            u.unpackString() // sha of the PNG data, recomputed by BlobStore
            val bytes = u.readPayload(u.unpackBinaryHeader())
            val key = BlobStore.put(bytes)
            val bitmap = ImageLoader.decode(bytes)
            repeat(u.unpackArrayHeader()) {
                val bounds = u.unpackRect()
                val matrix = u.unpackMatrix()
                val cropBounds = u.unpackRectF()
                val flipHorizontal = u.unpackBoolean()
                val flipVertical = u.unpackBoolean()
                if (bitmap != null) {
                    val sticker = DrawableSticker(BitmapDrawable(resources, bitmap), key)
                    sticker.realBounds = bounds
                    sticker.croppedBounds = cropBounds
                    sticker.setMatrix(matrix)
                    sticker.isFlippedHorizontally = flipHorizontal
                    sticker.isFlippedVertically = flipVertical
                    stickers.add(sticker)
                }
            }
        }
        return Board(canvasMatrix, stickers)
    }

    private fun readV2(u: MessageUnpacker, resources: Resources): Board {
        u.unpackLong() // date
        val canvasMatrix = u.unpackMatrix()
        val bitmaps = HashMap<String, Bitmap?>()
        repeat(u.unpackArrayHeader()) {
            val savedKey = u.unpackString()
            val bytes = u.readPayload(u.unpackBinaryHeader())
            val key = BlobStore.put(bytes)
            bitmaps[savedKey] = ImageLoader.decode(bytes)
            if (key != savedKey) {
                bitmaps[key] = bitmaps[savedKey]
            }
        }
        val stickers = ArrayList<Sticker>()
        repeat(u.unpackArrayHeader()) {
            readItem(u, resources, bitmaps)?.let(stickers::add)
        }
        return Board(canvasMatrix, stickers)
    }

    private fun MessageUnpacker.unpackFloats(): FloatArray {
        val n = unpackArrayHeader()
        return FloatArray(n) { unpackFloat() }
    }

    private fun readItem(
        u: MessageUnpacker,
        resources: Resources,
        bitmaps: Map<String, Bitmap?>
    ): Sticker? {
        var type = ""
        var blob: String? = null
        var bounds: Rect? = null
        var crop: RectF? = null
        var text = ""
        var textColor = Color.WHITE
        var backgroundColor = 0
        var textSize = NoteSticker.DEFAULT_TEXT_SIZE
        var color = Color.WHITE
        var strokeWidth = 4f
        var points = FloatArray(0)
        val matrix = Matrix()
        var flipH = false
        var flipV = false
        var grayscale = false
        var smooth = true
        var locked = false
        var opacity = 255
        var group = 0L
        var order = 0L
        var name: String? = null
        var comment: String? = null
        var source: String? = null

        repeat(u.unpackMapHeader()) {
            when (u.unpackString()) {
                "type" -> type = u.unpackString()
                "blob" -> blob = u.unpackString()
                "bounds" -> { u.unpackArrayHeader(); bounds = u.unpackRect() }
                "crop" -> { u.unpackArrayHeader(); crop = u.unpackRectF() }
                "text" -> text = u.unpackString()
                "textColor" -> textColor = u.unpackInt()
                "backgroundColor" -> backgroundColor = u.unpackInt()
                "textSize" -> textSize = u.unpackFloat()
                "color" -> color = u.unpackInt()
                "strokeWidth" -> strokeWidth = u.unpackFloat()
                "points" -> points = u.unpackFloats()
                "matrix" -> matrix.setValues(u.unpackFloats())
                "flipH" -> flipH = u.unpackBoolean()
                "flipV" -> flipV = u.unpackBoolean()
                "grayscale" -> grayscale = u.unpackBoolean()
                "smooth" -> smooth = u.unpackBoolean()
                "locked" -> locked = u.unpackBoolean()
                "opacity" -> opacity = u.unpackInt()
                "group" -> group = u.unpackLong()
                "order" -> order = u.unpackLong()
                "name" -> name = u.unpackString()
                "comment" -> comment = u.unpackString()
                "source" -> source = u.unpackString()
                else -> u.skipValue()
            }
        }

        val sticker: Sticker = when (type) {
            "image" -> {
                val key = blob ?: return null
                val bitmap = bitmaps[key] ?: return null
                DrawableSticker(BitmapDrawable(resources, bitmap), key).also { s ->
                    bounds?.let { s.realBounds = it }
                    crop?.let { s.croppedBounds = it }
                }
            }
            "note" -> NoteSticker(text, textColor, backgroundColor, textSize)
            "drawing" -> DrawingSticker.fromLocalPoints(color, strokeWidth, points, matrix)
            else -> return null
        }
        sticker.setMatrix(matrix)
        sticker.isFlippedHorizontally = flipH
        sticker.isFlippedVertically = flipV
        sticker.isGrayscale = grayscale
        sticker.isSmooth = smooth
        sticker.isLocked = locked
        sticker.opacity = opacity
        sticker.groupId = group
        if (order > 0) {
            sticker.addedOrder = order
        }
        sticker.name = name
        sticker.comment = comment
        sticker.source = source
        return sticker
    }

    companion object {
        const val SERIAL_VERSION = 2

        fun drawableToBitmap(drawable: Drawable): Bitmap {
            if (drawable is BitmapDrawable) {
                val bitmapDrawable = drawable
                if (bitmapDrawable.bitmap != null) {
                    return bitmapDrawable.bitmap
                }
            }
            var bitmap: Bitmap = if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
                Bitmap.createBitmap(
                    1,
                    1,
                    Bitmap.Config.ARGB_8888
                ) // Single color bitmap will be created of 1x1 pixel
            } else {
                Bitmap.createBitmap(
                    drawable.intrinsicWidth,
                    drawable.intrinsicHeight,
                    Bitmap.Config.ARGB_8888
                )
            }
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }
    }
}
