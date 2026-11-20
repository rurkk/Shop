package ru.embroidery.shop.order

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import ru.embroidery.shop.ApiFailure
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID

private const val YOOKASSA_API_BASE = "https://api.yookassa.ru/v3"

@ConfigurationProperties("shop.yookassa")
class YooKassaSettings(
    val enabled: Boolean = false,
    val shopId: String = "",
    val secretKey: String = "",
    val apiBase: String = YOOKASSA_API_BASE
)

internal data class GatewayPayment(
    val id: UUID,
    val status: ProviderStatus,
    val amountKopecks: Long,
    val orderId: UUID,
    val attemptId: UUID,
    val shopId: String,
    val checkoutUrl: String?
)

internal data class GatewayRefund(
    val id: UUID,
    val paymentId: UUID,
    val status: ProviderStatus,
    val amountKopecks: Long,
    val requestId: UUID?
)

@Component
@ConditionalOnProperty("shop.yookassa.enabled", havingValue = "true")
class YooKassa internal constructor(
    settings: YooKassaSettings,
    private val json: ObjectMapper,
    environment: Environment,
    builder: RestClient.Builder
) {
    @Autowired
    constructor(settings: YooKassaSettings, json: ObjectMapper, environment: Environment) :
        this(settings, json, environment, restClientBuilder())

    val shopId = settings.shopId
    private val testMode = !environment.acceptsProfiles(Profiles.of("production"))
    private val client: RestClient

    init {
        require(shopId.matches(Regex("[0-9]{1,32}")) && settings.secretKey.isNotBlank()) {
            "Set SHOP_YOOKASSA_SHOP_ID and SHOP_YOOKASSA_SECRET_KEY"
        }
        val apiBase = settings.apiBase.trimEnd('/')
        require(testMode || apiBase == YOOKASSA_API_BASE) {
            "The production profile requires the YooKassa API at $YOOKASSA_API_BASE"
        }
        client = builder.baseUrl(apiBase)
            .defaultHeaders { it.setBasicAuth(shopId, settings.secretKey) }.build()
    }

    internal fun requireConfiguredShop() {
        val shop = read(client.get().uri("/me"))
        if (shop.path("account_id").asString(null) != shopId || shop.path("status").stringValue(null) != "enabled" ||
            !matchesMode(shop)) {
            throw ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "YooKassa shop is unavailable or does not match this environment")
        }
    }

    internal fun createPayment(order: Order, payment: Payment, attempt: PaymentAttempt): GatewayPayment {
        return payment(post("/payments", attempt.id, mapOf(
            "amount" to amount(payment.amountKopecks),
            "capture" to true,
            "confirmation" to mapOf("type" to "redirect", "return_url" to attempt.returnUrl),
            "description" to "Заказ KNITSEW ${order.id}",
            "metadata" to mapOf("shop_order_id" to order.id.toString(), "shop_attempt_id" to attempt.id.toString())
        )))
    }

    internal fun getPayment(id: UUID): GatewayPayment = payment(read(client.get().uri("/payments/{id}", id)))

    internal fun createRefund(paymentId: UUID, amountKopecks: Long, refundId: UUID): GatewayRefund {
        return refund(post("/refunds", refundId, mapOf(
            "payment_id" to paymentId.toString(),
            "amount" to amount(amountKopecks),
            "metadata" to mapOf("shop_refund_id" to refundId.toString())
        )))
    }

    internal fun getRefund(id: UUID): GatewayRefund {
        // Refund objects have no test flag. Verify the merchant before reading them.
        requireConfiguredShop()
        return refund(read(client.get().uri("/refunds/{id}", id)))
    }

    private fun amount(kopecks: Long) = mapOf("currency" to "RUB", "value" to BigDecimal.valueOf(kopecks, 2).toPlainString())

    private fun post(path: String, key: UUID, data: Map<String, Any>): JsonNode {
        val body = json.writeValueAsBytes(data)
        return read(client.post().uri(path).header("Idempotence-Key", key.toString())
            .contentType(MediaType.APPLICATION_JSON).contentLength(body.size.toLong()).body(body))
    }

    private fun read(request: RestClient.RequestHeadersSpec<*>): JsonNode {
        try {
            return request.retrieve().body(JsonNode::class.java) ?: invalidResponse()
        } catch (_: RestClientException) {
            throw ApiFailure(HttpStatus.BAD_GATEWAY, "YooKassa did not return a usable response")
        }
    }

    private fun payment(node: JsonNode): GatewayPayment {
        val status = status(node)
        val paid = node.path("paid")
        if (!matchesMode(node) || !paid.isBoolean ||
            paid.booleanValue() != (status == ProviderStatus.SUCCEEDED)) {
            invalidResponse()
        }
        val confirmation = node.path("confirmation").path("confirmation_url").stringValue(null)
        if (confirmation != null) {
            val url = runCatching { URI.create(confirmation) }.getOrNull() ?: invalidResponse()
            if (url.scheme != "https" || url.host == null || url.userInfo != null || url.port !in listOf(-1, 443) ||
                url.host.lowercase() !in checkoutHosts) invalidResponse()
        }
        val metadata = node.path("metadata")
        val recipient = node.path("recipient").path("account_id").asString(null) ?: invalidResponse()
        return GatewayPayment(
            uuid(node.path("id")), status, kopecks(node.path("amount")), uuid(metadata.path("shop_order_id")),
            uuid(metadata.path("shop_attempt_id")), recipient, confirmation
        )
    }

    private fun refund(node: JsonNode) = GatewayRefund(
        uuid(node.path("id")), uuid(node.path("payment_id")), status(node), kopecks(node.path("amount")),
        node.path("metadata").path("shop_refund_id").stringValue(null)?.let {
            runCatching { UUID.fromString(it) }.getOrElse { invalidResponse() }
        }
    )

    private fun matchesMode(node: JsonNode): Boolean {
        val test = node.path("test")
        return test.isBoolean && test.booleanValue() == testMode
    }

    private fun status(node: JsonNode) = when (node.path("status").stringValue(null)) {
        "pending" -> ProviderStatus.PENDING
        "succeeded" -> ProviderStatus.SUCCEEDED
        "canceled" -> ProviderStatus.CANCELED
        else -> invalidResponse()
    }

    private fun kopecks(amount: JsonNode): Long {
        if (amount.path("currency").stringValue(null) != "RUB") invalidResponse()
        val value = amount.path("value").asString(null) ?: invalidResponse()
        return runCatching { BigDecimal(value).movePointRight(2).longValueExact() }
            .getOrElse { invalidResponse() }
    }

    private fun uuid(node: JsonNode): UUID {
        val value = node.stringValue(null) ?: invalidResponse()
        return runCatching { UUID.fromString(value) }.getOrElse { invalidResponse() }
    }

    private fun invalidResponse(): Nothing = throw ApiFailure(HttpStatus.BAD_GATEWAY, "Unexpected YooKassa response")

    private companion object {
        val checkoutHosts = setOf("yoomoney.ru", "www.yoomoney.ru", "yookassa.ru", "www.yookassa.ru")

        fun restClientBuilder(): RestClient.Builder {
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(Duration.ofSeconds(6)) }
            return RestClient.builder().requestFactory(factory)
        }
    }
}
