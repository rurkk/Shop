package ru.embroidery.shop.order

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import ru.embroidery.shop.conflict
import ru.embroidery.shop.missingOrder
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

enum class Garment { HOODIE, T_SHIRT, SWEATSHIRT }

enum class OrderStatus {
    DRAFT, EXPIRED, IN_REVIEW, AWAITING_DEPOSIT, PREPARING_SKETCH, SKETCH_REVIEW, CHANGES_REQUESTED,
    IN_PRODUCTION, AWAITING_BALANCE, READY_TO_SHIP, SHIPPED
}

enum class Installment { DEPOSIT, BALANCE }

data class Order(
    val id: UUID,
    val email: String,
    val garment: Garment,
    val size: String,
    val color: String,
    val description: String,
    val status: OrderStatus,
    val totalKopecks: Long?,
    val productionDays: Int?,
    val trackingNumber: String?,
    val createdAt: OffsetDateTime
) {
    fun requireStatus(vararg allowed: OrderStatus) {
        if (status !in allowed) conflict("Action is unavailable at the current order stage")
    }
}

data class Sketch(val id: UUID, val fileId: UUID, val version: Int, val approvedAt: OffsetDateTime?)
data class Payment(val id: UUID, val installment: Installment, val amountKopecks: Long, val paidAt: OffsetDateTime?)
data class OrderDetails(val order: Order, val sourceFileIds: List<UUID>, val sketch: Sketch?, val payments: List<Payment>)

@Repository
class OrderRepository(private val db: JdbcClient) {
    private val orderMapper = RowMapper { rs: ResultSet, _: Int ->
        Order(
            rs.getObject("id", UUID::class.java), rs.getString("email"), Garment.valueOf(rs.getString("garment")),
            rs.getString("garment_size"), rs.getString("color"), rs.getString("description"),
            OrderStatus.valueOf(rs.getString("status")), rs.getObject("total_kopecks", Long::class.javaObjectType),
            rs.getObject("production_days", Int::class.javaObjectType), rs.getString("tracking_number"),
            rs.getObject("created_at", OffsetDateTime::class.java)
        )
    }

    fun create(email: String, garment: Garment, size: String, color: String, description: String): UUID {
        val id = UUID.randomUUID()
        db.sql("""
            INSERT INTO orders (id, email, garment, garment_size, color, description, status)
            VALUES (:id, :email, :garment, :size, :color, :description, 'DRAFT')
        """).param("id", id).param("email", email).param("garment", garment.name)
            .param("size", size).param("color", color).param("description", description).update()
        return id
    }

    fun get(id: UUID): Order = find(id, false)
    fun lock(id: UUID): Order = find(id, true)

    private fun find(id: UUID, lock: Boolean): Order {
        val query = """
            SELECT * FROM orders
            WHERE id = :id AND status <> 'EXPIRED'
              AND (status <> 'DRAFT' OR created_at > now() - interval '1 day')
        """.trimIndent()
        return db.sql(if (lock) query + " FOR UPDATE" else query)
            .param("id", id).query(orderMapper).optional().orElseGet { missingOrder() }
    }

    fun listSubmitted(): List<Order> = db.sql("""
        SELECT * FROM orders WHERE status NOT IN ('DRAFT', 'EXPIRED') ORDER BY created_at DESC, id LIMIT 100
    """).query(orderMapper).list()

    fun setStatus(id: UUID, status: OrderStatus) {
        db.sql("UPDATE orders SET status = :status, updated_at = now() WHERE id = :id")
            .param("id", id).param("status", status.name).update()
    }

    fun setQuote(id: UUID, amount: Long, productionDays: Int) {
        db.sql("""
            UPDATE orders SET total_kopecks = :amount, production_days = :days, status = 'AWAITING_DEPOSIT',
                              updated_at = now()
            WHERE id = :id
        """).param("id", id).param("amount", amount).param("days", productionDays).update()
    }

    fun setShipped(id: UUID, trackingNumber: String) {
        db.sql("UPDATE orders SET tracking_number = :tracking, status = 'SHIPPED', updated_at = now() WHERE id = :id")
            .param("id", id).param("tracking", trackingNumber).update()
    }

    fun sources(id: UUID): List<UUID> = db.sql("""
        SELECT id FROM order_files WHERE order_id = :id AND purpose = 'SOURCE' AND ready ORDER BY created_at, id
    """).param("id", id).query(UUID::class.java).list()

    fun latestSketch(id: UUID): Sketch? = db.sql("""
        SELECT id, file_id, version, approved_at FROM sketches WHERE order_id = :id ORDER BY version DESC LIMIT 1
    """).param("id", id).query { rs, _ ->
        Sketch(
            rs.getObject("id", UUID::class.java), rs.getObject("file_id", UUID::class.java), rs.getInt("version"),
            rs.getObject("approved_at", OffsetDateTime::class.java)
        )
    }.optional().orElse(null)

    fun createSketch(orderId: UUID, fileId: UUID, version: Int) {
        db.sql("INSERT INTO sketches (id, order_id, file_id, version) VALUES (:id, :order, :file, :version)")
            .param("id", UUID.randomUUID()).param("order", orderId).param("file", fileId)
            .param("version", version).update()
    }

    fun approveSketch(id: UUID) {
        db.sql("UPDATE sketches SET approved_at = now() WHERE id = :id").param("id", id).update()
    }

    fun addPayment(orderId: UUID, installment: Installment, amount: Long) {
        db.sql("""
            INSERT INTO order_payments (id, order_id, installment, amount_kopecks)
            VALUES (:id, :order, :installment, :amount)
        """).param("id", UUID.randomUUID()).param("order", orderId).param("installment", installment.name)
            .param("amount", amount).update()
    }

    fun payments(orderId: UUID): List<Payment> = db.sql("""
        SELECT id, installment, amount_kopecks, paid_at FROM order_payments
        WHERE order_id = :id ORDER BY CASE installment WHEN 'DEPOSIT' THEN 0 ELSE 1 END
    """).param("id", orderId).query { rs, _ ->
        Payment(
            rs.getObject("id", UUID::class.java), Installment.valueOf(rs.getString("installment")),
            rs.getLong("amount_kopecks"), rs.getObject("paid_at", OffsetDateTime::class.java)
        )
    }.list()

    fun markPaid(id: UUID) {
        db.sql("UPDATE order_payments SET paid_at = now() WHERE id = :id").param("id", id).update()
    }
}
