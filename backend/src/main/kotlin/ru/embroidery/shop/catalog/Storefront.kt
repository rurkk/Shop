package ru.embroidery.shop.catalog

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.badRequest
import ru.embroidery.shop.conflict
import java.sql.Types
import java.util.UUID

data class GalleryExampleForm(
    val embroideryImageId: UUID,
    val sourceImageId: UUID? = null,
    @field:Size(max = 160) val caption: String? = null
)

data class StorefrontForm(
    @field:Positive val version: Int,
    @param:JsonSetter(nulls = Nulls.FAIL) val published: Boolean = false,
    @field:Size(max = 120) val heroTitle: String? = null,
    val heroImageId: UUID? = null,
    @field:Valid @field:Size(max = 24) @param:JsonSetter(contentNulls = Nulls.FAIL)
    val gallery: List<GalleryExampleForm> = emptyList()
)

data class GalleryExample(val embroidery: ImageReference, val source: ImageReference?, val caption: String?)

data class Storefront(
    val version: Int,
    val published: Boolean,
    val heroTitle: String?,
    val heroImage: ImageReference?,
    val gallery: List<GalleryExample>
)

@Repository
class StorefrontRepository(private val db: JdbcClient) {
    fun get(public: Boolean): Storefront = db.sql("""
        SELECT version, published, hero_title, hero_image_id FROM storefront
        WHERE NOT :public OR published
    """).param("public", public).query { rs, _ ->
        Storefront(
            rs.getInt("version"), rs.getBoolean("published"), rs.getString("hero_title"),
            rs.getObject("hero_image_id", UUID::class.java)?.let { imageReference(it, public) },
            emptyList()
        )
    }.optional().orElseThrow { ApiFailure(HttpStatus.NOT_FOUND, "Storefront is not published") }
        .copy(gallery = gallery(public))

    private fun gallery(public: Boolean): List<GalleryExample> = db.sql("""
        SELECT embroidery_image_id, source_image_id, caption FROM storefront_gallery ORDER BY display_order
    """).query { rs, _ ->
        GalleryExample(
            imageReference(rs.getObject("embroidery_image_id", UUID::class.java), public),
            rs.getObject("source_image_id", UUID::class.java)?.let { imageReference(it, public) },
            rs.getString("caption")
        )
    }.list()

    fun update(input: StorefrontForm) {
        val updated = db.sql("""
            UPDATE storefront SET hero_title = :title, hero_image_id = :hero, published = :published, version = version + 1
            WHERE version = :version
        """).param("title", input.heroTitle, Types.VARCHAR).param("hero", input.heroImageId, Types.OTHER)
            .param("published", input.published).param("version", input.version).update()
        if (updated == 0) conflict("The storefront has changed")
        db.sql("DELETE FROM storefront_gallery").update()
        input.gallery.forEachIndexed { index, item ->
            db.sql("""
                INSERT INTO storefront_gallery (display_order, embroidery_image_id, source_image_id, caption)
                VALUES (:index, :embroidery, :source, :caption)
            """).param("index", index).param("embroidery", item.embroideryImageId)
                .param("source", item.sourceImageId, Types.OTHER)
                .param("caption", item.caption?.trim()?.takeIf { it.isNotEmpty() }, Types.VARCHAR).update()
        }
    }
}

@Service
class StorefrontService(private val storefront: StorefrontRepository, private val images: CatalogImageRepository) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(public: Boolean) = storefront.get(public)

    @Transactional
    fun update(input: StorefrontForm): Storefront {
        val title = input.heroTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (input.published && (title == null || input.heroImageId == null)) {
            badRequest("Add a title and banner image before publishing the storefront")
        }
        val ids = listOfNotNull(input.heroImageId) +
            input.gallery.flatMap { listOfNotNull(it.embroideryImageId, it.sourceImageId) }
        images.requireReady(ids.distinct())
        storefront.update(input.copy(heroTitle = title))
        return storefront.get(public = false)
    }
}

@RestController
class StorefrontController(private val storefront: StorefrontService) {
    @GetMapping("/api/storefront")
    fun published() = storefront.get(public = true)

    @GetMapping("/api/staff/storefront")
    fun forStaff() = storefront.get(public = false)

    @PutMapping("/api/staff/storefront")
    fun update(@Valid @RequestBody input: StorefrontForm) = storefront.update(input)
}
