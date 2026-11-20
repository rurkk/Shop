package ru.embroidery.shop.sketch

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import ru.embroidery.shop.auth.Role
import ru.embroidery.shop.files.MAX_IMAGE_BYTES
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.Base64

@ConfigurationProperties("shop.sketch.genapi")
internal class GenApiSketchSettings(
    val apiKey: String,
    val testStub: Boolean = false,
)

@Component
@ConditionalOnProperty("shop.sketch.genapi.enabled", havingValue = "true")
internal class GenApiSketchGenerator internal constructor(
    private val settings: GenApiSketchSettings,
    private val json: ObjectMapper,
    private val client: RestClient,
    private val currentConfiguration: () -> SketchConfiguration,
    private val pause: () -> Unit = { Thread.sleep(2000) },
) : SketchGenerator {
    @Autowired
    constructor(settings: GenApiSketchSettings, json: ObjectMapper, environment: Environment, models: SketchModelSettings) :
        this(settings, json, genApiClient(settings, environment), models::configuration)

    init {
        require(settings.apiKey.isNotBlank()) { "Set SHOP_SKETCH_GENAPI_API_KEY to enable sketch generation" }
    }

    override fun generate(request: SketchGenerationRequest): List<SketchVariant> {
        val configuration = currentConfiguration()
        val references = (listOfNotNull(request.revision?.previousSketch) + request.sourcePhotos).map(::dataUri)
        val prompts = plan(request, configuration)
        val ids = prompts.map { prompt ->
            val response = post(apiHost, "/api/v1/networks/flux-2", mapOf(
                "model" to configuration.selection.imageModel,
                "prompt" to prompt,
                "image_urls" to references,
                "width" to 1024,
                "height" to 1024,
                "num_images" to 1,
                "output_format" to "png",
                "enable_safety_checker" to true,
            ))
            val id = response.path("request_id")
            if (!id.isIntegralNumber || !id.canConvertToLong() || id.longValue() <= 0) {
                throw SketchGenerationException("GenAPI omitted the generation ID")
            }
            id.longValue()
        }
        val deadline = System.nanoTime() + Duration.ofMinutes(6).toNanos()
        return ids.map { awaitImage(it, deadline) }
    }

    private fun plan(request: SketchGenerationRequest, configuration: SketchConfiguration): List<String> {
        val response = post(chatHost, "/v1/chat/completions", mapOf(
            "model" to configuration.selection.responseModel,
            "response_format" to mapOf("type" to "json_object"),
            "max_tokens" to 1800,
            "messages" to listOf(
                mapOf("role" to "system", "content" to instructions(configuration.selection.systemPrompt, request.maxVariants, request.revision != null)),
                mapOf("role" to "user", "content" to content(request, configuration.references)),
            ),
        ))
        val choice = response.path("choices").path(0)
        val plan = choice.path("message").path("content").stringValue(null)
        if (choice.path("finish_reason").stringValue(null) != "stop" || plan.isNullOrBlank()) {
            throw SketchGenerationException("GenAPI did not finish preparing an image prompt")
        }
        val prompts = parseJson(plan.toByteArray()).path("prompts")
        if (!prompts.isArray || prompts.size() !in 1..request.maxVariants) {
            throw SketchGenerationException("GenAPI returned an invalid number of image prompts")
        }
        return prompts.asSequence().map { item ->
            item.stringValue(null)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 3000 }
                ?: throw SketchGenerationException("GenAPI returned an invalid image prompt")
        }.toList()
    }

    private fun awaitImage(id: Long, deadline: Long): SketchVariant {
        while (System.nanoTime() - deadline < 0) {
            val response = parseJson(read(client.get().uri("$apiHost/api/v1/request/get/$id")
                .headers { it.setBearerAuth(settings.apiKey) }, MAX_JSON_BYTES))
            when (response.path("status").stringValue(null)) {
                "processing" -> pause()
                "success" -> {
                    val result = response.path("result")
                    val url = result.path(0).stringValue(null)
                    if (!result.isArray || result.size() != 1 || url.isNullOrBlank()) {
                        throw SketchGenerationException("GenAPI did not return exactly one image")
                    }
                    val image = read(client.get().uri(imageUri(url)), MAX_IMAGE_BYTES.toInt())
                    if (!image.take(PNG_SIGNATURE.size).toByteArray().contentEquals(PNG_SIGNATURE)) {
                        throw SketchGenerationException("GenAPI did not return a PNG image")
                    }
                    return SketchVariant(SketchImage("image/png", image))
                }
                else -> throw SketchGenerationException("GenAPI image generation failed")
            }
        }
        throw SketchGenerationException("GenAPI image generation timed out")
    }

    private fun imageUri(url: String): URI {
        val uri = try {
            URI(url)
        } catch (_: java.net.URISyntaxException) {
            throw SketchGenerationException("GenAPI returned an invalid image URL")
        }
        val allowedOrigin = if (settings.testStub) {
            uri.scheme == "http" && uri.host == "sketch-stub" && uri.port == 8080
        } else {
            uri.scheme == "https" && uri.host == "gen-api.storage.yandexcloud.net" && uri.port in listOf(-1, 443)
        }
        if (!allowedOrigin || uri.userInfo != null || uri.fragment != null) {
            throw SketchGenerationException("GenAPI returned an unexpected image origin")
        }
        return uri
    }

    private val apiHost = if (settings.testStub) "http://sketch-stub:8080" else "https://api.gen-api.ru"
    private val chatHost = if (settings.testStub) apiHost else "https://proxy.gen-api.ru"

    private fun post(host: String, path: String, payload: Map<String, Any>): JsonNode =
        parseJson(read(client.post().uri(host + path)
            .headers { it.setBearerAuth(settings.apiKey) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(json.writeValueAsBytes(payload)), MAX_JSON_BYTES))

    private fun read(request: RestClient.RequestHeadersSpec<*>, limit: Int): ByteArray = try {
        request.exchange { _, response ->
            if (!response.statusCode.is2xxSuccessful) {
                throw SketchGenerationException("GenAPI returned HTTP ${response.statusCode.value()}")
            }
            response.body.readNBytes(limit + 1).also {
                if (it.isEmpty() || it.size > limit) throw SketchGenerationException("Invalid GenAPI response size")
            }
        } ?: throw SketchGenerationException("GenAPI did not return a response")
    } catch (_: RestClientException) {
        throw SketchGenerationException("GenAPI request failed")
    } catch (_: IOException) {
        throw SketchGenerationException("GenAPI response could not be read")
    }

    private fun parseJson(bytes: ByteArray): JsonNode = try {
        json.readTree(bytes)
    } catch (_: tools.jackson.core.JacksonException) {
        throw SketchGenerationException("GenAPI returned invalid JSON")
    }

    private fun content(request: SketchGenerationRequest, references: List<SketchPromptReference>): List<Map<String, Any>> = buildList {
        fun text(value: String) = add(mapOf("type" to "text", "text" to value))
        fun image(value: SketchImage) = add(mapOf("type" to "image_url", "image_url" to mapOf("url" to dataUri(value))))
        if (references.isNotEmpty()) {
            text("Примеры команды для эскиза:")
            for (reference in references) {
                text("Файл-пример: ${reference.name}")
                if (reference.mediaType == "text/plain") text(reference.content.toString(Charsets.UTF_8))
                else image(SketchImage(reference.mediaType, reference.content))
            }
            text("Данные текущего заказа:")
        }
        request.revision?.let {
            text("Выбранный эскиз для правки:")
            image(it.previousSketch)
        }
        text("Исходные фото заказа:")
        request.sourcePhotos.forEach { image(it) }
        request.orderDescription?.takeIf { it.isNotBlank() }?.let { text("Описание заказа: $it") }
        for (message in request.conversation) {
            val author = if (message.sender == Role.CUSTOMER) "Покупатель" else "Сотрудник"
            text("$author: ${message.text}")
            message.image?.let { image(it) }
        }
        request.revision?.let { text("Замечания сотрудника к выбранному эскизу: ${it.feedback}") }
    }

    private fun instructions(systemPrompt: String, maxVariants: Int, revision: Boolean): String = systemPrompt + "\n\n" + """
        ${if (revision) "Первое изображение для генератора — выбранный эскиз. Опиши правку к нему и укажи, что следует сохранить." else ""}
        Верни только JSON-объект с полем "prompts", содержащим от 1 до $maxVariants строк длиной до 3000 символов.
    """.trimIndent()

    private companion object {
        const val MAX_JSON_BYTES = 1024 * 1024
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        fun dataUri(image: SketchImage) =
            "data:${image.contentType};base64,${Base64.getEncoder().encodeToString(image.bytes)}"

        fun genApiClient(settings: GenApiSketchSettings, environment: Environment): RestClient {
            if (settings.testStub) {
                require(environment.acceptsProfiles(Profiles.of("local & !production")) && settings.apiKey == "local-sketch-test") {
                    "The sketch fixture requires the local profile and its test key"
                }
            } else {
                require(settings.apiKey != "local-sketch-test") { "The sketch fixture key requires the fixture" }
            }
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()
            val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(Duration.ofSeconds(90)) }
            return RestClient.builder().requestFactory(factory).build()
        }
    }
}
