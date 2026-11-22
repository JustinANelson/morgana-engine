package com.morgana.tilegen.export

import com.morgana.tilegen.TileImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Pure-JVM PNG encoder for [TileImage] (8-bit RGBA, color type 6, no AWT dependency).
 */
object PngEncoder {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /**
     * Encodes [image] into a standard RGBA PNG byte array.
     */
    fun encode(image: TileImage): ByteArray {
        val out = ByteArrayOutputStream(image.width * image.height * 4 + 128)
        out.write(PNG_SIGNATURE)

        // IHDR: width(4), height(4), bitDepth(1)=8, colorType(1)=6 (RGBA), compression(1)=0, filter(1)=0, interlace(1)=0
        val ihdr = ByteArrayOutputStream(13)
        DataOutputStream(ihdr).use { dos ->
            dos.writeInt(image.width)
            dos.writeInt(image.height)
            dos.writeByte(8)
            dos.writeByte(6)
            dos.writeByte(0)
            dos.writeByte(0)
            dos.writeByte(0)
        }
        writeChunk(out, "IHDR", ihdr.toByteArray())

        // Raw scanlines: 1 filter byte (0 = None) + width * 4 RGBA bytes per row
        val stride = 1 + image.width * 4
        val raw = ByteArray(stride * image.height)
        for (y in 0 until image.height) {
            val rowOffset = y * stride
            raw[rowOffset] = 0 // Filter type None
            val pixelRow = y * image.width
            for (x in 0 until image.width) {
                val rgba = image.pixels[pixelRow + x]
                val pOff = rowOffset + 1 + x * 4
                raw[pOff] = ((rgba ushr 24) and 0xFF).toByte()
                raw[pOff + 1] = ((rgba ushr 16) and 0xFF).toByte()
                raw[pOff + 2] = ((rgba ushr 8) and 0xFF).toByte()
                raw[pOff + 3] = (rgba and 0xFF).toByte()
            }
        }

        val compressed = deflate(raw)
        writeChunk(out, "IDAT", compressed)
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /**
     * Encodes [image] as PNG and writes it to [path], creating parent directories if needed.
     */
    fun writeToFile(image: TileImage, path: Path) {
        path.parent?.let { Files.createDirectories(it) }
        Files.write(path, encode(image))
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            out.write(buf, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value

        DataOutputStream(out).use { dos ->
            dos.writeInt(data.size)
            dos.write(typeBytes)
            dos.write(data)
            dos.writeInt(crc.toInt())
        }
    }
}
