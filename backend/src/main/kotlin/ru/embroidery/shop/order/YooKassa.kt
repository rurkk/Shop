package ru.embroidery.shop.order

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import ru.embroidery.shop.ApiFailure
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID

@ConfigurationProperties("shop.yookassa")
class YooKassaSettings(
    val enabled: Boolean = false,
    val shopId: String = "",
    val secretKey: String = "",
    val publicOrigin: String = "",
    val apiBase: String = "https://api.yookassa.ru/v3"
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

@Profile("!production")
@Component
@ConditionalOnProperty("shop.yookassa.enabled", havingValue = "true")
class YooKassa(private val settings: YooKassaSettings, private val json: ObjectMapper) {
    val shopId = settings.shopId
    private val origin = URI.create(settings.publicOrigin)
    private val client: RestClient

    init {
        require(shopId.matches(Regex("[0-9]{1,32}")) && settings.secretKey.isNotBlank()) {
            "Set the test shop ID and secret key"
        }
        require(origin.host != null && origin.rawUserInfo == null && origin.rawQuery == null &&
            origin.rawFragment == null && origin.rawPath in listOf("", "/") &&
            (origin.scheme == "https" || (origin.scheme == "http" && origin.host in setOf("127.0.0.1", "localhost")))) {
            "Set the Shop public origin to an HTTPS origin or a loopback HTTP origin"
        }
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(Duration.ofSeconds(6)) }
        client = RestClient.builder().baseUrl(settings.apiBase.trimEnd('/')).requestFactory(factory)
            .defaultHeaders { it.setBasicAuth(shopId, settings.secretKey) }.build()
    }

    fun returnUrl(orderId: UUID): String = origin.resolve("/orders/$orderId").toString()

    internal fun requireTestShop() {
        val shop = read(client.get().uri("/me"))
        if (shop.path("account_id").asText() != shopId || shop.path("status").asText() != "enabled" ||
            shop.path("test").booleanValue() != true) {
            throw ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "Configure an enabled YooKassa test shop")
        }
    }

    internal fun createPayment(order: Order, payment: Payment, attempt: PaymentAttempt): GatewayPayment {
        return payment(post("/payments", attempt.id, mapOf(
            "amount" to amount(payment.amountKopecks),
            "capture" to true,
            "confirmation" to mapOf("type" to "redirect", "return_url" to attempt.returnUrl),
            "description" to "Заказ Shop ${order.id}",
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

    internal fun getRefund(id: UUID): GatewayRefund = refund(read(client.get().uri("/refunds/{id}", id)))

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
        if (node.path("test").booleanValue() != true ||
            node.path("paid").booleanValue() != (status == ProviderStatus.SUCCEEDED)) {
            invalidResponse()
        }
        val confirmation = node.path("confirmation").path("confirmation_url").textValue()
        if (confirmation != null) {
            val url = runCatching { URI.create(confirmation) }.getOrNull() ?: invalidResponse()
            if (url.scheme != "https" || url.host == null || url.userInfo != null || url.port !in listOf(-1, 443) ||
                url.host.lowercase() !in checkoutHosts) invalidResponse()
        }
        val metadata = node.path("metadata")
        return GatewayPayment(
            uuid(node.path("id")), status, kopecks(node.path("amount")), uuid(metadata.path("shop_order_id")),
            uuid(metadata.path("shop_attempt_id")), node.path("recipient").path("account_id").asText(), confirmation
        )
    }

    private fun refund(node: JsonNode) = GatewayRefund(
        uuid(node.path("id")), uuid(node.path("payment_id")), status(node), kopecks(node.path("amount")),
        node.path("metadata").path("shop_refund_id").textValue()?.let {
            runCatching { UUID.fromString(it) }.getOrElse { invalidResponse() }
        }
    )

    private fun status(node: JsonNode) = when (node.path("status").asText()) {
        "pending" -> ProviderStatus.PENDING
        "succeeded" -> ProviderStatus.SUCCEEDED
        "canceled" -> ProviderStatus.CANCELED
        else -> invalidResponse()
    }

    private fun kopecks(amount: JsonNode): Long {
        if (amount.path("currency").asText() != "RUB") invalidResponse()
        return runCatching { BigDecimal(amount.path("value").asText()).movePointRight(2).longValueExact() }
            .getOrElse { invalidResponse() }
    }

    private fun uuid(node: JsonNode): UUID = runCatching { UUID.fromString(node.asText()) }.getOrElse { invalidResponse() }

    private fun invalidResponse(): Nothing = throw ApiFailure(HttpStatus.BAD_GATEWAY, "Unexpected YooKassa response")

    private companion object {
        val checkoutHosts = setOf("yoomoney.ru", "www.yoomoney.ru", "yookassa.ru", "www.yookassa.ru")
    }
}
