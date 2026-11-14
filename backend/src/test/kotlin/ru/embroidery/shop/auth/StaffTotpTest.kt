package ru.embroidery.shop.auth

import jakarta.validation.Validation
import org.springframework.core.env.StandardEnvironment
import ru.embroidery.shop.ShopSettings
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val RFC_TEST_KEY = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
private const val LOCAL_TEST_KEY = "REXN65Q4MNZKUAK4UFU3LC7KVT54VKOI"

class StaffTotpTest {
    @Test
    fun `accepts the six-digit versions of RFC 6238 SHA1 vectors`() {
        val factor = StaffTotp(RFC_TEST_KEY)
        assertEquals(1L, factor.matchingStep("287082", Instant.ofEpochSecond(59)))
        assertEquals(37_037_036L, factor.matchingStep("081804", Instant.ofEpochSecond(1_111_111_109)))
        assertEquals(41_152_263L, factor.matchingStep("005924", Instant.ofEpochSecond(1_234_567_890)))
        assertEquals(666_666_666L, factor.matchingStep("353130", Instant.ofEpochSecond(20_000_000_000)))
    }

    @Test
    fun `allows a skew of one step`() {
        val factor = StaffTotp(RFC_TEST_KEY)
        assertEquals(1L, factor.matchingStep("287082", Instant.ofEpochSecond(29)))
        assertEquals(1L, factor.matchingStep("287082", Instant.ofEpochSecond(89)))
        assertNull(factor.matchingStep("287082", Instant.ofEpochSecond(90)))
    }

    @Test
    fun `requires a unique canonical Base32 key per account`() {
        assertFailsWith<IllegalArgumentException> { StaffTotp(RFC_TEST_KEY.lowercase()) }
        assertFailsWith<IllegalArgumentException> { StaffTotp(RFC_TEST_KEY + "=") }
        assertFailsWith<IllegalArgumentException> { accounts("alice@example.com=$RFC_TEST_KEY", "bob@example.com=$RFC_TEST_KEY") }
        assertFailsWith<IllegalArgumentException> { accounts("alice@example.com=$RFC_TEST_KEY", "ALICE@example.com=$LOCAL_TEST_KEY") }
    }

    @Test
    fun `rejects local test addresses and keys in production`() {
        assertFailsWith<IllegalArgumentException> { accounts("staff@example.test=$RFC_TEST_KEY", production = true) }
        assertFailsWith<IllegalArgumentException> { accounts("staff@example.com=$LOCAL_TEST_KEY", production = true) }
        assertNotNull(accounts("Staff@example.test=$LOCAL_TEST_KEY")["staff@example.test"])
    }

    private fun accounts(vararg entries: String, production: Boolean = false): StaffAccounts {
        val environment = StandardEnvironment().apply { setActiveProfiles(if (production) "production" else "local") }
        val origin = URI.create("https://shop.example.com")
        val settings = ShopSettings(
            staffTotpAccounts = entries.toList(),
            codeSecret = "example".repeat(5),
            mailFrom = "orders@example.com",
            publicOrigin = origin,
            storage = ShopSettings.Storage(origin, "shop-test", "test-key", "test-secret")
        )
        return Validation.buildDefaultValidatorFactory().use { factory ->
            StaffAccounts(settings, environment, factory.validator)
        }
    }
}
