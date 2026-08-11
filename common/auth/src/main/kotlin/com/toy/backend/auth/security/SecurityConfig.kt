package com.toy.backend.auth.security

import com.toy.backend.auth.security.JwtAuthFilter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableMethodSecurity
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
    additionalAuthFilters: ObjectProvider<AdditionalAuthFilter>,
    publicEndpoints: ObjectProvider<PublicEndpoint>,
) {
    private val additionalAuthFilters: List<AdditionalAuthFilter> = additionalAuthFilters.orderedStream().toList()
    private val publicEndpoints: List<PublicEndpoint> = publicEndpoints.orderedStream().toList()

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/auth/**")
                    .permitAll()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**")
                    .permitAll()

                // 앱 모듈이 기여한 무인증 경로. anyRequest() 앞에 와야 적용된다.
                publicEndpoints.forEach { endpoint ->
                    val method = endpoint.method()
                    if (method == null) {
                        it.requestMatchers(endpoint.pattern()).permitAll()
                    } else {
                        it.requestMatchers(method, endpoint.pattern()).permitAll()
                    }
                }

                it
                    .anyRequest()
                    .authenticated()
            }.exceptionHandling {
                it.authenticationEntryPoint(HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED))
            }.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        additionalAuthFilters.forEach { filter ->
            http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter::class.java)
        }

        return http.build()
    }
}
