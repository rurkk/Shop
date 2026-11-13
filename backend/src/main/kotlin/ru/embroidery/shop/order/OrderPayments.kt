package ru.embroidery.shop.order

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.auth.Actor
import ru.embroidery.shop.auth.Role
import ru.embroidery.shop.conflict
import ru.embroidery.shop.mail.ShopMail
import java.time.OffsetDateTime
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class YooKassaNotification(val event: String, @param:JsonProperty("object") val item: NotificationObject)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NotificationObject(val id: UUID)

@Service
class OrderPayments(
    private val orders: OrderRepository,
    private val payments: PaymentRepository,
    private val history: OrderHistory,
    private val mail: ShopMail,
    private val gateways: ObjectProvider<YooKassa>,
    private val tx: TransactionTemplate
) {
    fun checkout(actor: Actor, id: UUID): PaymentDetails {
        actor.requireOrder(id)
        if (actor.role != Role.CUSTOMER) throw ApiFailure(HttpStatus.FORBIDDEN, "Customer access required")
        val gateway = gateway()
        tx.executeWithoutResult {
            val order = orders.lock(id)
            order.requireStatus(OrderStatus.AWAITING_PAYMENT)
            val payment = requireNotNull(payments.forOrder(id))
            val previous = payments.latestAttempt(payment.id)
            if (previous == null || previous.status == ProviderStatus.CANCELED) {
                gateway.requireTestShop()
                payments.reserveAttempt(payment.id, gateway.shopId, gateway.returnUrl(id))
            }
        }
        return requireNotNull(tx.execute {
            val order = orders.lock(id)
            val payment = requireNotNull(payments.forOrder(id))
            val attempt = requireNotNull(payments.latestAttempt(payment.id))
            requireShop(attempt, gateway)
            if (order.status == OrderStatus.AWAITING_PAYMENT && attempt.status == ProviderStatus.CREATING) {
                createPayment(gateway, order, payment, attempt)
            }
            requireNotNull(payments.details(orders.get(id)))
        })
    }

    fun synchronize(actor: Actor, id: UUID): PaymentDetails {
        actor.requireOrder(id)
        val gateway = gateway()
        return requireNotNull(tx.execute {
            val order = orders.lock(id)
            val payment = payments.forOrder(id) ?: conflict("The order has no payment")
            val attempt = payments.latestAttempt(payment.id) ?: conflict("The payment has no checkout attempt")
            requireShop(attempt, gateway)
            when (attempt.status) {
                ProviderStatus.CREATING -> {
                    order.requireStatus(OrderStatus.AWAITING_PAYMENT)
                    createPayment(gateway, order, payment, attempt)
                }
                ProviderStatus.PENDING -> applyPayment(
                    order, payment, attempt, gateway.getPayment(requireNotNull(attempt.providerId))
                )
                else -> Unit
            }
            val refund = payments.latestRefund(attempt.id)
            if (refund?.status == ProviderStatus.PENDING) {
                applyRefund(order, payment, attempt, refund, gateway.getRefund(requireNotNull(refund.providerId)))
            }
            requireNotNull(payments.details(orders.get(id)))
        })
    }

    fun cancel(actor: Actor, id: UUID, reason: String) {
        tx.executeWithoutResult {
            val order = orders.lock(id)
            if (order.status in setOf(OrderStatus.CANCELLATION_REQUESTED, OrderStatus.CANCELLED)) return@executeWithoutResult
            order.requireStatus(
                OrderStatus.IN_REVIEW, OrderStatus.AWAITING_DELIVERY_QUOTE, OrderStatus.AWAITING_PAYMENT,
                OrderStatus.PREPARING_SKETCH, OrderStatus.SKETCH_REVIEW, OrderStatus.CHANGES_REQUESTED,
                OrderStatus.IN_PRODUCTION, OrderStatus.PREPARING_ORDER, OrderStatus.READY_TO_SHIP
            )
            val payment = payments.forOrder(id)
            val attempt = payment?.let { payments.latestAttempt(it.id) }
            if (attempt?.status == ProviderStatus.CREATING) {
                conflict("Synchronize the in-flight checkout before cancelling the order")
            }
            orders.requestCancellation(id, reason)
            history.record(id, OrderAction.CANCELLATION_REQUESTED, actor.email, mapOf("reason" to reason))
            if (payment?.paidAt == null && (attempt == null || attempt.status == ProviderStatus.CANCELED)) {
                finishCancellation(order)
            } else {
                mail.notifyCustomer(id, order.email, "Начата отмена заказа")
            }
        }
    }

    private fun createPayment(gateway: YooKassa, order: Order, payment: Payment, attempt: PaymentAttempt) {
        requireRetryWindow(attempt.createdAt)
        applyPayment(order, payment, attempt, gateway.createPayment(order, payment, attempt))
    }

    fun refund(actor: Actor, id: UUID): PaymentDetails {
        val gateway = gateway()
        tx.executeWithoutResult {
            val order = orders.lock(id)
            order.requireStatus(OrderStatus.CANCELLATION_REQUESTED, OrderStatus.CANCELLED, OrderStatus.SHIPPED)
            val payment = payments.forOrder(id)?.takeIf { it.paidAt != null } ?: conflict("The order has no confirmed payment")
            val attempt = payments.latestAttempt(payment.id) ?: conflict("The payment has no YooKassa transaction")
            requireShop(attempt, gateway)
            val refund = payments.latestRefund(attempt.id)
            if (refund == null || refund.status == ProviderStatus.CANCELED) {
                if (order.status == OrderStatus.CANCELLED) conflict("The order is already cancelled")
                val request = payments.reserveRefund(attempt.id)
                history.record(id, OrderAction.REFUND_REQUESTED, actor.email, mapOf(
                    "refundId" to request.id, "amountKopecks" to payment.amountKopecks
                ))
            }
        }
        return requireNotNull(tx.execute {
            val order = orders.lock(id)
            val payment = requireNotNull(payments.forOrder(id))
            val attempt = requireNotNull(payments.latestAttempt(payment.id))
            val refund = requireNotNull(payments.latestRefund(attempt.id))
            if (refund.status == ProviderStatus.CREATING) {
                requireRetryWindow(refund.createdAt)
                val result = gateway.createRefund(requireNotNull(attempt.providerId), payment.amountKopecks, refund.id)
                applyRefund(order, payment, attempt, refund, result)
            }
            requireNotNull(payments.details(orders.get(id)))
        })
    }

    fun notification(input: YooKassaNotification) {
        val gateway = gateway()
        when (input.event) {
            "payment.succeeded", "payment.canceled" -> {
                val result = gateway.getPayment(input.item.id)
                if (result.id != input.item.id) invalidReference()
                tx.executeWithoutResult {
                    val payment = payments.forOrder(result.orderId) ?: return@executeWithoutResult
                    val order = orders.lock(result.orderId)
                    val attempt = payments.attempt(payment.id, result.attemptId) ?: return@executeWithoutResult
                    requireShop(attempt, gateway)
                    applyPayment(order, payment, attempt, result)
                }
            }
            "refund.succeeded" -> {
                val result = gateway.getRefund(input.item.id)
                if (result.id != input.item.id) invalidReference()
                val refundId = result.requestId ?: return
                val orderId = payments.orderIdForRefund(refundId) ?: return
                tx.executeWithoutResult {
                    val order = orders.lock(orderId)
                    val payment = requireNotNull(payments.forOrder(orderId))
                    val attempt = requireNotNull(payments.latestAttempt(payment.id))
                    val refund = payments.latestRefund(attempt.id)
                    if (refund?.id != refundId) return@executeWithoutResult
                    requireShop(attempt, gateway)
                    applyRefund(order, payment, attempt, refund, result)
                }
            }
        }
    }

    private fun applyPayment(order: Order, payment: Payment, attempt: PaymentAttempt, result: GatewayPayment) {
        if (result.orderId != order.id || result.attemptId != attempt.id || result.shopId != attempt.shopId ||
            result.amountKopecks != payment.amountKopecks || (attempt.providerId != null && attempt.providerId != result.id)) {
            invalidReference()
        }
        if (attempt.status == ProviderStatus.SUCCEEDED || attempt.status == ProviderStatus.CANCELED) return
        val checkoutUrl = if (result.status == ProviderStatus.PENDING) {
            result.checkoutUrl ?: attempt.checkoutUrl ?: invalidReference()
        } else {
            null
        }
        payments.updateAttempt(attempt.id, result.id, result.status, checkoutUrl)
        when (result.status) {
            ProviderStatus.SUCCEEDED -> {
                order.requireStatus(OrderStatus.AWAITING_PAYMENT, OrderStatus.CANCELLATION_REQUESTED)
                payments.markPaid(payment.id)
                history.record(order.id, OrderAction.PAYMENT_CONFIRMED, null, mapOf("amountKopecks" to payment.amountKopecks))
                if (order.status == OrderStatus.AWAITING_PAYMENT) {
                    val next = when (order.type) {
                        OrderType.CUSTOM_PHOTO -> OrderStatus.PREPARING_SKETCH
                        OrderType.CATALOG -> OrderStatus.PREPARING_ORDER
                    }
                    orders.setStatus(order.id, next)
                    mail.notifyCustomer(order.id, order.email, "Оплата подтверждена")
                } else {
                    mail.notifyCustomer(order.id, order.email, "Платёж поступил при отмене заказа")
                }
            }
            ProviderStatus.CANCELED -> {
                if (order.status == OrderStatus.CANCELLATION_REQUESTED) finishCancellation(order)
            }
            else -> Unit
        }
    }

    private fun applyRefund(
        order: Order,
        payment: Payment,
        attempt: PaymentAttempt,
        refund: PaymentRefund,
        result: GatewayRefund
    ) {
        if (result.requestId != refund.id || result.amountKopecks != payment.amountKopecks ||
            result.paymentId != attempt.providerId ||
            (refund.providerId != null && refund.providerId != result.id)) invalidReference()
        if (refund.status == ProviderStatus.SUCCEEDED || refund.status == ProviderStatus.CANCELED) return
        payments.updateRefund(refund.id, result.id, result.status)
        val details = mapOf("refundId" to refund.id, "amountKopecks" to payment.amountKopecks)
        when (result.status) {
            ProviderStatus.SUCCEEDED -> {
                history.record(order.id, OrderAction.REFUND_SUCCEEDED, null, details)
                mail.notifyCustomer(order.id, order.email, "Возврат подтверждён")
                if (order.status == OrderStatus.CANCELLATION_REQUESTED) finishCancellation(order)
            }
            ProviderStatus.CANCELED -> history.record(order.id, OrderAction.REFUND_CANCELED, null, details)
            else -> Unit
        }
    }

    private fun finishCancellation(order: Order) {
        orders.setStatus(order.id, OrderStatus.CANCELLED)
        history.record(order.id, OrderAction.CANCELLED, null)
        mail.notifyCustomer(order.id, order.email, "Заказ отменён")
    }

    private fun requireShop(attempt: PaymentAttempt, gateway: YooKassa) {
        if (attempt.shopId != gateway.shopId) conflict("This checkout belongs to a different YooKassa shop")
    }

    private fun requireRetryWindow(createdAt: OffsetDateTime) {
        if (createdAt.plusHours(23).isBefore(OffsetDateTime.now())) {
            conflict("Check the operation in YooKassa before retrying")
        }
    }

    private fun invalidReference(): Nothing =
        throw ApiFailure(HttpStatus.BAD_GATEWAY, "YooKassa operation does not match the order")

    private fun gateway(): YooKassa = gateways.ifAvailable
        ?: throw ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "YooKassa test checkout is not enabled")
}

@RestController
class OrderPaymentController(private val payments: OrderPayments, private val orders: Orders) {
    @PostMapping("/api/orders/{id}/payment/checkout")
    fun checkout(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID) = payments.checkout(actor, id)

    @PostMapping("/api/orders/{id}/payment/sync")
    fun sync(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID) = payments.synchronize(actor, id)

    @PostMapping("/api/staff/orders/{id}/cancel")
    fun cancel(
        @AuthenticationPrincipal actor: Actor,
        @PathVariable id: UUID,
        @Valid @RequestBody input: OrderReason
    ): OrderDetails {
        payments.cancel(actor, id, input.reason.trim())
        return orders.details(actor, id)
    }

    @PostMapping("/api/staff/orders/{id}/refund")
    fun refund(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID) = payments.refund(actor, id)

    @PostMapping("/api/payments/yookassa/webhook")
    fun webhook(@RequestBody input: YooKassaNotification) {
        payments.notification(input)
    }
}
