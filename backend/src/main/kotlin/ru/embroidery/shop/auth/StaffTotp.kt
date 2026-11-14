package ru.embroidery.shop.auth

import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import ru.embroidery.shop.ShopSettings
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val TOTP_STEP_SECONDS = 30

@Component
class StaffTotpKeys(settings: ShopSettings, environment: Environment) {
    private val random = SecureRandom()
    private val key: SecretKeySpec

    init {
        val configured = settings.staffTotpEncryptionKey
        require(configured.matches(Regex("[0-9a-fA-F]{64}"))) {
            "SHOP_STAFF_TOTP_ENCRYPTION_KEY must contain 64 hexadecimal characters"
        }
        require(!environment.acceptsProfiles(Profiles.of("demo", "production")) ||
            !configured.equals("5a".repeat(32), ignoreCase = true)) {
            "The local TOTP encryption key is unavailable on a server"
        }
        key = SecretKeySpec(HexFormat.of().parseHex(configured), "AES")
    }

    fun generate(): String = CharArray(32) { BASE32[random.nextInt(BASE32.length)] }.concatToString()

    fun encrypt(credentialId: UUID, secret: String): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        return nonce + cipher(Cipher.ENCRYPT_MODE, credentialId, nonce).doFinal(secret.toByteArray(Charsets.US_ASCII))
    }

    internal fun decrypt(credentialId: UUID, encrypted: ByteArray): StaffTotp {
        val cipher = cipher(Cipher.DECRYPT_MODE, credentialId, encrypted.copyOfRange(0, 12))
        return StaffTotp(cipher.doFinal(encrypted, 12, encrypted.size - 12).toString(Charsets.US_ASCII))
    }

    private fun cipher(mode: Int, credentialId: UUID, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce))
            updateAAD(credentialId.toString().toByteArray(Charsets.US_ASCII))
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
