package ru.embroidery.shop.order

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import ru.embroidery.shop.badRequest
import ru.embroidery.shop.conflict
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.deser.std.StdScalarDeserializer
import java.sql.Types
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

data class DeliveryContact(
    @field:NotBlank @field:Size(max = 160) val recipientName: String,
    @field:Pattern(regexp = "\\+[1-9][0-9]{9,14}") val phone: String,
    @field:NotBlank @field:Size(max = 120) val city: String,
    @field:Size(max = 40) val pickupPointCode: String? = null,
    @field:Size(max = 300) val pickupPointAddress: String? = null
) {
    fun normalized(): DeliveryContact {
        val code = pickupPointCode?.trim()?.takeIf { it.isNotEmpty() }
        val address = pickupPointAddress?.trim()?.takeIf { it.isNotEmpty() }
        if (code == null && address == null) badRequest("Specify the pickup point code or address")
        return copy(
            recipientName = recipientName.trim(), city = city.trim(), pickupPointCode = code, pickupPointAddress = address
        )
    }
}

data class DeliveryQuote(
    @field:Positive val deliveryVersion: Int,
    @field:NotBlank @field:Size(max = 40) val pickupPointCode: String,
    @field:NotBlank @field:Size(max = 300) val pickupPointAddress: String,
    @field:PositiveOrZero @field:Max(MAX_KOPECKS)
    @param:JsonProperty(required = true) @param:JsonSetter(nulls = Nulls.FAIL) val priceKopecks: Long,
    @field:Positive val deliveryDays: Int,
    @param:JsonDeserialize(using = OrderDateDeserializer::class)
    val offerValidThrough: LocalDate,
    @param:JsonDeserialize(using = OrderDateDeserializer::class)
    val deliverBy: LocalDate
)

internal class OrderDateDeserializer : StdScalarDeserializer<LocalDate>(LocalDate::class.java) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): LocalDate {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) return context.reportInputMismatch(this, "Expected a YYYY-MM-DD date")
        val date = parser.string
        if (!date.matches(isoDate)) return context.reportInputMismatch(this, "Expected a YYYY-MM-DD date")
        return try {
            LocalDate.parse(date)
        } catch (_: DateTimeParseException) {
            context.reportInputMismatch(this, "Expected a valid calendar date")
        }
    }

    private companion object {
        val isoDate = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    }
}

enum class DeliveryMethod { CDEK_PICKUP }

data class OrderDelivery(
    val method: DeliveryMethod,
    val version: Int,
    val recipientName: String,
    val phone: String,
    val city: String,
    val pickupPointCode: String?,
    val pickupPointAddress: String?,
    val priceKopecks: Long?,
    val deliveryDays: Int?,
    @get:JsonFormat(shape = JsonFormat.Shape.STRING) val offerValidThrough: LocalDate?,
    @get:JsonFormat(shape = JsonFormat.Shape.STRING) val deliverBy: LocalDate?
)

@Repository
class OrderDeliveryRepository(private val db: JdbcClient) {
    fun get(orderId: UUID): OrderDelivery? = db.sql("""
        SELECT * FROM order_deliveries WHERE order_id = :order
    """).param("order", orderId).query { rs, _ ->
        OrderDelivery(
            DeliveryMethod.valueOf(rs.getString("method")), rs.getInt("version"),
            rs.getString("recipient_name"), rs.getString("phone"), rs.getString("city"),
            rs.getString("pickup_point_code"), rs.getString("pickup_point_address"),
            rs.getObject("price_kopecks", Long::class.javaObjectType),
            rs.getObject("delivery_days", Int::class.javaObjectType),
            rs.getObject("offer_valid_through", LocalDate::class.java),
            rs.getObject("deliver_by", LocalDate::class.java)
        )
    }.optional().orElse(null)

    fun saveContact(orderId: UUID, contact: DeliveryContact) {
        val updated = db.sql("""
            INSERT INTO order_deliveries (order_id, recipient_name, phone, city, pickup_point_code, pickup_point_address)
            VALUES (:order, :recipient, :phone, :city, :code, :address)
            ON CONFLICT (order_id) DO UPDATE SET
                recipient_name = EXCLUDED.recipient_name, phone = EXCLUDED.phone, city = EXCLUDED.city,
                pickup_point_code = EXCLUDED.pickup_point_code, pickup_point_address = EXCLUDED.pickup_point_address,
                version = CASE WHEN (
                    order_deliveries.recipient_name, order_deliveries.phone, order_deliveries.city,
                    order_deliveries.pickup_point_code, order_deliveries.pickup_point_address
                ) IS DISTINCT FROM (
                    EXCLUDED.recipient_name, EXCLUDED.phone, EXCLUDED.city,
                    EXCLUDED.pickup_point_code, EXCLUDED.pickup_point_address
                ) THEN order_deliveries.version + 1 ELSE order_deliveries.version END
            WHERE order_deliveries.price_kopecks IS NULL
        """).param("order", orderId).param("recipient", contact.recipientName).param("phone", contact.phone)
            .param("city", contact.city).param("code", contact.pickupPointCode, Types.VARCHAR)
            .param("address", contact.pickupPointAddress, Types.VARCHAR).update()
        if (updated == 0) conflict("Delivery details are already confirmed")
    }

    fun setQuote(orderId: UUID, quote: DeliveryQuote) {
        val updated = db.sql("""
            UPDATE order_deliveries
            SET pickup_point_code = :code, pickup_point_address = :address, price_kopecks = :price,
                delivery_days = :days, offer_valid_through = :offerValidThrough, deliver_by = :deliverBy
            WHERE order_id = :order AND version = :version AND price_kopecks IS NULL
        """).param("order", orderId).param("code", quote.pickupPointCode.trim())
            .param("address", quote.pickupPointAddress.trim()).param("price", quote.priceKopecks)
            .param("days", quote.deliveryDays).param("version", quote.deliveryVersion)
            .param("offerValidThrough", quote.offerValidThrough).param("deliverBy", quote.deliverBy).update()
        if (updated == 0) conflict("Delivery details are missing or have changed")
    }
}
