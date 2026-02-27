plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

tasks.named<Jar>("jar") {
    enabled = true
}

dependencies {
    api(project(":common-core"))
    api(libs.spring.boot.starter.security)
    api("org.springframework.security:spring-security-oauth2-jose")
    testFixturesImplementation(testFixtures(project(":common-core")))
    testFixturesImplementation(libs.spring.test)
    testFixturesImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.mockk)
}
