package ru.embroidery.shop.sketch

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.client.RestClient
import ru.embroidery.shop.auth.Role
import ru.embroidery.shop.order.EmbroideryConfiguration
import ru.embroidery.shop.order.EmbroideryFrame
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.UUID

class GenApiSketchGeneratorTest {
    private val json = JsonMapper.builder().build()
    private val source = SketchImage("image/png", byteArrayOf(1))
    private val attachment = SketchImage("image/jpeg", byteArrayOf(2))
    private val previous = SketchImage("image/png", byteArrayOf(3))
    private val mockup = SketchMockup(
        garment = "HOODIE", size = "M", color = "Чёрный",
        placement = SketchPlacement(GarmentSide.FRONT, 500, 500, 160, 160),
        template = SketchImage("image/jpeg", byteArrayOf(4)), guide = SketchImage("image/png", byteArrayOf(5)),
    )
    private val request = SketchGenerationRequest(
        null, emptyList(), listOf(source), mockup, EmbroideryConfiguration(EmbroideryFrame.STANDARD, false, false),
    )
    private val signature = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val imageUrl = "https://gen-api.storage.yandexcloud.net/image.png"

    @Test
    fun `revision sends ordered chat and renders separate prompts with reference images`() {
        var jobId = 0
        var polls = 0
        val prompts = mutableListOf<String>()
        val example = SketchPromptReference(UUID.randomUUID(), "threads.txt", "text/plain", "Use green cotton".toByteArray())
        val palette = SketchPromptReference(UUID.randomUUID(), "palette.png", "image/png", byteArrayOf(6))
        val adapter = adapter(references = listOf(example, palette)) { path, body ->
            when (path) {
                "/v1/chat/completions" -> {
                    assertEquals("gemini-2-5-flash-lite", body.path("model").stringValue())
                    assertTrue(body.path("messages").path(0).path("content").stringValue().contains("Editable prompt"))
                    val content = body.path("messages").path(1).path("content")
                    assertEquals(listOf(
                        "Примеры команды для эскиза:", "Файл-пример: threads.txt", "Use green cotton",
                        "Файл-пример: palette.png", "data:image/png;base64,Bg==", "Данные текущего заказа:",
                        "Изделие заказа: HOODIE, размер одежды M, цвет Чёрный.",
                        "Согласованное размещение: FRONT. Размер вышивки: 160 × 160 мм. Измеренная панель: 500 × 500 мм. " +
                            "Размер и положение уже рассчитаны в отдельной направляющей рамке для FLUX.2.",
                        "Выбранная рамка вышивки: 160 × 160 мм.",
                        "Фон вышивки: без отдельной фоновой композиции.",
                        "Госномер автомобиля: не вышивать.",
                        "Выбранный эскиз для правки:", "data:image/png;base64,Aw==",
                        "Исходные фото заказа:", "data:image/png;base64,AQ==",
                        "Описание заказа: Вышить машину",
                        "Покупатель: Оставьте номер", "data:image/jpeg;base64,Ag==",
                        "Сотрудник: Без фона",
                        "Замечания сотрудника к выбранному эскизу: Добавьте контур",
                    ), content.asSequence().map { it.path("text").stringValue(null) ?: it.path("image_url").path("url").stringValue() }.toList())
                    plan("Front view", "Side view")
                }
                "/api/v1/networks/flux-2" -> {
                    assertEquals("flash", body.path("model").stringValue())
                    assertEquals(1, body.path("num_images").intValue())
                    assertEquals(listOf("data:image/jpeg;base64,BA==", "data:image/png;base64,BQ==",
                        "data:image/png;base64,Aw==", "data:image/png;base64,AQ=="),
                        body.path("image_urls").asSequence().map { it.stringValue() }.toList())
                    val prompt = body.path("prompt").stringValue()
                    assertTrue(prompt.contains("Omit any car registration plate lettering"))
                    assertTrue(prompt.contains("without adding a separate background composition"))
                    prompts += prompt.split("\n\n")[1]
                    """{"request_id":${++jobId}}""".toByteArray()
                }
                "/api/v1/request/get/1", "/api/v1/request/get/2" ->
                    if (++polls == 1) """{"status":"processing"}""".toByteArray() else success(imageUrl)
                "/image.png" -> signature
                else -> error("Unexpected request")
            }
        }

        val variants = adapter.generate(request.copy(
            orderDescription = "Вышить машину",
            conversation = listOf(
                SketchMessage(Role.CUSTOMER, "Оставьте номер", attachment),
                SketchMessage(Role.STAFF, "Без фона"),
            ),
            maxVariants = 2,
            revision = SketchRevision(previous, "Добавьте контур"),
        ))

        assertEquals(listOf("Front view", "Side view"), prompts)
        assertEquals(2, variants.size)
        assertEquals(3, polls)
    }

