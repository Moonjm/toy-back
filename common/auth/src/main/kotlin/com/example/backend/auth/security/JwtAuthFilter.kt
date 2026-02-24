package com.example.backend.auth.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
) : OncePerRequestFilter() {
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
            val authority = claims["authority"] as? String ?: return@runCatching
            if (SecurityContextHolder.getContext().authentication == null) {
                val authorities = listOf(SimpleGrantedAuthority(authority))
                val auth =
                    UsernamePasswordAuthenticationToken(username, null, authorities).apply {
                        details = WebAuthenticationDetailsSource().buildDetails(request)
                    }
                SecurityContextHolder.getContext().authentication = auth
            }
        }
        filterChain.doFilter(request, response)
    }
}
