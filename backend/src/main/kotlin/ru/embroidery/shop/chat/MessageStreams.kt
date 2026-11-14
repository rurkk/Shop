package ru.embroidery.shop.chat

import jakarta.servlet.http.HttpSession
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.auth.Actor
import ru.embroidery.shop.auth.StaffAccounts
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

@Configuration
class StreamingConfiguration {
    @Bean
    fun chatExecutor() = executor("chat-", 4, 64)

    @Bean
    fun downloadExecutor() = executor("download-", 4, 12)

    @Bean
    fun asyncMvc(@Qualifier("downloadExecutor") executor: ThreadPoolTaskExecutor): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun configureAsyncSupport(configurer: AsyncSupportConfigurer) {
                configurer.setTaskExecutor(executor)
                configurer.setDefaultTimeout(45_000)
            }
        }

    private fun executor(prefix: String, workers: Int, queue: Int) = ThreadPoolTaskExecutor().apply {
        setThreadNamePrefix(prefix)
        corePoolSize = workers
        maxPoolSize = workers
        queueCapacity = queue
    }
}

@Component
class MessageStreams(
    private val messages: Messages,
    private val staffAccounts: StaffAccounts,
    @param:Qualifier("chatExecutor") private val executor: Executor
) {
    private val subscriptions = ConcurrentHashMap<UUID, Subscription>()

    @Synchronized
    fun open(orderId: UUID, after: Long, session: HttpSession?, actor: Actor): SseEmitter {
        if (session == null) throw ApiFailure(HttpStatus.UNAUTHORIZED, "Order session required")
        val sessionId = session.id
        if (subscriptions.size >= 64 || subscriptions.values.count { it.sessionId == sessionId } >= 4) {
            throw ApiFailure(HttpStatus.TOO_MANY_REQUESTS, "Too many open chat connections")
        }
        val emitter = SseEmitter(30 * 60 * 1000L)
        val subscription = Subscription(UUID.randomUUID(), orderId, session, sessionId, actor, emitter, after)
        emitter.onCompletion { subscriptions.remove(subscription.id) }
        emitter.onTimeout { close(subscription) }
        emitter.onError { subscriptions.remove(subscription.id) }
        subscriptions[subscription.id] = subscription
        schedule(subscription)
        return emitter
    }

    @TransactionalEventListener
    fun messageStored(event: MessageStored) {
        subscriptions.values.filter { it.orderId == event.orderId }.forEach(::schedule)
    }

    @Scheduled(fixedDelay = 20_000)
    fun heartbeat() {
        // Catch up from PostgreSQL even if a notification was missed.
        subscriptions.values.forEach(::schedule)
    }

    private fun schedule(subscription: Subscription) {
        subscription.pending.set(true)
        if (!subscriptions.containsKey(subscription.id) || !subscription.running.compareAndSet(false, true)) return
        try {
            executor.execute { deliver(subscription) }
        } catch (_: RejectedExecutionException) {
            subscription.running.set(false)
            close(subscription)
        }
    }

    private fun deliver(subscription: Subscription) {
        try {
            do {
                subscription.pending.set(false)
                if (!subscriptions.containsKey(subscription.id)) return
                if (!sessionIsValid(subscription)) {
                    subscription.emitter.send(SseEmitter.event().name("session.expired").data("reauthenticate"))
                    close(subscription)
                    return
                }
                val batch = messages.after(subscription.orderId, subscription.cursor)
                for (message in batch) {
                    subscription.emitter.send(
                        SseEmitter.event().id(message.sequence.toString()).name("message.created")
                            .data(message, MediaType.APPLICATION_JSON)
                    )
                    subscription.cursor = message.sequence
                }
                if (batch.size == 100) subscription.pending.set(true)
                if (batch.isEmpty()) subscription.emitter.send(SseEmitter.event().comment("keepalive"))
            } while (subscription.pending.get())
        } catch (_: IOException) {
            // The servlet container completes a failed write.
            subscriptions.remove(subscription.id)
        } catch (error: RuntimeException) {
            if (subscriptions.remove(subscription.id) != null) subscription.emitter.completeWithError(error)
        } finally {
            subscription.running.set(false)
            if (subscription.pending.get()) schedule(subscription)
        }
    }

    private fun sessionIsValid(subscription: Subscription): Boolean = try {
        val session = subscription.session
        val context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
            as? SecurityContext
        val inactivityLimit = session.maxInactiveInterval.toLong() * 1000
        val lastActivity = maxOf(session.lastAccessedTime, subscription.openedAt)
        session.id == subscription.sessionId && context?.authentication?.principal == subscription.actor &&
            (inactivityLimit <= 0 || System.currentTimeMillis() - lastActivity < inactivityLimit) &&
            staffAccounts.sessionIsActive(subscription.actor)
    } catch (_: IllegalStateException) {
        false
    }

    private fun close(subscription: Subscription) {
        if (subscriptions.remove(subscription.id) != null) subscription.emitter.complete()
    }

    private class Subscription(
        val id: UUID,
        val orderId: UUID,
        val session: HttpSession,
        val sessionId: String,
        val actor: Actor,
        val emitter: SseEmitter,
        var cursor: Long
    ) {
        val openedAt = System.currentTimeMillis()
        val pending = AtomicBoolean()
        val running = AtomicBoolean()
    }
}
