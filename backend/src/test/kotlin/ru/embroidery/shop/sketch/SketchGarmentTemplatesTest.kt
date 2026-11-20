package ru.embroidery.shop.sketch

import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.order.Garment
import ru.embroidery.shop.order.Order
import ru.embroidery.shop.order.OrderStatus
import ru.embroidery.shop.order.OrderType
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
            val placement = SketchPlacement(side, estimate.panelWidthMm, estimate.panelHeightMm, 120, 120)
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
        val placement = SketchPlacement(GarmentSide.FRONT, 360, 325, 140, 100)
        val guide = ImageIO.read(templates.prepare(order, placement).guide.bytes.inputStream()).raster

        assertEquals(0, guide.getSample(413, 522, 0))
        assertEquals(0, guide.getSample(596, 652, 0))
        assertEquals(255, guide.getSample(412, 522, 0))
        assertEquals(255, guide.getSample(597, 652, 0))
        assertEquals(255, guide.getSample(504, 587, 0))

        assertFailsWith<ApiFailure> { templates.validate(order, placement.copy(embroideryWidthMm = 361)) }
    }
}
