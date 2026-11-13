package ru.embroidery.shop.files

import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Semaphore
import javax.imageio.ImageIO

data class UploadedFile(val id: UUID, val mediaType: String, val byteSize: Long)

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
class FileStorage(
    private val s3: S3Client,
    settings: ShopSettings,
    private val repository: OrderFileRepository,
    private val db: JdbcClient
) {
    private val uploads = Semaphore(3)
    private val bucket = settings.storage.bucket
    private val logger = LoggerFactory.getLogger(javaClass)

    fun upload(actor: Actor, orderId: UUID, purpose: FilePurpose, part: MultipartFile): UploadedFile {
        actor.requireOrder(orderId)
        if (part.isEmpty) throw ApiFailure(HttpStatus.BAD_REQUEST, "Upload a non-empty file")
        if (!uploads.tryAcquire()) throw ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "Too many active uploads")
        try {
            val temporary = Files.createTempFile("order-photo-", ".upload")
            try {
                part.transferTo(temporary.toFile())
                val mediaType = validateImage(temporary)
                val file = repository.reserve(actor, orderId, purpose, mediaType, Files.size(temporary))
                s3.putObject(
                    { it.bucket(bucket).key(file.objectKey).contentType(mediaType) },
                    RequestBody.fromFile(temporary)
                )
                repository.complete(actor, file)
                return UploadedFile(file.id, file.mediaType, file.byteSize)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (_: SdkException) {
            throw ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "File storage is temporarily unavailable")
        } finally {
            uploads.release()
        }
    }

    fun download(actor: Actor, orderId: UUID, fileId: UUID): ResponseEntity<StreamingResponseBody> {
        actor.requireOrder(orderId)
        val file = repository.visibleTo(actor, orderId, fileId)
        val body = StreamingResponseBody { output ->
            s3.getObject { it.bucket(bucket).key(file.objectKey) }.use { source -> source.transferTo(output) }
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .contentLength(file.byteSize)
            .contentType(MediaType.parseMediaType(file.mediaType))
            .body(body)
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 30_000)
    fun removeExpiredUploads() {
        db.sql("""
            UPDATE orders SET status = 'EXPIRED', updated_at = now()
            WHERE status = 'DRAFT' AND created_at <= now() - interval '1 day'
        """).update()
        val files = db.sql("""
            SELECT f.id, f.order_id FROM order_files f JOIN orders o ON o.id = f.order_id
            WHERE o.status = 'EXPIRED' OR (NOT f.ready AND f.created_at <= now() - interval '1 hour')
            ORDER BY f.created_at, f.id LIMIT 25
        """).query { rs, _ -> rs.getObject("order_id", UUID::class.java) to rs.getObject("id", UUID::class.java) }.list()
        for ((orderId, fileId) in files) {
            try {
                s3.deleteObject { it.bucket(bucket).key("orders/$orderId/$fileId") }
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

    private fun validateImage(file: Path): String {
        if (Files.size(file) > 10 * 1024 * 1024) {
            throw ApiFailure(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the upload limit")
        }
        try {
            ImageIO.createImageInputStream(file.toFile()).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) invalidImage()
                val reader = readers.next()
                try {
                    val type = when (reader.formatName.lowercase(Locale.ROOT)) {
                        "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        else -> invalidImage()
                    }
                    reader.setInput(input, true, true)
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width !in 1..6000 || height !in 1..6000 || width.toLong() * height > 20_000_000L) {
                        throw ApiFailure(HttpStatus.BAD_REQUEST, "Image dimensions exceed the upload limit")
                    }
                    val sample = reader.defaultReadParam
                    sample.setSourceSubsampling(8, 8, 0, 0)
                    reader.read(0, sample)
                    return type
                } finally {
                    reader.dispose()
                }
            }
        } catch (_: IOException) {
            invalidImage()
        }
    }

    private fun invalidImage(): Nothing =
        throw ApiFailure(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Upload a valid JPEG or PNG image")
}

@RestController
class OrderFilesController(private val storage: FileStorage) {
    @PostMapping("/api/orders/{orderId}/files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(
        @AuthenticationPrincipal actor: Actor, @PathVariable orderId: UUID,
        @RequestParam purpose: FilePurpose, @RequestPart file: MultipartFile
    ) = storage.upload(actor, orderId, purpose, file)

    @GetMapping("/api/orders/{orderId}/files/{fileId}")
    fun download(@AuthenticationPrincipal actor: Actor, @PathVariable orderId: UUID, @PathVariable fileId: UUID) =
        storage.download(actor, orderId, fileId)
}
