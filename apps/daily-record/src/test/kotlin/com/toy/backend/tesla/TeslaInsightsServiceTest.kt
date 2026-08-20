package com.toy.backend.tesla

import com.toy.backend.common.exception.CustomException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.YearMonth

/**
 * 리포지토리를 목으로 두고 **서비스가 하는 일만** 본다 — 범위 계산, 빈 자리 채움,
 * 뺄셈(정지 시간), KST 되돌리기다. 집계·정렬·KST 자르기는 SQL의 일이라 여기서 안 본다.
 */
class TeslaInsightsServiceTest :
    BehaviorSpec({
        val insightsRepository = mockk<TeslaInsightsRepository>()
        val vehicleRepository = mockk<TeslaVehicleRepository>()
        val service = TeslaInsightsService(insightsRepository, vehicleRepository)

        fun stubEmpty() {
            every { insightsRepository.firstDriveMonth() } returns null
            every { vehicleRepository.driveTemperatureBuckets(any(), any()) } returns emptyList()
            every { vehicleRepository.driveTimes(any(), any()) } returns emptyList()
            every { vehicleRepository.driveDistanceBuckets(any(), any()) } returns emptyList()
            every { vehicleRepository.drivePlaces(any(), any()) } returns emptyList()
            every { vehicleRepository.driveStats() } returns DriveStatsRow(null, BigDecimal.ZERO, 0)
            every { vehicleRepository.carEfficiency() } returns BigDecimal("0.168")
            every { insightsRepository.driveMonthly(any(), any()) } returns emptyList()
            every { insightsRepository.chargeMonthly(any(), any()) } returns emptyList()
            every { insightsRepository.parkDrainMonthly(any(), any()) } returns emptyList()
        }

        Given("insights — 범위 검증") {
            When("months가 61이면") {
                Then("400이다") {
                    shouldThrow<CustomException> { service.insights(61) }
                }
            }

            When("months가 음수면") {
                Then("400이다") {
                    shouldThrow<CustomException> { service.insights(-1) }
                }
            }

            When("months가 0이면") {
                Then("전체 기간으로 해석돼 통과한다") {
                    stubEmpty()
                    service.insights(0).months shouldBe 0
                }
            }
        }

        Given("insights — 버킷 자리 채움") {
            When("행이 하나도 없으면") {
                Then("온도 다섯 칸·거리 다섯 칸이 0으로 자리를 지킨다") {
                    stubEmpty()
                    val response = service.insights(12)

                    response.temperatureBuckets.size shouldBe 5
                    response.temperatureBuckets.first().fromC shouldBe null
                    response.temperatureBuckets.first().driveCount shouldBe 0
                    response.distanceBuckets.size shouldBe 5
                    response.distanceBuckets.last().toKm shouldBe null
                }
            }

            When("지오펜스도 주소도 없으면") {
                Then("places가 null이 아니라 빈 배열이다") {
                    stubEmpty()
                    service.insights(12).places shouldBe emptyList()
                }
            }
        }

        Given("insights — 기존 계약을 그대로 싣는다") {
            When("주행 통계가 오면") {
                Then("이름을 바꾸지 않고 그대로 낸다") {
                    stubEmpty()
                    every { vehicleRepository.driveStats() } returns
                        DriveStatsRow(maxSpeedKmh = 138, totalDistanceKm = BigDecimal("107258.4"), recordedMonths = 59)

                    val response = service.insights(12)

                    response.maxSpeedKmh shouldBe 138
                    response.totalDistanceKm shouldBe BigDecimal("107258.4")
                    response.recordedMonths shouldBe 59
                    response.efficiencyKwhPerKm shouldBe BigDecimal("0.168")
                }
            }
        }

        Given("monthly — 달 축") {
            When("기록이 없는 달이 섞여 있으면") {
                Then("자리를 지키고 값이 null이다") {
                    stubEmpty()
                    val response = service.insights(3)

                    response.monthly.size shouldBe 3
                    response.monthly.first().distanceKm shouldBe null
                    response.monthly.first().driveCount shouldBe null
                    response.monthly.first().chargeCount shouldBe null
                }
            }

            When("오래된 것부터 오는지") {
                Then("첫 칸이 가장 옛 달이다") {
                    stubEmpty()
                    val response = service.insights(3)
                    response.monthly.map { it.yearMonth } shouldBe response.monthly.map { it.yearMonth }.sorted()
                }
            }

            When("팬텀 드레인 표본이 하나도 없으면") {
                Then("null이 아니라 0으로 온다 — 앱이 막대를 안 그린다") {
                    stubEmpty()
                    service.insights(3).monthly.forEach {
                        it.parkDrainSamples shouldBe 0
                        it.parkDrainRatedKm shouldBe BigDecimal.ZERO
                    }
                }
            }
        }

        Given("monthly — 정지 시간") {
            When("주행과 충전이 있으면") {
                Then("그 달 경과 분에서 뺀 값이다") {
                    stubEmpty()
                    val month = YearMonth.from(TeslaTime.nowKst())
                    every { insightsRepository.driveMonthly(any(), any()) } returns
                        listOf(InsightsDriveMonthRow(month, 10, BigDecimal("100.0"), 600, BigDecimal("110.0")))
                    every { insightsRepository.chargeMonthly(any(), any()) } returns
                        listOf(InsightsChargeMonthRow(month, 2, BigDecimal("50.0"), BigDecimal("53.0"), BigDecimal("12000"), 300))

                    val row = service.insights(1).monthly.single()
                    val elapsed = TeslaTime.monthElapsedMinutes(month, month.atDay(1).atStartOfDay(), TeslaTime.nowKst())

                    // 서비스와 테스트가 TeslaTime.nowKst()를 따로 읽어 분 경계에서 ±1분 어긋날 수 있다.
                    val diff = kotlin.math.abs(row.idleMin - (elapsed - 900))
                    diff shouldBeLessThanOrEqual 1
                }
            }

            When("주행·충전이 경과 시간보다 길면") {
                Then("음수가 아니라 0이다") {
                    stubEmpty()
                    val month = YearMonth.from(TeslaTime.nowKst())
                    every { insightsRepository.driveMonthly(any(), any()) } returns
                        listOf(InsightsDriveMonthRow(month, 10, BigDecimal("100.0"), 9_999_999, BigDecimal("110.0")))

                    service
                        .insights(1)
                        .monthly
                        .single()
                        .idleMin shouldBe 0
                }
            }
        }
    })
