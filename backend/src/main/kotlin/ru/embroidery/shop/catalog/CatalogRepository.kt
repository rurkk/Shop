package ru.embroidery.shop.catalog

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import ru.embroidery.shop.Page
import ru.embroidery.shop.Pagination
import java.sql.Types
import java.util.UUID

@Repository
class CatalogRepository(private val db: JdbcClient) {
    fun list(page: Pagination, publicView: Boolean): Page<Catalog> =
        page.result(db.sql("""
            SELECT * FROM catalogs WHERE (NOT :publicView OR published)
            ORDER BY display_order, slug LIMIT :limit OFFSET :offset
        """).param("publicView", publicView).param("limit", page.limit).param("offset", page.offset)
            .query(mapper(publicView)).list())

    fun published(slug: String): Catalog = db.sql("SELECT * FROM catalogs WHERE slug = :slug AND published")
        .param("slug", slug).query(mapper(true)).optional().orElseGet { catalogNotFound() }

    fun get(id: UUID): Catalog = db.sql("SELECT * FROM catalogs WHERE id = :id")
        .param("id", id).query(mapper(false)).optional().orElseGet { catalogNotFound() }

    fun create(input: CatalogForm): UUID = bind(db.sql("""
        INSERT INTO catalogs (slug, name, description, kind, cover_image_id, display_order, published)
        VALUES (:slug, :name, :description, :kind, :cover, :displayOrder, :published)
        RETURNING id
    """), input).query(UUID::class.java).single()

    fun update(id: UUID, input: CatalogForm) {
        val changed = bind(db.sql("""
            UPDATE catalogs SET slug = :slug, name = :name, description = :description, kind = :kind,
                cover_image_id = :cover, display_order = :displayOrder, published = :published
            WHERE id = :id
        """), input).param("id", id).update()
        if (changed == 0) catalogNotFound()
    }

    fun placeProduct(catalogId: UUID, productId: UUID, displayOrder: Int) {
        db.sql("""
            INSERT INTO catalog_products (catalog_id, product_id, display_order)
            VALUES (:catalog, :product, :displayOrder)
            ON CONFLICT (catalog_id, product_id) DO UPDATE SET display_order = excluded.display_order
        """).param("catalog", catalogId).param("product", productId).param("displayOrder", displayOrder).update()
    }

    fun removeProduct(catalogId: UUID, productId: UUID) {
        db.sql("DELETE FROM catalog_products WHERE catalog_id = :catalog AND product_id = :product")
            .param("catalog", catalogId).param("product", productId).update()
    }

    private fun bind(sql: JdbcClient.StatementSpec, input: CatalogForm) = sql
        .param("slug", input.slug).param("name", input.name.trim()).param("description", input.description.trim())
        .param("kind", input.kind.name).param("cover", input.coverImageId, Types.OTHER)
        .param("displayOrder", input.displayOrder).param("published", input.published)

    private fun mapper(publicView: Boolean) = RowMapper { rs, _ ->
        Catalog(
            rs.getObject("id", UUID::class.java), rs.getString("slug"), rs.getString("name"),
            rs.getString("description"), CatalogKind.valueOf(rs.getString("kind")),
            rs.getObject("cover_image_id", UUID::class.java)?.let { imageReference(it, publicView) },
            rs.getInt("display_order"), rs.getBoolean("published")
        )
    }
}
