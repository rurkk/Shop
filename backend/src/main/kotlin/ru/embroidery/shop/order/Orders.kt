package ru.embroidery.shop.order

import jakarta.validation.Valid
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
import java.time.LocalDate
import java.util.UUID

@Service
class Orders(
    private val orders: OrderRepository,
    private val products: ProductRepository,
    private val deliveries: OrderDeliveryRepository,
    private val files: OrderFileRepository,
    private val payments: PaymentRepository,
    private val acceptances: OrderAcceptanceRepository,
    private val history: OrderHistory,
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

    fun history(id: UUID, page: Pagination): Page<OrderEvent> {
        orders.get(id)
        return history.list(id, page)
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
        mail.notifyCustomer(id, order.email, "Заявка получена", "Команда проверит заявку и напишет вам. Следите за статусом в карточке заказа.")
        return snapshot(id)
    }

    @Transactional
    fun quote(actor: Actor, id: UUID, quote: Quote): OrderDetails {
        val order = orders.lock(id)
        order.requireType(OrderType.CUSTOM_PHOTO)
        order.requireStatus(OrderStatus.IN_REVIEW)
        orders.setQuote(id, quote)
        history.record(id, OrderAction.QUOTED, actor.email,
            mapOf("itemKopecks" to quote.itemKopecks, "productionDays" to quote.productionDays))
        mail.notifyCustomer(id, order.email, "Цена и срок заказа готовы", "Посмотрите оценку вышивки и укажите пункт выдачи СДЭК в карточке заказа.")
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
        mail.notifyCustomer(id, order.email, "Эскиз готов к согласованию", "Откройте эскиз в карточке заказа, чтобы согласовать его или запросить правки.")
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
        val workStatus = when (order.type) {
            OrderType.CUSTOM_PHOTO -> OrderStatus.IN_PRODUCTION
            OrderType.CATALOG -> OrderStatus.PREPARING_ORDER
        }
        order.requireStatus(workStatus)
        payments.requireConfirmed(order)
        orders.setStatus(id, OrderStatus.READY_TO_SHIP)
        mail.notifyCustomer(id, order.email, "Заказ готов к отправке", "Мы сообщим, когда передадим посылку в СДЭК. Следите за статусом в карточке заказа.")
        return snapshot(id)
    }

    @Transactional
    fun updateDelivery(actor: Actor, id: UUID, contact: DeliveryContact): OrderDetails {
        requireCustomer(actor, id)
        val order = orders.lock(id)
        when (order.type) {
            OrderType.CATALOG -> order.requireStatus(OrderStatus.DRAFT, OrderStatus.IN_REVIEW)
            OrderType.CUSTOM_PHOTO -> order.requireStatus(OrderStatus.IN_REVIEW, OrderStatus.AWAITING_DELIVERY_QUOTE)
        }
        deliveries.saveContact(id, contact.normalized())
        return snapshot(id)
    }

    @Transactional
    fun quoteDelivery(actor: Actor, id: UUID, quote: DeliveryQuote): OrderDetails {
        val order = orders.lock(id)
        val quoteStatus = when (order.type) {
            OrderType.CATALOG -> OrderStatus.IN_REVIEW
            OrderType.CUSTOM_PHOTO -> OrderStatus.AWAITING_DELIVERY_QUOTE
        }
        order.requireStatus(quoteStatus)
        if (quote.offerValidThrough.isBefore(LocalDate.now(ORDER_TIME_ZONE))) {
            badRequest("Offer validity date is in the past")
        }
        if (!quote.deliverBy.isAfter(quote.offerValidThrough)) {
            badRequest("Delivery deadline must be after the offer validity date")
        }
        val total = requireNotNull(order.itemKopecks) + quote.priceKopecks
        if (total > MAX_KOPECKS) badRequest("Order total exceeds the supported amount")
        deliveries.setQuote(id, quote)
        payments.create(id, total)
        orders.setStatus(id, OrderStatus.AWAITING_PAYMENT)
        history.record(id, OrderAction.DELIVERY_QUOTED, actor.email, mapOf(
            "deliveryKopecks" to quote.priceKopecks, "deliveryDays" to quote.deliveryDays, "totalKopecks" to total,
            "offerValidThrough" to quote.offerValidThrough.toString(), "deliverBy" to quote.deliverBy.toString()
        ))
        mail.notifyCustomer(id, order.email, "Условия заказа KNITSEW готовы", "Проверьте условия заказа и подтвердите их в карточке.")
        return snapshot(id)
    }

    @Transactional
    fun reject(actor: Actor, id: UUID, reason: String): OrderDetails {
        val order = orders.lock(id)
        order.requireStatus(OrderStatus.IN_REVIEW)
        orders.reject(id, reason)
        history.record(id, OrderAction.REJECTED, actor.email, mapOf("reason" to reason))
        mail.notifyCustomer(id, order.email, "Заявка отклонена", "Посмотрите причину отказа в карточке заказа.")
        return snapshot(id)
    }

    @Transactional
    fun ship(actor: Actor, id: UUID, trackingNumber: String): OrderDetails {
        val order = orders.lock(id)
        order.requireStatus(OrderStatus.READY_TO_SHIP)
        payments.requireConfirmed(order)
        orders.setShipped(id, trackingNumber)
        history.record(id, OrderAction.SHIPPED, actor.email, mapOf("trackingNumber" to trackingNumber))
        mail.notifyCustomer(id, order.email, "Заказ отправлен", "Трек-номер и ссылка на отслеживание СДЭК доступны в карточке заказа.")
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
            payment = payments.details(order),
            acceptance = acceptances.get(id)
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

    @GetMapping("/api/staff/orders/{id}/history")
    fun history(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "24") size: Int
    ) = orders.history(id, Pagination(page, size))

    @PostMapping("/api/staff/orders/{id}/quote")
    fun quote(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID, @Valid @RequestBody input: Quote) =
        orders.quote(actor, id, input)

    @PostMapping("/api/staff/orders/{id}/delivery-quote")
    fun quoteDelivery(
        @AuthenticationPrincipal actor: Actor, @PathVariable id: UUID, @Valid @RequestBody input: DeliveryQuote
    ) = orders.quoteDelivery(actor, id, input)

    @PostMapping("/api/staff/orders/{id}/reject")
    fun reject(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID, @Valid @RequestBody input: OrderReason) =
        orders.reject(actor, id, input.reason.trim())

    @PostMapping("/api/staff/orders/{id}/sketches")
    fun sketch(@PathVariable id: UUID, @RequestBody input: PublishSketch) = orders.publishSketch(id, input.fileId)

    @PostMapping("/api/staff/orders/{id}/production-complete")
    fun productionComplete(@PathVariable id: UUID) = orders.completeProduction(id)

    @PostMapping("/api/staff/orders/{id}/shipment")
    fun ship(@AuthenticationPrincipal actor: Actor, @PathVariable id: UUID, @Valid @RequestBody input: Shipment) =
        orders.ship(actor, id, input.trackingNumber.trim())
}
