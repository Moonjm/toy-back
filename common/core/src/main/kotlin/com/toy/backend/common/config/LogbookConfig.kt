package com.toy.backend.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.zalando.logbook.HttpRequest
import org.zalando.logbook.Logbook
import org.zalando.logbook.core.Conditions
import org.zalando.logbook.core.DefaultHttpLogWriter
import org.zalando.logbook.core.DefaultSink
import org.zalando.logbook.core.HeaderFilters
import org.zalando.logbook.json.JacksonJsonFieldBodyFilter
import org.zalando.logbook.json.JsonHttpLogFormatter
import java.util.function.Predicate

@Configuration
class LogbookConfig {
    companion object {
        private val EXCLUDE_PATHS =
            listOf(
                "/actuator/",
                "/swagger-ui/",
                "/api-docs/",
            )
    }

    @Bean
    fun logbook(): Logbook {
        val excludePredicate: Predicate<HttpRequest> =
            Predicate { req ->
                val path = req.path
                EXCLUDE_PATHS.any { path.contains(it) }
            }

        return Logbook
            .builder()
            .condition(Conditions.exclude(listOf(excludePredicate)))
            .headerFilter(
                HeaderFilters.replaceHeaders(
                    { name, _ ->
                        name.equals("Cookie", ignoreCase = true) ||
                            name.equals("Set-Cookie", ignoreCase = true) ||
                            name.equals("X-API-Key", ignoreCase = true)
                    },
                    "<obfuscated>",
                ),
            ).bodyFilter(
                // key: API 키 발급 응답의 원본 키 — 1회 노출 보장이 로그로 깨지지 않게 한다
                JacksonJsonFieldBodyFilter(
                    listOf("password", "key"),
                    "<obfuscated>",
                ),
            ).sink(
                DefaultSink(
                    JsonHttpLogFormatter(),
                    DefaultHttpLogWriter(),
                ),
            ).build()
    }
}
