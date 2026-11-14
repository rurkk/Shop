package ru.embroidery.shop.order

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import ru.embroidery.shop.Page
import ru.embroidery.shop.Pagination
import ru.embroidery.shop.catalog.Availability
import ru.embroidery.shop.catalog.CatalogVariant
import ru.embroidery.shop.missingOrder
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class OrderRepository(private val db: JdbcClient) {
    private val orderMapper = RowMapper { rs: ResultSet, _: Int ->
        Order(
            id = rs.getObject("id", UUID::class.java),
            type = OrderType.valueOf(rs.getString("order_type")),
            email = rs.getString("email"),
            garment = rs.getString("garment"),
            size = rs.getString("garment_size"),
            color = rs.getString("color"),
            description = rs.getString("description"),
            status = OrderStatus.valueOf(rs.getString("status")),
            itemKopecks = rs.getObject("item_kopecks", Long::class.javaObjectType),
            productionDays = rs.getObject("production_days", Int::class.javaObjectType),
            trackingNumber = rs.getString("tracking_number"),
            rejectionReason = rs.getString("rejection_reason"),
            cancellationReason = rs.getString("cancellation_reason"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java)
        )
    }

    fun createCustomDraft(email: String, input: NewDraft): UUID {
        val id = UUID.randomUUID()
        db.sql("""
            INSERT INTO orders (id, email, garment, garment_size, color, description, order_type, status)
            VALUES (:id, :email, :garment, :size, :color, :description, 'CUSTOM_PHOTO', 'DRAFT')
        """).param("id", id).param("email", email).param("garment", input.garment.name)
            .param("size", input.size.trim()).param("color", input.color.trim())
            .param("description", input.description.trim()).update()
        return id
    }

    fun createCatalogDraft(email: String, catalogSlug: String, selection: CatalogVariant): UUID {
        val id = UUID.randomUUID()
        val variant = selection.variant
        db.sql("""
            INSERT INTO orders (id, email, garment, garment_size, color, order_type, status, item_kopecks, production_days)
            VALUES (:id, :email, :garment, :size, :color, 'CATALOG', 'DRAFT', :price, :days)
        """).param("id", id).param("email", email).param("garment", selection.garmentType)
            .param("size", variant.size).param("color", variant.color).param("price", variant.priceKopecks)
            .param("days", variant.productionDays, Types.INTEGER).update()
        db.sql("""
            INSERT INTO catalog_order_items (order_id, variant_id, catalog_slug, product_slug, product_name, sku, availability)
            VALUES (:order, :variant, :catalog, :product, :name, :sku, :availability)
        """).param("order", id).param("variant", variant.id).param("catalog", catalogSlug)
            .param("product", selection.productSlug).param("name", selection.productName)
            .param("sku", variant.sku).param("availability", variant.availability.name).update()
        return id
    }

    fun catalogItem(id: UUID): CatalogOrderItem = db.sql("""
        SELECT * FROM catalog_order_items WHERE order_id = :id
    """).param("id", id).query { rs, _ ->
        CatalogOrderItem(
            rs.getString("catalog_slug"), rs.getString("product_slug"), rs.getString("product_name"),
            rs.getObject("variant_id", UUID::class.java), rs.getString("sku"),
            Availability.valueOf(rs.getString("availability"))
        )
    }.single()

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

    fun listSubmitted(page: Pagination, type: OrderType?, status: OrderStatus?, search: String?): Page<Order> {
        val orderId = search?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.takeIf { it.toString() == search }
        return page.result(db.sql("""
            SELECT * FROM orders
            WHERE status NOT IN ('DRAFT', 'EXPIRED')
              AND (:type IS NULL OR order_type = :type)
              AND (:status IS NULL OR status = :status)
              AND (:email IS NULL OR email = :email OR id = :orderId)
            ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset
        """).param("type", type?.name, Types.VARCHAR).param("status", status?.name, Types.VARCHAR)
            .param("email", search, Types.VARCHAR).param("orderId", orderId, Types.OTHER)
            .param("limit", page.limit).param("offset", page.offset).query(orderMapper).list())
    }

    fun setStatus(id: UUID, status: OrderStatus) {
        db.sql("UPDATE orders SET status = :status, updated_at = now() WHERE id = :id")
            .param("id", id).param("status", status.name).update()
    }

    fun setQuote(id: UUID, quote: Quote) {
        db.sql("""
            UPDATE orders SET item_kopecks = :amount, production_days = :days,
                              status = 'AWAITING_DELIVERY_QUOTE', updated_at = now()
            WHERE id = :id
        """).param("id", id).param("amount", quote.itemKopecks).param("days", quote.productionDays).update()
    }

    fun reject(id: UUID, reason: String) {
        db.sql("""
            UPDATE orders SET status = 'REJECTED', rejection_reason = :reason, updated_at = now() WHERE id = :id
        """).param("id", id).param("reason", reason).update()
    }

    fun requestCancellation(id: UUID, reason: String) {
        db.sql("""
            UPDATE orders SET status = 'CANCELLATION_REQUESTED', cancellation_reason = :reason, updated_at = now()
            WHERE id = :id
        """).param("id", id).param("reason", reason).update()
    }

    fun setShipped(id: UUID, trackingNumber: String) {
        db.sql("UPDATE orders SET tracking_number = :tracking, status = 'SHIPPED', updated_at = now() WHERE id = :id")
            .param("id", id).param("tracking", trackingNumber).update()
    }

    fun sources(id: UUID): List<UUID> = db.sql("""
        SELECT id FROM order_files WHERE order_id = :id AND purpose = 'SOURCE' AND ready AND NOT deleted
        ORDER BY created_at, id
    """).param("id", id).query { rs, _ -> requireNotNull(rs.getObject("id", UUID::class.java)) }.list()

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

}
