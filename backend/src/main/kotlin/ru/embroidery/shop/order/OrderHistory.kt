package ru.embroidery.shop.order

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import ru.embroidery.shop.Page
import ru.embroidery.shop.Pagination
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID

enum class OrderAction {
    QUOTED, DELIVERY_QUOTED, REJECTED, SHIPPED, CANCELLATION_REQUESTED, CANCELLED,
    PAYMENT_CONFIRMED, REFUND_REQUESTED, REFUND_SUCCEEDED, REFUND_CANCELED
}

data class OrderEvent(
    val id: Long,
    val action: OrderAction,
    val actorEmail: String?,
    val details: JsonNode,
    val createdAt: OffsetDateTime
)

@Repository
class OrderHistory(private val db: JdbcClient, private val json: ObjectMapper) {
    fun record(orderId: UUID, action: OrderAction, actorEmail: String?, details: Map<String, Any> = emptyMap()) {
        db.sql("""
            INSERT INTO order_events (order_id, action, actor_email, details)
            VALUES (:order, :action, :email, CAST(:details AS jsonb))
        """).param("order", orderId).param("action", action.name).param("email", actorEmail, Types.VARCHAR)
            .param("details", json.writeValueAsString(details)).update()
    }

    fun list(orderId: UUID, page: Pagination): Page<OrderEvent> = page.result(db.sql("""
        SELECT id, action, actor_email, details, created_at FROM order_events WHERE order_id = :order
        ORDER BY id DESC LIMIT :limit OFFSET :offset
    """).param("order", orderId).param("limit", page.limit).param("offset", page.offset)
        .query { rs, _ ->
            OrderEvent(
                rs.getLong("id"), OrderAction.valueOf(rs.getString("action")), rs.getString("actor_email"),
                json.readTree(rs.getString("details")), rs.getObject("created_at", OffsetDateTime::class.java)
            )
        }.list())
}
