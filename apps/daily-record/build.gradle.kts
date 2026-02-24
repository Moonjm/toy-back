plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

dependencies {
    implementation(project(":common-core"))
    implementation(project(":common-auth"))
    testImplementation(testFixtures(project(":common-core")))
    testImplementation(testFixtures(project(":common-auth")))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.mockk)
}
