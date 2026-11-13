package ru.embroidery.shop.catalog

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Types
import java.util.UUID

@Repository
class ProductRepository(private val db: JdbcClient) {
    private val summaryColumns = """
        p.id, p.slug, p.name, p.garment_type, p.published,
        (SELECT pi.image_id FROM product_images pi WHERE pi.product_id = p.id
         ORDER BY pi.display_order LIMIT 1) AS cover_image_id,
        (SELECT min(v.price_kopecks) FROM product_variants v WHERE v.product_id = p.id) AS from_price_kopecks
    """.trimIndent()

    private val variantMapper = RowMapper { rs, _ ->
        ProductVariant(
            rs.getObject("id", UUID::class.java), rs.getString("sku"), rs.getString("size"), rs.getString("color"),
            rs.getLong("price_kopecks"), Availability.valueOf(rs.getString("availability")),
            rs.getObject("production_days", Int::class.javaObjectType)
        )
    }

    fun list(page: CatalogPagination): CatalogPage<ProductSummary> = page.result(db.sql("""
        SELECT $summaryColumns, NULL::integer AS display_order FROM products p
        ORDER BY p.slug LIMIT :limit OFFSET :offset
    """).param("limit", page.limit).param("offset", page.offset).query(summaryMapper(false)).list())

    fun inCatalog(catalogId: UUID, page: CatalogPagination, publicView: Boolean): CatalogPage<ProductSummary> =
        page.result(db.sql("""
            SELECT $summaryColumns, cp.display_order
            FROM catalog_products cp JOIN products p ON p.id = cp.product_id
            WHERE cp.catalog_id = :catalog AND (NOT :publicView OR p.published)
            ORDER BY cp.display_order, cp.product_id LIMIT :limit OFFSET :offset
        """).param("catalog", catalogId).param("publicView", publicView)
            .param("limit", page.limit).param("offset", page.offset).query(summaryMapper(publicView)).list())

    fun get(id: UUID): Product =
        db.sql("SELECT * FROM products WHERE id = :id")
            .param("id", id).query(productMapper).optional().orElseGet { productNotFound() }

    fun published(catalogSlug: String, productSlug: String): Product = db.sql("""
        SELECT p.* FROM products p
        JOIN catalog_products cp ON cp.product_id = p.id
        JOIN catalogs c ON c.id = cp.catalog_id
        WHERE c.slug = :catalogSlug AND c.published AND p.slug = :productSlug AND p.published
    """).param("catalogSlug", catalogSlug).param("productSlug", productSlug)
        .query(productMapper).optional().orElseGet { productNotFound() }

    fun lock(id: UUID): Boolean = db.sql("SELECT published FROM products WHERE id = :id FOR UPDATE")
        .param("id", id).query(Boolean::class.java).optional().orElseGet { productNotFound() }

    fun create(input: ProductForm): UUID = bindProduct(db.sql("""
        INSERT INTO products (slug, name, design, garment_type, description, published)
        VALUES (:slug, :name, :design, :garment, :description, :published)
        RETURNING id
    """), input).query(UUID::class.java).single()

    fun update(id: UUID, input: ProductForm) {
        bindProduct(db.sql("""
            UPDATE products SET slug = :slug, name = :name, design = :design, garment_type = :garment,
                description = :description, published = :published
            WHERE id = :id
        """), input).param("id", id).update()
    }

    fun replaceImages(id: UUID, images: List<UUID>) {
        db.sql("DELETE FROM product_images WHERE product_id = :product").param("product", id).update()
        images.forEachIndexed { order, image ->
            db.sql("""
                INSERT INTO product_images (product_id, image_id, display_order)
                VALUES (:product, :image, :displayOrder)
            """).param("product", id).param("image", image).param("displayOrder", order).update()
        }
    }

    fun images(id: UUID, publicView: Boolean): List<ImageReference> = db.sql("""
        SELECT image_id FROM product_images WHERE product_id = :product ORDER BY display_order
    """).param("product", id).query(UUID::class.java).list().map { imageReference(it, publicView) }

    fun variants(id: UUID): List<ProductVariant> = db.sql("""
        SELECT * FROM product_variants WHERE product_id = :product ORDER BY sku
    """).param("product", id).query(variantMapper).list()

    fun hasVariant(id: UUID): Boolean = db.sql("""
        SELECT EXISTS (SELECT 1 FROM product_variants WHERE product_id = :product)
    """).param("product", id).query(Boolean::class.java).single()

    fun createVariant(productId: UUID, input: VariantForm): ProductVariant = bindVariant(db.sql("""
        INSERT INTO product_variants (product_id, sku, size, color, price_kopecks, availability, production_days)
        VALUES (:product, :sku, :size, :color, :price, :availability, :days)
        RETURNING *
    """), input).param("product", productId).query(variantMapper).single()

    fun updateVariant(productId: UUID, variantId: UUID, input: VariantForm): ProductVariant = bindVariant(db.sql("""
        UPDATE product_variants SET sku = :sku, size = :size, color = :color, price_kopecks = :price,
            availability = :availability, production_days = :days
        WHERE product_id = :product AND id = :variant
        RETURNING *
    """), input).param("product", productId).param("variant", variantId)
        .query(variantMapper).optional().orElseGet { variantNotFound() }

    fun removeVariant(productId: UUID, variantId: UUID) {
        val changed = db.sql("DELETE FROM product_variants WHERE product_id = :product AND id = :variant")
            .param("product", productId).param("variant", variantId).update()
        if (changed == 0) variantNotFound()
    }

    private fun bindProduct(sql: JdbcClient.StatementSpec, input: ProductForm) = sql
        .param("slug", input.slug).param("name", input.name.trim()).param("design", input.design.trim())
        .param("garment", input.garmentType.trim()).param("description", input.description.trim())
        .param("published", input.published)

    private fun bindVariant(sql: JdbcClient.StatementSpec, input: VariantForm) = sql
        .param("sku", input.sku).param("size", input.size.trim()).param("color", input.color.trim())
        .param("price", input.priceKopecks).param("availability", input.availability.name)
        .param("days", input.productionDays, Types.INTEGER)

    private fun summaryMapper(publicView: Boolean) = RowMapper { rs, _ ->
        ProductSummary(
            rs.getObject("id", UUID::class.java), rs.getString("slug"), rs.getString("name"),
            rs.getString("garment_type"), rs.getBoolean("published"),
            rs.getObject("cover_image_id", UUID::class.java)?.let { imageReference(it, publicView) },
            rs.getObject("from_price_kopecks", Long::class.javaObjectType),
            rs.getObject("display_order", Int::class.javaObjectType)
        )
    }

    private val productMapper = RowMapper { rs, _ ->
        Product(
            rs.getObject("id", UUID::class.java), rs.getString("slug"), rs.getString("name"), rs.getString("design"),
            rs.getString("garment_type"), rs.getString("description"), rs.getBoolean("published"),
            emptyList(), emptyList()
        )
    }
}