    @Test
    fun `three frame background and selected registration reach both providers`() {
        val withBackground = request.copy(
            sourcePhotos = listOf(source, attachment, previous),
            embroidery = EmbroideryConfiguration(EmbroideryFrame.LARGE, true, true),
        )
        val adapter = adapter { path, body ->
            when (path) {
                "/v1/chat/completions" -> {
                    val content = body.path("messages").path(1).path("content").toList()
                    val texts = content.mapNotNull { it.path("text").stringValue(null) }
                    assertTrue("Выбранная рамка вышивки: 220 × 220 мм." in texts)
                    assertTrue("Фон вышивки: композиция из трёх исходных кадров автомобиля." in texts)
                    assertTrue("Госномер автомобиля: вышить." in texts)
                    assertEquals(listOf("data:image/png;base64,AQ==", "data:image/jpeg;base64,Ag==", "data:image/png;base64,Aw=="),
                        content.mapNotNull { it.path("image_url").path("url").stringValue(null) })
                    plan("Motif with the original registration")
                }
                "/api/v1/networks/flux-2" -> {
                    val prompt = body.path("prompt").stringValue()
                    assertTrue(prompt.contains("Compose the embroidered background from all three reference frames of the car"))
                    assertTrue(prompt.contains("Include the car registration plate only when its text is clearly legible"))
                    assertTrue(prompt.contains("Do not invent any characters"))
                    assertTrue(prompt.contains("No frames, measurement markings"))
                    assertEquals(listOf("data:image/jpeg;base64,BA==", "data:image/png;base64,BQ==",
                        "data:image/png;base64,AQ==", "data:image/jpeg;base64,Ag==", "data:image/png;base64,Aw=="),
                        body.path("image_urls").asSequence().map { it.stringValue() }.toList())
                    """{"request_id":1}""".toByteArray()
                }
                "/api/v1/request/get/1" -> success(imageUrl)
                "/image.png" -> signature
                else -> error("Unexpected request")
            }
        }
        assertFailsWith<IllegalArgumentException> { withBackground.copy(sourcePhotos = listOf(source)) }
        assertEquals(1, adapter.generate(withBackground).size)
    }

    @Test
    fun `malformed plans fail before making a paid image request`() {
        for (response in listOf(plan(), plan(""), plan("a", "b"), "<html>".toByteArray())) {
            assertFailsWith<SketchGenerationException> {
                adapter { path, _ ->
                    assertEquals("/v1/chat/completions", path)
                    response
                }.generate(request)
            }
        }
    }

    @Test
    fun `untrusted file URLs and provider errors stop the job`() {
        for (response in listOf(success("http://127.0.0.1/image.png"), """{"status":"error"}""".toByteArray())) {
            assertFailsWith<SketchGenerationException> {
                adapter { path, _ ->
                    when (path) {
                        "/v1/chat/completions" -> plan("One")
                        "/api/v1/networks/flux-2" -> """{"request_id":1}""".toByteArray()
                        "/api/v1/request/get/1" -> response
                        else -> error("Unexpected download")
                    }
                }.generate(request)
            }
        }
    }

    @Test
    fun `the next job uses the latest text and image model selection`() {
        var selection = SketchModels(2, "gemini-2-5-flash-lite", "flash", "Editable prompt")
        val models = mutableListOf<String>()
        val adapter = adapter({ selection }) { path, body ->
            when (path) {
                "/v1/chat/completions" -> plan("One").also { models += body.path("model").stringValue() }
                "/api/v1/networks/flux-2" ->
                    """{"request_id":1}""".toByteArray().also { models += body.path("model").stringValue() }
                "/api/v1/request/get/1" -> success(imageUrl)
                "/image.png" -> signature
                else -> error("Unexpected request")
            }
        }
        adapter.generate(request)
        selection = SketchModels(3, "gemini-3-1-flash-lite", "standard", "Updated prompt")
        adapter.generate(request)
        assertEquals(listOf("gemini-2-5-flash-lite", "flash", "gemini-3-1-flash-lite", "standard"), models)
    }

    private fun plan(vararg prompts: String) = json.writeValueAsBytes(mapOf(
        "choices" to listOf(mapOf("finish_reason" to "stop",
            "message" to mapOf("content" to json.writeValueAsString(mapOf("prompts" to prompts))))),
    ))

    private fun success(url: String) = json.writeValueAsBytes(mapOf("status" to "success", "result" to listOf(url)))

    private fun adapter(
        selection: () -> SketchModels = { SketchModels(2, "gemini-2-5-flash-lite", "flash", "Editable prompt") },
        references: List<SketchPromptReference> = emptyList(),
        respond: (String, JsonNode) -> ByteArray,
    ): GenApiSketchGenerator {
        val client = RestClient.builder().requestInterceptor { request, bytes, _ ->
            val auth = request.headers.getFirst(HttpHeaders.AUTHORIZATION)
            if (request.uri.host == "gen-api.storage.yandexcloud.net") assertNull(auth)
            else assertEquals("Bearer fixture-key", auth)
            val response = respond(request.uri.path, if (bytes.isEmpty()) json.createObjectNode() else json.readTree(bytes))
            object : ClientHttpResponse {
                override fun getStatusCode() = HttpStatus.OK
                override fun getStatusText() = HttpStatus.OK.reasonPhrase
                override fun getHeaders() = HttpHeaders()
                override fun getBody() = response.inputStream()
                override fun close() = Unit
            }
        }.build()
        return GenApiSketchGenerator(GenApiSketchSettings("fixture-key"), json, client, { SketchConfiguration(selection(), references) }, pause = {})
    }
}
