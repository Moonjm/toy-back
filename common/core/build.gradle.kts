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
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.data.jpa)
    api(libs.spring.boot.starter.validation)
    api(libs.springdoc.openapi)
    api(libs.kotlin.reflect)
    api(libs.jackson.module.kotlin)
    api(libs.kotlin.logging)
    api(libs.kotlin.jdsl.jpql.dsl)
    api(libs.kotlin.jdsl.jpql.render)
    api(libs.kotlin.jdsl.spring.data.jpa)
    runtimeOnly(libs.postgresql)
    testFixturesImplementation(libs.spring.test)
    testFixturesImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.mockk)
}
