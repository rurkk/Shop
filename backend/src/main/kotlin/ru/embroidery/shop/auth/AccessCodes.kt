package ru.embroidery.shop.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mail.MailException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.ShopSettings
import ru.embroidery.shop.badRequest
import ru.embroidery.shop.mail.ShopMail
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun normalizedEmail(email: String): String = email.trim().lowercase(Locale.ROOT)

private val sixDigitPattern = Regex("[0-9]{6}")

@Service
class AccessCodes(private val db: JdbcClient, private val staffAccounts: StaffAccounts, settings: ShopSettings) {
    private val random = SecureRandom()
    private val key = SecretKeySpec(settings.codeSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    @Transactional
    fun issue(scope: String, email: String, role: Role): String? {
        val hourlyLimit = if (role == Role.STAFF) 5 else 10
        val allowed = db.sql("""
            INSERT INTO access_mail_limits (role, email, window_start, last_sent_at, sent_count)
            VALUES (:role, :email, now(), now(), 1)
            ON CONFLICT (role, email) DO UPDATE SET
                sent_count = CASE WHEN access_mail_limits.window_start <= now() - interval '1 hour'
                                  THEN 1 ELSE access_mail_limits.sent_count + 1 END,
                window_start = CASE WHEN access_mail_limits.window_start <= now() - interval '1 hour'
                                    THEN now() ELSE access_mail_limits.window_start END,
                last_sent_at = now()
            WHERE access_mail_limits.last_sent_at <= now() - interval '1 minute'
              AND (access_mail_limits.sent_count < :hourlyLimit OR access_mail_limits.window_start <= now() - interval '1 hour')
        """).param("role", role.name).param("email", email).param("hourlyLimit", hourlyLimit).update()
        if (allowed == 0) return null

        val code = random.nextInt(1_000_000).toString().padStart(6, '0')
        db.sql("""
            INSERT INTO access_codes (scope, digest, expires_at)
            VALUES (:scope, :digest, now() + interval '10 minutes')
            ON CONFLICT (scope) DO UPDATE
            SET digest = excluded.digest, attempts = 0, expires_at = excluded.expires_at
        """).param("scope", scope).param("digest", digest(scope, code)).update()
        return code
    }

    @Transactional
    fun consume(scope: String, code: String): Boolean = consume(scope, code) { true }

    @Transactional
    fun consumeStaffCode(email: String, code: String, totp: String): Actor? {
        val login = staffAccounts.login(email) ?: return null
        if (!consume(staffScope(login.credentialId), code) { consumeTotp(email, login.totp, totp) }) return null
        return Actor(email, Role.STAFF, staffCredentialId = login.credentialId, canManageStaff = login.canManageStaff)
    }

    private fun consume(scope: String, code: String, verifyFactor: () -> Boolean): Boolean {
        val expected = db.sql("""
            SELECT digest FROM access_codes
            WHERE scope = :scope AND attempts < 5 AND expires_at > now()
            FOR UPDATE
        """).param("scope", scope).query(ByteArray::class.java).optional().orElse(null) ?: return false
        if (!MessageDigest.isEqual(expected, digest(scope, code)) || !verifyFactor()) {
            db.sql("UPDATE access_codes SET attempts = attempts + 1 WHERE scope = :scope")
                .param("scope", scope).update()
            return false
        }
        db.sql("DELETE FROM access_codes WHERE scope = :scope").param("scope", scope).update()
        return true
    }

    private fun consumeTotp(email: String, credential: StaffTotp, code: String): Boolean {
        val step = credential.matchingStep(code, Instant.now()) ?: return false
        return db.sql("""
            INSERT INTO staff_totp_use (email, secret_fingerprint, last_step)
            VALUES (:email, :fingerprint, :step)
            ON CONFLICT (email, secret_fingerprint) DO UPDATE SET last_step = excluded.last_step
            WHERE staff_totp_use.last_step < excluded.last_step
        """).param("email", email).param("fingerprint", credential.fingerprint).param("step", step).update() == 1
    }

    @Scheduled(fixedDelay = 3_600_000)
    fun purge() {
        db.sql("DELETE FROM access_codes WHERE expires_at < now()").update()
        db.sql("DELETE FROM access_mail_limits WHERE last_sent_at < now() - interval '1 day'").update()
    }

    private fun digest(scope: String, code: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal("$scope:$code".toByteArray(Charsets.UTF_8))
    }
}

data class CodeRequest(@field:NotBlank @field:Email @field:Size(max = 254) val email: String)
data class CodeVerification(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    @field:Pattern(regexp = "[0-9]{6}") val code: String
)

data class StaffCodeVerification(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    val code: JsonNode,
    val totp: JsonNode
)

private fun staffScope(credentialId: UUID) = "staff:$credentialId"

private fun sixDigitCode(node: JsonNode): String {
    val code = node.stringValue(null)
    if (code == null || !sixDigitPattern.matches(code)) badRequest("Invalid request")
    return code
}

@RestController
class AccessController(
    private val db: JdbcClient,
    private val codes: AccessCodes,
    private val sessions: BrowserSessions,
    private val mail: ShopMail,
    private val staffAccounts: StaffAccounts,
    @param:Qualifier("staffAccessMailExecutor") private val staffAccessMailExecutor: TaskExecutor
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/orders/{orderId}/access/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestOrderCode(@PathVariable orderId: UUID, @Valid @RequestBody input: CodeRequest) {
        val email = normalizedEmail(input.email)
        if (ownsOrder(orderId, email)) send(orderScope(orderId, email), email, Role.CUSTOMER)
    }

    @PostMapping("/api/orders/{orderId}/access/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun verifyOrderCode(
        @PathVariable orderId: UUID, @Valid @RequestBody input: CodeVerification,
        request: HttpServletRequest, response: HttpServletResponse
    ) {
        val email = normalizedEmail(input.email)
        if (!ownsOrder(orderId, email) || !codes.consume(orderScope(orderId, email), input.code)) invalidCode()
        sessions.start(Actor(email, Role.CUSTOMER, orderId), request, response)
    }

    @PostMapping("/api/staff/access/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestStaffCode(@Valid @RequestBody input: CodeRequest) {
        val email = normalizedEmail(input.email)
        try {
            staffAccessMailExecutor.execute {
                try {
                    val credentialId = staffAccounts.credentialId(email) ?: return@execute
                    send(staffScope(credentialId), email, Role.STAFF)
                } catch (error: RuntimeException) {
                    logger.error("Unable to issue a staff access code ({})", error.javaClass.simpleName)
                }
            }
        } catch (_: TaskRejectedException) {
            logger.warn("Staff access mail queue is full")
        }
    }

    @PostMapping("/api/staff/access/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun verifyStaffCode(
        @Valid @RequestBody input: StaffCodeVerification, request: HttpServletRequest, response: HttpServletResponse
    ) {
        val email = normalizedEmail(input.email)
        val actor = codes.consumeStaffCode(email, sixDigitCode(input.code), sixDigitCode(input.totp)) ?: invalidCode()
        sessions.start(actor, request, response)
    }

    private fun ownsOrder(orderId: UUID, email: String): Boolean = db.sql("""
        SELECT EXISTS (SELECT 1 FROM orders
            WHERE id = :id AND email = :email AND status <> 'EXPIRED'
              AND (status <> 'DRAFT' OR created_at > now() - interval '1 day'))
    """).param("id", orderId).param("email", email).query(Boolean::class.java).single()

    private fun send(scope: String, email: String, role: Role) {
        val code = codes.issue(scope, email, role) ?: return
        try {
            mail.send(email, "Код доступа", "Код доступа: $code\nОн действует 10 минут.")
        } catch (error: MailException) {
            logger.warn("Unable to send an access code ({})", error.javaClass.simpleName)
        }
    }

    private fun orderScope(orderId: UUID, email: String) = "order:$orderId:$email"

    private fun invalidCode(): Nothing = throw ApiFailure(HttpStatus.UNAUTHORIZED, "Invalid or expired code")
}
