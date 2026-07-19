package com.toy.backend.ledger.apikeys

import com.toy.backend.auth.security.AdditionalAuthFilter
import com.toy.backend.common.utils.TokenHasher
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/** 단축어 전용: X-API-Key 헤더로 /inbound 요청을 인증한다. */
@Component
class ApiKeyAuthFilter(
    private val repository: ApiKeyRepository,
) : AdditionalAuthFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val key = request.getHeader(API_KEY_HEADER)
        if (request.requestURI == INBOUND_PATH &&
            !key.isNullOrBlank() &&
            SecurityContextHolder.getContext().authentication == null
        ) {
            repository.findWithUserByKeyHash(TokenHasher.sha256(key))?.let { apiKey ->
                val authorities = listOf(SimpleGrantedAuthority(apiKey.user.authority.name))
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(apiKey.user.username, null, authorities)
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        private const val API_KEY_HEADER = "X-API-Key"
        private const val INBOUND_PATH = "/inbound"
    }
}
