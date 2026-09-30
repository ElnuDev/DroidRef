package xyz.ruin.droidref

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Writes 8-bit grey or RGB pixels as PNG, much faster than going through a Bitmap. */
object Png {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    /** [pixels] holds [height] rows of [width] * [channels] (1 or 3) bytes. */
    fun encode(width: Int, height: Int, channels: Int, pixels: ByteArray): ByteArray {
        require(channels == 1 || channels == 3)
        val out = ByteArrayOutputStream(pixels.size / 8)
        out.write(SIGNATURE)
        val header = ByteBuffer.allocate(13).putInt(width).putInt(height)
            .put(8).put(if (channels == 3) 2 else 0).put(0).put(0).put(0)
        chunk(out, "IHDR", header.array())
        val data = ByteArrayOutputStream(pixels.size / 8)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        DeflaterOutputStream(data, deflater, 1 shl 16).use { z ->
            val stride = width * channels
            val row = ByteArray(stride + 1)
            // The "up" filter: scans and line art are mostly like the row above.
            row[0] = 2
            for (y in 0 until height) {
                val start = y * stride
                for (x in 0 until stride) {
                    val above = if (y > 0) pixels[start - stride + x] else 0
                    row[x + 1] = (pixels[start + x] - above).toByte()
                }
                z.write(row)
            }
        }
        deflater.end()
        chunk(out, "IDAT", data.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }
}
