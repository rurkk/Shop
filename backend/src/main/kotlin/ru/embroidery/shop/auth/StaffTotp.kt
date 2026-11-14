package ru.embroidery.shop.auth

import jakarta.validation.Validator
import jakarta.validation.constraints.Email
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import ru.embroidery.shop.ShopSettings
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val TOTP_STEP_SECONDS = 30

@Component
class StaffAccounts(settings: ShopSettings, environment: Environment, validator: Validator) {
    private val credentials = buildMap {
        val production = environment.acceptsProfiles(Profiles.of("production"))
        val secrets = mutableSetOf<String>()
        for (entry in settings.staffTotpAccounts) {
            val separator = entry.lastIndexOf('=')
            require(separator > 0) { "Configure SHOP_STAFF_TOTP_ACCOUNTS as email=Base32-secret pairs" }
            val email = normalizedEmail(entry.substring(0, separator))
            require(email.length <= 254 && '@' in email && validator.validate(StaffEmail(email)).isEmpty()) {
                "SHOP_STAFF_TOTP_ACCOUNTS contains an invalid email"
            }
            val secret = entry.substring(separator + 1)
            val credential = StaffTotp(secret)
            require(!production || (!email.endsWith(".test") &&
                HexFormat.of().formatHex(credential.fingerprint) !in localTestFingerprints)) {
                "Local test accounts and secrets are unavailable in production"
            }
            require(email !in this && secrets.add(secret)) {
                "Each staff account must have a unique email and TOTP secret"
            }
            put(email, credential)
        }
        require(isNotEmpty()) { "Configure at least one staff TOTP account" }
    }

    internal operator fun get(email: String): StaffTotp? = credentials[email]

    private class StaffEmail(@field:Email val value: String)

    private companion object {
        // Reject the public .env.example keys even if they are assigned a different address.
        val localTestFingerprints = setOf(
            "c9927a8a8fa2711ee80aa7231c875b06ecfd7af2de351809063541c28b01f393",
            "aeb7172be4b77998c2bdc11edd759c8815cee7b4574a629c5bc43e1d8e8cfc1f",
            "e9137f754e01d83c2e01b117a67f97c2bdb95e1f1a5218f0ed2ef39d61014ce1",
            "9a459d9b61e01010b3f4a8e5b9a1ed0aab36ff3389b5ceb5e931ef0a34e8e863"
        )
    }
}

internal class StaffTotp(secret: String) {
    private val key: SecretKeySpec
    val fingerprint: ByteArray

    init {
        require(secret.length == 32 && secret.all { it in BASE32 }) {
            "Each staff TOTP secret must contain 32 uppercase Base32 characters without padding"
        }
        val rawKey = ByteArray(20)
        // Eight Base32 symbols hold exactly five bytes.
        for (start in secret.indices step 8) {
            var block = 0L
            for (index in start until start + 8) block = (block shl 5) or BASE32.indexOf(secret[index]).toLong()
            for (index in 0 until 5) rawKey[start / 8 * 5 + index] = (block ushr (32 - index * 8)).toByte()
        }
        key = SecretKeySpec(rawKey, "HmacSHA1")
        fingerprint = MessageDigest.getInstance("SHA-256").digest(rawKey)
    }

    fun matchingStep(code: String, now: Instant): Long? {
        val current = Math.floorDiv(now.epochSecond, TOTP_STEP_SECONDS.toLong())
        if (current < 0) return null
        val supplied = code.toByteArray(Charsets.US_ASCII)
        val mac = Mac.getInstance("HmacSHA1").apply { init(key) }
        var matching: Long? = null
        for (step in maxOf(0, current - 1)..current + 1) {
            val digest = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
            val offset = digest.last().toInt() and 0x0f
            val value = ByteBuffer.wrap(digest, offset, Int.SIZE_BYTES).int and Int.MAX_VALUE
            val expected = (value % 1_000_000).toString().padStart(6, '0').toByteArray(Charsets.US_ASCII)
            if (MessageDigest.isEqual(expected, supplied)) matching = step
        }
        return matching
    }
}
