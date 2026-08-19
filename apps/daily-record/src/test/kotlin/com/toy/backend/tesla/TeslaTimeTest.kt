package com.toy.backend.tesla

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

/**
 * 범위 계산만 본다 — `toUtc`·`toKst`는 기존 코드가 이미 쓰고 있고 여기서 바꾸지 않는다.
 *
 * **시각을 인자로 받는 순수 함수라 못 박을 수 있다.** `nowKst()`를 직접 부르는 함수였다면
 * 자정 근처에서만 깨지는 테스트가 됐을 것이다.
 */
class TeslaTimeTest :
    BehaviorSpec({
        Given("days=7로 범위를 계산할 때") {
            val now = LocalDateTime.of(2026, 8, 19, 12, 34, 56)
            val (from, to) = TeslaTime.timelineWindowKst(7, now)

            // 앱이 하루에 한 행씩 그린다. 범위가 임의 시각에서 시작하면 첫 행이 반쪽이 된다.
            Then("시작은 KST 오늘 자정에서 6일을 뺀 자정이다") {
                from shouldBe LocalDateTime.of(2026, 8, 13, 0, 0)
            }

            Then("끝은 요청 시각 그대로다") {
                to shouldBe now
            }
        }

        // days=1이면 오늘 하루만 본다 — 자정에서 0일을 뺀다.
        Given("days=1로 범위를 계산할 때") {
            val now = LocalDateTime.of(2026, 8, 19, 12, 34, 56)

            Then("시작이 오늘 자정이다") {
                TeslaTime.timelineWindowKst(1, now).first shouldBe LocalDateTime.of(2026, 8, 19, 0, 0)
            }
        }

        // 달을 거스르는 경계다. LocalDate 산술이 알아서 하지만 못 박아 둔다.
        Given("월초에 days=30으로 범위를 계산할 때") {
            val now = LocalDateTime.of(2026, 3, 2, 9, 0)

            Then("시작이 전달로 넘어간다") {
                TeslaTime.timelineWindowKst(30, now).first shouldBe LocalDateTime.of(2026, 2, 1, 0, 0)
            }
        }
    })
