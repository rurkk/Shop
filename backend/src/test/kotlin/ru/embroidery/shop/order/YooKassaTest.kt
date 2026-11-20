package ru.embroidery.shop.order

import org.springframework.core.env.StandardEnvironment
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.client.RestClient
import ru.embroidery.shop.ApiFailure
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YooKassaTest {
    private val settings = YooKassaSettings(enabled = true, shopId = "12345", secretKey = "example-key")
    private val paymentId = UUID.randomUUID()

    @Test
    fun `shop and payment must use the mode required by the active profile`() {
        for ((profile, flag) in mapOf("production" to "false", "local" to "true", "demo" to "true")) {
            val gateway = gateway(profile) { request ->
                when (request.uri.path) {
                    "/v3/me" -> shop(flag)
                    "/v3/payments/$paymentId" -> payment(flag)
                    else -> error("Unexpected request to ${request.uri}")
                }
            }
            gateway.requireConfiguredShop()
            assertEquals(paymentId, gateway.getPayment(paymentId).id)
        }
    }

    @Test
    fun `wrong and malformed mode flags cannot pass shop or payment verification`() {
        for ((profile, flag) in mapOf("production" to "false", "demo" to "true")) {
            val wrongFlag = (flag != "true").toString()
            for (reportedFlag in listOf(wrongFlag, null, "null", "\"$flag\"")) {
                val gateway = gateway(profile) { request ->
                    when (request.uri.path) {
                        "/v3/me" -> shop(reportedFlag)
                        "/v3/payments/$paymentId" -> payment(reportedFlag)
                        else -> error("Unexpected request to ${request.uri}")
                    }
                }
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ApiFailure> {
                    gateway.requireConfiguredShop()
                }.status, "$profile /me test=$reportedFlag")
                assertEquals(HttpStatus.BAD_GATEWAY, assertFailsWith<ApiFailure> {
                    gateway.getPayment(paymentId)
                }.status, "$profile payment test=$reportedFlag")
            }
        }
    }

    @Test
    fun `refund reads require a matching enabled shop and have no mode flag of their own`() {
        val requests = mutableListOf<String>()
        val refundId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val refund = """{"id":"$refundId","payment_id":"$paymentId","status":"succeeded",
            "amount":{"value":"2.00","currency":"RUB"},"metadata":{"shop_refund_id":"$requestId"}}"""
        val gateway = gateway("production") { request ->
            requests += "${request.method} ${request.uri.path}"
            when (request.uri.path) {
                "/v3/me" -> when (requests.size) {
                    1 -> shop("false", shopId = "54321")
                    2 -> shop("false", status = "disabled")
                    3 -> shop("true")
                    else -> shop("false")
                }
                "/v3/refunds/$refundId" -> refund
                else -> error("Unexpected request to ${request.uri}")
            }
        }
        repeat(3) {
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ApiFailure> { gateway.getRefund(refundId) }.status)
        }
        assertEquals(List(3) { "GET /v3/me" }, requests)
        val accepted = gateway.getRefund(refundId)
        assertEquals(requestId, accepted.requestId)
        assertEquals(paymentId, accepted.paymentId)
        assertEquals(List(4) { "GET /v3/me" } + "GET /v3/refunds/$refundId", requests)
    }

    @Test
    fun `production refuses a replaced provider API endpoint`() {
        val override = YooKassaSettings(enabled = true, shopId = "12345", secretKey = "example-key", apiBase = "http://localhost/v3")
        assertFailsWith<IllegalArgumentException> {
            gateway("production", override) { error("Production credentials must never reach the override") }
        }
    }

    private fun shop(test: String?, shopId: String = settings.shopId, status: String = "enabled") =
        jsonWithMode(""""account_id":"$shopId","status":"$status"""", test)

    private fun payment(test: String?) = jsonWithMode("""
        "id":"$paymentId","status":"succeeded","paid":true,"amount":{"value":"2.00","currency":"RUB"},
        "recipient":{"account_id":"${settings.shopId}"},
        "metadata":{"shop_order_id":"${UUID.randomUUID()}","shop_attempt_id":"${UUID.randomUUID()}"}
    """.trimIndent(), test)

    private fun jsonWithMode(fields: String, test: String?): String =
        if (test == null) "{$fields}" else "{$fields,\"test\":$test}"

    private fun gateway(profile: String, settings: YooKassaSettings = this.settings, respond: (HttpRequest) -> String): YooKassa {
        val env = StandardEnvironment().apply { setActiveProfiles(profile) }
        val builder = RestClient.builder().requestInterceptor { request, _, _ ->
            val body = respond(request).byteInputStream()
            object : ClientHttpResponse {
                override fun getStatusCode() = HttpStatus.OK
                override fun getStatusText() = HttpStatus.OK.reasonPhrase
                override fun getHeaders() = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
                override fun getBody() = body
                override fun close() = body.close()
            }
        }
        return YooKassa(settings, JsonMapper.builder().build(), env, builder)
    }
}
