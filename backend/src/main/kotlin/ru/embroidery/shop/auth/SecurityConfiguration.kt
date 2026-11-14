package ru.embroidery.shop.auth

import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import ru.embroidery.shop.missingOrder
import java.io.Serializable
import java.util.UUID

enum class Role { CUSTOMER, STAFF }

data class Actor(
    val email: String,
    val role: Role,
    val orderId: UUID? = null,
    val staffCredentialId: UUID? = null,
    val canManageStaff: Boolean = false
) : Serializable {
    fun requireOrder(id: UUID) {
        if (role != Role.STAFF && orderId != id) missingOrder()
    }
}

@Configuration
class SecurityConfiguration {
    @Bean
    fun securityContexts() = HttpSessionSecurityContextRepository()

    @Bean
    fun csrfTokens() = HttpSessionCsrfTokenRepository()

    @Bean
    fun staffAccessMailExecutor() = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 2
        queueCapacity = 16
        setThreadNamePrefix("staff-code-")
    }

    @Bean
    fun security(
        http: HttpSecurity,
        contexts: HttpSessionSecurityContextRepository,
        tokens: HttpSessionCsrfTokenRepository,
        staffAccounts: StaffAccounts
    ): SecurityFilterChain = http
        .securityContext { it.securityContextRepository(contexts) }
        .addFilterAfter(StaffSessionFilter(staffAccounts), SecurityContextHolderFilter::class.java)
        .csrf {
            it.csrfTokenRepository(tokens)
                .ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/payments/yookassa/webhook"))
        }
        .requestCache { it.disable() }
        .exceptionHandling {
            it.authenticationEntryPoint { _, response, _ -> response.sendError(401) }
            it.accessDeniedHandler { _, response, _ -> response.sendError(403) }
        }
        .logout {
            it.logoutUrl("/api/session/logout")
            it.logoutSuccessHandler(HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
        }
        .authorizeHttpRequests {
            it.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
            it.requestMatchers(HttpMethod.GET, "/actuator/health/liveness", "/actuator/health/readiness", "/api/csrf").permitAll()
            it.requestMatchers(HttpMethod.GET, "/api/storefront", "/api/catalogs", "/api/catalogs/**", "/api/catalog-images/*")
                .permitAll()
            it.requestMatchers(HttpMethod.HEAD, "/api/catalogs", "/api/catalogs/**", "/api/catalog-images/*").permitAll()
            it.requestMatchers(
                HttpMethod.POST, "/api/orders/drafts", "/api/orders/catalog-drafts",
                "/api/orders/*/access/code", "/api/orders/*/access/verify",
                "/api/staff/access/code", "/api/staff/access/verify", "/api/payments/yookassa/webhook"
            ).permitAll()
            it.requestMatchers("/api/staff/accounts", "/api/staff/accounts/**").hasAuthority("MANAGE_STAFF")
            it.requestMatchers("/api/staff/**").hasRole("STAFF")
            it.requestMatchers("/api/orders/**").hasAnyRole("CUSTOMER", "STAFF")
            it.anyRequest().denyAll()
        }
        .build()
}

private class StaffSessionFilter(private val accounts: StaffAccounts) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.getSession(false) != null) {
            val actor = SecurityContextHolder.getContext().authentication?.principal as? Actor
            if (actor?.role == Role.STAFF && !accounts.sessionIsActive(actor)) {
                request.getSession(false)?.invalidate()
                SecurityContextHolder.clearContext()
            }
        }
        chain.doFilter(request, response)
    }
}

@Component
class BrowserSessions(
    private val contexts: HttpSessionSecurityContextRepository,
    private val tokens: HttpSessionCsrfTokenRepository
) {
    fun start(actor: Actor, request: HttpServletRequest, response: HttpServletResponse) {
        request.getSession(true)
        request.changeSessionId()
        tokens.saveToken(null, request, response)
        val authorities = mutableListOf(SimpleGrantedAuthority("ROLE_${actor.role}"))
        if (actor.canManageStaff) authorities += SimpleGrantedAuthority("MANAGE_STAFF")
        val authentication = UsernamePasswordAuthenticationToken.authenticated(actor, null, authorities)
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        contexts.saveContext(context, request, response)
    }
}

@RestController
class CsrfController {
    @GetMapping("/api/csrf")
    fun csrf(token: CsrfToken) = mapOf("headerName" to token.headerName, "token" to token.token)
}
