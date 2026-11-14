package ru.embroidery.shop.mail

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mail.MailException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.embroidery.shop.ShopSettings
import java.util.UUID

@Component
class ShopMail(
    private val db: JdbcClient,
    private val sender: JavaMailSender,
    private val settings: ShopSettings
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun send(recipient: String, subject: String, body: String) {
        sender.send(SimpleMailMessage().apply {
            from = settings.mailFrom
            setTo(recipient)
            setSubject(subject)
            text = body
        })
    }

    fun notifyCustomer(orderId: UUID, email: String, subject: String, action: String) {
        val body = "$action\n\n${settings.orderUrl(orderId)}\n\nНомер заказа: $orderId"
        db.sql("""
            INSERT INTO mail_outbox (recipient, subject, body, expires_at)
            VALUES (:recipient, :subject, :body, now() + interval '1 day')
        """).param("recipient", email).param("subject", subject)
            .param("body", body)
            .update()
    }

    @Scheduled(fixedDelay = 10_000)
    fun deliverQueued() {
        db.sql("DELETE FROM mail_outbox WHERE expires_at <= now()").update()
        val messages = db.sql("""
            SELECT id, recipient, subject, body FROM mail_outbox
            WHERE next_attempt_at <= now() ORDER BY id LIMIT 5
        """).query { rs, _ ->
            QueuedMail(rs.getLong("id"), rs.getString("recipient"), rs.getString("subject"), rs.getString("body"))
        }.list()
        for (message in messages) {
            try {
                send(message.recipient, message.subject, message.body)
                db.sql("DELETE FROM mail_outbox WHERE id = :id").param("id", message.id).update()
            } catch (error: MailException) {
                logger.warn("Unable to send notification {} ({})", message.id, error.javaClass.simpleName)
                db.sql("""
                    UPDATE mail_outbox SET attempts = attempts + 1,
                        next_attempt_at = now() + interval '1 minute' * least(60, 1 << least(attempts, 6))
                    WHERE id = :id
                """).param("id", message.id).update()
            }
        }
    }

    private data class QueuedMail(val id: Long, val recipient: String, val subject: String, val body: String)
}
