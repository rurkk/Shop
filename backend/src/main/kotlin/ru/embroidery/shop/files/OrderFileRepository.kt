package ru.embroidery.shop.files

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.auth.Actor
import ru.embroidery.shop.auth.Role
import ru.embroidery.shop.conflict
import ru.embroidery.shop.order.Order
import ru.embroidery.shop.order.OrderRepository
import ru.embroidery.shop.order.OrderStatus
import ru.embroidery.shop.order.OrderType
import java.util.UUID

enum class FilePurpose { SOURCE, SKETCH, CHAT }

data class OrderFile(
    val id: UUID, val orderId: UUID, val purpose: FilePurpose,
    val mediaType: String, val byteSize: Long
) {
    val objectKey get() = "orders/$orderId/$id"
}

@Repository
class OrderFileRepository(private val db: JdbcClient, private val orders: OrderRepository) {
    @Transactional
    fun reserve(actor: Actor, orderId: UUID, purpose: FilePurpose, mediaType: String, size: Long): OrderFile {
        val order = orders.lock(orderId)
        checkUpload(actor, order, purpose)
        val count = db.sql("""
            SELECT count(*) FROM order_files WHERE order_id = :order AND purpose = :purpose
        """).param("order", orderId).param("purpose", purpose.name).query(Long::class.java).single()
        val limit = when (purpose) {
            FilePurpose.SOURCE -> 5
            FilePurpose.SKETCH -> 25
            FilePurpose.CHAT -> 100
        }
        if (count >= limit) conflict("Attachment limit reached")
        val file = OrderFile(UUID.randomUUID(), orderId, purpose, mediaType, size)
        db.sql("""
            INSERT INTO order_files (id, order_id, purpose, uploader_email, media_type, byte_size)
            VALUES (:id, :order, :purpose, :uploader, :media, :size)
        """).param("id", file.id).param("order", orderId).param("purpose", purpose.name)
            .param("uploader", actor.email).param("media", mediaType).param("size", size).update()
        return file
    }

    @Transactional
    fun complete(actor: Actor, file: OrderFile) {
        val order = orders.lock(file.orderId)
        checkUpload(actor, order, file.purpose)
        val updated = db.sql("""
            UPDATE order_files SET ready = true
            WHERE id = :id AND created_at > now() - interval '1 hour'
        """).param("id", file.id).update()
        if (updated != 1) conflict("Upload expired")
    }

    fun visibleTo(actor: Actor, orderId: UUID, fileId: UUID): OrderFile {
        orders.get(orderId)
        return db.sql("""
            SELECT f.id, f.order_id, f.purpose, f.media_type, f.byte_size FROM order_files f
            WHERE f.id = :file AND f.order_id = :order AND f.ready
              AND (:staff
                OR f.purpose = 'SOURCE'
                OR (f.purpose = 'SKETCH' AND EXISTS (SELECT 1 FROM sketches WHERE file_id = f.id))
                OR (f.purpose = 'CHAT' AND (f.uploader_email = :email OR EXISTS (
                    SELECT 1 FROM order_messages WHERE attachment_id = f.id))))
        """).param("file", fileId).param("order", orderId)
            .param("staff", actor.role == Role.STAFF).param("email", actor.email)
            .query { rs, _ ->
                OrderFile(
                    rs.getObject("id", UUID::class.java), rs.getObject("order_id", UUID::class.java),
                    FilePurpose.valueOf(rs.getString("purpose")), rs.getString("media_type"), rs.getLong("byte_size")
                )
            }.optional().orElseThrow { ApiFailure(HttpStatus.NOT_FOUND, "File not found") }
    }

    fun canPublishSketch(orderId: UUID, fileId: UUID): Boolean = db.sql("""
        SELECT EXISTS (SELECT 1 FROM order_files f
            WHERE f.order_id = :order AND f.id = :file AND f.purpose = 'SKETCH' AND f.ready
              AND NOT EXISTS (SELECT 1 FROM sketches WHERE file_id = f.id))
    """).param("order", orderId).param("file", fileId).query(Boolean::class.java).single()

    fun canAttach(actor: Actor, orderId: UUID, fileId: UUID): Boolean = db.sql("""
        SELECT EXISTS (SELECT 1 FROM order_files
            WHERE order_id = :order AND id = :file AND purpose = 'CHAT' AND ready AND uploader_email = :email)
    """).param("order", orderId).param("file", fileId).param("email", actor.email).query(Boolean::class.java).single()

    private fun checkUpload(actor: Actor, order: Order, purpose: FilePurpose) {
        when (purpose) {
            FilePurpose.SOURCE -> {
                if (actor.role != Role.CUSTOMER) forbiddenUpload()
                order.requireType(OrderType.CUSTOM_PHOTO)
                order.requireStatus(OrderStatus.DRAFT)
            }
            FilePurpose.SKETCH -> {
                if (actor.role != Role.STAFF) forbiddenUpload()
                order.requireType(OrderType.CUSTOM_PHOTO)
                order.requireStatus(OrderStatus.PREPARING_SKETCH, OrderStatus.SKETCH_REVIEW, OrderStatus.CHANGES_REQUESTED)
            }
            FilePurpose.CHAT -> {
                if (order.status == OrderStatus.DRAFT) conflict("Submit the draft before opening the chat")
            }
        }
    }

    private fun forbiddenUpload(): Nothing = throw ApiFailure(HttpStatus.FORBIDDEN, "Upload is unavailable for this role")
}
