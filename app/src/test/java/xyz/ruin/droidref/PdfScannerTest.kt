package xyz.ruin.droidref

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

class PdfScannerTest {
    /** Writes a PDF from numbered object bodies (1-based), with a plain xref table. */
    private fun pdf(objects: List<ByteArray>): File {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        val offsets = objects.mapIndexed { i, body ->
            val offset = out.size()
            out.write("${i + 1} 0 obj\n".toByteArray())
            out.write(body)
            out.write("\nendobj\n".toByteArray())
            offset
        }
        val xref = out.size()
        out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray())
        offsets.forEach { out.write("%010d 00000 n \n".format(it).toByteArray()) }
        out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        return File.createTempFile("scan", ".pdf").apply {
            deleteOnExit()
            writeBytes(out.toByteArray())
        }
    }

    private fun stream(dict: String, data: ByteArray) =
        "<< $dict /Length ${data.size} >>\nstream\n".toByteArray() + data + "\nendstream".toByteArray()

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArray(data.size + 64)
        val n = deflater.deflate(out)
        return out.copyOf(n)
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun findsFullPageImagesAndText() {
        val file = pdf(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(),
                "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 200 100] >>".toByteArray(),
                "<< /Type /Page /Parent 2 0 R /Resources << /XObject << /Im0 5 0 R >> >> /Contents 6 0 R >>".toByteArray(),
                "<< /Type /Page /Parent 2 0 R /Rotate 90 /Resources << /Font << >> >> /Contents 7 0 R >>".toByteArray(),
                stream("/Type /XObject /Subtype /Image /Width 800 /Height 400 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", jpeg),
                stream("/Filter /FlateDecode", deflate("q 200 0 0 100 0 0 cm /Im0 Do Q".toByteArray())),
                stream("", "BT /F1 12 Tf (a \\) b) Tj ET".toByteArray()),
            )
        )
        val pages = PdfScanner.scan(file)
        assertEquals(2, pages.size)

        val picture = pages[0]!!
        assertFalse(picture.hasOtherContent)
        val image = picture.images.single()
        assertEquals(PdfScanner.Box(0.0, 0.0, 200.0, 100.0), image.bounds)
        assertEquals(4.0, image.scale, 1e-9)
        assertTrue(image.upright)
        val copy = image.copy as PdfScanner.JpegCopy
        assertEquals(jpeg.size, copy.length)
        val bytes = file.readBytes().copyOfRange(copy.offset.toInt(), copy.offset.toInt() + copy.length)
        assertTrue(bytes.contentEquals(jpeg))

        val text = pages[1]!!
        assertTrue(text.hasOtherContent)
        assertEquals(90, text.rotate)
        assertEquals(100.0, text.displayWidth, 0.0)
        assertEquals(200.0, text.displayHeight, 0.0)
    }

    @Test
    fun ignoresInvisibleTextOverScans() {
        val file = pdf(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(),
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(),
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>".toByteArray(),
                stream("/Subtype /Image /Width 10 /Height 10 /ColorSpace /DeviceGray /BitsPerComponent 8 /SMask 4 0 R /Filter /DCTDecode", jpeg),
                stream("", "q 100 0 0 100 0 0 cm /Im0 Do Q BT 3 Tr (hidden) Tj ET".toByteArray()),
            )
        )
        val page = PdfScanner.scan(file).single()!!
        assertFalse(page.hasOtherContent)
        // A JPEG with a soft mask has to be rendered rather than copied out.
        assertNull(page.images.single().copy)
    }

    @Test
    fun copiesDeflatedPixelsWithTheirMask() {
        val pixels = byteArrayOf(10, 20, 30, 40, 50, 60)
        val mask = byteArrayOf(-1, 0)
        val file = pdf(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(),
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(),
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 2 1] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 6 0 R >>".toByteArray(),
                stream("/Subtype /Image /Width 2 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 /SMask 5 0 R /Filter /FlateDecode", deflate(pixels)),
                stream("/Subtype /Image /Width 2 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode", deflate(mask)),
                stream("", "q 2 0 0 1 0 0 cm /Im0 Do Q".toByteArray()),
            )
        )
        val copy = PdfScanner.scan(file).single()!!.images.single().copy as PdfScanner.PixelCopy
        assertEquals(3, copy.channels)
        java.io.RandomAccessFile(file, "r").use { raf ->
            assertTrue(PdfScanner.inflate(raf, copy.samples).contentEquals(pixels))
            assertTrue(PdfScanner.inflate(raf, copy.mask!!).contentEquals(mask))
        }
    }

    @Test
    fun mapsToRotatedDisplayCoordinates() {
        val page = PdfScanner.Page(PdfScanner.Box(0.0, 0.0, 200.0, 100.0), 90, emptyList(), false)
        // The bottom left corner of the unrotated page ends up at the top left.
        val corner = PdfScanner.Box(0.0, 0.0, 10.0, 20.0)
        assertTrue(page.toDisplay(corner).contentEquals(doubleArrayOf(0.0, 0.0, 20.0, 10.0)))
        val unrotated = PdfScanner.Page(page.cropBox, 0, emptyList(), false)
        assertTrue(unrotated.toDisplay(corner).contentEquals(doubleArrayOf(0.0, 80.0, 10.0, 100.0)))
    }

    @Test
    fun readsAsciiAndLzwContentStreams() {
        // "q 2 0 0 1 0 0 cm /Im0 Do Q" in each encoding.
        val content = "q 2 0 0 1 0 0 cm /Im0 Do Q".toByteArray()
        val hex = content.joinToString("") { "%02x".format(it) } + ">"
        val encodings = listOf(
            "/Filter /ASCIIHexDecode" to hex.toByteArray(),
            "/Filter /ASCII85Decode" to ascii85(content),
            "/Filter /LZWDecode" to lzw(content),
        )
        for ((filter, data) in encodings) {
            val file = pdf(
                listOf(
                    "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(),
                    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(),
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 2 1] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>".toByteArray(),
                    stream("/Subtype /Image /Width 2 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode", deflate(byteArrayOf(1, 2))),
                    stream(filter, data),
                )
            )
            val page = PdfScanner.scan(file).single()
            assertEquals(filter, 1, page?.images?.size)
        }
    }

    private fun ascii85(data: ByteArray): ByteArray {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val n = minOf(4, data.size - i)
            var v = 0L
            for (k in 0 until 4) v = (v shl 8) or (if (k < n) data[i + k].toLong() and 0xff else 0)
            val chars = CharArray(5)
            for (k in 4 downTo 0) {
                chars[k] = ('!'.code + (v % 85).toInt()).toChar()
                v /= 85
            }
            sb.append(chars, 0, n + 1)
            i += 4
        }
        return "$sb~>".toByteArray()
    }

    /** LZW with the table never growing past 9-bit codes: each byte is a literal code. */
    private fun lzw(data: ByteArray): ByteArray {
        val codes = listOf(256) + data.map { it.toInt() and 0xff } + 257
        val out = ByteArrayOutputStream()
        var buffer = 0L
        var bits = 0
        var tableSize = 258
        var width = 9
        for ((index, code) in codes.withIndex()) {
            buffer = (buffer shl width) or code.toLong()
            bits += width
            while (bits >= 8) {
                out.write((buffer shr (bits - 8)).toInt() and 0xff)
                bits -= 8
            }
            // Mirror the decoder: every literal after the first adds an entry.
            if (index >= 2 && code < 256) tableSize++
            width = if (tableSize + 1 >= 512) 10 else 9
        }
        if (bits > 0) out.write((buffer shl (8 - bits)).toInt() and 0xff)
        return out.toByteArray()
    }

    @Test
    fun unreadablePageIsNull() {
        val file = pdf(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(),
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(),
                "<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>".toByteArray(),
                stream("/Filter /RunLengthDecode", byteArrayOf(1, 2, 3)),
            )
        )
        assertNull(PdfScanner.scan(file).single())
    }
}
