package com.toy.backend.dispatch

import com.toy.backend.auth.security.PublicEndpoint
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod

/**
 * **조회만** 무인증으로 연다. 업로드·검수·저장은 인증된 앱에서만 한다.
 * 응답에 실명·차량번호가 없으므로(`ShiftDayResponse`) 공개해도 남는 것은 근무 여부와 순번뿐이다.
 */
@Configuration
class DispatchPublicEndpoints {
    @Bean
    fun dispatchShiftReadEndpoint(): PublicEndpoint =
        object : PublicEndpoint {
            override fun method(): HttpMethod = HttpMethod.GET

            override fun pattern(): String = "/dispatch/shifts"
        }
}
