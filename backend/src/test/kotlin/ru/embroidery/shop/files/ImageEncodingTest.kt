package ru.embroidery.shop.files

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.math.pow
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageEncodingTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `JPEG keeps all EXIF orientations while discarding GPS and other metadata`() {
        val original = BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB)
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val graphics = original.createGraphics()
        try {
            colors.forEachIndexed { index, color ->
                graphics.color = color
                graphics.fillRect(index % 2 * 60, index / 2 * 40, 60, 40)
            }
        } finally {
            graphics.dispose()
        }
        val jpeg = imageBytes(original, "jpeg")
        val expectedCorners = listOf(
            listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2)
        )
        val source = temporary.resolve("original.jpg")
        val destination = temporary.resolve("canonical.jpg")
        for (orientation in 1..8) {
            val order = if (orientation % 2 == 0) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            val input = jpeg.copyOfRange(0, 2) + exifSegment(exif(orientation, order)) + jpeg.copyOfRange(2, jpeg.size)
            assertTrue(input.decodeToString().contains("Synthetic photographer"))
            Files.write(source, input)
            val result = encodeCatalogImage(source, destination)
            val encoded = Files.readAllBytes(destination)
            assertEquals("image/jpeg", result.mediaType)
            assertEquals(encoded.size.toLong(), result.byteSize)
            assertFalse(encoded.decodeToString().contains("Exif\u0000\u0000"))
            assertFalse(encoded.decodeToString().contains("Synthetic photographer"))
            assertContentEquals(input, Files.readAllBytes(source))

            val decoded = ImageIO.read(destination.toFile())
            assertEquals(if (orientation <= 4) 120 to 80 else 80 to 120, decoded.width to decoded.height)
            val actualCorners = listOf(
                decoded.getRGB(10, 10), decoded.getRGB(decoded.width - 10, 10),
                decoded.getRGB(10, decoded.height - 10), decoded.getRGB(decoded.width - 10, decoded.height - 10)
            )
            assertEquals(expectedCorners[orientation - 1], actualCorners.map { rgb ->
                val sample = Color(rgb)
                colors.indices.minBy { index -> colorDistance(sample, colors[index]) }
            }, "EXIF orientation $orientation")
        }
    }

    @Test
    fun `PNG retains alpha and orientation while discarding text and EXIF chunks`() {
        val original = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        val pixels = intArrayOf(0xffff0000.toInt(), 0x8000ff00.toInt(), 0x400000ff, 0)
        original.setRGB(0, 0, 2, 2, pixels, 0, 2)
        val png = imageBytes(original, "png")
        val input = png.copyOfRange(0, 33) +
            pngChunk("tEXt", "Artist\u0000Synthetic photographer".encodeToByteArray()) +
            pngChunk("eXIf", exif(6, ByteOrder.BIG_ENDIAN)) +
            png.copyOfRange(33, png.size)
        val source = Files.write(temporary.resolve("original.png"), input)
        val destination = temporary.resolve("canonical.png")

        val result = encodeCatalogImage(source, destination)

        val encoded = Files.readAllBytes(destination)
        assertEquals("image/png", result.mediaType)
        assertEquals(encoded.size.toLong(), result.byteSize)
        assertFalse(encoded.decodeToString().contains("tEXt"))
        assertFalse(encoded.decodeToString().contains("eXIf"))
        assertFalse(encoded.decodeToString().contains("Synthetic photographer"))
        assertContentEquals(intArrayOf(pixels[2], pixels[0], pixels[3], pixels[1]),
            ImageIO.read(destination.toFile()).getRGB(0, 0, 2, 2, null, 0, 2))
    }

    @Test
    fun `large catalog image is reduced within its pixel and byte budgets`() {
        val original = BufferedImage(3000, 2000, BufferedImage.TYPE_INT_ARGB)
        val source = temporary.resolve("large.png")
        val destination = temporary.resolve("canonical.png")
        assertTrue(ImageIO.write(original, "png", source.toFile()))

        val result = encodeCatalogImage(source, destination)

        assertTrue(result.byteSize in 1..MAX_IMAGE_BYTES)
        val decoded = ImageIO.read(destination.toFile())
        assertEquals(1936, decoded.width)
        assertEquals(1290, decoded.height)
        assertEquals(0, decoded.getRGB(0, 0))
    }

    private fun imageBytes(image: BufferedImage, format: String): ByteArray = ByteArrayOutputStream().run {
        assertTrue(ImageIO.write(image, format, this))
        toByteArray()
    }

    private fun exif(orientation: Int, order: ByteOrder): ByteArray {
        val artist = "Synthetic photographer\u0000".encodeToByteArray()
        val tiff = ByteBuffer.allocate(110).order(order)
        tiff.putShort(if (order == ByteOrder.BIG_ENDIAN) 0x4d4d else 0x4949)
        tiff.putShort(42)
        tiff.putInt(8)
        tiff.putShort(3)
        tiff.putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0)
        tiff.putShort(0x013b).putShort(2).putInt(artist.size).putInt(52)
        tiff.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(76).putInt(0)
        tiff.position(52).put(artist)
        tiff.position(76).putShort(1)
        tiff.putShort(2).putShort(5).putInt(3).putInt(86).putInt(0)
        tiff.position(86).putInt(55).putInt(1).putInt(30).putInt(1).putInt(0).putInt(1)
        return tiff.array()
    }

    private fun exifSegment(tiff: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).use { segment ->
            segment.writeShort(0xffe1)
            segment.writeShort(tiff.size + 8)
            segment.write("Exif\u0000\u0000".encodeToByteArray())
            segment.write(tiff)
        }
    }.toByteArray()

    private fun pngChunk(type: String, contents: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        val name = type.encodeToByteArray()
        val checksum = CRC32().apply {
            update(name)
            update(contents)
        }
        DataOutputStream(this).use { chunk ->
            chunk.writeInt(contents.size)
            chunk.write(name)
            chunk.write(contents)
            chunk.writeInt(checksum.value.toInt())
        }
    }.toByteArray()

    private fun colorDistance(a: Color, b: Color): Double =
        (a.red - b.red).toDouble().pow(2) + (a.green - b.green).toDouble().pow(2) + (a.blue - b.blue).toDouble().pow(2)
}
