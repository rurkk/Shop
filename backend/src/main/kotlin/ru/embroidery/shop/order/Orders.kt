package ru.embroidery.shop.order

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.auth.Actor
import ru.embroidery.shop.auth.Role
import ru.embroidery.shop.auth.normalizedEmail
import ru.embroidery.shop.conflict
import ru.embroidery.shop.files.FilePurpose
import ru.embroidery.shop.files.OrderFileRepository
import ru.embroidery.shop.mail.ShopMail
import java.util.UUID

data class NewDraft(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    val garment: Garment,
    @field:NotBlank @field:Size(max = 24) val size: String,
    @field:NotBlank @field:Size(max = 80) val color: String,
    @field:Size(max = 2000) val description: String = ""
)

data class DraftCreated(val id: UUID)
data class Quote(@field:Min(2) @field:Max(9_007_199_254_740_991) val totalKopecks: Long,
                 @field:Positive val productionDays: Int)
data class PublishSketch(val fileId: UUID)
data class Shipment(@field:NotBlank @field:Size(max = 120) val trackingNumber: String)

@Service
class Orders(
    private val orders: OrderRepository,
    private val files: OrderFileRepository,
    private val mail: ShopMail
) {
    fun createDraft(input: NewDraft) = DraftCreated(
        orders.create(normalizedEmail(input.email), input.garment, input.size.trim(), input.color.trim(), input.description.trim())
    )

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun details(actor: Actor, id: UUID): OrderDetails {
        actor.requireOrder(id)
        return snapshot(id)
    }

    fun list() = orders.listSubmitted()

    @Transactional
    fun submit(actor: Actor, id: UUID): OrderDetails {
        requireCustomer(actor, id)
        val order = orders.lock(id)
        if (order.status == OrderStatus.IN_REVIEW) return snapshot(id)
        order.requireStatus(OrderStatus.DRAFT)
        if (orders.sources(id).isEmpty()) conflict("Upload a source photo before submitting")
        orders.setStatus(id, OrderStatus.IN_REVIEW)
        return snapshot(id)
    }

    @Transactional
    fun quote(id: UUID, quote: Quote): OrderDetails {
        val order = orders.lock(id)
        order.requireStatus(OrderStatus.IN_REVIEW)
        orders.setQuote(id, quote.totalKopecks, quote.productionDays)
        // The first payment gets the extra kopeck when the total is odd.
        val deposit = quote.totalKopecks / 2 + quote.totalKopecks % 2
        orders.addPayment(id, Installment.DEPOSIT, deposit)
        mail.notifyCustomer(id, order.email, "Цена и срок заказа готовы")
        return snapshot(id)
    }

    @Transactional
    fun publishSketch(id: UUID, fileId: UUID): OrderDetails {
        val order = orders.lock(id)
        order.requireStatus(OrderStatus.PREPARING_SKETCH, OrderStatus.SKETCH_REVIEW, OrderStatus.CHANGES_REQUESTED)
        val latest = orders.latestSketch(id)
        if (latest?.fileId == fileId && order.status == OrderStatus.SKETCH_REVIEW) return snapshot(id)
        if (!files.canPublishSketch(id, fileId)) conflict("Upload a new sketch for this order")
        orders.createSketch(id, fileId, (latest?.version ?: 0) + 1)
        orders.setStatus(id, OrderStatus.SKETCH_REVIEW)
        mail.notifyCustomer(id, order.email, "Эскиз готов к согласованию")
        return snapshot(id)
    }

    @Transactional
    fun approve(actor: Actor, id: UUID, sketchId: UUID): OrderDetails {
        requireCustomer(actor, id)
        val order = orders.lock(id)
        val latest = currentSketch(id, sketchId)
        if (latest.approvedAt != null) return snapshot(id)
        order.requireStatus(OrderStatus.SKETCH_REVIEW)
        orders.approveSketch(latest.id)
        orders.setStatus(id, OrderStatus.IN_PRODUCTION)
        return snapshot(id)
    }

    @Transactional
    fun requestChanges(actor: Actor, id: UUID, sketchId: UUID): OrderDetails {
        requireCustomer(actor, id)
        val order = orders.lock(id)
        currentSketch(id, sketchId)
        if (order.status == OrderStatus.CHANGES_REQUESTED) return snapshot(id)
        order.requireStatus(OrderStatus.SKETCH_REVIEW)
        orders.setStatus(id, OrderStatus.CHANGES_REQUESTED)
        return snapshot(id)
    }

    @Transactional
    fun completeProduction(id: UUID): OrderDetails {
        val order = orders.lock(id)
        order.requireStatus(OrderStatus.IN_PRODUCTION)
        val deposit = orders.payments(id).single { it.installment == Installment.DEPOSIT }
        orders.addPayment(id, Installment.BALANCE, requireNotNull(order.totalKopecks) - deposit.amountKopecks)
        orders.setStatus(id, OrderStatus.AWAITING_BALANCE)
        mail.notifyCustomer(id, order.email, "Заказ готов к доплате")
        return snapshot(id)
    }

    @Transactional
    fun confirmTestPayment(id: UUID, paymentId: UUID): OrderDetails {
        val order = orders.lock(id)
        val payment = orders.payments(id).find { it.id == paymentId }
            ?: throw ApiFailure(HttpStatus.NOT_FOUND, "Payment not found")
        if (payment.paidAt != null) return snapshot(id)
        val nextStatus = when (payment.installment) {
            Installment.DEPOSIT -> {
                order.requireStatus(OrderStatus.AWAITING_DEPOSIT)
                OrderStatus.PREPARING_SKETCH
            }
            Installment.BALANCE -> {
                order.requireStatus(OrderStatus.AWAITING_BALANCE)
                OrderStatus.READY_TO_SHIP
            }
        }
        orders.markPaid(paymentId)
        orders.setStatus(id, nextStatus)
        return snapshot(id)
    }

    @Transactional
    fun ship(id: UUID, trackingNumber: String): OrderDetails {
        val order = orders.lock(id)
        order.requireStatus(OrderStatus.READY_TO_SHIP)
        orders.setShipped(id, trackingNumber)
        mail.notifyCustomer(id, order.email, "Заказ отправлен")
        return snapshot(id)
    }

    private fun snapshot(id: UUID) = OrderDetails(orders.get(id), orders.sources(id), orders.latestSketch(id), orders.payments(id))

    private fun currentSketch(id: UUID, sketchId: UUID): Sketch {
        val latest = orders.latestSketch(id)
        if (latest == null || latest.id != sketchId) conflict("The sketch version has changed")
        return latest
    }

    private fun requireCustomer(actor: Actor, id: UUID) {
        actor.requireOrder(id)
        if (actor.role != Role.CUSTOMER) throw ApiFailure(HttpStatus.FORBIDDEN, "Customer access required")
    }
}

