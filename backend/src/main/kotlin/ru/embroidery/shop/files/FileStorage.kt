package ru.embroidery.shop.files

import org.slf4j.LoggerFactory
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.ShopSettings
import ru.embroidery.shop.auth.Actor
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

data class UploadedFile(val id: UUID, val mediaType: String, val byteSize: Long)
data class ValidatedImage(val path: Path, val mediaType: String, val byteSize: Long)
data class ImageInfo(val mediaType: String, val byteSize: Long)

@Configuration
class StorageConfiguration {
    @Bean(destroyMethod = "close")
    fun s3(settings: ShopSettings): S3Client {
        val storage = settings.storage
        return S3Client.builder()
            .endpointOverride(storage.endpoint)
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(storage.accessKey, storage.secretKey)
            ))
            .forcePathStyle(true)
            .httpClientBuilder(UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(15)))
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(25)).apiCallAttemptTimeout(Duration.ofSeconds(15)).build())
            .build()
    }

    @Bean
    fun objectStorageHealthIndicator(s3: S3Client, settings: ShopSettings): HealthIndicator = HealthIndicator {
        try {
            s3.headBucket { it.bucket(settings.storage.bucket) }
            Health.up().build()
        } catch (_: SdkException) {
            Health.down().build()
        }
    }
}

@Service
class ImageStorage(private val s3: S3Client, settings: ShopSettings) {
    private val uploads = Semaphore(3)
    private val catalogEncoder = Semaphore(1, true)
    private val bucket = settings.storage.bucket

    fun <T> withValidatedImage(part: MultipartFile, action: (ValidatedImage) -> T): T = withUpload(part) { path ->
        action(ValidatedImage(path, validateImage(path), Files.size(path)))
    }

    internal fun <T> withGeneratedImage(bytes: ByteArray, action: (ValidatedImage) -> T): T {
        if (bytes.size > MAX_IMAGE_BYTES) throw ApiFailure(HttpStatus.BAD_GATEWAY, "Generated image exceeds the file limit")
        return withTempFile { path ->
            Files.write(path, bytes)
            action(ValidatedImage(path, validateImage(path), Files.size(path)))
        }
    }

    internal fun readForGeneration(file: OrderFile): ByteArray = withTempFile { source ->
        try {
            s3.getObject { it.bucket(bucket).key(file.objectKey) }.use { input ->
                Files.newOutputStream(source).use { output ->
                    if (input.copyLimitedTo(output, MAX_IMAGE_BYTES + 1) != file.byteSize) {
                        input.abort()
                        throw ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "Order image could not be read")
                    }
                }
            }
        } catch (_: SdkException) {
            throw ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "File storage is temporarily unavailable")
        }
        encodeCatalog(source) { image -> Files.readAllBytes(image.path) }
    }

    fun <T> withCatalogUpload(part: MultipartFile, action: (ValidatedImage) -> T): T = withUpload(part) { path ->
        if (!catalogEncoder.tryAcquire(0, TimeUnit.SECONDS)) {
            throw ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "Catalog image processing is busy")
        }
        try {
            encodeCatalog(path, action)
        } finally {
            catalogEncoder.release()
        }
    }

    // Legacy objects are read once by the background worker, with the same input limit as direct uploads.
    fun migrateCatalogImage(sourceKey: String, destinationKey: String): ImageInfo {
        catalogEncoder.acquire()
        try {
            return withTempFile { source ->
                try {
                    s3.getObject { it.bucket(bucket).key(sourceKey) }.use { input ->
                        Files.newOutputStream(source).use { output ->
                            if (input.copyLimitedTo(output, MAX_IMAGE_BYTES + 1) > MAX_IMAGE_BYTES) {
                                input.abort()
                                throw ApiFailure(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the upload limit")
                            }
                        }
                    }
                } catch (_: SdkException) {
                    throw ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "File storage is temporarily unavailable")
                }
                encodeCatalog(source) { image ->
                    put(destinationKey, image)
                    ImageInfo(image.mediaType, image.byteSize)
                }
            }
        } finally {
            catalogEncoder.release()
        }
    }

    private fun <T> encodeCatalog(source: Path, action: (ValidatedImage) -> T): T = withTempFile { encoded ->
        action(encodeCatalogImage(source, encoded))
    }

    private fun <T> withUpload(part: MultipartFile, action: (Path) -> T): T {
        if (part.isEmpty) throw ApiFailure(HttpStatus.BAD_REQUEST, "Upload a non-empty file")
        if (part.size > MAX_IMAGE_BYTES) throw ApiFailure(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the upload limit")
        if (!uploads.tryAcquire()) throw ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "Too many active uploads")
        try {
            return withTempFile { temporary ->
                part.transferTo(temporary.toFile())
                action(temporary)
            }
        } finally {
            uploads.release()
        }
    }

    private fun <T> withTempFile(action: (Path) -> T): T {
        val temporary = Files.createTempFile("shop-image-", ".upload")
        try {
            return action(temporary)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun put(objectKey: String, image: ValidatedImage) {
        try {
            s3.putObject(
                { it.bucket(bucket).key(objectKey).contentType(image.mediaType) },
                RequestBody.fromFile(image.path)
            )
        } catch (_: SdkException) {
            throw ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "File storage is temporarily unavailable")
        }
    }

    fun delete(objectKey: String) {
        s3.deleteObject { it.bucket(bucket).key(objectKey) }
    }

    fun download(
        objectKey: String,
        mediaType: String,
        byteSize: Long,
        cacheControl: CacheControl = CacheControl.noStore()
    ): ResponseEntity<StreamingResponseBody> {
        val body = StreamingResponseBody { output ->
            s3.getObject { it.bucket(bucket).key(objectKey) }.use { source -> source.transferTo(output) }
        }
        return ResponseEntity.ok()
            .cacheControl(cacheControl)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .contentLength(byteSize)
            .contentType(MediaType.parseMediaType(mediaType))
            .body(body)
    }
}

