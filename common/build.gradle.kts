plugins {
    `java-library`
    `java-test-fixtures`
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

tasks.named<Jar>("jar") {
    enabled = true
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-webflux")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.1")
    api("org.jetbrains.kotlin:kotlin-reflect")
    api("tools.jackson.module:jackson-module-kotlin")
    api("io.github.oshai:kotlin-logging:7.0.12")
    api("io.jsonwebtoken:jjwt-api:0.12.6")
    api("com.linecorp.kotlin-jdsl:jpql-dsl:3.5.5")
    api("com.linecorp.kotlin-jdsl:jpql-render:3.5.5")
    api("com.linecorp.kotlin-jdsl:spring-data-jpa-support:3.5.5")
    api("software.amazon.awssdk:s3:2.31.1")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    runtimeOnly("org.postgresql:postgresql")
    testFixturesImplementation("org.springframework:spring-test")
    testFixturesImplementation("io.kotest:kotest-runner-junit5:6.1.3")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.kotest:kotest-runner-junit5:6.1.3")
    testImplementation("io.mockk:mockk:1.14.2")
}