@RestController
class OrderController(private val orders: Orders) {
    @PostMapping("/api/orders/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody input: NewDraft) = orders.createDraft(input)

    @GetMapping("/api/orders/{id}")
    fun details(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID) = orders.details(actor, id)

    @PostMapping("/api/orders/{id}/submit")
    fun submit(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID) = orders.submit(actor, id)

    @PostMapping("/api/orders/{id}/sketches/{sketchId}/approve")
    fun approve(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID, @PathVariable sketchId: UUID) =
        orders.approve(actor, id, sketchId)

    @PostMapping("/api/orders/{id}/sketches/{sketchId}/request-changes")
    fun requestChanges(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID, @PathVariable sketchId: UUID) =
        orders.requestChanges(actor, id, sketchId)

    @GetMapping("/api/staff/orders")
    fun list() = orders.list()

    @PostMapping("/api/staff/orders/{id}/quote")
    fun quote(@PathVariable id: UUID, @Valid @RequestBody input: Quote) = orders.quote(id, input)

    @PostMapping("/api/staff/orders/{id}/sketches")
    fun sketch(@PathVariable id: UUID, @RequestBody input: PublishSketch) = orders.publishSketch(id, input.fileId)

    @PostMapping("/api/staff/orders/{id}/production-complete")
    fun productionComplete(@PathVariable id: UUID) = orders.completeProduction(id)

    @PostMapping("/api/staff/orders/{id}/shipment")
    fun ship(@PathVariable id: UUID, @Valid @RequestBody input: Shipment) = orders.ship(id, input.trackingNumber.trim())
}

@Profile("local")
@RestController
class TestPaymentController(private val orders: Orders) {
    @PostMapping("/api/staff/orders/{id}/test-payments/{paymentId}/confirm")
    fun confirm(@PathVariable id: UUID, @PathVariable paymentId: UUID) = orders.confirmTestPayment(id, paymentId)
}
