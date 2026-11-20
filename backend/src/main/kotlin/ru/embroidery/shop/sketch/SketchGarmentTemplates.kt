package ru.embroidery.shop.sketch

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import ru.embroidery.shop.badRequest
import ru.embroidery.shop.order.EmbroideryFrame
import ru.embroidery.shop.order.Garment
import ru.embroidery.shop.order.Order
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.math.roundToInt

internal const val SKETCH_IMAGE_SIZE = 1024

internal enum class GarmentSide { FRONT, BACK }

internal data class SketchPlacement(
    val side: GarmentSide,
    @field:Min(1) @field:Max(9999) val panelWidthMm: Int,
    @field:Min(1) @field:Max(9999) val panelHeightMm: Int,
    @field:Min(1) @field:Max(9999) val embroideryWidthMm: Int,
    @field:Min(1) @field:Max(9999) val embroideryHeightMm: Int,
    val position: SketchPosition,
)

internal data class SketchPosition(val xPercent: Int, val yPercent: Int) {
    init {
        require(xPercent in 0..100 && yPercent in 0..100)
    }

    companion object {
        val CENTER = SketchPosition(50, 50)
        // On a front-view template, the wearer's left is on the right of the image.
        val LEFT_CHEST = SketchPosition(90, 22)
    }
}

internal data class SketchRectangle(val x: Int, val y: Int, val width: Int, val height: Int) {
    init {
        require(x >= 0 && y >= 0 && width > 0 && height > 0)
        require(x + width <= SKETCH_IMAGE_SIZE && y + height <= SKETCH_IMAGE_SIZE)
    }

    fun frame(placement: SketchPlacement): SketchRectangle {
        if (placement.embroideryWidthMm > placement.panelWidthMm || placement.embroideryHeightMm > placement.panelHeightMm) {
            badRequest("Embroidery must fit the measured garment panel")
        }
        val frameWidth = (width.toDouble() * placement.embroideryWidthMm / placement.panelWidthMm).roundToInt()
        val frameHeight = (height.toDouble() * placement.embroideryHeightMm / placement.panelHeightMm).roundToInt()
        if (frameWidth < 2 || frameHeight < 2) badRequest("Embroidery is too small to preview")
        val left = (width - frameWidth) * placement.position.xPercent / 100
        val top = (height - frameHeight) * placement.position.yPercent / 100
        return SketchRectangle(x + left, y + top, frameWidth, frameHeight)
    }

    fun guide(): SketchImage {
        val image = BufferedImage(SKETCH_IMAGE_SIZE, SKETCH_IMAGE_SIZE, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.BLACK
            repeat(minOf(4, width / 2, height / 2)) { edge ->
                graphics.drawRect(x + edge, y + edge, width - 1 - 2 * edge, height - 1 - 2 * edge)
            }
        } finally {
            graphics.dispose()
        }
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", output))
        return SketchImage("image/png", output.toByteArray())
    }
}

internal data class SketchMockup(
    val garment: String,
    val size: String,
    val color: String,
    val placement: SketchPlacement,
    val panel: SketchRectangle,
    val template: SketchImage,
) {
    val frame = panel.frame(placement)
    val guide by lazy { frame.guide() }
}

internal data class SketchPanelPreview(val area: SketchRectangle, val defaultPosition: SketchPosition)

internal data class SketchPanelEstimate(
    val panelWidthMm: Int,
    val panelHeightMm: Int,
    val foldedWidthMm: Int,
    val foldedHeightMm: Int,
)

@Component
internal class SketchGarmentTemplates {
    private val colors = mapOf(
        "черный" to "black", "белый" to "white", "молочный" to "ivory", "серый" to "gray", "бордовый" to "burgundy",
    )
    private val panels by lazy {
        val resource = ClassPathResource("sketch-templates/panels.properties")
        if (!resource.exists()) badRequest("No template for this garment, color and side")
        val properties = Properties()
        resource.inputStream.use(properties::load)
        properties.stringPropertyNames().associateWith { key ->
            val values = properties.getProperty(key).split(',').map(String::toInt)
            require(values.size == 7)
            val (x, y, width, height, foldedWidth) = values
            val foldedHeight = values[5]
            val foldedWidthMm = values[6]
            require(foldedWidth in width..SKETCH_IMAGE_SIZE && foldedHeight in height..SKETCH_IMAGE_SIZE && foldedWidthMm > 0)
            val mmPerPixel = foldedWidthMm.toDouble() / foldedWidth
            val estimate = SketchPanelEstimate(
                (width * mmPerPixel).roundToInt(), (height * mmPerPixel).roundToInt(),
                foldedWidthMm, (foldedHeight * mmPerPixel).roundToInt(),
            )
            TemplatePanel(SketchRectangle(x, y, width, height), estimate)
        }
    }

    fun validate(order: Order, placement: SketchPlacement) {
        resourceFor(order, placement.side).first.area.frame(placement)
    }

    fun prepare(order: Order, placement: SketchPlacement): SketchMockup {
        val (panel, template) = resourceFor(order, placement.side)
        return SketchMockup(
            order.garment, order.size, order.color, placement, panel.area, loadTemplate(template),
        )
    }

    fun estimates(order: Order): Map<GarmentSide, SketchPanelEstimate> {
        if (order.size != "M") return emptyMap()
        return GarmentSide.entries.mapNotNull { side -> find(order, side)?.let { side to it.first.estimate } }.toMap()
    }

    fun previews(order: Order): Map<GarmentSide, SketchPanelPreview> = GarmentSide.entries.mapNotNull { side ->
        find(order, side)?.let { side to SketchPanelPreview(it.first.area, defaultPosition(order, side)) }
    }.toMap()

    fun image(order: Order, side: GarmentSide): SketchImage = loadTemplate(resourceFor(order, side).second)

    private fun loadTemplate(template: ClassPathResource) =
        SketchImage("image/jpeg", template.inputStream.use { it.readAllBytes() })

    private fun defaultPosition(order: Order, side: GarmentSide) =
        if (order.embroidery?.frame == EmbroideryFrame.MINI && side == GarmentSide.FRONT) SketchPosition.LEFT_CHEST
        else SketchPosition.CENTER

    private fun resourceFor(order: Order, side: GarmentSide) =
        find(order, side) ?: badRequest("No template for this garment, color and side")

    private fun find(order: Order, side: GarmentSide): Pair<TemplatePanel, ClassPathResource>? {
        val garment = Garment.entries.singleOrNull { it.name == order.garment } ?: return null
        val color = colors[order.color.lowercase(Locale.ROOT).replace('ё', 'е')] ?: return null
        val key = garment.name.lowercase(Locale.ROOT).replace('_', '-') + "-" + side.name.lowercase(Locale.ROOT)
        val panel = panels[key] ?: return null
        val resource = ClassPathResource("sketch-templates/$key-$color.jpg")
        return if (resource.exists()) panel to resource else null
    }

    private data class TemplatePanel(val area: SketchRectangle, val estimate: SketchPanelEstimate)
}
