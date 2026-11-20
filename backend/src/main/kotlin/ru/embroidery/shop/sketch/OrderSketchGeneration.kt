package ru.embroidery.shop.sketch

import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.auth.Actor
import ru.embroidery.shop.auth.Role
import ru.embroidery.shop.badRequest
import ru.embroidery.shop.chat.Messages
import ru.embroidery.shop.conflict
import ru.embroidery.shop.files.FilePurpose
import ru.embroidery.shop.files.ImageStorage
import ru.embroidery.shop.files.OrderFile
import ru.embroidery.shop.files.OrderFileRepository
import ru.embroidery.shop.order.Order
import ru.embroidery.shop.order.OrderDeliveryRepository
import ru.embroidery.shop.order.OrderRepository
import ru.embroidery.shop.order.OrderStatus
import ru.embroidery.shop.order.OrderType
import java.util.UUID

private const val MAX_CONTEXT_MESSAGES = 1000
private const val MAX_CONTEXT_TEXT = 400_000
private const val MAX_CONTEXT_IMAGE_BYTES = 24 * 1024 * 1024

@Service
internal class OrderSketchGeneration(
    private val generators: ObjectProvider<SketchGenerator>,
    private val orders: OrderRepository,
    private val deliveries: OrderDeliveryRepository,
    private val messages: Messages,
    private val jobs: SketchJobs,
    private val files: OrderFileRepository,
    private val images: ImageStorage,
    private val transaction: TransactionTemplate,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun workspace(orderId: UUID): SketchWorkspace {
        orders.get(orderId).requireType(OrderType.CUSTOM_PHOTO)
        return SketchWorkspace(generators.ifAvailable != null, jobs.latest(orderId), jobs.drafts(orderId))
    }

    @Transactional
    fun start(actor: Actor, orderId: UUID, input: StartSketchJob): SketchJobSummary {
        orders.lock(orderId).requireSketchStage()
        val feedback = input.feedback?.trim()
        val previous = input.previousFileId
        if ((previous == null) != (feedback == null) || feedback?.isEmpty() == true) {
            badRequest("Choose a sketch and enter revision feedback together")
        }
        if (previous != null && !jobs.hasVariant(orderId, previous)) badRequest("Sketch variant not found")
        if (orders.sources(orderId).isEmpty()) conflict("An order source photo is required")
        if (generators.ifAvailable == null) throw ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "Sketch generation is unavailable")
        return jobs.enqueue(orderId, actor.email, messages.lastSequence(orderId), input.copy(feedback = feedback))
            ?: conflict("Sketch generation is already active for this order")
    }

    @Scheduled(fixedDelay = 2000)
    fun processNext() {
        jobs.expireInterrupted()
        val generator = generators.ifAvailable ?: return
        val job = jobs.claim() ?: return
        try {
            val actor = Actor(job.requestedBy, Role.STAFF)
            val variants = generator.generate(buildRequest(actor, job))
            if (variants.size !in 1..job.maxVariants) throw SketchGenerationException("Invalid number of variants")
            saveVariants(actor, job, variants)
        } catch (_: SketchContextTooLarge) {
            jobs.fail(job.id, "CONTEXT_TOO_LARGE")
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            logger.warn("Sketch generation job {} failed ({})", job.id, error.javaClass.simpleName)
            jobs.fail(job.id, "GENERATION_FAILED")
        }
    }

    private fun buildRequest(actor: Actor, job: ClaimedSketchJob): SketchGenerationRequest {
        val order = orders.get(job.orderId)
        order.requireSketchStage()
        val conversation = messages.through(job.orderId, job.throughSequence, MAX_CONTEXT_MESSAGES + 1)
        if (conversation.size > MAX_CONTEXT_MESSAGES || conversation.sumOf { it.body.length } > MAX_CONTEXT_TEXT) {
            throw SketchContextTooLarge()
        }
        val delivery = deliveries.get(job.orderId)
        val redactor = SketchContextRedactor(listOfNotNull(
            order.email, delivery?.recipientName, delivery?.phone, delivery?.pickupPointCode, delivery?.pickupPointAddress,
        ))
        var totalBytes = 0L
        val imageCache = mutableMapOf<UUID, SketchImage>()
        fun image(id: UUID): SketchImage {
            val image = imageCache.getOrPut(id) {
                val file = files.visibleTo(actor, job.orderId, id)
                SketchImage(file.mediaType, images.readForGeneration(file))
            }
            totalBytes += image.bytes.size
            if (totalBytes > MAX_CONTEXT_IMAGE_BYTES) throw SketchContextTooLarge()
            return image
        }
        return SketchGenerationRequest(
            orderDescription = order.description?.let(redactor::removeContacts),
            conversation = conversation.map { SketchMessage(it.senderRole, redactor.removeContacts(it.body), it.attachmentId?.let(::image)) },
            sourcePhotos = orders.sources(job.orderId).map(::image),
            maxVariants = job.maxVariants,
            revision = job.previousFileId?.let { SketchRevision(image(it), redactor.removeContacts(requireNotNull(job.feedback))) },
        )
    }

    private fun saveVariants(actor: Actor, job: ClaimedSketchJob, variants: List<SketchVariant>) {
        val staged: List<OrderFile> = variants.map { variant ->
            images.withGeneratedImage(variant.image.bytes) { image ->
                val file = files.reserve(actor, job.orderId, FilePurpose.SKETCH, image.mediaType, image.byteSize, null)
                images.put(file.objectKey, image)
                file
            }
        }
        transaction.executeWithoutResult {
            staged.forEachIndexed { index, file ->
                files.complete(actor, file)
                jobs.addVariant(job, file.id, index + 1, variants[index].interpretation?.take(240))
            }
            check(jobs.succeed(job.id)) { "Generation job expired before completion" }
        }
    }
}

private fun Order.requireSketchStage() {
    requireType(OrderType.CUSTOM_PHOTO)
    requireStatus(OrderStatus.PREPARING_SKETCH, OrderStatus.SKETCH_REVIEW, OrderStatus.CHANGES_REQUESTED)
}

private class SketchContextTooLarge : RuntimeException()

internal class SketchContextRedactor(identifiers: List<String>) {
    private val known = identifiers.filter { it.length >= 3 }.sortedByDescending { it.length }
    private val email = Regex("""[\p{L}\d.!#$%&'*+/=?^_`{|}~-]+@[\p{L}\d.-]+\.[\p{L}]{2,}""")
    private val phone = Regex("""(?<![\p{L}\d])\+?\d(?:[ ().-]*\d){9,14}(?![\p{L}\d])""")

    fun removeContacts(text: String): String {
        val withoutKnown = known.fold(text) { value, identifier -> value.replace(identifier, "[скрыто]", ignoreCase = true) }
        return phone.replace(email.replace(withoutKnown, "[скрыто]"), "[скрыто]")
    }
}

@RestController
@RequestMapping("/api/staff/orders/{orderId}/sketch-generation")
internal class OrderSketchGenerationController(private val generation: OrderSketchGeneration) {
    @GetMapping
    fun workspace(@PathVariable orderId: UUID): ResponseEntity<SketchWorkspace> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(generation.workspace(orderId))

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(
        @AuthenticationPrincipal actor: Actor,
        @PathVariable orderId: UUID,
        @Valid @RequestBody input: StartSketchJob,
    ) = generation.start(actor, orderId, input)
}
