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
        Given("hours=24로 범위를 계산할 때") {
            val now = LocalDateTime.of(2026, 8, 19, 13, 5)
            val (from, to) = TeslaTime.timelineWindowKst(24, now)

            // 앱이 24시간을 한 줄로 그리고 오른쪽 끝이 「지금」이다.
            Then("끝은 요청 시각 그대로다") {
                to shouldBe now
            }

            Then("시작은 끝에서 24시간을 뺀 시각이다") {
                from shouldBe LocalDateTime.of(2026, 8, 18, 13, 5)
            }

            // **자정 스냅이 없다는 것을 못 박는다.** 초판은 여기서 2026-08-18T00:00을 냈다 —
            // 그 정렬이 남아 있으면 오른쪽 끝이 「지금」이 아니게 된다.
            Then("시작이 자정으로 당겨지지 않는다") {
                from.toLocalTime() shouldBe now.toLocalTime()
            }
        }

        // 날짜를 거스르는 경계다. 자정을 넘어가도 시·분이 그대로 유지된다.
        Given("자정 직후에 hours=1로 범위를 계산할 때") {
            val now = LocalDateTime.of(2026, 8, 19, 0, 30)

            Then("시작이 전날로 넘어간다") {
                TeslaTime.timelineWindowKst(1, now).first shouldBe LocalDateTime.of(2026, 8, 18, 23, 30)
            }
        }

        // 상한이다. 168시간 = 7일.
        Given("hours=168로 범위를 계산할 때") {
            val now = LocalDateTime.of(2026, 8, 19, 13, 5)

            Then("시작이 7일 전 같은 시각이다") {
                TeslaTime.timelineWindowKst(168, now).first shouldBe LocalDateTime.of(2026, 8, 12, 13, 5)
            }
        }
    })