private fun InputStream.copyLimitedTo(output: OutputStream, limit: Long): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (copied < limit) {
        val read = readNBytes(buffer, 0, minOf(buffer.size.toLong(), limit - copied).toInt())
        if (read == 0) break
        output.write(buffer, 0, read)
        copied += read
    }
    return copied
}

@Service
class FileStorage(
    private val storage: ImageStorage,
    private val repository: OrderFileRepository,
    private val db: JdbcClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun upload(actor: Actor, orderId: UUID, purpose: FilePurpose, part: MultipartFile, replaces: UUID?): UploadedFile {
        actor.requireOrder(orderId)
        return storage.withValidatedImage(part) { image ->
            val file = repository.reserve(actor, orderId, purpose, image.mediaType, image.byteSize, replaces)
            try {
                storage.put(file.objectKey, image)
                repository.complete(actor, file)
            } catch (error: RuntimeException) {
                if (purpose == FilePurpose.SOURCE) {
                    try {
                        repository.abandonSource(file.id)
                    } catch (cleanupError: RuntimeException) {
                        error.addSuppressed(cleanupError)
                    }
                }
                throw error
            }
            UploadedFile(file.id, file.mediaType, file.byteSize)
        }
    }

    fun download(actor: Actor, orderId: UUID, fileId: UUID): ResponseEntity<StreamingResponseBody> {
        actor.requireOrder(orderId)
        val file = repository.visibleTo(actor, orderId, fileId)
        return storage.download(file.objectKey, file.mediaType, file.byteSize)
    }

    fun removeSource(actor: Actor, orderId: UUID, fileId: UUID) {
        actor.requireOrder(orderId)
        repository.removeSource(actor, orderId, fileId)
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 30_000)
    fun removeExpiredUploads() {
        db.sql("""
            UPDATE orders SET status = 'EXPIRED', updated_at = now()
            WHERE status = 'DRAFT' AND created_at <= now() - interval '1 day'
        """).update()
        val files = db.sql("""
            SELECT f.id, f.order_id FROM order_files f JOIN orders o ON o.id = f.order_id
            WHERE o.status = 'EXPIRED' OR (f.deleted AND f.ready)
               OR (NOT f.ready AND f.created_at <= now() - interval '1 hour')
            ORDER BY f.created_at, f.id LIMIT 25
        """).query { rs, _ -> rs.getObject("order_id", UUID::class.java) to rs.getObject("id", UUID::class.java) }.list()
        for ((orderId, fileId) in files) {
            try {
                storage.delete("orders/$orderId/$fileId")
                db.sql("DELETE FROM order_files WHERE id = :id").param("id", fileId).update()
            } catch (error: SdkException) {
                logger.warn("Could not delete an expired upload ({})", error.javaClass.simpleName)
            }
        }
        db.sql("""
            DELETE FROM orders o WHERE o.status = 'EXPIRED'
              AND NOT EXISTS (SELECT 1 FROM order_files f WHERE f.order_id = o.id)
        """).update()
    }
}

@RestController
class OrderFilesController(private val storage: FileStorage) {
    @PostMapping("/api/orders/{orderId}/files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(
        @AuthenticationPrincipal actor: Actor, @PathVariable orderId: UUID,
        @RequestParam purpose: FilePurpose, @RequestPart file: MultipartFile, @RequestParam replace: UUID?
    ) = storage.upload(actor, orderId, purpose, file, replace)

    @GetMapping("/api/orders/{orderId}/files/{fileId}")
    fun download(@AuthenticationPrincipal actor: Actor, @PathVariable orderId: UUID, @PathVariable fileId: UUID) =
        storage.download(actor, orderId, fileId)

    @DeleteMapping("/api/orders/{orderId}/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeSource(@AuthenticationPrincipal actor: Actor, @PathVariable orderId: UUID, @PathVariable fileId: UUID) =
        storage.removeSource(actor, orderId, fileId)
}
