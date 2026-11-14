package ru.embroidery.shop.files

import org.springframework.http.HttpStatus
import ru.embroidery.shop.ApiFailure
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageInputStream
import javax.imageio.stream.FileImageOutputStream
import kotlin.math.sqrt

internal const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
private const val MAX_CATALOG_PIXELS = 2_500_000

private enum class ImageFormat(val mimeType: String) { JPEG("image/jpeg"), PNG("image/png") }

internal fun validateImage(file: Path): String = readImage(file) { reader, format, _, _ ->
    val sample = reader.defaultReadParam.apply { setSourceSubsampling(8, 8, 0, 0) }
    reader.read(0, sample)
    format.mimeType
}

internal fun encodeCatalogImage(file: Path, destination: Path): ValidatedImage {
    val (image, format) = readImage(file) { reader, format, width, height ->
        val pixels = width * height
        val scale = if (pixels > MAX_CATALOG_PIXELS) sqrt(MAX_CATALOG_PIXELS.toDouble() / pixels) else 1.0
        val subsampling = (1.0 / scale).toInt()
        val parameters = reader.defaultReadParam.apply { setSourceSubsampling(subsampling, subsampling, 0, 0) }
        val orientation = when (format) {
            ImageFormat.JPEG -> jpegOrientation(file)
            ImageFormat.PNG -> pngOrientation(file)
        }
        val source = reader.read(0, parameters)
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        render(source, targetWidth, targetHeight, orientation) to format
    }
    val writer = ImageIO.getImageWritersByFormatName(format.name).next()
    try {
        FileImageOutputStream(destination.toFile()).use { output ->
            writer.output = output
            val parameters = writer.defaultWriteParam
            if (format == ImageFormat.JPEG) {
                parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
                parameters.compressionQuality = 0.92f
            }
            writer.write(null, IIOImage(image, null, null), parameters)
        }
    } finally {
        writer.dispose()
        image.flush()
    }
    val size = Files.size(destination)
    check(size > 0)
    requireImageSize(size)
    return ValidatedImage(destination, format.mimeType, size)
}

private fun render(source: BufferedImage, width: Int, height: Int, orientation: Int): BufferedImage {
    val swapAxes = orientation >= 5
    val target = BufferedImage(
        if (swapAxes) height else width,
        if (swapAxes) width else height,
        if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
    )
    val graphics = target.createGraphics()
    try {
        graphics.composite = AlphaComposite.Src
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        val transform = AffineTransform.getScaleInstance(
            target.width.toDouble() / if (swapAxes) source.height else source.width,
            target.height.toDouble() / if (swapAxes) source.width else source.height
        )
        transform.concatenate(orientationTransform(orientation, source.width.toDouble(), source.height.toDouble()))
        graphics.drawImage(source, transform, null)
    } finally {
        graphics.dispose()
        source.flush()
    }
    return target
}

private fun orientationTransform(orientation: Int, width: Double, height: Double): AffineTransform =
    when (orientation) {
        1 -> AffineTransform()
        2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, width, 0.0)
        3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, width, height)
        4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, height)
        5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
        6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, height, 0.0)
        7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, height, width)
        8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, width)
        else -> invalidImage()
    }

private val exifSignature = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)

// The JPEG reader ignores metadata. Read only the IFD0 orientation before decoding the pixels.
private fun jpegOrientation(file: Path): Int {
    DataInputStream(Files.newInputStream(file).buffered()).use { input ->
        if (input.readUnsignedShort() != 0xffd8) invalidImage()
        while (true) {
            if (input.readUnsignedByte() != 0xff) invalidImage()
            var marker = input.readUnsignedByte()
            while (marker == 0xff) marker = input.readUnsignedByte()
            if (marker == 0xda || marker == 0xd9) return 1
            if (marker == 0x01 || marker in 0xd0..0xd7) continue
            val length = input.readUnsignedShort() - 2
            if (length < 0) invalidImage()
            if (marker == 0xe1 && length >= exifSignature.size) {
                if (input.readNBytes(exifSignature.size).contentEquals(exifSignature)) {
                    val tiff = ByteArray(length - exifSignature.size)
                    input.readFully(tiff)
                    return exifOrientation(tiff)
                }
                input.skipNBytes((length - exifSignature.size).toLong())
            } else {
                input.skipNBytes(length.toLong())
            }
        }
    }
}

private fun pngOrientation(file: Path): Int {
    DataInputStream(Files.newInputStream(file).buffered()).use { input ->
        if (input.readLong() != 0x89504e470d0a1a0aUL.toLong()) invalidImage()
        while (true) {
            val length = input.readInt()
            if (length.toLong() !in 0L..MAX_IMAGE_BYTES) invalidImage()
            when (input.readInt()) {
                0x49454e44 -> return 1 // IEND
                0x65584966 -> { // eXIf
                    val tiff = input.readNBytes(length)
                    if (tiff.size != length) invalidImage()
                    return exifOrientation(tiff)
                }
                else -> input.skipNBytes(length.toLong() + 4)
            }
        }
    }
}

private fun exifOrientation(tiff: ByteArray): Int {
    if (tiff.size < 8) invalidImage()
    val data = ByteBuffer.wrap(tiff)
    val order = when (data.getShort(0).toInt()) {
        0x4949 -> ByteOrder.LITTLE_ENDIAN
        0x4d4d -> ByteOrder.BIG_ENDIAN
        else -> invalidImage()
    }
    data.order(order)
    if (data.getShort(2).toInt() != 42) invalidImage()
    val directory = data.getInt(4)
    if (directory !in 8..tiff.size - 2) invalidImage()
    val entries = data.getShort(directory).toInt() and 0xffff
    if (directory.toLong() + 2 + entries.toLong() * 12 > tiff.size) invalidImage()
    for (index in 0 until entries) {
        val entry = directory + 2 + index * 12
        if (data.getShort(entry).toInt() != 0x0112) continue
        if (data.getShort(entry + 2).toInt() != 3 || data.getInt(entry + 4) != 1) invalidImage()
        return data.getShort(entry + 8).toInt().also { if (it !in 1..8) invalidImage() }
    }
    return 1
}

private fun <T> readImage(file: Path, action: (ImageReader, ImageFormat, Int, Int) -> T): T {
    requireImageSize(Files.size(file))
    try {
        FileImageInputStream(file.toFile()).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) invalidImage()
            val reader = readers.next()
            try {
                val format = when (reader.formatName.uppercase(Locale.ROOT)) {
                    "JPEG" -> ImageFormat.JPEG
                    "PNG" -> ImageFormat.PNG
                    else -> invalidImage()
                }
                reader.setInput(input, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width !in 1..6000 || height !in 1..6000 || width.toLong() * height > 20_000_000L) {
                    throw ApiFailure(HttpStatus.BAD_REQUEST, "Image dimensions exceed the upload limit")
                }
                return action(reader, format, width, height)
            } finally {
                reader.dispose()
            }
        }
    } catch (_: IOException) {
        invalidImage()
    }
}

private fun requireImageSize(bytes: Long) {
    if (bytes > MAX_IMAGE_BYTES) throw ApiFailure(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the upload limit")
}

private fun invalidImage(): Nothing =
    throw ApiFailure(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Upload a valid JPEG or PNG image")
