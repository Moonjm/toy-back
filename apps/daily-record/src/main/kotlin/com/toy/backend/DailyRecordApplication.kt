package com.toy.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
@EnableScheduling
// 사진 인식·피드백 생성을 응답에서 떼어낸다. 큐는 쓰지 않는다 — 사용자 2명·하루 수십 건 규모다.
@EnableAsync
class DailyRecordApplication

fun main(args: Array<String>) {
    runApplication<DailyRecordApplication>(*args)
}
