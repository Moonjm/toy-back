package com.toy.backend.holidays

import com.toy.backend.auth.security.PublicEndpoint
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod

/**
 * **조회만** 무인증으로 연다. 갱신(`POST /holidays/{year}`)은 외부 공휴일 API를 호출하고
 * DB를 쓰므로 인증된 앱에서만 한다.
 * 응답은 공공데이터로 공개된 연도별 공휴일 날짜뿐이라 열어도 남는 것이 없다.
 */
@Configuration
class HolidayPublicEndpoints {
    @Bean
    fun holidayReadEndpoint(): PublicEndpoint =
        object : PublicEndpoint {
            override fun method(): HttpMethod = HttpMethod.GET

            override fun pattern(): String = "/holidays"
        }
}
