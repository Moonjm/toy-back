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
    api(libs.aws.s3)
    testImplementation(testFixtures(project(":common-core")))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.mockk)
}
