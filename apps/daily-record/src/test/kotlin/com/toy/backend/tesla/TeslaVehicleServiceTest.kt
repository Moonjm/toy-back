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
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * **12개월 추이·이번 달·직전 달이 한 벌의 그룹 집계에서 나온다** — 직전 달이 12개월 창 안에 든다.
 * 주행만 있는 달과 충전만 있는 달이 하나로 합쳐지는지, 없는 달이 0이 아니라 null인지가 핵심이다.
 */
class TeslaVehicleServiceTest :
    BehaviorSpec({
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

            // 12개월 창의 시작은 2025-09, 끝은 2026-09 직전이다. 둘 다 KST 경계를 UTC로 옮긴 값이다.
            Then("12개월 창이 UTC 경계로 번역된다") {
                start.captured shouldBe LocalDateTime.of(2025, 8, 31, 15, 0)
                end.captured shouldBe LocalDateTime.of(2026, 8, 31, 15, 0)
            }
        }

        Given("주행만 있는 달과 충전만 있는 달이 섞여 있을 때") {
            every { vehicleRepository.driveMonthly(any(), any()) } returns
                listOf(DriveMonthRow(YearMonth.of(2026, 8), 61, BigDecimal("842.3"), 1043))
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

            // 목록은 그 달만이다 — 12개월 창을 쓰면 안 된다.
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
    })
