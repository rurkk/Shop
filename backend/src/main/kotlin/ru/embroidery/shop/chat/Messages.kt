package ru.embroidery.shop.chat

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import ru.embroidery.shop.auth.Actor
import ru.embroidery.shop.auth.Role
import ru.embroidery.shop.badRequest
import ru.embroidery.shop.conflict
import ru.embroidery.shop.files.OrderFileRepository
import ru.embroidery.shop.mail.ShopMail
import ru.embroidery.shop.order.OrderRepository
import ru.embroidery.shop.order.OrderStatus
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID

data class NewMessage(
    val clientMessageId: UUID,
    @field:Size(max = 4000) val body: String = "",
    val attachmentId: UUID? = null
)

data class Message(
    val id: UUID,
    val sequence: Long,
    val clientMessageId: UUID,
    val senderRole: Role,
    val body: String,
    val attachmentId: UUID?,
    val createdAt: OffsetDateTime
)

data class History(val messages: List<Message>, val lastSequence: Long, val hasMore: Boolean)
data class MessageStored(val orderId: UUID)

@Service
class Messages(
    private val db: JdbcClient,
    private val orders: OrderRepository,
    private val files: OrderFileRepository,
    private val mail: ShopMail,
    private val events: ApplicationEventPublisher
) {
    private val messageMapper = RowMapper { rs: ResultSet, _: Int ->
        Message(
            rs.getObject("id", UUID::class.java), rs.getLong("sequence_no"),
            rs.getObject("client_message_id", UUID::class.java), Role.valueOf(rs.getString("sender_role")),
            rs.getString("body"), rs.getObject("attachment_id", UUID::class.java),
            rs.getObject("created_at", OffsetDateTime::class.java)
        )
    }

    fun checkAccess(actor: Actor, orderId: UUID) {
        actor.requireOrder(orderId)
        if (orders.get(orderId).status == OrderStatus.DRAFT) conflict("Submit the draft before opening the chat")
    }

    fun history(actor: Actor, orderId: UUID, before: Long?): History {
        checkAccess(actor, orderId)
        val rows = db.sql("""
            SELECT * FROM order_messages
            WHERE order_id = :order AND sequence_no < :before
            ORDER BY sequence_no DESC LIMIT 51
        """).param("order", orderId).param("before", before ?: Long.MAX_VALUE).query(messageMapper).list()
        val messages = rows.take(50).asReversed()
        return History(messages, messages.lastOrNull()?.sequence ?: 0, rows.size > 50)
    }

    fun after(orderId: UUID, sequence: Long): List<Message> = db.sql("""
        SELECT * FROM order_messages
        WHERE order_id = :order AND sequence_no > :sequence ORDER BY sequence_no LIMIT 100
    """).param("order", orderId).param("sequence", sequence).query(messageMapper).list()

    @Transactional
    fun send(actor: Actor, orderId: UUID, input: NewMessage): Message {
        actor.requireOrder(orderId)
        // Serialize message numbers for an order in commit order, so SSE cursors cannot skip a late commit.
        val order = orders.lock(orderId)
        if (order.status == OrderStatus.DRAFT) conflict("Submit the draft before opening the chat")
        val body = input.body.trim()
        val existing = db.sql("""
            SELECT * FROM order_messages
            WHERE order_id = :order AND sender_role = :role AND sender_email = :email AND client_message_id = :clientId
        """).param("order", orderId).param("role", actor.role.name).param("email", actor.email)
            .param("clientId", input.clientMessageId).query(messageMapper).optional().orElse(null)
        if (existing != null) {
            if (existing.body != body || existing.attachmentId != input.attachmentId) {
                conflict("This message identifier is already used")
            }
            return existing
        }

        if (body.isEmpty() && input.attachmentId == null) badRequest("Enter a message or attach an image")
        if (input.attachmentId != null && !files.canAttach(actor, orderId, input.attachmentId)) {
            badRequest("The attachment is unavailable")
        }
        val message = db.sql("""
            INSERT INTO order_messages (id, order_id, sequence_no, client_message_id, sender_role,
                                        sender_email, body, attachment_id)
            VALUES (:id, :order, (SELECT coalesce(max(sequence_no), 0) + 1 FROM order_messages WHERE order_id = :order),
                    :clientId, :role, :email, :body, :file)
            RETURNING *
        """).param("id", UUID.randomUUID()).param("order", orderId).param("clientId", input.clientMessageId)
            .param("role", actor.role.name).param("email", actor.email).param("body", body)
            .param("file", input.attachmentId, Types.OTHER).query(messageMapper).single()
        if (actor.role == Role.STAFF) mail.notifyCustomer(orderId, order.email, "Новое сообщение по заказу")
        events.publishEvent(MessageStored(orderId))
        return message
    }
}

@RestController
@RequestMapping("/api/orders/{orderId}/messages")
class MessageController(private val messages: Messages, private val streams: MessageStreams) {
    @GetMapping
    fun history(@AuthenticationPrincipal actor: Actor, @PathVariable orderId: UUID, @RequestParam before: Long?): History {
        if (before != null && before < 1) badRequest("Invalid history cursor")
        return messages.history(actor, orderId, before)
    }

    @PostMapping
    fun send(@AuthenticationPrincipal actor: Actor, @PathVariable orderId: UUID, @Valid @RequestBody input: NewMessage) =
        messages.send(actor, orderId, input)

    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @AuthenticationPrincipal actor: Actor,
        @PathVariable orderId: UUID,
        @RequestParam(defaultValue = "0") after: String,
        @RequestHeader("Last-Event-ID", required = false) lastEventId: String?,
        request: HttpServletRequest
    ): ResponseEntity<SseEmitter> {
        val cursor = (lastEventId ?: after).toLongOrNull()?.takeIf { it >= 0 } ?: badRequest("Invalid message cursor")
        messages.checkAccess(actor, orderId)
        val emitter = streams.open(orderId, cursor, request.getSession(false), actor)
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .cacheControl(CacheControl.noStore())
            .header("X-Accel-Buffering", "no")
            .body(emitter)
    }
}
