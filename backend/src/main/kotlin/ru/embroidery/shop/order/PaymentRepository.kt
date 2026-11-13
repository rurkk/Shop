package ru.embroidery.shop.order

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import ru.embroidery.shop.conflict
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID

enum class ProviderStatus { CREATING, PENDING, SUCCEEDED, CANCELED }

data class Payment(val id: UUID, val amountKopecks: Long, val paidAt: OffsetDateTime?)
data class PaymentDetails(
    val id: UUID,
    val amountKopecks: Long,
    val paidAt: OffsetDateTime?,
    val checkoutStatus: ProviderStatus?,
    val checkoutUrl: String?,
    val refund: RefundDetails?
)

data class RefundDetails(val id: UUID, val status: ProviderStatus, val updatedAt: OffsetDateTime)

internal data class PaymentAttempt(
    val id: UUID,
    val shopId: String,
    val providerId: UUID?,
    val status: ProviderStatus,
    val returnUrl: String,
    val checkoutUrl: String?,
    val createdAt: OffsetDateTime
)

internal data class PaymentRefund(
    val id: UUID,
    val providerId: UUID?,
    val status: ProviderStatus,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)

@Repository
class PaymentRepository(private val db: JdbcClient) {
    private val attemptMapper = RowMapper { rs, _ ->
        PaymentAttempt(
            rs.getObject("id", UUID::class.java), rs.getString("shop_id"),
            rs.getObject("provider_id", UUID::class.java), ProviderStatus.valueOf(rs.getString("status")),
            rs.getString("return_url"), rs.getString("checkout_url"), rs.getObject("created_at", OffsetDateTime::class.java)
        )
    }

    private val refundMapper = RowMapper { rs, _ ->
        PaymentRefund(
            rs.getObject("id", UUID::class.java), rs.getObject("provider_id", UUID::class.java),
            ProviderStatus.valueOf(rs.getString("status")), rs.getObject("created_at", OffsetDateTime::class.java),
            rs.getObject("updated_at", OffsetDateTime::class.java)
        )
    }

    fun create(orderId: UUID, amount: Long) {
        db.sql("INSERT INTO order_payments (id, order_id, amount_kopecks) VALUES (:id, :order, :amount)")
            .param("id", UUID.randomUUID()).param("order", orderId).param("amount", amount).update()
    }

    fun forOrder(orderId: UUID): Payment? = db.sql("""
        SELECT id, amount_kopecks, paid_at FROM order_payments WHERE order_id = :order
    """).param("order", orderId).query { rs, _ ->
        Payment(rs.getObject("id", UUID::class.java), rs.getLong("amount_kopecks"),
            rs.getObject("paid_at", OffsetDateTime::class.java))
    }.optional().orElse(null)

    fun details(order: Order): PaymentDetails? {
        val payment = forOrder(order.id) ?: return null
        val attempt = latestAttempt(payment.id)
        val refund = attempt?.let { latestRefund(it.id) }
        return PaymentDetails(
            payment.id, payment.amountKopecks, payment.paidAt, attempt?.status,
            attempt?.checkoutUrl?.takeIf { order.status == OrderStatus.AWAITING_PAYMENT },
            refund?.let { RefundDetails(it.id, it.status, it.updatedAt) }
        )
    }

    fun markPaid(id: UUID) {
        db.sql("UPDATE order_payments SET paid_at = now() WHERE id = :id").param("id", id).update()
    }

    internal fun requireConfirmed(order: Order) {
        val payment = forOrder(order.id)
        if (payment?.paidAt == null) conflict("The order has no confirmed payment")
    }

    internal fun latestAttempt(paymentId: UUID): PaymentAttempt? = db.sql("""
        SELECT * FROM payment_attempts WHERE payment_id = :payment ORDER BY created_at DESC, id DESC LIMIT 1
    """).param("payment", paymentId).query(attemptMapper).optional().orElse(null)

    internal fun attempt(paymentId: UUID, attemptId: UUID): PaymentAttempt? = db.sql("""
        SELECT * FROM payment_attempts WHERE payment_id = :payment AND id = :id
    """).param("payment", paymentId).param("id", attemptId).query(attemptMapper).optional().orElse(null)

    internal fun reserveAttempt(paymentId: UUID, shopId: String, returnUrl: String): PaymentAttempt = db.sql("""
        INSERT INTO payment_attempts (id, payment_id, shop_id, status, return_url)
        VALUES (:id, :payment, :shop, 'CREATING', :url) RETURNING *
    """).param("id", UUID.randomUUID()).param("payment", paymentId).param("shop", shopId)
        .param("url", returnUrl).query(attemptMapper).single()

    internal fun updateAttempt(id: UUID, providerId: UUID, status: ProviderStatus, checkoutUrl: String?) {
        db.sql("""
            UPDATE payment_attempts SET provider_id = :provider, status = :status, checkout_url = :url WHERE id = :id
        """).param("id", id).param("provider", providerId).param("status", status.name)
            .param("url", checkoutUrl, Types.VARCHAR).update()
    }

    internal fun latestRefund(attemptId: UUID): PaymentRefund? = db.sql("""
        SELECT * FROM payment_refunds WHERE attempt_id = :attempt ORDER BY created_at DESC, id DESC LIMIT 1
    """).param("attempt", attemptId).query(refundMapper).optional().orElse(null)

    internal fun reserveRefund(attemptId: UUID): PaymentRefund = db.sql("""
        INSERT INTO payment_refunds (id, attempt_id, status)
        VALUES (:id, :attempt, 'CREATING') RETURNING *
    """).param("id", UUID.randomUUID()).param("attempt", attemptId).query(refundMapper).single()

    internal fun updateRefund(id: UUID, providerId: UUID, status: ProviderStatus) {
        db.sql("""
            UPDATE payment_refunds SET provider_id = :provider, status = :status, updated_at = now() WHERE id = :id
        """).param("id", id).param("provider", providerId).param("status", status.name).update()
    }

    internal fun orderIdForRefund(refundId: UUID): UUID? = db.sql("""
        SELECT p.order_id FROM payment_refunds r JOIN payment_attempts a ON a.id = r.attempt_id
        JOIN order_payments p ON p.id = a.payment_id WHERE r.id = :id
    """).param("id", refundId).query(UUID::class.java).optional().orElse(null)
}
