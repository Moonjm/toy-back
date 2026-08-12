package com.toy.backend.ledger.inbound

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 두 출처 중 무엇을 언제 쓰는지가 이 클래스의 전부다. 실제 HTTP는 각 소스가 하고 여기서는 보지 않는다.
 */
class FxRateClientTest :
    BehaviorSpec({
        val today = LocalDate.now()

        Given("오늘 날짜로 물으면") {
            val naver = mockk<NaverFxRateSource>()
            val frankfurter = mockk<FrankfurterFxRateSource>(relaxed = true)
            every { naver.rateToKrw("USD") } returns BigDecimal("1428.20")
            val client = FxRateClient(naver, frankfurter)

            val rate = client.rateToKrw("USD", today)

            Then("네이버(하나은행 매매기준율)를 쓴다 — ECB와 실측 0.5~1.1% 차이가 난다") {
                rate shouldBe BigDecimal("1428.20")
            }

            Then("Frankfurter는 부르지 않는다") {
                verify(exactly = 0) { frankfurter.rateToKrw(any(), any()) }
            }
        }

        Given("날짜를 안 주면") {
            val naver = mockk<NaverFxRateSource>()
            val frankfurter = mockk<FrankfurterFxRateSource>(relaxed = true)
            every { naver.rateToKrw("JPY") } returns BigDecimal("9.102")
            val client = FxRateClient(naver, frankfurter)

            Then("오늘로 보고 네이버를 쓴다") {
                client.rateToKrw("JPY") shouldBe BigDecimal("9.102")
                verify(exactly = 0) { frankfurter.rateToKrw(any(), any()) }
            }
        }

        // 미래 날짜는 아직 고시 전이라 과거 조회가 성립하지 않는다.
        Given("내일 날짜로 물으면") {
            val naver = mockk<NaverFxRateSource>()
            val frankfurter = mockk<FrankfurterFxRateSource>(relaxed = true)
            every { naver.rateToKrw("USD") } returns BigDecimal("1428.20")
            val client = FxRateClient(naver, frankfurter)

            Then("실시간으로 본다") {
                client.rateToKrw("USD", today.plusDays(1)) shouldBe BigDecimal("1428.20")
                verify(exactly = 0) { frankfurter.rateToKrw(any(), any()) }
            }
        }

        // **네이버 계산기에는 날짜 인자가 없다.** 과거를 물으면 오늘 환율이 나오므로 부르면 안 된다.
        Given("과거 날짜로 물으면") {
            val naver = mockk<NaverFxRateSource>(relaxed = true)
            val frankfurter = mockk<FrankfurterFxRateSource>()
            val past = today.minusDays(13)
            every { frankfurter.rateToKrw("JPY", past) } returns BigDecimal("8.9993")
            val client = FxRateClient(naver, frankfurter)

            val rate = client.rateToKrw("JPY", past)

            Then("Frankfurter만 쓴다 — 네이버는 그날 환율을 줄 수 없다") {
                rate shouldBe BigDecimal("8.9993")
            }

            Then("네이버는 부르지 않는다 — 부르면 오늘 환율이 그날 것으로 기록된다") {
                verify(exactly = 0) { naver.rateToKrw(any()) }
            }
        }

        Given("네이버가 실패하면") {
            val naver = mockk<NaverFxRateSource>()
            val frankfurter = mockk<FrankfurterFxRateSource>()
            every { naver.rateToKrw("USD") } returns null
            every { frankfurter.rateToKrw("USD", null) } returns BigDecimal("1435.86")
            val client = FxRateClient(naver, frankfurter)

            // 문서화된 API가 아니라 예고 없이 막힐 수 있다. 그때 기록이 멈추면 안 된다.
            Then("Frankfurter로 되돌아간다") {
                client.rateToKrw("USD", today) shouldBe BigDecimal("1435.86")
            }
        }

        Given("둘 다 실패하면") {
            val naver = mockk<NaverFxRateSource>()
            val frankfurter = mockk<FrankfurterFxRateSource>()
            every { naver.rateToKrw("USD") } returns null
            every { frankfurter.rateToKrw("USD", null) } returns null
            val client = FxRateClient(naver, frankfurter)

            Then("null — 환율 없이 내역을 저장한다") {
                client.rateToKrw("USD", today) shouldBe null
            }
        }

        Given("소문자 통화코드로 물으면") {
            val naver = mockk<NaverFxRateSource>()
            val frankfurter = mockk<FrankfurterFxRateSource>(relaxed = true)
            every { naver.rateToKrw("USD") } returns BigDecimal("1428.20")
            val client = FxRateClient(naver, frankfurter)

            Then("대문자로 바꿔 조회한다") {
                client.rateToKrw("usd", today) shouldBe BigDecimal("1428.20")
            }
        }

        Given("원화를 물으면") {
            val naver = mockk<NaverFxRateSource>(relaxed = true)
            val frankfurter = mockk<FrankfurterFxRateSource>(relaxed = true)
            val client = FxRateClient(naver, frankfurter)

            Then("조회하지 않고 null") {
                client.rateToKrw("KRW", today) shouldBe null
                verify(exactly = 0) { naver.rateToKrw(any()) }
                verify(exactly = 0) { frankfurter.rateToKrw(any(), any()) }
            }
        }
    })
