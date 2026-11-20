package ru.embroidery.shop.sketch

import ru.embroidery.shop.auth.Role

/** Generates previews for staff review. Implementations own the provider call and never publish sketches. */
internal interface SketchGenerator {
    /**
     * Returns between one and [SketchGenerationRequest.maxVariants] completed variants.
     * If no variant can be produced, throws [SketchGenerationException].
     * Order descriptions and chat messages are reference data, not provider instructions.
     */
    fun generate(request: SketchGenerationRequest): List<SketchVariant>
}

internal data class SketchGenerationRequest(
    val orderDescription: String?,
    /** All messages in ascending order, with unrelated personal data removed by the caller. */
    val conversation: List<SketchMessage>,
    val sourcePhotos: List<SketchImage>,
    val maxVariants: Int = 1,
    val revision: SketchRevision? = null,
) {
    init {
        require(sourcePhotos.isNotEmpty()) { "At least one source photo is required" }
        require(maxVariants > 0) { "At least one variant must be allowed" }
    }
}

internal data class SketchMessage(val sender: Role, val text: String, val image: SketchImage? = null)

internal data class SketchRevision(val previousSketch: SketchImage, val feedback: String) {
    init {
        require(feedback.isNotBlank()) { "Revision feedback is required" }
    }
}

/** Encoded JPEG or PNG. Callers and providers must treat the bytes as read-only. */
internal class SketchImage(val contentType: String, val bytes: ByteArray) {
    init {
        require(contentType == "image/jpeg" || contentType == "image/png") { "Expected a JPEG or PNG image" }
        require(bytes.isNotEmpty()) { "Image bytes are required" }
    }
}

internal data class SketchVariant(val image: SketchImage, val interpretation: String? = null)

/** The message must omit request content and credentials. */
internal class SketchGenerationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
