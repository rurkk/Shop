package ru.embroidery.shop.sketch

import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.order.EmbroideryConfiguration
import ru.embroidery.shop.order.EmbroideryFrame
import ru.embroidery.shop.order.Garment
import ru.embroidery.shop.order.Order
import ru.embroidery.shop.order.OrderStatus
import ru.embroidery.shop.order.OrderType
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SketchGarmentTemplatesTest {
    private val templates = SketchGarmentTemplates()
    private val order = Order(
        UUID.randomUUID(), OrderType.CUSTOM_PHOTO, "buyer@example.test", Garment.HOODIE.name, "M", "чёрный",
        null, OrderStatus.PREPARING_SKETCH, null, null, null, null, null, OffsetDateTime.now(),
    )

    @Test
    fun `all order garments colors and sides have a calibrated square template`() {
        val colors = listOf("чёрный", "белый", "молочный", "серый", "бордовый")
        for (garment in Garment.entries) for (color in colors) for (side in GarmentSide.entries) {
            val product = order.copy(garment = garment.name, color = color)
            val estimate = templates.estimates(product).getValue(side)
            assertTrue(estimate.foldedWidthMm > estimate.panelWidthMm)
            assertTrue(estimate.foldedHeightMm > estimate.panelHeightMm)
            val placement = SketchPlacement(side, estimate.panelWidthMm, estimate.panelHeightMm, 120, 120, SketchPosition.CENTER)
            val rendered = templates.prepare(product, placement)
            val image = ImageIO.read(rendered.template.bytes.inputStream())
            assertEquals(SKETCH_IMAGE_SIZE, image.width, "$garment $color $side")
            assertEquals(SKETCH_IMAGE_SIZE, image.height, "$garment $color $side")
        }
        assertTrue(templates.estimates(order.copy(size = "L")).isEmpty())
        assertTrue(templates.estimates(order.copy(color = "другой")).isEmpty())
    }

    @Test
    fun `both physical axes are scaled to a centered guide inside the prepared panel`() {
        val placement = SketchPlacement(GarmentSide.FRONT, 360, 325, 140, 100, SketchPosition.CENTER)
        val guide = ImageIO.read(templates.prepare(order, placement).guide.bytes.inputStream()).raster

        assertEquals(0, guide.getSample(413, 522, 0))
        assertEquals(0, guide.getSample(596, 652, 0))
        assertEquals(255, guide.getSample(412, 522, 0))
        assertEquals(255, guide.getSample(597, 652, 0))
        assertEquals(255, guide.getSample(504, 587, 0))

        assertFailsWith<ApiFailure> { templates.validate(order, placement.copy(embroideryWidthMm = 361)) }
    }

    @Test
    fun `mini embroidery starts at the wearers left chest and can move inside the same panel`() {
        val miniOrder = order.copy(embroidery = EmbroideryConfiguration(EmbroideryFrame.MINI, false, false))
        val defaultPosition = templates.previews(miniOrder).getValue(GarmentSide.FRONT).defaultPosition
        val placement = SketchPlacement(GarmentSide.FRONT, 360, 325, 80, 80, defaultPosition)
        val mockup = templates.prepare(miniOrder, placement)

        assertEquals(SketchRectangle(600, 444, 105, 105), mockup.frame)
        assertEquals(SketchRectangle(453, 535, 105, 105), mockup.copy(placement = placement.copy(position = SketchPosition.CENTER)).frame)
        assertEquals(SketchRectangle(269, 696, 105, 105), mockup.copy(placement = placement.copy(position = SketchPosition(0, 100))).frame)
    }

    @Test
    fun `isolated stitches fit the frame and preserve the garment pixels and aspect ratio`() {
        val placement = SketchPlacement(GarmentSide.FRONT, 360, 325, 140, 100, SketchPosition.CENTER)
        val mockup = templates.prepare(order, placement)
        val isolated = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val graphics = isolated.createGraphics()
        graphics.color = Color.ORANGE
        graphics.fillRect(4, 10, 24, 12)
        graphics.dispose()
        fun encoded(): SketchImage {
            val bytes = ByteArrayOutputStream()
            ImageIO.write(isolated, "png", bytes)
            return SketchImage("image/png", bytes.toByteArray())
        }
        val original = ImageIO.read(mockup.template.bytes.inputStream())
        val result = ImageIO.read(mockup.place(encoded()).bytes.inputStream())
        assertEquals(Color.ORANGE.rgb, result.getRGB(413, 541))
        assertEquals(Color.ORANGE.rgb, result.getRGB(596, 632))
        for ((x, y) in listOf(412 to 587, 597 to 587, 504 to 540, 504 to 633)) {
            assertEquals(original.getRGB(x, y), result.getRGB(x, y))
        }

        isolated.setRGB(0, 0, Color.ORANGE.rgb)
        assertFailsWith<SketchGenerationException> { mockup.place(encoded()) }
    }
}
