package com.toy.backend.auth.security

import org.springframework.http.HttpMethod

/**
 * 앱 모듈이 **인증 없이 열 경로**를 시큐리티 체인에 기여할 때 등록하는 빈.
 * `AdditionalAuthFilter`와 같은 확장 방식이다 — 앱별 경로를 `SecurityConfig`에
 * 하드코딩하면 `common-auth`가 앱을 알게 되고, 다른 앱에도 그 경로가 열린다.
 */
interface PublicEndpoint {
    /** null이면 모든 메서드 */
    fun method(): HttpMethod?

    fun pattern(): String
}
