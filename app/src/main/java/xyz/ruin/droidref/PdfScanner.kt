package xyz.ruin.droidref

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Just enough PDF parsing to see what each page draws, so a page that is only
 * a picture can be imported at the picture's own resolution. Drawing is left to
 * PdfRenderer: anything this doesn't understand throws, and the caller renders
 * the page without knowing what's on it.
 */
class PdfScanner private constructor(private val buf: ByteBuffer) {

    /** A rectangle in PDF space (y up); left <= right, bottom <= top. */
    data class Box(val left: Double, val bottom: Double, val right: Double, val top: Double) {
        val width get() = right - left
        val height get() = top - bottom

        fun intersect(o: Box) = Box(max(left, o.left), max(bottom, o.bottom), min(right, o.right), min(top, o.top))
        fun union(o: Box) = Box(min(left, o.left), min(bottom, o.bottom), max(right, o.right), max(top, o.top))

        fun contains(o: Box, tolerance: Double) = o.left >= left - tolerance && o.bottom >= bottom - tolerance &&
                o.right <= right + tolerance && o.top <= top + tolerance
    }

    class Image(
        /** Size in pixels. */
        val width: Int,
        val height: Int,
        /** Area covered on the page. */
        val bounds: Box,
        /** Pixels per point at which it is drawn: rendering at this scale shows it 1:1. */
        val scale: Double,
        /** Drawn the right way up without rotation or skew. */
        val upright: Boolean,
        /** Stored lossily (JPEG or JPEG 2000), so a lossy copy loses little more. */
        val isLossy: Boolean,
        /** Where its pixels can be copied out from, if they don't need PdfRenderer to look right. */
        val copy: Copy?,
    )

    sealed class Copy

    /** JPEG data that decodes to exactly the image. */
    class JpegCopy(val offset: Long, val length: Int) : Copy()

    /** 8-bit samples, [channels] per pixel, with an optional soft mask of the same size. */
    class PixelCopy(val samples: Flate, val channels: Int, val mask: Flate?) : Copy()

    /** Deflated data in the file; read it with [inflate]. */
    class Flate(val offset: Long, val length: Int, val predictor: Int, val colors: Int, val columns: Int)

    class Page(
        /** Visible area, in PDF space. */
        val cropBox: Box,
        /** Clockwise, 0, 90, 180 or 270. */
        val rotate: Int,
        val images: List<Image>,
        /** Draws something other than images: visible text, paths, shadings, inline images. */
        val hasOtherContent: Boolean,
    ) {
        /** Width and height as displayed, after rotation. */
        val displayWidth get() = if (rotate % 180 == 0) cropBox.width else cropBox.height
        val displayHeight get() = if (rotate % 180 == 0) cropBox.height else cropBox.width

        /**
         * [box] in displayed page coordinates: points from the top left of the
         * rotated page, as PdfRenderer uses. Returns left, top, right, bottom.
         */
        fun toDisplay(box: Box): DoubleArray {
            val w = cropBox.width
            val h = cropBox.height
            val l = box.left - cropBox.left
            val r = box.right - cropBox.left
            val b = box.bottom - cropBox.bottom
            val t = box.top - cropBox.bottom
            return when (rotate) {
                90 -> doubleArrayOf(b, l, t, r)
                180 -> doubleArrayOf(w - r, b, w - l, t)
                270 -> doubleArrayOf(h - t, w - r, h - b, w - l)
                else -> doubleArrayOf(l, h - t, r, h - b)
            }
        }
    }

    // region Objects

    private data class Name(val value: String)
    private data class Ref(val num: Int, val gen: Int)
    private data class Op(val name: String)
    private object Str
    private object Eof
    private class Stream(val dict: Map<String, Any?>, val source: ByteBuffer, val offset: Int, val length: Int)

    private sealed class Entry
    private class AtOffset(val offset: Int) : Entry()
    private class InStream(val stream: Int, val index: Int) : Entry()

    private val xref = HashMap<Int, Entry>()
    private var trailer: Map<String, Any?> = emptyMap()
    private val objects = HashMap<Int, Any?>()
    private val resolving = HashSet<Int>()
    private val objectStreams = HashMap<Int, Pair<ByteBuffer, Map<Int, Int>>>()

