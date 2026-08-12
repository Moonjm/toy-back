package com.toy.backend.dispatch

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import jakarta.validation.Validation
import java.time.LocalDate

/**
 * **잘못된 설정은 기동 시점에 걸려야 한다.** 이 값들이 틀리면 엄마가 조용히 매일 휴무로
 * 나오는데, 화면만으로는 「쉬는 날」인지 「설정이 틀린 것」인지 구분되지 않는다.
 *
 * 오프셋은 `Math.floorMod(elapsed, cycleDays)` 결과와 비교되므로 `0 until cycleDays` 밖의
 * 값은 영원히 일치하지 않는다 — 그 근무일이 소리 없이 사라진다.
 *
 * `@ConfigurationProperties`에 `@Validated`가 붙어 있어 Spring이 바인딩 직후 이 제약을
 * 돌린다. 여기서는 컨텍스트 없이 validator를 직접 만들어 같은 제약을 확인한다.
 */
class MotherPatternPropertiesTest :
    BehaviorSpec({
        val validator = Validation.buildDefaultValidatorFactory().validator

        fun violations(
            cycleDays: Int,
            workingOffsets: List<Int>,
        ) = validator
            .validate(
                MotherPatternProperties(
                    cycleDays = cycleDays,
                    workingOffsets = workingOffsets,
                    anchorDate = LocalDate.of(2026, 8, 8),
                ),
            ).map { it.propertyPath.toString() }
            // 위반 순서는 보장되지 않는다.
            .sorted()

        Given("기본값과 같은 정상 설정") {
            Then("통과한다") {
                violations(cycleDays = 3, workingOffsets = listOf(1, 2)) shouldBe emptyList()
            }

            Then("경계값 0과 cycleDays - 1도 통과한다") {
                violations(cycleDays = 3, workingOffsets = listOf(0, 2)) shouldBe emptyList()
            }
        }

        Given("cycleDays 이상인 오프셋") {
            Then("걸린다 — 5는 3일 주기에서 영원히 도달하지 않는다") {
                violations(cycleDays = 3, workingOffsets = listOf(1, 5)) shouldBe listOf("offsetsWithinCycle")
            }
        }

        Given("음수 오프셋") {
            Then("걸린다 — floorMod 결과는 음수가 되지 않는다") {
                violations(cycleDays = 3, workingOffsets = listOf(-1, 1)) shouldBe listOf("offsetsWithinCycle")
            }
        }

        Given("빈 오프셋 목록") {
            Then("걸린다 — 매일 휴무는 주기 설정이 아니라 설정을 빠뜨린 것에 가깝다") {
                violations(cycleDays = 3, workingOffsets = emptyList()) shouldBe listOf("offsetsWithinCycle")
            }
        }

        Given("cycleDays가 0인 설정") {
            Then("걸린다 — 그대로 두면 Math.floorMod가 무인증 엔드포인트에서 500을 낸다") {
                // @field:Min(1)이 cycleDays를, @get:AssertTrue가 오프셋 범위를 함께 잡는다.
                violations(cycleDays = 0, workingOffsets = listOf(1, 2)) shouldBe
                    listOf("cycleDays", "offsetsWithinCycle").sorted()
            }
        }
    })
