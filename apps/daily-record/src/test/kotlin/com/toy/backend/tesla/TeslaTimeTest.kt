package com.toy.backend.tesla

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.time.YearMonth

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

        Given("monthElapsedMinutes — 달의 경과 분") {
            val start = LocalDateTime.of(2026, 3, 1, 0, 0)

            When("이미 끝난 달이면") {
                Then("그 달 전체 분이다") {
                    // 2026-04는 30일 = 43,200분
                    TeslaTime.monthElapsedMinutes(YearMonth.of(2026, 4), start, LocalDateTime.of(2026, 8, 20, 15, 0)) shouldBe 43_200
                }
            }

            When("진행 중인 달이면") {
                Then("지금까지만 센다") {
                    // 8/1 00:00 ~ 8/20 15:00 = 19일 15시간 = 28,260분
                    TeslaTime.monthElapsedMinutes(YearMonth.of(2026, 8), start, LocalDateTime.of(2026, 8, 20, 15, 0)) shouldBe 28_260
                }
            }

            When("범위가 달 도중에 시작하면") {
                Then("범위 시작부터 센다") {
                    // 3/1 00:00 시작이 아니라 3/10 12:00 시작이면 3/10 12:00 ~ 3/31 24:00 = 21일 12시간
                    TeslaTime.monthElapsedMinutes(
                        YearMonth.of(2026, 3),
                        LocalDateTime.of(2026, 3, 10, 12, 0),
                        LocalDateTime.of(2026, 8, 20, 15, 0),
                    ) shouldBe 30_960
                }
            }

            When("범위 밖의 달이면") {
                Then("0이다 — 음수가 나오지 않는다") {
                    TeslaTime.monthElapsedMinutes(YearMonth.of(2025, 1), start, LocalDateTime.of(2026, 8, 20, 15, 0)) shouldBe 0
                    TeslaTime.monthElapsedMinutes(YearMonth.of(2027, 1), start, LocalDateTime.of(2026, 8, 20, 15, 0)) shouldBe 0
                }
            }
        }

        Given("weekdaySpans — 요일별 등장 수와 경과 분") {
            When("정확히 한 주면") {
                val spans =
                    TeslaTime.weekdaySpans(
                        LocalDateTime.of(2026, 8, 10, 0, 0), // 월요일
                        LocalDateTime.of(2026, 8, 17, 0, 0), // 다음 월요일 00:00
                    )

                Then("일곱 요일이 한 번씩, 하루씩이다") {
                    spans.keys shouldBe (1..7).toSet()
                    spans.values.map { it.occurrences }.toSet() shouldBe setOf(1)
                    spans.values.map { it.elapsedMin }.toSet() shouldBe setOf(1_440)
                }
            }

            When("오늘이 아직 안 끝났으면") {
                val spans =
                    TeslaTime.weekdaySpans(
                        LocalDateTime.of(2026, 8, 17, 0, 0), // 월요일 00:00
                        LocalDateTime.of(2026, 8, 20, 15, 0), // 목요일 15:00
                    )

                Then("오늘도 한 번으로 세되 경과 분은 지금까지다") {
                    spans[4]!!.occurrences shouldBe 1 // 목요일
                    spans[4]!!.elapsedMin shouldBe 900 // 15시간
                    spans[1]!!.elapsedMin shouldBe 1_440 // 월요일은 온전히 지났다
                }
            }

            When("끝이 시작보다 이르면") {
                Then("빈 맵이 아니라 전부 0이다") {
                    val spans =
                        TeslaTime.weekdaySpans(
                            LocalDateTime.of(2026, 8, 20, 0, 0),
                            LocalDateTime.of(2026, 8, 19, 0, 0),
                        )
                    spans.keys shouldBe (1..7).toSet()
                    spans.values.map { it.occurrences }.toSet() shouldBe setOf(0)
                }
            }
        }
    })
