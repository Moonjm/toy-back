package com.toy.backend.tesla

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * **12개월 추이·이번 달·직전 달이 한 벌의 그룹 집계에서 나온다** — 직전 달이 12개월 범위 안에 든다.
 * 주행만 있는 달과 충전만 있는 달이 하나로 합쳐지는지, 없는 달이 0이 아니라 null인지가 핵심이다.
 */
class TeslaVehicleServiceTest :
    BehaviorSpec({
        val kst = ZoneId.of("Asia/Seoul")
        val vehicleRepository = mockk<TeslaVehicleRepository>()
        val chargeRepository = mockk<TeslaChargeRepository>()
        val service = TeslaVehicleService(vehicleRepository, chargeRepository)

        Given("yearMonth로 요약을 조회할 때") {
            val start = slot<LocalDateTime>()
            val end = slot<LocalDateTime>()
            every { vehicleRepository.driveMonthly(capture(start), capture(end)) } returns emptyList()
            every { chargeRepository.chargeMonthly(any(), any()) } returns emptyList()
            every { chargeRepository.findMonthCharges(any(), any()) } returns emptyList()

            service.summary(YearMonth.of(2026, 8))

            // 12개월 범위의 시작은 2025-09, 끝은 2026-09 직전이다. 둘 다 KST 경계를 UTC로 옮긴 값이다.
            Then("12개월 범위가 UTC 경계로 번역된다") {
                start.captured shouldBe LocalDateTime.of(2025, 8, 31, 15, 0)
                end.captured shouldBe LocalDateTime.of(2026, 8, 31, 15, 0)
            }
        }

        Given("주행만 있는 달과 충전만 있는 달이 섞여 있을 때") {
            every { vehicleRepository.driveMonthly(any(), any()) } returns
                listOf(
                    DriveMonthRow(YearMonth.of(2026, 8), 61, BigDecimal("842.3"), 1043),
                    DriveMonthRow(YearMonth.of(2026, 6), 30, BigDecimal("410.5"), 520),
                )
            every { chargeRepository.chargeMonthly(any(), any()) } returns
                listOf(
                    ChargeMonthRow(YearMonth.of(2026, 8), 5, BigDecimal("186.4"), BigDecimal("201.7"), BigDecimal("52300")),
                    ChargeMonthRow(YearMonth.of(2026, 7), 4, BigDecimal("141.0"), BigDecimal("152.2"), BigDecimal("39800")),
                )
            every { chargeRepository.findMonthCharges(any(), any()) } returns emptyList()

            val response = service.summary(YearMonth.of(2026, 8))

            Then("이번 달은 주행과 충전이 한 항목으로 합쳐진다") {
                response.month.yearMonth shouldBe YearMonth.of(2026, 8)
                response.month.distanceKm shouldBe BigDecimal("842.3")
                response.month.driveCount shouldBe 61
                response.month.cost shouldBe BigDecimal("52300")
                response.month.chargeCount shouldBe 5
            }

            // 7월은 충전만 있다 — 주행 필드가 0이 아니라 null이어야 「기록이 없다」로 읽힌다.
            Then("충전만 있는 달은 주행 필드가 null이다") {
                response.previous.yearMonth shouldBe YearMonth.of(2026, 7)
                response.previous.cost shouldBe BigDecimal("39800")
                response.previous.distanceKm shouldBe null
                response.previous.driveCount shouldBe null
            }

            // 6월은 주행만 있다 — 충전 필드가 0이 아니라 null이어야 「기록이 없다」로 읽힌다.
            Then("주행만 있는 달은 충전 필드가 null이다") {
                val june = response.trend.first { it.yearMonth == YearMonth.of(2026, 6) }
                june.driveCount shouldBe 30
                june.cost shouldBe null
            }

            Then("추이는 기준 달 포함 12개월이고 오래된 것부터다") {
                response.trend.size shouldBe 12
                response.trend.first().yearMonth shouldBe YearMonth.of(2025, 9)
                response.trend.last().yearMonth shouldBe YearMonth.of(2026, 8)
            }

            Then("데이터가 없는 달도 자리를 채우고 값은 null이다") {
                val empty = response.trend.first()
                empty.distanceKm shouldBe null
                empty.cost shouldBe null
                empty.chargeCount shouldBe null
            }
        }

        Given("1월을 조회할 때") {
            every { vehicleRepository.driveMonthly(any(), any()) } returns emptyList()
            every { chargeRepository.chargeMonthly(any(), any()) } returns emptyList()
            every { chargeRepository.findMonthCharges(any(), any()) } returns emptyList()

            val response = service.summary(YearMonth.of(2026, 1))

            Then("직전 달은 전년 12월이다") {
                response.previous.yearMonth shouldBe YearMonth.of(2025, 12)
            }
        }

        Given("그 달의 충전 목록이 있을 때") {
            val start = slot<LocalDateTime>()
            val end = slot<LocalDateTime>()
            every { vehicleRepository.driveMonthly(any(), any()) } returns emptyList()
            every { chargeRepository.chargeMonthly(any(), any()) } returns emptyList()
            every { chargeRepository.findMonthCharges(capture(start), capture(end)) } returns
                listOf(
                    ChargeRow(
                        id = 3312,
                        startDateUtc = LocalDateTime.of(2026, 8, 11, 13, 14),
                        endDateUtc = LocalDateTime.of(2026, 8, 11, 17, 31),
                        durationMin = 257,
                        locationName = "집",
                        energyAddedKwh = BigDecimal("48.2"),
                        energyUsedKwh = BigDecimal("51.8"),
                        startBatteryLevel = 18,
                        endBatteryLevel = 90,
                        cost = BigDecimal("14100"),
                    ),
                )

            val response = service.summary(YearMonth.of(2026, 8))

            // 목록은 그 달만이다 — 12개월 범위를 쓰면 안 된다.
            Then("목록은 그 달의 경계로 조회한다") {
                start.captured shouldBe LocalDateTime.of(2026, 7, 31, 15, 0)
                end.captured shouldBe LocalDateTime.of(2026, 8, 31, 15, 0)
            }

            Then("항목 시각이 KST로 되돌아온다") {
                response.charges[0].startedAt shouldBe LocalDateTime.of(2026, 8, 11, 22, 14)
                response.charges[0].endedAt shouldBe LocalDateTime.of(2026, 8, 12, 2, 31)
            }
        }

        Given("yearMonth가 없을 때") {
            Then("400이다") {
                shouldThrow<CustomException> { service.summary(null) }
                    .errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }

        Given("차가 충전 중일 때") {
            every { vehicleRepository.findLatestPosition() } returns
                PositionRow(
                    dateUtc = LocalDateTime.of(2026, 8, 13, 5, 2),
                    latitude = BigDecimal("37.5665"),
                    longitude = BigDecimal("126.9780"),
                    batteryLevel = 72,
                    usableBatteryLevel = 70,
                    ratedRangeKm = BigDecimal("312.4"),
                    estRangeKm = BigDecimal("288.0"),
                    odometerKm = 41203.8,
                    insideTempC = BigDecimal("31.5"),
                    outsideTempC = BigDecimal("33.0"),
                    climateOn = false,
                    tpmsFl = BigDecimal("2.9"),
                    tpmsFr = BigDecimal("2.9"),
                    tpmsRl = BigDecimal("2.8"),
                    tpmsRr = BigDecimal("2.9"),
                )
            every { vehicleRepository.findOpenState() } returns
                StateRow("online", LocalDateTime.of(2026, 8, 13, 0, 30))
            every { vehicleRepository.findActivity() } returns ActivityRow(charging = true, driving = false)
            every { vehicleRepository.findGeofences() } returns emptyList()

            val status = service.status()

            // states 테이블에는 online·offline·asleep 셋뿐이라, 충전 중에도 online으로 저장된다.
            Then("states가 online이어도 charging이다") {
                status.state shouldBe "charging"
            }

            Then("asOf와 stateSince가 KST로 되돌아온다") {
                status.asOf shouldBe LocalDateTime.of(2026, 8, 13, 14, 2)
                status.stateSince shouldBe LocalDateTime.of(2026, 8, 13, 9, 30)
            }

            Then("공기압은 bar 그대로 낸다") {
                status.tpmsBar?.fl shouldBe BigDecimal("2.9")
                status.tpmsBar?.rl shouldBe BigDecimal("2.8")
            }

            Then("지오펜스가 없으면 위치 이름이 null이다") {
                status.locationName shouldBe null
            }
        }

        Given("충전과 주행이 동시에 열려 있을 때") {
            every { vehicleRepository.findLatestPosition() } returns null
            every { vehicleRepository.findOpenState() } returns null
            every { vehicleRepository.findActivity() } returns ActivityRow(charging = true, driving = true)
            every { vehicleRepository.findGeofences() } returns emptyList()

            val status = service.status()

            // TeslaMate가 죽었다 살아나면 끝나지 않은 주행 행이 남는다. 그때 사실에 가까운 쪽은 충전이다.
            Then("charging이 이긴다") {
                status.state shouldBe "charging"
            }
        }

        Given("차가 주행 중일 때") {
            every { vehicleRepository.findLatestPosition() } returns null
            every { vehicleRepository.findOpenState() } returns
                StateRow("online", LocalDateTime.of(2026, 8, 13, 0, 30))
            every { vehicleRepository.findActivity() } returns ActivityRow(charging = false, driving = true)
            every { vehicleRepository.findGeofences() } returns emptyList()

            val status = service.status()

            Then("driving이다") {
                status.state shouldBe "driving"
            }
        }

        Given("충전도 주행도 아닐 때") {
            every { vehicleRepository.findLatestPosition() } returns null
            every { vehicleRepository.findOpenState() } returns
                StateRow("asleep", LocalDateTime.of(2026, 8, 13, 0, 30))
            every { vehicleRepository.findActivity() } returns ActivityRow(charging = false, driving = false)
            every { vehicleRepository.findGeofences() } returns emptyList()

            val status = service.status()

            // 모르는 값도 그대로 통과한다 — 상류가 states_status에 값을 늘려도 매핑이 틀리지 않는다.
            Then("states의 값이 그대로 나온다") {
                status.state shouldBe "asleep"
            }
        }

        Given("위치 기록이 하나도 없을 때") {
            every { vehicleRepository.findLatestPosition() } returns null
            every { vehicleRepository.findOpenState() } returns null
            every { vehicleRepository.findActivity() } returns ActivityRow(charging = false, driving = false)
            every { vehicleRepository.findGeofences() } returns emptyList()

            val status = service.status()

            // 「기록이 아직 없다」와 「못 읽었다」는 다르다 — 500이 아니라 빈 상태다.
            Then("asOf가 null인 빈 상태를 낸다") {
                status.asOf shouldBe null
                status.batteryLevel shouldBe null
                status.tpmsBar shouldBe null
                status.state shouldBe null
                status.stateSince shouldBe null
            }
        }

        Given("차가 지오펜스 반경 안에 있을 때") {
            every { vehicleRepository.findLatestPosition() } returns
                PositionRow(
                    dateUtc = LocalDateTime.of(2026, 8, 13, 5, 2),
                    latitude = BigDecimal("37.56650"),
                    longitude = BigDecimal("126.97800"),
                    batteryLevel = 72,
                    usableBatteryLevel = 70,
                    ratedRangeKm = null,
                    estRangeKm = null,
                    odometerKm = null,
                    insideTempC = null,
                    outsideTempC = null,
                    climateOn = null,
                    tpmsFl = null,
                    tpmsFr = null,
                    tpmsRl = null,
                    tpmsRr = null,
                )
            every { vehicleRepository.findOpenState() } returns null
            every { vehicleRepository.findActivity() } returns ActivityRow(charging = false, driving = false)
            every { vehicleRepository.findGeofences() } returns
                listOf(
                    // 약 3km 떨어진 것 — 반경 100m 밖이다.
                    GeofenceRow("회사", BigDecimal("37.59300"), BigDecimal("126.97800"), 100),
                    // 차 위치에서 약 11m — 반경 안이지만 더 멀다. 리스트의 첫 반경-안 항목이라
                    // `firstOrNull`로 바꿔도 이게 나와 버린다.
                    GeofenceRow("집", BigDecimal("37.56660"), BigDecimal("126.97800"), 500),
                    // 차와 좌표가 같다 — 거리 0, 반경 안에서 가장 가깝다.
                    GeofenceRow("동네", BigDecimal("37.56650"), BigDecimal("126.97800"), 500),
                )

            val status = service.status()

            // 「집」(약 11m)과 「동네」(0m) 둘 다 반경 안이다 — `minByOrNull`이 더 가까운
            // 「동네」를 골라야 한다. `firstOrNull`이면 리스트 순서상 「집」이 나와 버린다.
            Then("반경 안의 지오펜스 중 가장 가까운 이름이 나온다") {
                status.locationName shouldBe "동네"
            }
        }

        Given("차가 어느 지오펜스 반경에도 없을 때") {
            every { vehicleRepository.findLatestPosition() } returns
                PositionRow(
                    dateUtc = LocalDateTime.of(2026, 8, 13, 5, 2),
                    latitude = BigDecimal("35.16650"),
                    longitude = BigDecimal("129.07800"),
                    batteryLevel = 72,
                    usableBatteryLevel = 70,
                    ratedRangeKm = null,
                    estRangeKm = null,
                    odometerKm = null,
                    insideTempC = null,
                    outsideTempC = null,
                    climateOn = null,
                    tpmsFl = null,
                    tpmsFr = null,
                    tpmsRl = null,
                    tpmsRr = null,
                )
            every { vehicleRepository.findOpenState() } returns null
            every { vehicleRepository.findActivity() } returns ActivityRow(charging = false, driving = false)
            every { vehicleRepository.findGeofences() } returns
                listOf(GeofenceRow("집", BigDecimal("37.56650"), BigDecimal("126.97800"), 100))

            val status = service.status()

            // 주소는 내지 않는다 — positions에 주소 참조가 없고 역지오코딩을 붙이지 않는다.
            Then("위치 이름이 null이다") {
                status.locationName shouldBe null
            }
        }

        // 서비스가 하는 일은 행→표본 변환과 정렬뿐이다. 중앙값·월 경계·표본 조건은 전부 SQL이 한다.
        Given("배터리 건강 표본이 하나도 없을 때") {
            every { vehicleRepository.batteryHealthMonthly() } returns emptyList()

            val response = service.batteryHealth()

            // 빈 배열이어야 한다. null이면 앱이 「응답이 깨졌다」와 「표본이 없다」를 못 가른다.
            Then("samples가 빈 배열이다") {
                response.samples shouldBe emptyList()
            }
        }

        Given("용량 표본이 없는 달이 섞여 있을 때") {
            every { vehicleRepository.batteryHealthMonthly() } returns
                listOf(
                    BatteryHealthMonthRow(YearMonth.of(2026, 7), BigDecimal("527.1"), null, 1, 0),
                    BatteryHealthMonthRow(YearMonth.of(2026, 8), BigDecimal("525.3"), BigDecimal("71.6"), 3, 1),
                )

            val response = service.batteryHealth()

            // ΔSoC ≥ 40인 충전은 몇 달에 한 번이라 capacityKwh는 자주 null이다. 0이 아니다.
            Then("용량 표본이 없는 달은 capacityKwh가 null이고 개수가 0이다") {
                val july = response.samples.first { it.yearMonth == YearMonth.of(2026, 7) }
                july.fullRangeKm shouldBe BigDecimal("527.1")
                july.capacityKwh shouldBe null
                july.sampleCount shouldBe 1
                july.capacitySampleCount shouldBe 0
            }

            Then("용량 표본이 있는 달은 두 개수가 따로 온다") {
                val august = response.samples.first { it.yearMonth == YearMonth.of(2026, 8) }
                august.capacityKwh shouldBe BigDecimal("71.6")
                august.sampleCount shouldBe 3
                august.capacitySampleCount shouldBe 1
            }
        }

        // SQL이 이미 ORDER BY month_start로 내지만, 정렬 책임을 응답 쪽에도 못 박는다 —
        // 앱이 선을 그리는 순서가 여기에 달렸다.
        Given("여러 달의 표본이 뒤섞여 올 때") {
            every { vehicleRepository.batteryHealthMonthly() } returns
                listOf(
                    BatteryHealthMonthRow(YearMonth.of(2026, 8), BigDecimal("525.3"), null, 3, 0),
                    BatteryHealthMonthRow(YearMonth.of(2025, 12), BigDecimal("534.0"), null, 2, 0),
                    BatteryHealthMonthRow(YearMonth.of(2026, 7), BigDecimal("527.1"), null, 1, 0),
                )

            val response = service.batteryHealth()

            Then("오래된 것부터 나온다") {
                response.samples.map { it.yearMonth } shouldBe
                    listOf(YearMonth.of(2025, 12), YearMonth.of(2026, 7), YearMonth.of(2026, 8))
            }

            // 표본이 없는 달(2026-01~06)은 배열에서 빠진다. trend가 빈 달을 채우는 것과 다르다 —
            // 열화는 월 경계가 의미를 갖는 값이 아니라, 선을 이을지 끊을지는 앱이 정한다.
            Then("표본이 없는 달은 자리를 채우지 않는다") {
                response.samples.size shouldBe 3
            }
        }

        // 서비스가 하는 일은 버킷 자리 채움과 행→DTO 변환뿐이다. 합계·KST 변환은 SQL이 한다.
        Given("주행 인사이트를 조회할 때 리포지토리가 아무 행도 주지 않으면") {
            every { vehicleRepository.driveTemperatureBuckets(any()) } returns emptyList()
            every { vehicleRepository.driveTimes(any()) } returns emptyList()
            every { vehicleRepository.driveDistanceBuckets(any()) } returns emptyList()
            every { vehicleRepository.drivePlaces(any()) } returns emptyList()
            every { vehicleRepository.carEfficiency() } returns BigDecimal("0.1367")
            every { vehicleRepository.driveStats() } returns DriveStatsRow(138, BigDecimal("1331.3"), BigDecimal("13440.4"))

            val response = service.driveInsights(12)

            // 비교하려고 보는 차트라 자리가 비면 축이 흔들린다. 빈 버킷도 다섯 개가 온다.
            Then("온도 버킷 다섯 개가 자리를 지킨다") {
                response.temperatureBuckets.size shouldBe 5
                response.temperatureBuckets.map { it.fromC } shouldBe listOf(null, 0, 10, 20, 30)
                response.temperatureBuckets.map { it.toC } shouldBe listOf(0, 10, 20, 30, null)
                response.temperatureBuckets.all { it.driveCount == 0 } shouldBe true
            }

            // 빈 버킷의 숫자는 0이다. null이 아니다 — 「그 온도대에 안 탔다」는 사실이지
            // 「기록이 없다」가 아니다.
            Then("빈 버킷의 합은 0이다") {
                response.temperatureBuckets.first().distanceKm shouldBe BigDecimal.ZERO
                response.temperatureBuckets.first().ratedRangeUsedKm shouldBe BigDecimal.ZERO
            }

            Then("거리 버킷 다섯 개가 자리를 지킨다") {
                response.distanceBuckets.map { it.fromKm } shouldBe listOf(0, 5, 20, 50, 100)
                response.distanceBuckets.map { it.toKm } shouldBe listOf(5, 20, 50, 100, null)
                response.distanceBuckets.all { it.driveCount == 0 } shouldBe true
            }

            // 168칸 중 대부분이 0이라 히트맵은 없는 칸을 빈칸으로 그린다. 자리를 채우지 않는다.
            Then("driveTimes는 자리를 채우지 않는다") {
                response.driveTimes shouldBe emptyList()
            }

            // 도착지 이름이 하나도 없으면 빈 배열이어야 한다(null이 아니다).
            Then("places가 빈 배열이다") {
                response.places shouldBe emptyList()
            }

            Then("months를 되돌려 실어 앱이 무엇을 받았는지 알 수 있다") {
                response.months shouldBe 12
            }
        }

        Given("일부 버킷에만 주행이 있을 때") {
            every { vehicleRepository.driveTemperatureBuckets(any()) } returns
                listOf(
                    DriveTemperatureBucketRow(1, 82, BigDecimal("2424.8"), BigDecimal("2939.2")),
                    DriveTemperatureBucketRow(5, 118, BigDecimal("2494.6"), BigDecimal("2551.5")),
                )
            every { vehicleRepository.driveTimes(any()) } returns
                listOf(DriveTimeRow(1, 8, 43), DriveTimeRow(2, 17, 41))
            every { vehicleRepository.driveDistanceBuckets(any()) } returns
                listOf(DriveDistanceBucketRow(5, 3, BigDecimal("412.0")))
            every { vehicleRepository.drivePlaces(any()) } returns
                listOf(
                    DrivePlaceRow("집", 124, BigDecimal("812.4")),
                    // 지오펜스가 없어 주소로 떨어진 도착지도 그대로 나간다 —
                    // 서비스는 이름이 어디서 왔는지 구분하지 않는다.
                    DrivePlaceRow("Goyang-daero", 69, BigDecimal("774.0")),
                )
            every { vehicleRepository.carEfficiency() } returns BigDecimal("0.1367")
            every { vehicleRepository.driveStats() } returns DriveStatsRow(138, BigDecimal("1331.3"), BigDecimal("13440.4"))

            val response = service.driveInsights(12)

            Then("온 버킷은 값이 실리고 안 온 버킷은 0으로 채워진다") {
                val below = response.temperatureBuckets.first { it.fromC == null }
                below.driveCount shouldBe 82
                below.distanceKm shouldBe BigDecimal("2424.8")
                below.ratedRangeUsedKm shouldBe BigDecimal("2939.2")

                val middle = response.temperatureBuckets.first { it.fromC == 10 }
                middle.driveCount shouldBe 0
                middle.distanceKm shouldBe BigDecimal.ZERO
            }

            Then("거리 버킷도 같은 방식으로 채워진다") {
                response.distanceBuckets.first { it.fromKm == 100 }.driveCount shouldBe 3
                response.distanceBuckets.first { it.fromKm == 0 }.driveCount shouldBe 0
            }

            // dow는 0이 일요일이다. 서버가 번역하지 않고 그대로 올린다.
            Then("driveTimes는 온 것만 그대로 나간다") {
                response.driveTimes.size shouldBe 2
                response.driveTimes.first().weekday shouldBe 1
                response.driveTimes.first().hour shouldBe 8
                response.driveTimes.first().count shouldBe 43
            }

            Then("places는 온 것만 그대로 나간다") {
                response.places.map { it.name } shouldBe listOf("집", "Goyang-daero")
                response.places.first().driveCount shouldBe 124
            }
        }

        // TeslaMate가 efficiency를 아직 못 채운 경우다. 그때 앱은 전비 카드를 감춘다.
        Given("cars.efficiency가 null일 때") {
            every { vehicleRepository.driveTemperatureBuckets(any()) } returns emptyList()
            every { vehicleRepository.driveTimes(any()) } returns emptyList()
            every { vehicleRepository.driveDistanceBuckets(any()) } returns emptyList()
            every { vehicleRepository.drivePlaces(any()) } returns emptyList()
            every { vehicleRepository.carEfficiency() } returns null
            every { vehicleRepository.driveStats() } returns DriveStatsRow(138, BigDecimal("1331.3"), BigDecimal("13440.4"))

            val response = service.driveInsights(12)

            Then("efficiencyKwhPerKm이 null이다") {
                response.efficiencyKwhPerKm shouldBe null
            }
        }

        Given("months가 범위 경계일 때") {
            every { vehicleRepository.driveTemperatureBuckets(any()) } returns emptyList()
            every { vehicleRepository.driveTimes(any()) } returns emptyList()
            every { vehicleRepository.driveDistanceBuckets(any()) } returns emptyList()
            every { vehicleRepository.drivePlaces(any()) } returns emptyList()
            every { vehicleRepository.carEfficiency() } returns null
            every { vehicleRepository.driveStats() } returns DriveStatsRow(138, BigDecimal("1331.3"), BigDecimal("13440.4"))

            Then("1과 60은 통과한다") {
                service.driveInsights(1).months shouldBe 1
                service.driveInsights(60).months shouldBe 60
            }

            Then("0과 61은 400이다") {
                shouldThrow<CustomException> { service.driveInsights(0) }
                    .errorCode shouldBe ErrorCode.INVALID_REQUEST
                shouldThrow<CustomException> { service.driveInsights(61) }
                    .errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }

        // months는 이 엔드포인트의 유일한 파라미터다. 스텁을 전부 any()로 두면 서비스가
        // driveTimes(12)처럼 값을 하드코딩해도 테스트가 초록으로 남는다 — 12가 아닌 값으로
        // 불러 네 리포지토리 메서드가 그 값을 그대로 받는지 캡처로 확인한다.
        // `carEfficiency()`는 파라미터가 없어 대상이 아니다.
        Given("months=3으로 조회할 때") {
            val temperatureMonths = slot<Int>()
            val timesMonths = slot<Int>()
            val distanceMonths = slot<Int>()
            val placesMonths = slot<Int>()
            every { vehicleRepository.driveTemperatureBuckets(capture(temperatureMonths)) } returns emptyList()
            every { vehicleRepository.driveTimes(capture(timesMonths)) } returns emptyList()
            every { vehicleRepository.driveDistanceBuckets(capture(distanceMonths)) } returns emptyList()
            every { vehicleRepository.drivePlaces(capture(placesMonths)) } returns emptyList()
            every { vehicleRepository.carEfficiency() } returns null
            every { vehicleRepository.driveStats() } returns DriveStatsRow(138, BigDecimal("1331.3"), BigDecimal("13440.4"))

            service.driveInsights(3)

            Then("months를 받는 네 리포지토리 메서드가 3을 그대로 받는다") {
                temperatureMonths.captured shouldBe 3
                timesMonths.captured shouldBe 3
                distanceMonths.captured shouldBe 3
                placesMonths.captured shouldBe 3
            }
        }

        // 셋의 범위가 서로 다르다 — 최고 속도는 전 기간이고 거리 둘은 KST 월·연 경계다.
        // 서비스는 그것을 해석하지 않고 그대로 올린다.
        Given("주행 통계가 올 때") {
            every { vehicleRepository.driveTemperatureBuckets(any()) } returns emptyList()
            every { vehicleRepository.driveTimes(any()) } returns emptyList()
            every { vehicleRepository.driveDistanceBuckets(any()) } returns emptyList()
            every { vehicleRepository.drivePlaces(any()) } returns emptyList()
            every { vehicleRepository.carEfficiency() } returns null
            every { vehicleRepository.driveStats() } returns
                DriveStatsRow(138, BigDecimal("1331.3"), BigDecimal("13440.4"))

            val response = service.driveInsights(12)

            Then("세 값이 그대로 실린다") {
                response.maxSpeedKmh shouldBe 138
                response.monthDistanceKm shouldBe BigDecimal("1331.3")
                response.yearDistanceKm shouldBe BigDecimal("13440.4")
            }
        }

        // 주행이 하나도 없는 경우다. 「역대 최고」는 값 자체가 없지만(null),
        // 거리 둘은 기간이 못박힌 합계라 0이 사실이다 — 서비스가 null로 바꾸지 않는다.
        Given("주행이 하나도 없을 때") {
            every { vehicleRepository.driveTemperatureBuckets(any()) } returns emptyList()
            every { vehicleRepository.driveTimes(any()) } returns emptyList()
            every { vehicleRepository.driveDistanceBuckets(any()) } returns emptyList()
            every { vehicleRepository.drivePlaces(any()) } returns emptyList()
            every { vehicleRepository.carEfficiency() } returns null
            every { vehicleRepository.driveStats() } returns
                DriveStatsRow(null, BigDecimal.ZERO, BigDecimal.ZERO)

            val response = service.driveInsights(12)

            Then("최고 속도는 null이고 거리 둘은 0이다") {
                response.maxSpeedKmh shouldBe null
                response.monthDistanceKm shouldBe BigDecimal.ZERO
                response.yearDistanceKm shouldBe BigDecimal.ZERO
            }
        }

        Given("상태 타임라인을 조회할 때") {
            val start = slot<LocalDateTime>()
            val end = slot<LocalDateTime>()
            every { vehicleRepository.stateSegments(capture(start), capture(end)) } returns emptyList()
            every { vehicleRepository.driveSegments(any(), any()) } returns emptyList()
            every { vehicleRepository.chargeSegments(any(), any()) } returns emptyList()

            val response = service.stateTimeline(7)

            // 앱이 하루에 한 행씩 그린다. 범위가 임의 시각에서 시작하면 첫 행이 반쪽이 된다.
            // 고정 시각 검증은 `TeslaTimeTest`가 한다 — 여기서는 자정에 맞았는지만 본다.
            Then("범위 시작이 KST 자정에서 6일을 뺀 자정이다") {
                response.from shouldBe LocalDate.now(kst).minusDays(6).atStartOfDay()
            }

            // KST 자정을 UTC로 옮기면 전날 15시다. 리포지토리는 UTC로 받아야 한다 —
            // KST로 넘기면 TeslaMate의 타임존 없는 컬럼과 9시간 어긋난다.
            Then("리포지토리는 UTC 경계를 받는다") {
                start.captured shouldBe TeslaTime.toUtc(response.from)
                end.captured shouldBe TeslaTime.toUtc(response.to)
            }

            // 「그 기간에 기록이 없다」는 404가 아니다.
            Then("세 배열이 비어도 응답이 성립한다") {
                response.days shouldBe 7
                response.states shouldBe emptyList()
                response.drives shouldBe emptyList()
                response.charges shouldBe emptyList()
            }
        }

        Given("구간이 UTC로 올 때") {
            every { vehicleRepository.stateSegments(any(), any()) } returns
                listOf(
                    StateSegmentRow("offline", LocalDateTime.of(2026, 8, 12, 15, 0), LocalDateTime.of(2026, 8, 12, 21, 14)),
                    StateSegmentRow("online", LocalDateTime.of(2026, 8, 12, 21, 14), LocalDateTime.of(2026, 8, 12, 21, 26)),
                )
            every { vehicleRepository.driveSegments(any(), any()) } returns
                listOf(SegmentRow(LocalDateTime.of(2026, 8, 12, 21, 18), LocalDateTime.of(2026, 8, 12, 21, 36)))
            every { vehicleRepository.chargeSegments(any(), any()) } returns
                listOf(SegmentRow(LocalDateTime.of(2026, 8, 15, 13, 11), LocalDateTime.of(2026, 8, 15, 20, 40)))

            val response = service.stateTimeline(7)

            // UTC + 9h = KST. 빠뜨리면 새벽 6시 출발이 밤 9시로 찍힌다.
            Then("세 배열의 시각이 전부 KST로 바뀐다") {
                response.states.first().from shouldBe LocalDateTime.of(2026, 8, 13, 0, 0)
                response.states.first().to shouldBe LocalDateTime.of(2026, 8, 13, 6, 14)
                response.drives.first().from shouldBe LocalDateTime.of(2026, 8, 13, 6, 18)
                response.charges.first().to shouldBe LocalDateTime.of(2026, 8, 16, 5, 40)
            }

            // 상류가 값을 늘리면 그대로 올라온다. 서버는 번역하지 않는다.
            Then("state 문자열은 그대로 나간다") {
                response.states.map { it.state } shouldBe listOf("offline", "online")
            }
        }

        Given("days가 범위 경계일 때") {
            every { vehicleRepository.stateSegments(any(), any()) } returns emptyList()
            every { vehicleRepository.driveSegments(any(), any()) } returns emptyList()
            every { vehicleRepository.chargeSegments(any(), any()) } returns emptyList()

            Then("1과 30은 통과한다") {
                service.stateTimeline(1).days shouldBe 1
                service.stateTimeline(30).days shouldBe 30
            }

            Then("0과 31은 400이다") {
                shouldThrow<CustomException> { service.stateTimeline(0) }
                    .errorCode shouldBe ErrorCode.INVALID_REQUEST
                shouldThrow<CustomException> { service.stateTimeline(31) }
                    .errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }
    })
