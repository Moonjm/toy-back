package com.toy.backend.maintenance

import com.toy.backend.common.exception.CustomException
import com.toy.backend.maintenance.llm.MaintenanceVisionClient
import com.toy.backend.maintenance.llm.RecognizedBill
import com.toy.backend.maintenance.llm.RecognizedItem
import com.toy.backend.maintenance.llm.RecognizedUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.LocalDate

/**
 * **합계 검증은 금액 오독만 잡는다.** 실측에서 `2.5-flash`가 수도 사용량을 10.8→10.6으로
 * 틀렸는데 그 실행도 합계는 통과했다. 그래서 사용량에는 대응하는 플래그를 두지 않는다 —
 * 없는 안전망을 있는 척 만들면 검수하는 사람이 눈으로 볼 이유를 잃는다.
 */
class MaintenanceRecognitionServiceTest :
    BehaviorSpec({
        val visionClient = mockk<MaintenanceVisionClient>()
        val service = MaintenanceRecognitionService(visionClient)

        fun recognized(
            year: Int = 2026,
            month: Int = 3,
            items: List<Pair<String, Int>> = listOf("일반관리비" to 34700, "관리비차감" to -13790),
            usages: List<Triple<String, String, String>> = listOf(Triple("전기", "261", "kwh")),
            chargedAmount: Int = 20910,
            dueDate: String = "2026-04-30",
        ) = RecognizedBill(
            year = year,
            month = month,
            dong = "5103",
            ho = "1404",
            areaM2 = BigDecimal("98.8"),
            items = items.map { RecognizedItem(it.first, BigDecimal(it.second)) },
            usages = usages.map { RecognizedUsage(it.first, BigDecimal(it.second), it.third) },
            chargedAmount = BigDecimal(chargedAmount),
            discountTotal = BigDecimal.ZERO,
            unpaidAmount = BigDecimal.ZERO,
            unpaidLateFee = BigDecimal.ZERO,
            dueAmount = BigDecimal(chargedAmount),
            dueDate = dueDate,
        )

        Given("정상적으로 읽힌 영수증") {
            every { visionClient.read(any(), any()) } returns recognized()

            When("인식하면") {
                val response = service.recognize(byteArrayOf(1), "image/jpeg")

                Then("연월을 읽는다") {
                    response.yearMonth shouldBe "2026-03"
                }

                Then("항목 합계가 부과액과 맞으면 sumMatched가 참이다") {
                    response.sumMatched shouldBe true
                }

                Then("사용량을 이름별 자리에 넣는다") {
                    response.usage.electricityKwh shouldBe BigDecimal("261")
                    response.usage.heatingGcal.shouldBeNull()
                }

                Then("납기일을 해석한다") {
                    response.dueDate shouldBe LocalDate.of(2026, 4, 30)
                }
            }
        }

        Given("합계가 부과액과 어긋난 영수증") {
            every { visionClient.read(any(), any()) } returns recognized(chargedAmount = 999999)

            When("인식하면") {
                val response = service.recognize(byteArrayOf(1), "image/jpeg")

                Then("sumMatched가 거짓이고 경고가 붙는다") {
                    response.sumMatched shouldBe false
                    response.warnings.any { it.contains("합계") } shouldBe true
                }
            }
        }

        Given("연월을 못 읽은 영수증") {
            // 프롬프트가 「못 읽으면 0」을 약속한다. strict 스키마는 정수라는 것만 보장하므로
            // 13 같은 값도 올 수 있고, 그대로 YearMonth.of에 넣으면 500이 된다.
            every { visionClient.read(any(), any()) } returns recognized(year = 0, month = 0)

            When("인식하면") {
                val response = service.recognize(byteArrayOf(1), "image/jpeg")

                Then("연월이 비고 검수 화면이 채우게 둔다") {
                    response.yearMonth.shouldBeNull()
                }
            }
        }

        Given("월이 범위를 벗어난 영수증") {
            every { visionClient.read(any(), any()) } returns recognized(month = 13)

            When("인식하면") {
                Then("예외가 아니라 빈 연월로 넘어간다") {
                    service.recognize(byteArrayOf(1), "image/jpeg").yearMonth.shouldBeNull()
                }
            }
        }

        Given("모르는 이름의 사용량") {
            every { visionClient.read(any(), any()) } returns
                recognized(usages = listOf(Triple("가스", "1.2", "㎥")))

            When("인식하면") {
                val response = service.recognize(byteArrayOf(1), "image/jpeg")

                Then("버리지 않고 경고로 알린다") {
                    response.warnings.any { it.contains("가스") } shouldBe true
                }
            }
        }

        Given("납기일을 못 읽은 영수증") {
            every { visionClient.read(any(), any()) } returns recognized(dueDate = "")

            When("인식하면") {
                Then("납기일이 비고 예외가 나지 않는다") {
                    service.recognize(byteArrayOf(1), "image/jpeg").dueDate.shouldBeNull()
                }
            }
        }

        Given("인식이 실패한 경우") {
            every { visionClient.read(any(), any()) } returns null

            When("인식하면") {
                Then("VISION_UNAVAILABLE로 거부한다") {
                    val e = shouldThrow<CustomException> { service.recognize(byteArrayOf(1), "image/jpeg") }
                    e.errorCode shouldBe MaintenanceErrorCode.VISION_UNAVAILABLE
                }
            }
        }

        Given("빈 이미지") {
            When("인식하면") {
                Then("IMAGE_REQUIRED로 거부한다") {
                    val e = shouldThrow<CustomException> { service.recognize(byteArrayOf(), "image/jpeg") }
                    e.errorCode shouldBe MaintenanceErrorCode.IMAGE_REQUIRED
                }
            }
        }
    })
