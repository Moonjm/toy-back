package com.toy.backend.diet.llm

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class OpenRouterConfigTest :
    BehaviorSpec({
        // OpenRouterConfig는 WebClient를 직접 만들므로 WebClient 자동설정이 필요 없다.
        val runner =
            ApplicationContextRunner()
                .withUserConfiguration(TestPropertiesConfig::class.java, OpenRouterConfig::class.java)

        Given("openrouter.api-key가 비어 있으면") {
            When("컨텍스트를 띄우면") {
                Then("OpenRouterClient 빈이 없다 — 키 없이 로컬을 띄울 수 있어야 한다") {
                    runner.withPropertyValues("openrouter.api-key=").run { context ->
                        context.getBeanNamesForType(OpenRouterClient::class.java).size shouldBe 0
                    }
                }
            }

            When("프로퍼티 자체가 없으면") {
                Then("역시 빈이 없다") {
                    runner.run { context ->
                        context.getBeanNamesForType(OpenRouterClient::class.java).size shouldBe 0
                    }
                }
            }
        }

        Given("openrouter.api-key가 설정되면") {
            When("컨텍스트를 띄우면") {
                Then("OpenRouterClient 빈이 등록된다") {
                    runner.withPropertyValues("openrouter.api-key=sk-test").run { context ->
                        context.getBeanNamesForType(OpenRouterClient::class.java).size shouldBe 1
                    }
                }
            }
        }
    })

@Configuration
@EnableConfigurationProperties(OpenRouterProperties::class)
private class TestPropertiesConfig
