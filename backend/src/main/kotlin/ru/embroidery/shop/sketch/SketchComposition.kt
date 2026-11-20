package ru.embroidery.shop.sketch

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

internal enum class SketchBackdrop(val description: String) {
    MAGENTA("solid bright chroma magenta (#FF00FF)"),
    GREEN("solid bright chroma green (#00FF00)");

    private fun excess(rgb: Int): Int {
        val red = rgb shr 16 and 255
        val green = rgb shr 8 and 255
        val blue = rgb and 255
        return when (this) {
            MAGENTA -> minOf(red, blue) - green
            GREEN -> green - maxOf(red, blue)
        }
    }

    fun foregroundOpacity(rgb: Int): Int = ((90 - excess(rgb)) * 255 / 35).coerceIn(0, 255)

    companion object {
        fun choose(sources: List<SketchImage>): SketchBackdrop {
            val scores = IntArray(entries.size)
            for (source in sources) {
                val image = source.decode()
                for (y in 0 until image.height step maxOf(1, image.height / 64)) {
                    for (x in 0 until image.width step maxOf(1, image.width / 64)) {
                        val pixel = image.getRGB(x, y)
                        for (backdrop in entries) if (backdrop.excess(pixel) > 45) scores[backdrop.ordinal]++
                    }
                }
            }
            return entries.minBy { scores[it.ordinal] }
        }
    }
}

internal fun SketchMockup.place(rendered: SketchImage, backdrop: SketchBackdrop): SketchImage {
    val motif = rendered.decode()
    val cutout = BufferedImage(motif.width, motif.height, BufferedImage.TYPE_INT_ARGB)
    var minX = motif.width
    var minY = motif.height
    var maxX = -1
    var maxY = -1
    var opaqueBorderPixels = 0
    for (y in 0 until motif.height) for (x in 0 until motif.width) {
        val pixel = motif.getRGB(x, y)
        val alpha = (pixel ushr 24) * backdrop.foregroundOpacity(pixel) / 255
        cutout.setRGB(x, y, (alpha shl 24) or (pixel and 0xFFFFFF))
        if (alpha < 128) continue
        if (x == 0 || y == 0 || x == motif.width - 1 || y == motif.height - 1) opaqueBorderPixels++
        minX = minOf(minX, x)
        minY = minOf(minY, y)
        maxX = maxOf(maxX, x)
        maxY = maxOf(maxY, y)
    }
    if (opaqueBorderPixels != 0 || maxX - minX < 2 || maxY - minY < 2) {
        throw SketchGenerationException("GenAPI did not return an isolated embroidery motif")
    }
    val width = maxX - minX + 1
    val height = maxY - minY + 1
    val scale = minOf(frame.width.toDouble() / width, frame.height.toDouble() / height)
    val scaledWidth = (width * scale).roundToInt().coerceIn(1, frame.width)
    val scaledHeight = (height * scale).roundToInt().coerceIn(1, frame.height)
    val left = frame.x + (frame.width - scaledWidth) / 2
    val top = frame.y + (frame.height - scaledHeight) / 2
    val result = template.decode()
    val graphics = result.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.drawImage(cutout.getSubimage(minX, minY, width, height), left, top, scaledWidth, scaledHeight, null)
    } finally {
        graphics.dispose()
    }
    val bytes = ByteArrayOutputStream()
    check(ImageIO.write(result, "png", bytes))
    return SketchImage("image/png", bytes.toByteArray())
}

private fun SketchImage.decode(): BufferedImage =
    ImageIO.read(bytes.inputStream()) ?: throw SketchGenerationException("Unable to read embroidery image")
