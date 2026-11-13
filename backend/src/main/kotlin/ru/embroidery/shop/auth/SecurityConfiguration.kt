package ru.embroidery.shop.auth

import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import ru.embroidery.shop.missingOrder
import java.io.Serializable
import java.util.UUID

enum class Role { CUSTOMER, STAFF }

data class Actor(val email: String, val role: Role, val orderId: UUID? = null) : Serializable {
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
    fun security(
        http: HttpSecurity,
        contexts: HttpSessionSecurityContextRepository,
        tokens: HttpSessionCsrfTokenRepository
    ): SecurityFilterChain = http
        .securityContext { it.securityContextRepository(contexts) }
        .csrf { it.csrfTokenRepository(tokens) }
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
            it.requestMatchers(HttpMethod.GET, "/api/catalogs", "/api/catalogs/**", "/api/catalog-images/*").permitAll()
            it.requestMatchers(HttpMethod.HEAD, "/api/catalogs", "/api/catalogs/**", "/api/catalog-images/*").permitAll()
            it.requestMatchers(
                HttpMethod.POST, "/api/orders/drafts", "/api/orders/*/access/code", "/api/orders/*/access/verify",
                "/api/staff/access/code", "/api/staff/access/verify"
            ).permitAll()
            it.requestMatchers("/api/staff/**").hasRole("STAFF")
            it.requestMatchers("/api/orders/**").hasAnyRole("CUSTOMER", "STAFF")
            it.anyRequest().denyAll()
        }
        .build()
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
        val authentication = UsernamePasswordAuthenticationToken.authenticated(
            actor, null, listOf(SimpleGrantedAuthority("ROLE_${actor.role}"))
        )
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
