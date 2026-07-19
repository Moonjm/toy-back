package com.toy.backend.auth.security

import org.springframework.web.filter.OncePerRequestFilter

/**
 * 앱 모듈이 자체 인증 수단(API 키 등)을 시큐리티 필터 체인에 추가할 때 상속하는 베이스.
 * 이 타입의 빈은 SecurityConfig가 UsernamePasswordAuthenticationFilter 앞에 자동 등록한다.
 */
abstract class AdditionalAuthFilter : OncePerRequestFilter()
