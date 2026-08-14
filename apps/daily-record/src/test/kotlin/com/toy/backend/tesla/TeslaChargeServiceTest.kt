package com.toy.backend.tesla

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * **TeslaMate는 UTC 값을 타임존 없는 timestamp 컬럼에 넣는다.** 경계를 KST로 받아 UTC로 바꾸지
 * 않으면 월초·월말 9시간이 옆 달로 샌다. 이 테스트가 그 변환을 양방향으로 못 박는다.
 */
class TeslaChargeServiceTest :
    BehaviorSpec({
        val repository = mockk<TeslaChargeRepository>()
        val service = TeslaChargeService(repository)

        Given("금액이 빈 충전을 조회할 때") {
            every { repository.countMissingCost() } returns 37
            every { repository.findMissingCost(50) } returns
                listOf(
                    ChargeRow(
                        id = 3120,
                        startDateUtc = LocalDateTime.of(2026, 8, 11, 13, 14),
                        endDateUtc = LocalDateTime.of(2026, 8, 11, 17, 31),
                        durationMin = 257,
                        locationName = null,
                        energyAddedKwh = BigDecimal("48.2"),
                        energyUsedKwh = BigDecimal("51.8"),
                        startBatteryLevel = 18,
                        endBatteryLevel = 90,
                        cost = null,
                    ),
                )

            val response = service.missingCost(50)

            Then("항목이 KST로 실린다") {
                response.items[0].id shouldBe 3120L
                response.items[0].startedAt shouldBe LocalDateTime.of(2026, 8, 11, 22, 14)
            }

            // 배지에 「미등록 37건」을 띄우고 채울수록 줄어드는 것을 본다.
            Then("totalCount는 limit과 무관한 전체 개수다") {
                response.totalCount shouldBe 37
                response.items.size shouldBe 1
            }
        }

        Given("limit이 범위 밖일 때") {
            Then("0이면 400이다") {
                shouldThrow<CustomException> { service.missingCost(0) }
                    .errorCode shouldBe ErrorCode.INVALID_REQUEST
            }

            Then("201이면 400이다") {
                shouldThrow<CustomException> { service.missingCost(201) }
                    .errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }

        Given("상세를 조회할 때") {
            every { repository.findDetail(3312L) } returns
                ChargeDetailRow(
                    id = 3312,
                    startDateUtc = LocalDateTime.of(2026, 8, 11, 13, 14),
                    endDateUtc = LocalDateTime.of(2026, 8, 11, 17, 31),
                    durationMin = 257,
                    energyAddedKwh = BigDecimal("48.2"),
                    energyUsedKwh = BigDecimal("51.0"),
                    startBatteryLevel = 18,
                    endBatteryLevel = 90,
                    startRatedRangeKm = BigDecimal("72.4"),
                    endRatedRangeKm = BigDecimal("361.8"),
                    outsideTempAvg = BigDecimal("27.5"),
                    geofenceName = "집",
                    address = "서울특별시 강남구 …",
                    cost = BigDecimal("14100"),
                )
            every { repository.findChargeStats(3312L) } returns
                ChargeStatsRow(
                    maxPowerKw = 11,
                    avgPowerKw = BigDecimal("10.4"),
                    fastCharger = false,
                    fastChargerBrand = null,
                    fastChargerType = null,
                )

            val detail = service.detail(3312L)

            Then("시각이 KST로 되돌아온다") {
                detail.startedAt shouldBe LocalDateTime.of(2026, 8, 11, 22, 14)
                detail.endedAt shouldBe LocalDateTime.of(2026, 8, 12, 2, 31)
            }

            Then("장소는 지오펜스 이름과 주소를 따로 싣는다") {
                detail.geofenceName shouldBe "집"
                detail.address shouldBe "서울특별시 강남구 …"
            }

            Then("charges 집계가 실린다") {
                detail.maxPowerKw shouldBe 11
                detail.avgPowerKw shouldBe BigDecimal("10.4")
                detail.fastCharger shouldBe false
                detail.fastChargerType shouldBe null
            }
        }

        Given("charges 샘플이 하나도 없는 오래된 세션을 조회할 때") {
            every { repository.findDetail(9L) } returns
                ChargeDetailRow(
                    id = 9,
                    startDateUtc = LocalDateTime.of(2026, 1, 1, 0, 0),
                    endDateUtc = LocalDateTime.of(2026, 1, 1, 1, 0),
                    durationMin = 60,
                    energyAddedKwh = null,
                    energyUsedKwh = null,
                    startBatteryLevel = null,
                    endBatteryLevel = null,
                    startRatedRangeKm = null,
                    endRatedRangeKm = null,
                    outsideTempAvg = null,
                    geofenceName = null,
                    address = null,
                    cost = null,
                )
            every { repository.findChargeStats(9L) } returns
                ChargeStatsRow(null, null, null, null, null)

            val detail = service.detail(9L)

            // 0은 「0kW로 충전했다」는 뜻이 되어 없는 데이터와 구분되지 않는다.
            Then("출력·충전기 필드가 0이 아니라 null이다") {
                detail.maxPowerKw shouldBe null
                detail.avgPowerKw shouldBe null
                detail.fastCharger shouldBe null
                detail.fastChargerBrand shouldBe null
                detail.fastChargerType shouldBe null
            }
        }

        Given("없는 id로 상세를 조회할 때") {
            every { repository.findDetail(404L) } returns null

            Then("404다") {
                shouldThrow<CustomException> { service.detail(404L) }
                    .errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
            }
        }

        // 브랜드·타입에 서로 다른 값을 준다 — 둘이 뒤바뀌어 매핑돼도 통과하는 것을 막는다.
        Given("급속 충전 세션의 상세를 조회할 때") {
            every { repository.findDetail(7L) } returns
                ChargeDetailRow(
                    id = 7,
                    startDateUtc = LocalDateTime.of(2026, 8, 1, 3, 0),
                    endDateUtc = LocalDateTime.of(2026, 8, 1, 3, 40),
                    durationMin = 40,
                    energyAddedKwh = BigDecimal("52.0"),
                    energyUsedKwh = BigDecimal("54.1"),
                    startBatteryLevel = 12,
                    endBatteryLevel = 80,
                    startRatedRangeKm = BigDecimal("48.0"),
                    endRatedRangeKm = BigDecimal("321.0"),
                    outsideTempAvg = BigDecimal("30.1"),
                    geofenceName = null,
                    address = "경기도 용인시 …",
                    cost = BigDecimal("22000"),
                )
            every { repository.findChargeStats(7L) } returns
                ChargeStatsRow(
                    maxPowerKw = 168,
                    avgPowerKw = BigDecimal("121.3"),
                    fastCharger = true,
                    fastChargerBrand = "Tesla",
                    fastChargerType = "Supercharger",
                )

            val detail = service.detail(7L)

            Then("브랜드와 타입이 각자 제자리로 간다") {
                detail.fastCharger shouldBe true
                detail.fastChargerBrand shouldBe "Tesla"
                detail.fastChargerType shouldBe "Supercharger"
            }

            Then("지오펜스가 없으면 주소만 실린다") {
                detail.geofenceName shouldBe null
                detail.address shouldBe "경기도 용인시 …"
            }
        }

        Given("금액을 수정할 때") {
            every { repository.updateCost(3312L, BigDecimal("15000")) } returns 1

            Then("예외 없이 끝난다") {
                service.updateCost(3312L, ChargeCostRequest(BigDecimal("15000")))
            }
        }

        // 없는 id, 그리고 진행 중이라 UPDATE 필터에 걸린 행이 모두 영향 행 0으로 온다.
        Given("영향 행이 0일 때") {
            every { repository.updateCost(404L, any()) } returns 0

            Then("404다") {
                shouldThrow<CustomException> {
                    service.updateCost(404L, ChargeCostRequest(BigDecimal("15000")))
                }.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
            }
        }

        // 리포지토리 스텁을 두지 않는다 — null이면 리포지토리에 닿기 전에 던져야 하고,
        // mockk 엄격 스텁이라 닿으면 이 테스트가 깨진다.
        Given("cost 없이 금액을 수정할 때") {
            Then("400이다") {
                shouldThrow<CustomException> {
                    service.updateCost(3312L, ChargeCostRequest(null))
                }.errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }
    })
