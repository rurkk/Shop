package ru.embroidery.shop.order

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import ru.embroidery.shop.catalog.Availability
import ru.embroidery.shop.conflict
import java.time.OffsetDateTime
import java.util.UUID

internal const val MAX_KOPECKS = 9_007_199_254_740_991L

enum class Garment { HOODIE, T_SHIRT, SWEATSHIRT }
enum class OrderType { CUSTOM_PHOTO, CATALOG }
enum class OrderStatus {
    DRAFT, EXPIRED, IN_REVIEW, AWAITING_DELIVERY_QUOTE, AWAITING_PAYMENT, PREPARING_SKETCH, SKETCH_REVIEW,
    CHANGES_REQUESTED, IN_PRODUCTION, PREPARING_ORDER, READY_TO_SHIP, SHIPPED, REJECTED,
    CANCELLATION_REQUESTED, CANCELLED
}

data class NewDraft(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    val garment: Garment,
    @field:NotBlank @field:Size(max = 24) val size: String,
    @field:NotBlank @field:Size(max = 80) val color: String,
    @field:Size(max = 2000) val description: String = ""
)

data class NewCatalogDraft(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    @field:Size(max = 80) @field:Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") val catalogSlug: String,
    val variantId: UUID,
    @field:Valid val delivery: DeliveryContact
)

data class DraftCreated(val id: UUID)

data class Quote(
    @field:Min(1) @field:Max(MAX_KOPECKS) val itemKopecks: Long,
    @field:Positive val productionDays: Int,
)

data class PublishSketch(val fileId: UUID)
data class Shipment(@field:NotBlank @field:Size(max = 120) val trackingNumber: String)
data class OrderReason(@field:NotBlank @field:Size(max = 400) val reason: String)

data class Order(
    val id: UUID,
    val type: OrderType,
    val email: String,
    val garment: String,
    val size: String,
    val color: String,
    val description: String?,
    val status: OrderStatus,
    val itemKopecks: Long?,
    val productionDays: Int?,
    val trackingNumber: String?,
    val rejectionReason: String?,
    val cancellationReason: String?,
    val createdAt: OffsetDateTime
) {
    fun requireStatus(vararg allowed: OrderStatus) {
        if (status !in allowed) conflict("Action is unavailable at the current order stage")
    }

    fun requireType(expected: OrderType) {
        if (type != expected) conflict("Action is unavailable for this order type")
    }
}

data class CatalogOrderItem(
    val catalogSlug: String,
    val productSlug: String,
    val productName: String,
    val variantId: UUID,
    val sku: String,
    val availability: Availability
)

data class Sketch(val id: UUID, val fileId: UUID, val version: Int, val approvedAt: OffsetDateTime?)

data class OrderDetails(
    val order: Order,
    val catalogItem: CatalogOrderItem?,
    val delivery: OrderDelivery?,
    val sourceFileIds: List<UUID>,
    val sketch: Sketch?,
    val payment: PaymentDetails?,
    val acceptance: OrderAcceptance?
) {
    val offerVersion: String get() = CURRENT_OFFER_VERSION

    val totalKopecks: Long?
        get() = delivery?.priceKopecks?.let { requireNotNull(order.itemKopecks) + it }
}
