package com.toy.backend.auth.security

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private val log = KotlinLogging.logger {}

@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
) : OncePerRequestFilter() {
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token =
            request.cookies
                ?.firstOrNull { it.name == "access_token" }
                ?.value

        if (token.isNullOrBlank()) {
            filterChain.doFilter(request, response)
            return
        }

        runCatching {
            val claims = jwtService.parseClaims(token)
            val username = claims.subject ?: return@runCatching
            val authority = claims.getStringClaim("authority") ?: return@runCatching
            if (SecurityContextHolder.getContext().authentication == null) {
                val authorities = listOf(SimpleGrantedAuthority(authority))
                val auth =
                    UsernamePasswordAuthenticationToken(username, null, authorities).apply {
                        details = WebAuthenticationDetailsSource().buildDetails(request)
                    }
                SecurityContextHolder.getContext().authentication = auth
            }
        }.onFailure { e ->
            log.warn(e) { "JWT 인증 실패 [${request.requestURI}]" }
        }
        filterChain.doFilter(request, response)
    }
}
