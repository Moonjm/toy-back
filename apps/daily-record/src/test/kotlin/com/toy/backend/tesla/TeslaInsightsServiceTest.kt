package com.toy.backend.tesla

import com.toy.backend.common.exception.CustomException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.LocalDateTime
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
            every { insightsRepository.weekdayDrives(any(), any()) } returns emptyList()
            every { insightsRepository.weekdayCharges(any(), any()) } returns emptyList()
            every { insightsRepository.chargeTimes(any(), any()) } returns emptyList()
            every { insightsRepository.speedBuckets(any(), any()) } returns emptyList()
            every { insightsRepository.speedEnergyBuckets(any(), any()) } returns emptyList()
            every { insightsRepository.chargeLevelBuckets(any(), any()) } returns emptyList()
            every { insightsRepository.chargers(any(), any()) } returns emptyList()
            every { insightsRepository.regions(any(), any()) } returns RegionRow(0, 0, 0)
            every { insightsRepository.driveRecords() } returns emptyList()
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

            When("months가 0이고 첫 주행 달이 있으면") {
                Then("monthly가 그 달부터 이번 달까지 자리를 채운다") {
                    stubEmpty()
                    // stubEmpty()가 늘 null을 줘서 이 경로(coerceAtMost, 첫 주행 달부터 N칸)가
                    // 그동안 한 번도 안 돌았다 — 앱의 「전체」 기간 칩이 정확히 이 경로다.
                    val thisMonth = YearMonth.from(TeslaTime.nowKst())
                    val firstMonth = thisMonth.minusMonths(3)
                    every { insightsRepository.firstDriveMonth() } returns firstMonth

                    val monthly = service.insights(0).monthly

                    monthly.size shouldBe 4
                    monthly.first().yearMonth shouldBe firstMonth
                    monthly.last().yearMonth shouldBe thisMonth
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

        Given("weekday — 요일 축") {
            When("행이 하나도 없으면") {
                Then("일곱 요일이 1(월)부터 자리를 지킨다") {
                    stubEmpty()
                    val weekday = service.insights(12).weekday

                    weekday.size shouldBe 7
                    weekday.map { it.weekday } shouldBe (1..7).toList()
                    weekday.first().driveCount shouldBe 0
                }
            }

            When("범위가 여러 달에 걸치면") {
                Then("occurrences 합이 범위의 날 수와 같다 — 어느 날도 두 요일에 세어지지 않는다") {
                    stubEmpty()
                    // 범위 시작이 달 1일이라 정확한 주 수를 못 박을 수 없다.
                    // 대신 합이 범위의 날 수와 같은지 본다 — 어느 날도 두 요일에 세어지지 않는다.
                    val weekday = service.insights(3).weekday
                    weekday.sumOf { it.occurrences } shouldBe
                        TeslaTime
                            .weekdaySpans(
                                YearMonth
                                    .from(TeslaTime.nowKst())
                                    .minusMonths(2)
                                    .atDay(1)
                                    .atStartOfDay(),
                                TeslaTime.nowKst(),
                            ).values
                            .sumOf { it.occurrences }
                }
            }

            When("주행·충전이 있으면") {
                Then("idleMin이 그 요일 경과 분에서 뺀 값이다") {
                    stubEmpty()
                    every { insightsRepository.weekdayDrives(any(), any()) } returns
                        listOf(WeekdayDriveRow(1, 5, BigDecimal("80.0"), 200))
                    every { insightsRepository.weekdayCharges(any(), any()) } returns
                        listOf(WeekdayChargeRow(1, 100))

                    val monday = service.insights(1).weekday.single { it.weekday == 1 }
                    val span =
                        TeslaTime
                            .weekdaySpans(YearMonth.from(TeslaTime.nowKst()).atDay(1).atStartOfDay(), TeslaTime.nowKst())
                            .getValue(1)

                    // 서비스와 테스트가 TeslaTime.nowKst()를 따로 읽어 분 경계에서 ±1분 어긋날 수 있다.
                    val diff = kotlin.math.abs(monday.idleMin - (span.elapsedMin - 300).coerceAtLeast(0))
                    diff shouldBeLessThanOrEqual 1
                    monday.occurrences shouldBe span.occurrences
                }
            }
        }

        Given("속도 버킷") {
            When("행이 하나도 없으면") {
                Then("최고속도 일곱 칸·평균속도 다섯 칸이 자리를 지킨다") {
                    stubEmpty()
                    val response = service.insights(12)

                    response.speedBuckets.size shouldBe 7
                    response.speedBuckets.last().fromKmh shouldBe 120
                    response.speedBuckets.last().toKmh shouldBe null
                    response.speedEnergyBuckets.size shouldBe 5
                    response.speedEnergyBuckets.last().fromKmh shouldBe 80
                    response.speedEnergyBuckets.last().toKmh shouldBe null
                }
            }

            When("행이 오면") {
                Then("그 칸에 값이 들어간다") {
                    stubEmpty()
                    every { insightsRepository.speedBuckets(any(), any()) } returns listOf(SpeedBucketRow(6, 372))

                    service
                        .insights(12)
                        .speedBuckets
                        .single { it.fromKmh == 100 }
                        .driveCount shouldBe 372
                }
            }
        }

        Given("chargeTimes") {
            When("0인 칸이 있으면") {
                Then("행이 온 칸만 낸다 — 168칸을 채우지 않는다") {
                    stubEmpty()
                    every { insightsRepository.chargeTimes(any(), any()) } returns listOf(ChargeTimeRow(6, 22, 10))

                    val times = service.insights(12).chargeTimes
                    times.size shouldBe 1
                    times.single().weekday shouldBe 6
                    times.single().hour shouldBe 22
                    times.single().count shouldBe 10
                }
            }
        }

        Given("충전 SoC 버킷") {
            When("행이 하나도 없으면") {
                Then("시작·종료 각각 열 칸이 자리를 지킨다") {
                    stubEmpty()
                    val response = service.insights(12)

                    response.chargeStartLevels.size shouldBe 10
                    response.chargeEndLevels.size shouldBe 10
                    response.chargeEndLevels.last().fromPct shouldBe 90
                    response.chargeEndLevels.last().toPct shouldBe 100
                }
            }

            When("100%로 끝난 충전이 오면") {
                Then("마지막 칸에 든다 — 열한 번째 칸이 생기지 않는다") {
                    stubEmpty()
                    every { insightsRepository.chargeLevelBuckets(any(), any()) } returns
                        listOf(ChargeLevelBucketRow(bucket = 10, startCount = 4, endCount = 71))

                    val response = service.insights(12)
                    response.chargeEndLevels.size shouldBe 10
                    response.chargeEndLevels.last().count shouldBe 71
                    response.chargeStartLevels.last().count shouldBe 4
                }
            }
        }

        Given("chargers·regions") {
            When("지오펜스가 0행이면") {
                Then("chargers가 null이 아니라 빈 배열이다") {
                    stubEmpty()
                    service.insights(12).chargers shouldBe emptyList()
                }
            }

            When("금액 미입력이 섞여 있으면") {
                Then("그 개수를 함께 낸다") {
                    stubEmpty()
                    every { insightsRepository.chargers(any(), any()) } returns
                        listOf(ChargerRow("Soraebi-ro", 5, BigDecimal("173.6"), null, 5))

                    val charger = service.insights(12).chargers.single()
                    charger.cost shouldBe null
                    charger.costMissingCount shouldBe 5
                }
            }

            When("주소가 하나도 없으면") {
                Then("regions가 전부 0이다 — null이 아니다") {
                    stubEmpty()
                    service.insights(12).regions.cities shouldBe 0
                }
            }
        }

        Given("records") {
            When("주행이 하나도 없으면") {
                Then("셋 다 null이다 — 「역대」라는 값 자체가 없다") {
                    stubEmpty()
                    val records = service.insights(12).records

                    records.longestDistance shouldBe null
                    records.longestDuration shouldBe null
                    records.bestEfficiency shouldBe null
                }
            }

            When("세 기록이 오면") {
                Then("종류별로 갈라 싣고 시각을 KST로 되돌린다") {
                    stubEmpty()
                    every { insightsRepository.driveRecords() } returns
                        listOf(
                            DriveRecordRow(
                                "distance",
                                3619,
                                LocalDateTime.of(2024, 9, 13, 0, 50),
                                BigDecimal("293.2"),
                                308,
                                BigDecimal("241.4"),
                            ),
                            DriveRecordRow(
                                "duration",
                                3619,
                                LocalDateTime.of(2024, 9, 13, 0, 50),
                                BigDecimal("293.2"),
                                308,
                                BigDecimal("241.4"),
                            ),
                            DriveRecordRow(
                                "efficiency",
                                3342,
                                LocalDateTime.of(2024, 6, 2, 4, 31),
                                BigDecimal("26.7"),
                                30,
                                BigDecimal("15.3"),
                            ),
                        )

                    val records = service.insights(12).records

                    val longestDistance = records.longestDistance.shouldNotBeNull()
                    longestDistance.driveId shouldBe 3619
                    // 2024-09-13 00:50 UTC → KST 09:50
                    longestDistance.startedAt shouldBe LocalDateTime.of(2024, 9, 13, 9, 50)
                    longestDistance.distanceKm shouldBe BigDecimal("293.2")
                    records.longestDuration.shouldNotBeNull().durationMin shouldBe 308
                    val bestEfficiency = records.bestEfficiency.shouldNotBeNull()
                    bestEfficiency.driveId shouldBe 3342
                    bestEfficiency.ratedRangeUsedKm shouldBe BigDecimal("15.3")
                }
            }

            When("효율 기록만 없으면") {
                Then("나머지 둘은 그대로 오고 그것만 null이다") {
                    stubEmpty()
                    every { insightsRepository.driveRecords() } returns
                        listOf(
                            DriveRecordRow("distance", 1, LocalDateTime.of(2024, 1, 1, 0, 0), BigDecimal("10.0"), 20, BigDecimal("11.0")),
                            DriveRecordRow("duration", 1, LocalDateTime.of(2024, 1, 1, 0, 0), BigDecimal("10.0"), 20, BigDecimal("11.0")),
                        )

                    val records = service.insights(12).records
                    records.longestDistance shouldNotBe null
                    records.bestEfficiency shouldBe null
                }
            }

            When("distance 행은 왔는데 그 행의 ratedRangeUsedKm가 null이면") {
                Then("longestDistance는 정상으로 나오고 bestEfficiency만 영향받는다") {
                    stubEmpty()
                    every { insightsRepository.driveRecords() } returns
                        listOf(
                            DriveRecordRow("distance", 1, LocalDateTime.of(2024, 1, 1, 0, 0), BigDecimal("10.0"), 20, null),
                        )

                    val records = service.insights(12).records

                    records.longestDistance.shouldNotBeNull().distanceKm shouldBe BigDecimal("10.0")
                    records.bestEfficiency shouldBe null
                }
            }
        }

        Given("batteryWindow — 범위 검증") {
            When("hours가 0이거나 169면") {
                Then("400이다") {
                    shouldThrow<CustomException> { service.batteryWindow(0) }
                    shouldThrow<CustomException> { service.batteryWindow(169) }
                }
            }
        }

        Given("batteryWindow — 응답") {
            fun stubWindow() {
                every { insightsRepository.batterySamples(any(), any()) } returns emptyList()
                every { insightsRepository.parkDrainSince(any()) } returns ParkDrainRow(BigDecimal.ZERO, BigDecimal.ZERO, 0)
                every { vehicleRepository.chargeSegments(any(), any()) } returns emptyList()
            }

            When("표본이 없으면") {
                Then("null이 아니라 빈 배열이다") {
                    stubWindow()
                    val response = service.batteryWindow(48)

                    response.samples shouldBe emptyList()
                    response.charges shouldBe emptyList()
                }
            }

            When("끝이 요청 시각인지") {
                Then("from이 to − hours다") {
                    stubWindow()
                    val response = service.batteryWindow(48)
                    response.from shouldBe response.to.minusHours(48)
                }
            }

            When("표본이 오면") {
                Then("시각을 KST로 되돌린다") {
                    stubWindow()
                    every { insightsRepository.batterySamples(any(), any()) } returns
                        listOf(BatterySampleRow(LocalDateTime.of(2026, 8, 18, 6, 2), 62, null))

                    val sample = service.batteryWindow(48).samples.single()
                    sample.at shouldBe LocalDateTime.of(2026, 8, 18, 15, 2)
                    sample.batteryLevel shouldBe 62
                    sample.usableBatteryLevel shouldBe null
                }
            }

            When("팬텀 드레인 표본이 0이면") {
                Then("null이 아니라 samples 0으로 온다 — 앱이 그 줄을 감춘다") {
                    stubWindow()
                    service.batteryWindow(48).parkDrain.samples shouldBe 0
                }
            }
        }
    })
