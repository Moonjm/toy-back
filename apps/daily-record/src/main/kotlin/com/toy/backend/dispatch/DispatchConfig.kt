package com.toy.backend.dispatch

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 엄마 근무 주기 설정을 등록한다. **vision 설정과 분리한다** — 엄마 주기는 사진 인식과
 * 아무 관계가 없어 `DispatchVisionConfig`에 얹어 두면 어디서 켜지는지 찾기 어렵다.
 */
@Configuration
@EnableConfigurationProperties(MotherPatternProperties::class)
class DispatchConfig
