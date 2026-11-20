package ru.embroidery.shop.sketch

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

internal fun SketchMockup.place(rendered: SketchImage): SketchImage {
    val motif = rendered.decode()
    if (!motif.colorModel.hasAlpha()) throw SketchGenerationException("GenAPI motif is missing a transparent background")
    var minX = motif.width
    var minY = motif.height
    var maxX = -1
    var maxY = -1
    var opaqueBorderPixels = 0
    for (y in 0 until motif.height) for (x in 0 until motif.width) {
        val alpha = motif.getRGB(x, y) ushr 24
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
        graphics.drawImage(motif.getSubimage(minX, minY, width, height), left, top, scaledWidth, scaledHeight, null)
    } finally {
        graphics.dispose()
    }
    val bytes = ByteArrayOutputStream()
    check(ImageIO.write(result, "png", bytes))
    return SketchImage("image/png", bytes.toByteArray())
}

private fun SketchImage.decode(): BufferedImage =
    ImageIO.read(bytes.inputStream()) ?: throw SketchGenerationException("Unable to read embroidery image")
