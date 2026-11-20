package ru.embroidery.shop.order

import com.fasterxml.jackson.annotation.JsonFormat
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

internal const val CURRENT_OFFER_VERSION = "2026-11-14-draft"
internal val ORDER_TIME_ZONE: ZoneId = ZoneId.of("Europe/Moscow")

data class OrderAcceptance(
    val offerVersion: String,
    @get:JsonFormat(shape = JsonFormat.Shape.STRING) val acceptedAt: OffsetDateTime
)

internal data class AcceptedOrderTerms(
    val orderUrl: String,
    val orderType: OrderType,
    val garment: String,
    val size: String,
    val color: String,
    val quantity: Int,
    val catalogItem: CatalogOrderItem?,
    val description: String?,
    val embroidery: EmbroideryConfiguration?,
    val sourceFileIds: List<UUID>,
    val itemKopecks: Long,
    val deliveryKopecks: Long,
    val totalKopecks: Long,
    val productionDays: Int?,
    val deliveryMethod: DeliveryMethod,
    val deliveryDays: Int,
    val city: String,
    val pickupPointCode: String,
    val pickupPointAddress: String,
    @get:JsonFormat(shape = JsonFormat.Shape.STRING) val offerValidThrough: LocalDate,
    @get:JsonFormat(shape = JsonFormat.Shape.STRING) val deliverBy: LocalDate
)

@Repository
class OrderAcceptanceRepository(private val db: JdbcClient, private val json: ObjectMapper) {
    fun get(orderId: UUID): OrderAcceptance? = db.sql("""
        SELECT offer_version, accepted_at FROM order_acceptances WHERE order_id = :order
    """).param("order", orderId).query { rs, _ ->
        OrderAcceptance(rs.getString("offer_version"), rs.getObject("accepted_at", OffsetDateTime::class.java))
    }.optional().orElse(null)

    internal fun record(orderId: UUID, acceptance: OrderAcceptance, terms: AcceptedOrderTerms) {
        db.sql("""
            INSERT INTO order_acceptances (order_id, offer_version, accepted_at, terms)
            VALUES (:order, :version, :acceptedAt, CAST(:terms AS jsonb))
        """).param("order", orderId).param("version", acceptance.offerVersion)
            .param("acceptedAt", acceptance.acceptedAt).param("terms", json.writeValueAsString(terms)).update()
    }
}