    private fun resolve(value: Any?): Any? {
        if (value !is Ref) return value
        objects[value.num]?.let { return it }
        if (!resolving.add(value.num)) throw IOException("Reference loop at ${value.num}")
        try {
            val obj = when (val entry = xref[value.num]) {
                is AtOffset -> readObjectAt(entry.offset, value.num)
                is InStream -> readFromObjectStream(entry.stream, entry.index, value.num)
                null -> null
            }
            objects[value.num] = obj
            return obj
        } finally {
            resolving.remove(value.num)
        }
    }

    private fun dict(value: Any?): Map<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        return when (val v = resolve(value)) {
            is Stream -> v.dict
            is Map<*, *> -> v as Map<String, Any?>
            else -> null
        }
    }

    private fun list(value: Any?): List<Any?>? = resolve(value) as? List<*>
    private fun number(value: Any?): Double? = resolve(value) as? Double
    private fun name(value: Any?): String? = (resolve(value) as? Name)?.value

    private fun box(value: Any?): Box? {
        val l = list(value)?.map { number(it) ?: return null } ?: return null
        if (l.size != 4) return null
        return Box(min(l[0], l[2]), min(l[1], l[3]), max(l[0], l[2]), max(l[1], l[3]))
    }

    private fun readObjectAt(offset: Int, num: Int): Any? {
        val lexer = Lexer(buf, offset)
        val n = lexer.token()
        lexer.token()
        if (n != num.toDouble() || lexer.token() != Op("obj")) {
            throw IOException("Object $num is not at $offset")
        }
        val obj = parse(lexer, true)
        if (obj is Map<*, *>) {
            val afterDict = lexer.pos
            if (lexer.token() == Op("stream")) {
                var start = lexer.pos
                if (byte(buf, start) == '\r'.code) start++
                if (byte(buf, start) == '\n'.code) start++
                @Suppress("UNCHECKED_CAST")
                val d = obj as Map<String, Any?>
                val declared = number(d["Length"])?.toInt() ?: -1
                val length = if (declared >= 0 && start + declared <= buf.limit() && endsStream(start + declared)) {
                    declared
                } else {
                    findEndstream(start) - start
                }
                return Stream(d, buf, start, length)
            }
            lexer.pos = afterDict
        }
        return obj
    }

    private fun endsStream(pos: Int): Boolean {
        val lexer = Lexer(buf, pos)
        return lexer.token() == Op("endstream")
    }

    private fun findEndstream(from: Int): Int {
        val marker = "endstream".toByteArray()
        var i = from
        while (i + marker.size <= buf.limit()) {
            if (marker.indices.all { buf.get(i + it) == marker[it] }) {
                var end = i
                if (end > from && byte(buf, end - 1) == '\n'.code) end--
                if (end > from && byte(buf, end - 1) == '\r'.code) end--
                return end
            }
            i++
        }
        throw IOException("Unterminated stream")
    }

    private fun readFromObjectStream(streamNum: Int, index: Int, num: Int): Any? {
        val (data, offsets) = objectStreams.getOrPut(streamNum) {
            val stream = resolve(Ref(streamNum, 0)) as? Stream ?: throw IOException("Missing object stream $streamNum")
            val bytes = ByteBuffer.wrap(decode(stream))
            val count = number(stream.dict["N"])?.toInt() ?: 0
            val first = number(stream.dict["First"])?.toInt() ?: 0
            val lexer = Lexer(bytes, 0)
            val map = HashMap<Int, Int>()
            repeat(count) {
                val n = lexer.token() as? Double ?: return@repeat
                val o = lexer.token() as? Double ?: return@repeat
                map[n.toInt()] = first + o.toInt()
            }
            bytes to map
        }
        val offset = offsets[num] ?: throw IOException("Object $num missing from stream $streamNum (index $index)")
        return parse(Lexer(data, offset), true)
    }

    // endregion

    // region Syntax

    private class Lexer(val b: ByteBuffer, var pos: Int) {
        private val end = b.limit()

        private fun at(i: Int) = b.get(i).toInt() and 0xff

        fun skipSpace() {
            while (pos < end) {
                val c = at(pos)
                if (isSpace(c)) {
                    pos++
                } else if (c == '%'.code) {
                    while (pos < end && at(pos) != '\n'.code && at(pos) != '\r'.code) pos++
                } else {
                    break
                }
            }
        }

        fun token(): Any? {
            skipSpace()
            if (pos >= end) return Eof
            val c = at(pos)
            when (c) {
                '/'.code -> {
                    pos++
                    val sb = StringBuilder()
                    while (pos < end && isRegular(at(pos))) {
                        val ch = at(pos)
                        if (ch == '#'.code && pos + 2 < end) {
                            val hex = "${at(pos + 1).toChar()}${at(pos + 2).toChar()}".toIntOrNull(16)
                            if (hex != null) {
                                sb.append(hex.toChar())
                                pos += 3
                                continue
                            }
                        }
                        sb.append(ch.toChar())
                        pos++
                    }
                    return Name(sb.toString())
                }
                '('.code -> {
                    pos++
                    var depth = 1
                    while (pos < end && depth > 0) {
                        when (at(pos)) {
                            '\\'.code -> pos++
                            '('.code -> depth++
                            ')'.code -> depth--
                        }
                        pos++
                    }
                    return Str
                }
                '<'.code -> {
                    if (pos + 1 < end && at(pos + 1) == '<'.code) {
                        pos += 2
                        return Op("<<")
                    }
                    while (pos < end && at(pos) != '>'.code) pos++
                    pos++
                    return Str
                }
                '>'.code -> {
                    if (pos + 1 < end && at(pos + 1) == '>'.code) {
                        pos += 2
                        return Op(">>")
                    }
                    pos++
                    return Op(">")
                }
                '['.code, ']'.code, '{'.code, '}'.code -> {
                    pos++
                    return Op(c.toChar().toString())
                }
                ')'.code -> {
                    pos++
                    return Op(")")
                }
            }
            val start = pos
            while (pos < end && isRegular(at(pos))) pos++
            val word = String(ByteArray(pos - start) { b.get(start + it) }, Charsets.ISO_8859_1)
            val first = word[0]
            if (first.isDigit() || first == '-' || first == '+' || first == '.') {
                word.toDoubleOrNull()?.let { return it }
            }
            return when (word) {
                "true" -> true
                "false" -> false
                "null" -> null
                else -> Op(word)
            }
        }

        companion object {
            fun isSpace(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
            fun isRegular(c: Int) = !isSpace(c) && c !in DELIMITERS
            private val DELIMITERS = "()<>[]{}/%".map { it.code }.toSet()
        }
    }

    /** Reads one object; with [refs], "1 0 R" becomes a [Ref] (never in content streams). */
    private fun parse(lexer: Lexer, refs: Boolean): Any? {
        val t = lexer.token()
        when {
            t is Double && refs && t >= 0 && t == Math.floor(t) -> {
                val save = lexer.pos
                val gen = lexer.token()
                if (gen is Double && gen >= 0 && lexer.token() == Op("R")) {
                    return Ref(t.toInt(), gen.toInt())
                }
                lexer.pos = save
                return t
            }
            t == Op("[") -> {
                val items = ArrayList<Any?>()
                while (true) {
                    val item = parse(lexer, refs)
                    if (item == Op("]") || item === Eof) return items
                    items.add(item)
                }
            }
            t == Op("<<") -> {
                val map = HashMap<String, Any?>()
                while (true) {
                    val key = parse(lexer, refs)
                    if (key == Op(">>") || key === Eof) return map
                    val value = parse(lexer, refs)
                    if (key is Name) map[key.value] = value
                }
            }
            else -> return t
        }
    }

    private fun decode(stream: Stream): ByteArray {
        var data = ByteArray(stream.length) { stream.source.get(stream.offset + it) }
        val filters = when (val f = resolve(stream.dict["Filter"] ?: stream.dict["F"])) {
            null -> emptyList()
            is List<*> -> f.map { name(it) }
            else -> listOf(name(f))
        }
        val params = when (val p = resolve(stream.dict["DecodeParms"] ?: stream.dict["DP"])) {
            is List<*> -> p.map { dict(it) }
            null -> emptyList()
            else -> listOf(dict(p))
        }
        filters.forEachIndexed { i, filter ->
            data = when (filter) {
                "FlateDecode", "Fl" -> {
                    val p = params.getOrNull(i)
                    unpredict(
                        inflate(data),
                        number(p?.get("Predictor"))?.toInt() ?: 1,
                        number(p?.get("Colors"))?.toInt() ?: 1,
                        number(p?.get("BitsPerComponent"))?.toInt() ?: 8,
                        number(p?.get("Columns"))?.toInt() ?: 1,
                    )
                }
                "LZWDecode", "LZW" -> {
                    val p = params.getOrNull(i)
                    unpredict(
                        lzw(data, number(p?.get("EarlyChange"))?.toInt() ?: 1),
                        number(p?.get("Predictor"))?.toInt() ?: 1,
                        number(p?.get("Colors"))?.toInt() ?: 1,
                        number(p?.get("BitsPerComponent"))?.toInt() ?: 8,
                        number(p?.get("Columns"))?.toInt() ?: 1,
                    )
                }
                "ASCII85Decode", "A85" -> ascii85(data)
                "ASCIIHexDecode", "AHx" -> asciiHex(data)
                else -> throw IOException("Unsupported filter $filter")
            }
        }
        return data
    }

    // endregion

    // region Cross-reference

    private fun readXref() {
        val startxref = lastIndexOf("startxref", max(0, buf.limit() - 65536))
        if (startxref < 0) throw IOException("No startxref")
        var offset = (Lexer(buf, startxref + 9).token() as? Double)?.toInt() ?: throw IOException("Bad startxref")
        val seen = HashSet<Int>()
        var first = true
        while (offset in 0 until buf.limit() && seen.add(offset)) {
            val lexer = Lexer(buf, offset)
            val section: Map<String, Any?>
            if (lexer.token() == Op("xref")) {
                while (true) {
                    val start = lexer.token()
                    if (start == Op("trailer")) break
                    val count = lexer.token()
                    if (start !is Double || count !is Double) throw IOException("Bad xref table")
                    for (i in 0 until count.toInt()) {
                        val entryOffset = lexer.token() as? Double ?: throw IOException("Bad xref entry")
                        lexer.token()
                        val type = lexer.token()
                        val num = start.toInt() + i
                        if (type == Op("n") && num !in xref) xref[num] = AtOffset(entryOffset.toInt())
                    }
                }
                @Suppress("UNCHECKED_CAST")
                section = parse(lexer, true) as? Map<String, Any?> ?: throw IOException("Bad trailer")
                // Hybrid files list their compressed objects in a separate stream.
                number(section["XRefStm"])?.let { readXrefStream(it.toInt()) }
            } else {
                section = readXrefStream(offset)
            }
            if (first) {
                trailer = section
                first = false
            }
            offset = number(section["Prev"])?.toInt() ?: -1
        }
        if (first) throw IOException("No cross-reference")
    }

    private fun readXrefStream(offset: Int): Map<String, Any?> {
        val lexer = Lexer(buf, offset)
        val num = (lexer.token() as? Double)?.toInt() ?: throw IOException("Bad xref stream")
        val stream = readObjectAt(offset, num) as? Stream ?: throw IOException("Bad xref stream")
        val data = decode(stream)
        val widths = list(stream.dict["W"])?.map { number(it)?.toInt() ?: 0 } ?: throw IOException("No /W")
        if (widths.size < 3) throw IOException("Bad /W")
        val size = number(stream.dict["Size"])?.toInt() ?: 0
        val index = list(stream.dict["Index"])?.map { number(it)?.toInt() ?: 0 } ?: listOf(0, size)
        var pos = 0
        fun field(width: Int, default: Int): Int {
            if (width == 0) return default
            var v = 0
            repeat(width) { v = (v shl 8) or (data.getOrElse(pos++) { 0 }.toInt() and 0xff) }
            return v
        }
        for (s in 0 until index.size / 2) {
            val start = index[s * 2]
            for (i in 0 until index[s * 2 + 1]) {
                val type = field(widths[0], 1)
                val a = field(widths[1], 0)
                val b = field(widths[2], 0)
                val n = start + i
                if (n in xref) continue
                when (type) {
                    1 -> xref[n] = AtOffset(a)
                    2 -> xref[n] = InStream(a, b)
                }
            }
        }
        return stream.dict
    }

    private fun lastIndexOf(text: String, from: Int): Int {
        val bytes = text.toByteArray()
        var i = buf.limit() - bytes.size
        while (i >= from) {
            if (bytes.indices.all { buf.get(i + it) == bytes[it] }) return i
            i--
        }
        return -1
    }

    // endregion

    // region Pages

    private class Inherited(val resources: Any?, val mediaBox: Any?, val cropBox: Any?, val rotate: Any?)

    private fun pages(): List<Page?> {
        val root = dict(trailer["Root"]) ?: throw IOException("No catalog")
        val result = ArrayList<Page?>()
        val visited = HashSet<Any?>()
        fun walk(ref: Any?, inherited: Inherited, depth: Int) {
            if (depth > 64 || (ref is Ref && !visited.add(ref))) throw IOException("Bad page tree")
            val node = dict(ref) ?: throw IOException("Bad page tree node")
            val here = Inherited(
                node["Resources"] ?: inherited.resources,
                node["MediaBox"] ?: inherited.mediaBox,
                node["CropBox"] ?: inherited.cropBox,
                node["Rotate"] ?: inherited.rotate,
            )
            val kids = list(node["Kids"])
            if (name(node["Type"]) != "Page" && kids != null) {
                kids.forEach { walk(it, here, depth + 1) }
            } else {
                // One odd page shouldn't stop the others from being scanned.
                result.add(
                    try {
                        page(node, here)
                    } catch (e: Exception) {
                        null
                    }
                )
            }
        }
        walk(root["Pages"], Inherited(null, null, null, null), 0)
        return result
    }

    private fun page(node: Map<String, Any?>, inherited: Inherited): Page {
        val media = box(inherited.mediaBox) ?: Box(0.0, 0.0, 612.0, 792.0)
        val crop = box(inherited.cropBox)?.intersect(media)?.takeIf { it.width > 0 && it.height > 0 } ?: media
        val rotate = (((number(inherited.rotate)?.toInt() ?: 0) % 360 + 360) % 360).let { it - it % 90 }
        val content = when (val c = resolve(node["Contents"])) {
            is Stream -> decode(c)
            is List<*> -> {
                val out = ByteArrayOutputStream()
                c.forEach { part ->
                    (resolve(part) as? Stream)?.let {
                        out.write(decode(it))
                        out.write(' '.code)
                    }
                }
                out.toByteArray()
            }
            else -> ByteArray(0)
        }
        val scan = ContentScan()
        scan.run(content, dict(inherited.resources), IDENTITY, 0)
        return Page(crop, rotate, scan.images, scan.other)
    }

    private inner class ContentScan {
        val images = ArrayList<Image>()
        var other = false

        private inner class State(var ctm: DoubleArray, var textMode: Int) {
            fun copy() = State(ctm, textMode)
        }

        fun run(content: ByteArray, resources: Map<String, Any?>?, ctm: DoubleArray, depth: Int) {
            val lexer = Lexer(ByteBuffer.wrap(content), 0)
            val operands = ArrayList<Any?>()
            var state = State(ctm, 0)
            val saved = ArrayDeque<State>()
            while (true) {
                val t = parse(lexer, false)
                if (t === Eof) break
                if (t !is Op) {
                    operands.add(t)
                    continue
                }
                when (t.name) {
                    "q" -> saved.addLast(state.copy())
                    "Q" -> saved.removeLastOrNull()?.let { state = it }
                    "cm" -> if (operands.size == 6 && operands.all { it is Double }) {
                        state.ctm = multiply(DoubleArray(6) { operands[it] as Double }, state.ctm)
                    }
                    "Tr" -> (operands.lastOrNull() as? Double)?.let { state.textMode = it.toInt() }
                    // Modes 3 and 7 draw nothing, e.g. the text layer of a scan.
                    "Tj", "TJ", "'", "\"" -> if (state.textMode != 3 && state.textMode != 7) other = true
                    "S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "sh" -> other = true
                    "BI" -> {
                        other = true
                        skipInlineImage(lexer)
                    }
                    "Do" -> (operands.lastOrNull() as? Name)?.let { draw(it.value, resources, state.ctm, depth) }
                }
                operands.clear()
            }
        }

        private fun skipInlineImage(lexer: Lexer) {
            while (true) {
                val t = parse(lexer, false)
                if (t === Eof) return
                if (t == Op("ID")) break
            }
            val b = lexer.b
            var i = lexer.pos + 1
            while (i + 1 < b.limit()) {
                if (b.get(i) == 'E'.code.toByte() && b.get(i + 1) == 'I'.code.toByte() &&
                    Lexer.isSpace(b.get(i - 1).toInt()) &&
                    (i + 2 >= b.limit() || Lexer.isSpace(b.get(i + 2).toInt()))
                ) {
                    lexer.pos = i + 2
                    return
                }
                i++
            }
            lexer.pos = b.limit()
        }

        private fun draw(name: String, resources: Map<String, Any?>?, ctm: DoubleArray, depth: Int) {
            val xobject = resolve(dict(resources?.get("XObject"))?.get(name)) as? Stream ?: return
            val d = xobject.dict
            when (name(d["Subtype"])) {
                "Image" -> {
                    if (resolve(d["ImageMask"]) == true) {
                        // A stencil: paints the fill colour through the image.
                        other = true
                    } else {
                        image(xobject, ctm)?.let { images.add(it) }
                    }
                }
                "Form" -> {
                    if (depth >= 12) throw IOException("Forms nested too deep")
                    val matrix = list(d["Matrix"])?.mapNotNull { number(it) }?.takeIf { it.size == 6 }?.toDoubleArray()
                        ?: IDENTITY
                    run(decode(xobject), dict(d["Resources"]) ?: resources, multiply(matrix, ctm), depth + 1)
                }
            }
        }

        private fun image(stream: Stream, ctm: DoubleArray): Image? {
            val d = stream.dict
            val width = number(d["Width"])?.toInt() ?: return null
            val height = number(d["Height"])?.toInt() ?: return null
            if (width <= 0 || height <= 0) return null
            val a = ctm[0]
            val b = ctm[1]
            val c = ctm[2]
            val dd = ctm[3]
            val e = ctm[4]
            val f = ctm[5]
            val xs = doubleArrayOf(e, a + e, c + e, a + c + e)
            val ys = doubleArrayOf(f, b + f, dd + f, b + dd + f)
            val bounds = Box(xs.min(), ys.min(), xs.max(), ys.max())
            val placedWidth = hypot(a, b)
            val placedHeight = hypot(c, dd)
            if (placedWidth < 1e-6 || placedHeight < 1e-6) return null
            val scale = max(width / placedWidth, height / placedHeight)
            val upright = abs(b) < 1e-6 * placedWidth && abs(c) < 1e-6 * placedHeight && a > 0 && dd > 0

            val filters = filters(d)
            val isLossy = filters.lastOrNull() == "DCTDecode" || filters.lastOrNull() == "JPXDecode"
            val channels = when (val colorSpace = resolve(d["ColorSpace"])) {
                Name("DeviceRGB") -> 3
                Name("DeviceGray") -> 1
                is List<*> -> if (name(colorSpace.firstOrNull()) == "ICCBased") {
                    number(dict(colorSpace.getOrNull(1))?.get("N"))?.toInt()?.takeIf { it == 1 || it == 3 }
                } else null
                else -> null
            }
            val plain = channels != null && (number(d["BitsPerComponent"])?.toInt() ?: 8) == 8 &&
                    d["Mask"] == null && d["Decode"] == null && stream.source === buf
            val copy = when {
                !plain -> null
                // Decode parameters can change how the colours are read.
                filters == listOf("DCTDecode") && d["SMask"] == null && d["DecodeParms"] == null ->
                    JpegCopy(stream.offset.toLong(), stream.length)
                filters.size == 1 && isFlate(filters[0]) -> {
                    val mask = d["SMask"]?.let { softMask(it, width, height) }
                    if (d["SMask"] != null && mask == null) null else PixelCopy(flate(stream), channels!!, mask)
                }
                else -> null
            }
            return Image(width, height, bounds, scale, upright, isLossy, copy)
        }

        /** A soft mask simple enough to apply without PdfRenderer. */
        private fun softMask(ref: Any?, width: Int, height: Int): Flate? {
            val stream = resolve(ref) as? Stream ?: return null
            val d = stream.dict
            val simple = number(d["Width"])?.toInt() == width && number(d["Height"])?.toInt() == height &&
                    (number(d["BitsPerComponent"])?.toInt() ?: 8) == 8 && resolve(d["ColorSpace"]) == Name("DeviceGray") &&
                    d["Matte"] == null && d["Decode"] == null && stream.source === buf
            val filters = filters(d)
            return if (simple && filters.size == 1 && isFlate(filters[0])) flate(stream) else null
        }

        private fun isFlate(filter: String?) = filter == "FlateDecode" || filter == "Fl"

        private fun filters(d: Map<String, Any?>) = when (val filter = resolve(d["Filter"])) {
            is List<*> -> filter.map { name(it) }
            null -> emptyList()
            else -> listOf(name(filter))
        }

        private fun flate(stream: Stream): Flate {
            val params = when (val p = resolve(stream.dict["DecodeParms"])) {
                is List<*> -> dict(p.firstOrNull())
                else -> dict(p)
            }
            return Flate(
                stream.offset.toLong(), stream.length,
                number(params?.get("Predictor"))?.toInt() ?: 1,
                number(params?.get("Colors"))?.toInt() ?: 1,
                number(params?.get("Columns"))?.toInt() ?: 1,
            )
        }
    }

    // endregion

    companion object {
        private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        private fun byte(b: ByteBuffer, i: Int) = if (i < b.limit()) b.get(i).toInt() and 0xff else -1

        /** Matrix product in PDF's row-vector order: the result maps through [m], then [n]. */
        private fun multiply(m: DoubleArray, n: DoubleArray) = doubleArrayOf(
            m[0] * n[0] + m[1] * n[2],
            m[0] * n[1] + m[1] * n[3],
            m[2] * n[0] + m[3] * n[2],
            m[2] * n[1] + m[3] * n[3],
            m[4] * n[0] + m[5] * n[2] + n[4],
            m[4] * n[1] + m[5] * n[3] + n[5],
        )

        private fun inflate(data: ByteArray): ByteArray {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val chunk = ByteArray(65536)
            try {
                while (!inflater.finished()) {
                    val n = inflater.inflate(chunk)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(chunk, 0, n)
                }
            } catch (e: DataFormatException) {
                // Keep what decoded; damaged streams are common and often still usable.
            } finally {
                inflater.end()
            }
            return out.toByteArray()
        }

        private fun unpredict(data: ByteArray, predictor: Int, colors: Int, bits: Int, columns: Int): ByteArray {
            if (predictor < 10) {
                if (predictor != 1) throw IOException("Unsupported predictor $predictor")
                return data
            }
            val bpp = max(1, colors * bits / 8)
            val rowLength = (colors * bits * columns + 7) / 8
            val out = ByteArrayOutputStream(data.size)
            var previous = ByteArray(rowLength)
            var i = 0
            while (i < data.size) {
                val type = data[i].toInt()
                val row = ByteArray(rowLength)
                for (x in 0 until rowLength) {
                    val raw = if (i + 1 + x < data.size) data[i + 1 + x].toInt() and 0xff else 0
                    val left = if (x >= bpp) row[x - bpp].toInt() and 0xff else 0
                    val up = previous[x].toInt() and 0xff
                    val upLeft = if (x >= bpp) previous[x - bpp].toInt() and 0xff else 0
                    val value = when (type) {
                        1 -> raw + left
                        2 -> raw + up
                        3 -> raw + (left + up) / 2
                        4 -> {
                            val p = left + up - upLeft
                            val pa = abs(p - left)
                            val pb = abs(p - up)
                            val pc = abs(p - upLeft)
                            raw + if (pa <= pb && pa <= pc) left else if (pb <= pc) up else upLeft
                        }
                        else -> raw
                    }
                    row[x] = value.toByte()
                }
                out.write(row)
                previous = row
                i += rowLength + 1
            }
            return out.toByteArray()
        }

        private fun asciiHex(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size / 2)
            var high = -1
            for (b in data) {
                val c = b.toInt().toChar()
                if (c == '>') break
                val digit = Character.digit(c, 16)
                if (digit < 0) continue
                if (high < 0) {
                    high = digit
                } else {
                    out.write(high * 16 + digit)
                    high = -1
                }
            }
            if (high >= 0) out.write(high * 16)
            return out.toByteArray()
        }

        private fun ascii85(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size)
            var group = 0L
            var count = 0
            var i = 0
            if (data.size >= 2 && data[0] == '<'.code.toByte() && data[1] == '~'.code.toByte()) i = 2
            while (i < data.size) {
                val c = data[i++].toInt()
                when {
                    c == '~'.code -> break
                    c == 'z'.code && count == 0 -> repeat(4) { out.write(0) }
                    c in '!'.code..'u'.code -> {
                        group = group * 85 + (c - '!'.code)
                        if (++count == 5) {
                            for (shift in intArrayOf(24, 16, 8, 0)) out.write((group shr shift).toInt() and 0xff)
                            group = 0
                            count = 0
                        }
                    }
                }
            }
            if (count > 1) {
                // A partial group is padded with 'u' and truncated.
                repeat(5 - count) { group = group * 85 + 84 }
                for (k in 0 until count - 1) out.write((group shr (24 - 8 * k)).toInt() and 0xff)
            }
            return out.toByteArray()
        }

        private fun lzw(data: ByteArray, earlyChange: Int): ByteArray {
            val out = ByteArrayOutputStream(data.size * 3)
            val table = ArrayList<ByteArray>(4096)
            fun reset() {
                table.clear()
                for (k in 0 until 256) table.add(byteArrayOf(k.toByte()))
                table.add(ByteArray(0))
                table.add(ByteArray(0))
            }
            reset()
            var width = 9
            var bitBuffer = 0L
            var bits = 0
            var previous: ByteArray? = null
            var i = 0
            while (true) {
                while (bits < width && i < data.size) {
                    bitBuffer = (bitBuffer shl 8) or (data[i++].toLong() and 0xff)
                    bits += 8
                }
                if (bits < width) break
                val code = ((bitBuffer shr (bits - width)) and ((1L shl width) - 1)).toInt()
                bits -= width
                when {
                    code == 256 -> {
                        reset()
                        width = 9
                        previous = null
                        continue
                    }
                    code == 257 -> break
                }
                val entry = when {
                    code < table.size -> table[code]
                    previous != null && code == table.size -> previous + previous[0]
                    else -> break
                }
                out.write(entry)
                if (previous != null && table.size < 4096) table.add(previous + entry[0])
                previous = entry
                val next = table.size + earlyChange
                width = when {
                    next >= 2048 -> 12
                    next >= 1024 -> 11
                    next >= 512 -> 10
                    else -> 9
                }
            }
            return out.toByteArray()
        }

        /** Decompressed contents of [flate]. */
        fun inflate(file: RandomAccessFile, flate: Flate): ByteArray {
            val data = ByteArray(flate.length)
            file.seek(flate.offset)
            file.readFully(data)
            return unpredict(inflate(data), flate.predictor, flate.colors, 8, flate.columns)
        }

        /**
         * What each page of [file] draws, in page order. A page is null where
         * it couldn't be read; throws if the file's structure can't be.
         */
        fun scan(file: File): List<Page?> {
            RandomAccessFile(file, "r").use { raf ->
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                val scanner = PdfScanner(buf)
                scanner.readXref()
                if (scanner.trailer["Encrypt"] != null) throw IOException("Encrypted")
                return scanner.pages()
            }
        }
    }
}
