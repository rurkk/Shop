package ru.embroidery.shop.catalog

import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.badRequest
import ru.embroidery.shop.conflict
import ru.embroidery.shop.files.ImageStorage
import ru.embroidery.shop.files.ValidatedImage
import software.amazon.awssdk.core.exception.SdkException
import java.util.UUID

data class CatalogImage(val id: UUID, val mediaType: String, val byteSize: Long) {
    fun objectKey() = "catalog/$id"
}

@Repository
class CatalogImageRepository(private val db: JdbcClient) {
    private val mapper = RowMapper { rs, _ ->
        CatalogImage(rs.getObject("id", UUID::class.java), rs.getString("media_type"), rs.getLong("byte_size"))
    }

    fun reserve(image: ValidatedImage): CatalogImage {
        val record = CatalogImage(UUID.randomUUID(), image.mediaType, image.byteSize)
        db.sql("""
            INSERT INTO catalog_images (id, media_type, byte_size, status) VALUES (:id, :type, :size, 'PENDING')
        """).param("id", record.id).param("type", record.mediaType).param("size", record.byteSize).update()
        return record
    }

    fun complete(id: UUID) {
        val changed = db.sql("""
            UPDATE catalog_images SET status = 'READY' WHERE id = :id AND status = 'PENDING'
        """).param("id", id).update()
        if (changed == 0) conflict("Upload expired")
    }

    fun requireReady(ids: List<UUID>) {
        if (ids.isEmpty()) return
        val ready = db.sql("""
            SELECT id FROM catalog_images
            WHERE id IN (:ids) AND status = 'READY' ORDER BY id FOR SHARE
        """).param("ids", ids).query(UUID::class.java).list()
        if (ready.size != ids.size) badRequest("One or more catalog images are unavailable")
    }

    fun forStaff(id: UUID): CatalogImage = db.sql("SELECT * FROM catalog_images WHERE id = :id AND status = 'READY'")
        .param("id", id).query(mapper).optional().orElseGet { imageNotFound() }

    fun published(id: UUID): CatalogImage = db.sql("""
        SELECT i.* FROM catalog_images i WHERE i.id = :id AND i.status = 'READY' AND (
            EXISTS (SELECT 1 FROM catalogs c WHERE c.cover_image_id = i.id AND c.published)
            OR EXISTS (
                SELECT 1 FROM product_images pi JOIN products p ON p.id = pi.product_id
                JOIN catalog_products cp ON cp.product_id = p.id JOIN catalogs c ON c.id = cp.catalog_id
                WHERE pi.image_id = i.id AND p.published AND c.published
            )
            OR EXISTS (SELECT 1 FROM storefront s WHERE s.published AND s.hero_image_id = i.id)
            OR EXISTS (
                SELECT 1 FROM storefront s CROSS JOIN storefront_gallery g
                WHERE s.published AND (g.embroidery_image_id = i.id OR g.source_image_id = i.id)
            )
        )
    """).param("id", id).query(mapper).optional().orElseGet { imageNotFound() }

    @Transactional
    fun markForDeletion(id: UUID): CatalogImage? {
        val image = db.sql("SELECT * FROM catalog_images WHERE id = :id AND status <> 'PENDING' FOR UPDATE")
            .param("id", id).query(mapper).optional().orElse(null) ?: return null
        val referenced = db.sql("""
            SELECT EXISTS (SELECT 1 FROM catalogs WHERE cover_image_id = :id)
                OR EXISTS (SELECT 1 FROM product_images WHERE image_id = :id)
                OR EXISTS (SELECT 1 FROM storefront WHERE hero_image_id = :id)
                OR EXISTS (SELECT 1 FROM storefront_gallery WHERE embroidery_image_id = :id OR source_image_id = :id)
        """).param("id", id).query(Boolean::class.java).single()
        if (referenced) conflict("Remove the image from the storefront, catalogs and products before deleting it")
        db.sql("UPDATE catalog_images SET status = 'DELETING' WHERE id = :id").param("id", id).update()
        return image
    }

    fun markExpiredUploads() {
        db.sql("""
            UPDATE catalog_images SET status = 'DELETING'
            WHERE status = 'PENDING' AND created_at < now() - interval '1 hour'
        """).update()
    }

    fun pendingDeletions(): List<CatalogImage> = db.sql("""
        SELECT * FROM catalog_images WHERE status = 'DELETING' ORDER BY created_at, id LIMIT 25
    """).query(mapper).list()

    fun finishDeletion(id: UUID) {
        db.sql("DELETE FROM catalog_images WHERE id = :id AND status = 'DELETING'").param("id", id).update()
    }

    private fun imageNotFound(): Nothing = throw ApiFailure(HttpStatus.NOT_FOUND, "Catalog image not found")
}

@Service
class CatalogImages(private val storage: ImageStorage, private val images: CatalogImageRepository) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun upload(part: MultipartFile): ImageReference = storage.withValidatedImage(part) { image ->
        val record = images.reserve(image)
        storage.put(record.objectKey(), image)
        images.complete(record.id)
        imageReference(record.id, public = false)
    }

    fun preview(id: UUID) = images.forStaff(id).let { storage.download(it.objectKey(), it.mediaType, it.byteSize) }

    fun published(id: UUID, request: ServletWebRequest): ResponseEntity<StreamingResponseBody>? {
        val image = images.published(id)
        val cache = CacheControl.noCache().cachePublic()
        request.response?.setHeader(HttpHeaders.CACHE_CONTROL, cache.headerValue)
        if (request.checkNotModified("\"$id\"")) return null
        return storage.download(image.objectKey(), image.mediaType, image.byteSize, cache)
    }

    fun delete(id: UUID) {
        val image = images.markForDeletion(id) ?: return
        try {
            remove(image)
        } catch (_: SdkException) {
            throw ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "File storage is temporarily unavailable")
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 30_000)
    fun cleanUp() {
        images.markExpiredUploads()
        for (image in images.pendingDeletions()) {
            try {
                remove(image)
            } catch (error: SdkException) {
                logger.warn("Could not delete a catalog image ({})", error.javaClass.simpleName)
            }
        }
    }

    private fun remove(image: CatalogImage) {
        storage.delete(image.objectKey())
        images.finishDeletion(image.id)
    }
}

@RestController
class CatalogImagesController(private val images: CatalogImages) {
    @GetMapping("/api/catalog-images/{id}")
    fun published(@PathVariable id: UUID, request: ServletWebRequest) = images.published(id, request)

    @PostMapping("/api/staff/catalog-images", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(@RequestPart file: MultipartFile) = images.upload(file)

    @GetMapping("/api/staff/catalog-images/{id}")
    fun preview(@PathVariable id: UUID) = images.preview(id)

    @DeleteMapping("/api/staff/catalog-images/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = images.delete(id)
}
