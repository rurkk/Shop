package ru.embroidery.shop.order

import jakarta.validation.Valid
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.Page
import ru.embroidery.shop.Pagination
import ru.embroidery.shop.auth.Actor
import ru.embroidery.shop.auth.Role
import ru.embroidery.shop.auth.normalizedEmail
import ru.embroidery.shop.badRequest
import ru.embroidery.shop.catalog.Availability
import ru.embroidery.shop.catalog.ProductRepository
import ru.embroidery.shop.conflict
import ru.embroidery.shop.files.OrderFileRepository
import ru.embroidery.shop.mail.ShopMail
import java.util.UUID

@Service
class Orders(
    private val orders: OrderRepository,
    private val products: ProductRepository,
    private val deliveries: OrderDeliveryRepository,
    private val files: OrderFileRepository,
    private val mail: ShopMail
) {
    fun createDraft(input: NewDraft) = DraftCreated(
        orders.createCustomDraft(normalizedEmail(input.email), input)
    )

    @Transactional
    fun createCatalogDraft(input: NewCatalogDraft): DraftCreated {
        val contact = input.delivery.normalized()
        val selection = products.publishedVariant(input.catalogSlug, input.variantId)
        if (selection.variant.availability == Availability.OUT_OF_STOCK) conflict("Product variant is out of stock")
        val id = orders.createCatalogDraft(normalizedEmail(input.email), input.catalogSlug, selection)
        deliveries.saveContact(id, contact)
        return DraftCreated(id)
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun details(actor: Actor, id: UUID): OrderDetails {
        actor.requireOrder(id)
        return snapshot(id)
    }

    fun list(page: Pagination, type: OrderType?, status: OrderStatus?, search: String?): Page<Order> {
        val term = search?.let(::normalizedEmail)?.takeIf { it.isNotEmpty() }
        if (term != null && term.length > 254) badRequest("Search must be an order ID or an email")
        return orders.listSubmitted(page, type, status, term)
    }

    @Transactional
    fun submit(actor: Actor, id: UUID): OrderDetails {
        requireCustomer(actor, id)
        val order = orders.lock(id)
        if (order.status == OrderStatus.IN_REVIEW) return snapshot(id)
        order.requireStatus(OrderStatus.DRAFT)
        if (order.type == OrderType.CUSTOM_PHOTO && orders.sources(id).isEmpty()) {
            conflict("Upload a source photo before submitting")
        }
        orders.setStatus(id, OrderStatus.IN_REVIEW)
        return snapshot(id)
    }

    @Transactional
    fun quote(id: UUID, quote: Quote): OrderDetails {
        val order = orders.lock(id)
        order.requireType(OrderType.CUSTOM_PHOTO)
        order.requireStatus(OrderStatus.IN_REVIEW)
        orders.setQuote(id, quote)
        // The first payment gets the extra kopeck when the total is odd.
        val deposit = quote.itemKopecks / 2 + quote.itemKopecks % 2
        orders.addPayment(id, Installment.DEPOSIT, deposit)
        mail.notifyCustomer(id, order.email, "Цена и срок заказа готовы")
        return snapshot(id)
    }

    @Transactional
    fun publishSketch(id: UUID, fileId: UUID): OrderDetails {
        val order = orders.lock(id)
        order.requireType(OrderType.CUSTOM_PHOTO)
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
        order.requireType(OrderType.CUSTOM_PHOTO)
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
        order.requireType(OrderType.CUSTOM_PHOTO)
        currentSketch(id, sketchId)
        if (order.status == OrderStatus.CHANGES_REQUESTED) return snapshot(id)
        order.requireStatus(OrderStatus.SKETCH_REVIEW)
        orders.setStatus(id, OrderStatus.CHANGES_REQUESTED)
        return snapshot(id)
    }

    @Transactional
    fun completeProduction(id: UUID): OrderDetails {
        val order = orders.lock(id)
        when (order.type) {
            OrderType.CUSTOM_PHOTO -> {
                order.requireStatus(OrderStatus.IN_PRODUCTION)
                orders.setStatus(id, OrderStatus.AWAITING_DELIVERY_QUOTE)
                mail.notifyCustomer(id, order.email, "Вышивка готова")
            }
            OrderType.CATALOG -> {
                order.requireStatus(OrderStatus.PREPARING_ORDER)
                orders.setStatus(id, OrderStatus.READY_TO_SHIP)
            }
        }
        return snapshot(id)
    }

    @Transactional
    fun updateDelivery(actor: Actor, id: UUID, contact: DeliveryContact): OrderDetails {
        requireCustomer(actor, id)
        val order = orders.lock(id)
        when (order.type) {
            OrderType.CATALOG -> order.requireStatus(OrderStatus.DRAFT, OrderStatus.IN_REVIEW)
            OrderType.CUSTOM_PHOTO -> order.requireStatus(
                OrderStatus.AWAITING_DEPOSIT, OrderStatus.PREPARING_SKETCH, OrderStatus.SKETCH_REVIEW,
                OrderStatus.CHANGES_REQUESTED, OrderStatus.IN_PRODUCTION, OrderStatus.AWAITING_DELIVERY_QUOTE
            )
        }
        deliveries.saveContact(id, contact.normalized())
        return snapshot(id)
    }

    @Transactional
    fun quoteDelivery(id: UUID, quote: DeliveryQuote): OrderDetails {
        val order = orders.lock(id)
        when (order.type) {
            OrderType.CATALOG -> order.requireStatus(OrderStatus.IN_REVIEW)
            OrderType.CUSTOM_PHOTO -> order.requireStatus(OrderStatus.AWAITING_DELIVERY_QUOTE)
        }
        val total = requireNotNull(order.itemKopecks) + quote.priceKopecks
        if (total > MAX_KOPECKS) badRequest("Order total exceeds the supported amount")
        deliveries.setQuote(id, quote)
        when (order.type) {
            OrderType.CATALOG -> {
                orders.addPayment(id, Installment.FULL, total)
                orders.setStatus(id, OrderStatus.AWAITING_PAYMENT)
                mail.notifyCustomer(id, order.email, "Заказ готов к оплате")
            }
            OrderType.CUSTOM_PHOTO -> {
                val deposit = orders.payments(id).single { it.installment == Installment.DEPOSIT }
                orders.addPayment(id, Installment.BALANCE, total - deposit.amountKopecks)
                orders.setStatus(id, OrderStatus.AWAITING_BALANCE)
                mail.notifyCustomer(id, order.email, "Заказ готов к доплате")
            }
        }
        return snapshot(id)
    }

    @Transactional
    fun reject(id: UUID, reason: String): OrderDetails {
        val order = orders.lock(id)
        order.requireStatus(OrderStatus.IN_REVIEW)
        orders.reject(id, reason)
        mail.notifyCustomer(id, order.email, "Заявка отклонена")
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
            Installment.FULL -> {
                order.requireStatus(OrderStatus.AWAITING_PAYMENT)
                OrderStatus.PREPARING_ORDER
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

    private fun snapshot(id: UUID): OrderDetails {
        val order = orders.get(id)
        return OrderDetails(
            order = order,
            catalogItem = if (order.type == OrderType.CATALOG) orders.catalogItem(id) else null,
            delivery = deliveries.get(id),
            sourceFileIds = orders.sources(id),
            sketch = orders.latestSketch(id),
            payments = orders.payments(id)
        )
    }

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

    @PostMapping("/api/orders/catalog-drafts")
    @ResponseStatus(HttpStatus.CREATED)
    fun createCatalog(@Valid @RequestBody input: NewCatalogDraft) = orders.createCatalogDraft(input)

    @GetMapping("/api/orders/{id}")
    fun details(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID) = orders.details(actor, id)

    @PutMapping("/api/orders/{id}/delivery")
    fun delivery(
        @AuthenticationPrincipal actor: Actor,
        @PathVariable id: UUID,
        @Valid @RequestBody contact: DeliveryContact
    ) = orders.updateDelivery(actor, id, contact)

    @PostMapping("/api/orders/{id}/submit")
    fun submit(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID) = orders.submit(actor, id)

    @PostMapping("/api/orders/{id}/sketches/{sketchId}/approve")
    fun approve(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID, @PathVariable sketchId: UUID) =
        orders.approve(actor, id, sketchId)

    @PostMapping("/api/orders/{id}/sketches/{sketchId}/request-changes")
    fun requestChanges(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID, @PathVariable sketchId: UUID) =
        orders.requestChanges(actor, id, sketchId)

    @GetMapping("/api/staff/orders")
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "24") size: Int,
        @RequestParam(required = false) type: OrderType?,
        @RequestParam(required = false) status: OrderStatus?,
        @RequestParam(required = false) search: String?
    ) = orders.list(Pagination(page, size), type, status, search)

    @GetMapping("/api/staff/orders/{id}")
    fun staffDetails(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID) = orders.details(actor, id)

    @PostMapping("/api/staff/orders/{id}/quote")
    fun quote(@PathVariable id: UUID, @Valid @RequestBody input: Quote) = orders.quote(id, input)

    @PostMapping("/api/staff/orders/{id}/delivery-quote")
    fun quoteDelivery(@PathVariable id: UUID, @Valid @RequestBody input: DeliveryQuote) = orders.quoteDelivery(id, input)

    @PostMapping("/api/staff/orders/{id}/reject")
    fun reject(@PathVariable id: UUID, @Valid @RequestBody input: Rejection) = orders.reject(id, input.reason.trim())

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
