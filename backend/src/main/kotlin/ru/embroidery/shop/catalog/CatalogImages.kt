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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.badRequest
import ru.embroidery.shop.conflict
import ru.embroidery.shop.files.ImageInfo
import ru.embroidery.shop.files.ImageStorage
import ru.embroidery.shop.files.ValidatedImage
import software.amazon.awssdk.core.exception.SdkException
import java.util.UUID

enum class CatalogImageEncoding { LEGACY, SANITIZED_COPY, SANITIZED }

data class CatalogImage(val id: UUID, val mediaType: String, val byteSize: Long, val encoding: CatalogImageEncoding) {
    fun objectKey() = "catalog-clean/v1/$id"
    fun legacyKey() = "catalog/$id"
}

@Repository
class CatalogImageRepository(private val db: JdbcClient) {
    private val mapper = RowMapper { rs, _ ->
        CatalogImage(
            rs.getObject("id", UUID::class.java), rs.getString("media_type"), rs.getLong("byte_size"),
            CatalogImageEncoding.valueOf(rs.getString("encoding"))
        )
    }

    fun reserve(image: ValidatedImage): CatalogImage {
        val record = CatalogImage(UUID.randomUUID(), image.mediaType, image.byteSize, CatalogImageEncoding.SANITIZED)
        db.sql("""
            INSERT INTO catalog_images (id, media_type, byte_size, status, encoding)
            VALUES (:id, :type, :size, 'PENDING', :encoding)
        """).param("id", record.id).param("type", record.mediaType).param("size", record.byteSize)
            .param("encoding", record.encoding.name).update()
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
            WHERE id IN (:ids) AND status = 'READY' AND encoding <> 'LEGACY' ORDER BY id FOR SHARE
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

    @Transactional
    fun migrateNext(writeSafeCopy: (CatalogImage) -> ImageInfo, removeLegacy: (CatalogImage) -> Unit): Boolean {
        val image = db.sql("""
            SELECT * FROM catalog_images
            WHERE status = 'READY' AND encoding <> 'SANITIZED' AND sanitize_after <= now()
            ORDER BY (encoding = 'SANITIZED_COPY') DESC, sanitize_after, created_at, id
            LIMIT 1 FOR UPDATE SKIP LOCKED
        """).query(mapper).optional().orElse(null) ?: return false
        when (image.encoding) {
            CatalogImageEncoding.LEGACY -> {
                val sanitized = writeSafeCopy(image)
                db.sql("""
                    UPDATE catalog_images
                    SET encoding = 'SANITIZED_COPY', media_type = :type, byte_size = :size, sanitize_after = now()
                    WHERE id = :id
                """).param("type", sanitized.mediaType).param("size", sanitized.byteSize).param("id", image.id).update()
            }
            CatalogImageEncoding.SANITIZED_COPY -> {
                removeLegacy(image)
                db.sql("UPDATE catalog_images SET encoding = 'SANITIZED' WHERE id = :id").param("id", image.id).update()
            }
            CatalogImageEncoding.SANITIZED -> error("Image is already sanitized")
        }
        return true
    }

    fun postponeMigration(id: UUID) {
        db.sql("""
            UPDATE catalog_images SET sanitize_after = now() + interval '5 minutes'
            WHERE id = :id AND status = 'READY' AND encoding <> 'SANITIZED'
        """).param("id", id).update()
    }

    private fun imageNotFound(): Nothing = throw ApiFailure(HttpStatus.NOT_FOUND, "Catalog image not found")
}

@Service
class CatalogImages(private val storage: ImageStorage, private val images: CatalogImageRepository) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun upload(part: MultipartFile): ImageReference = storage.withCatalogUpload(part) { image ->
        val record = images.reserve(image)
        storage.put(record.objectKey(), image)
        images.complete(record.id)
        imageReference(record.id, public = false)
    }

    fun preview(id: UUID) = images.forStaff(id).let {
        requireSanitized(it)
        storage.download(it.objectKey(), it.mediaType, it.byteSize)
    }

    fun published(id: UUID, request: ServletWebRequest): ResponseEntity<StreamingResponseBody>? {
        val image = images.published(id)
        requireSanitized(image)
        val cache = CacheControl.noCache().cachePublic()
        request.response?.setHeader(HttpHeaders.CACHE_CONTROL, cache.headerValue)
        if (request.checkNotModified("\"clean-v1-$id\"")) return null
        if (request.request.method == "HEAD") {
            return ResponseEntity.ok()
                .cacheControl(cache)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentLength(image.byteSize)
                .contentType(MediaType.parseMediaType(image.mediaType))
                .build()
        }
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

    @Scheduled(fixedDelay = 1_000)
    fun backfillSanitizedImages() {
        repeat(4) {
            var processing: CatalogImage? = null
            try {
                val processed = images.migrateNext(
                    writeSafeCopy = { image ->
                        processing = image
                        storage.migrateCatalogImage(image.legacyKey(), image.objectKey())
                    },
                    removeLegacy = { image ->
                        processing = image
                        storage.delete(image.legacyKey())
                    }
                )
                if (!processed) return
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (error: Exception) {
                val image = processing ?: throw error
                val action = if (image.encoding == CatalogImageEncoding.LEGACY) "encode" else "remove original for"
                logger.warn("Could not {} catalog image {} ({})", action, image.id, error.javaClass.simpleName)
                images.postponeMigration(image.id)
            }
        }
    }

    private fun requireSanitized(image: CatalogImage) {
        if (image.encoding == CatalogImageEncoding.LEGACY) {
            throw ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "Catalog image is awaiting metadata removal")
        }
    }

    private fun remove(image: CatalogImage) {
        if (image.encoding != CatalogImageEncoding.SANITIZED) storage.delete(image.legacyKey())
        storage.delete(image.objectKey())
        images.finishDeletion(image.id)
    }
}

@RestController
class CatalogImagesController(private val images: CatalogImages) {
    @RequestMapping("/api/catalog-images/{id}", method = [RequestMethod.GET, RequestMethod.HEAD])
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
