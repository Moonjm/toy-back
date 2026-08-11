package com.toy.backend.dispatch

import com.toy.backend.common.exception.CustomException
import com.toy.backend.dispatch.image.DispatchImageSlicer
import com.toy.backend.dispatch.image.ImageSlice
import com.toy.backend.dispatch.llm.DispatchVisionClient
import com.toy.backend.dispatch.llm.DispatchVisionProperties
import com.toy.backend.dispatch.llm.RecognizedCell
import com.toy.backend.dispatch.llm.RecognizedSlice
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

/**
 * 조각 둘을 하나로 합친다. **겹친 구간이 공짜 교차검증**이 된다 — 두 조각의 답이
 * 갈리면 어느 쪽도 고르지 않고 `conflict`를 달아 검수하는 사람에게 넘긴다.
 *
 * 행 매칭이 **조용히 어긋나는 것**이 이 기능의 가장 큰 위험이다. 인원이 바뀌어 행 순서가
 * 밀리면 엉뚱한 기사의 근무가 아빠 달력에 들어가고 아무도 눈치채지 못한다.
 */
class DispatchRecognitionServiceTest :
    BehaviorSpec({
        val rosterRepository = mockk<DispatchRosterRepository>(relaxed = true)
        // relaxed 모드는 JpaRepository.save()의 제네릭 반환 타입(<S : T> S save(S))을
        // 못 풀어 ClassCastException을 낸다. 이 저장소의 다른 테스트들과 같은 방식으로 직접 답한다.
        every { rosterRepository.save(any()) } answers { firstArg() }
        val visionClient = mockk<DispatchVisionClient>()

        val slicer = mockk<DispatchImageSlicer>()
        every { slicer.slice(any()) } returns
            listOf(ImageSlice(0, "A", 0, 100), ImageSlice(1, "B", 90, 200))

        fun serviceWith(name: String = "홍길동") =
            DispatchRecognitionService(
                rosterRepository,
                visionClient,
                DispatchVisionProperties(apiKey = "sk-test", fatherName = name),
                slicer,
            )

        fun sliceResult(
            hasName: Boolean,
            cells: List<Pair<Int, String>>,
            rowCount: Int = 13,
            visibleDays: List<Int> = cells.map { it.first },
        ) = RecognizedSlice(
            hasNameColumn = hasName,
            rowIndex = 2,
            rowCount = rowCount,
            year = 2026,
            month = 8,
            visibleDays = visibleDays,
            cells = cells.map { RecognizedCell(it.first, it.second) },
        )

        Given("성명 컬럼이 보이는 사진") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1", 2 to "", 3 to "*97"))
            every { visionClient.read(match { it.index == 1 }, "홍길동", null) } returns
                sliceResult(true, listOf(4 to "2", 5 to ""))

            val result = serviceWith().recognize(ByteArray(1))

            Then("이름으로 매칭했다고 알린다") {
                result.matchedBy shouldBe MatchedBy.NAME
                result.hasNameColumn shouldBe true
            }

            Then("숫자는 근무 순번이 된다") {
                result.days.first { it.day == 1 }.working shouldBe true
                result.days.first { it.day == 1 }.slot shouldBe 1
            }

            Then("빈 칸은 휴무다") {
                result.days.first { it.day == 2 }.working shouldBe false
                result.days.first { it.day == 2 }.slot shouldBe null
            }

            Then("글자는 휴무이고 원문이 note로 남는다") {
                val day3 = result.days.first { it.day == 3 }
                day3.working shouldBe false
                day3.note shouldBe "*97"
            }

            Then("두 조각이 합쳐진다") {
                result.days.map { it.day } shouldBe listOf(1, 2, 3, 4, 5)
            }

            Then("행 위치를 기억한다") {
                io.mockk.verify { rosterRepository.save(any()) }
            }
        }

        Given("성명 컬럼이 없는 사진과 저장된 기준") {
            every { rosterRepository.findByYearMonth("2026-08") } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            every { visionClient.read(any(), null, 2) } returns
                sliceResult(false, listOf(10 to "2", 11 to ""))

            val result = serviceWith().recognize(ByteArray(1))

            Then("행 위치로 매칭했다고 알린다") {
                result.matchedBy shouldBe MatchedBy.ROW_INDEX
            }
        }

        Given("성명 컬럼이 없는데 저장된 기준도 없는 경우") {
            every { rosterRepository.findByYearMonth(any()) } returns null
            every { visionClient.read(any(), "홍길동", null) } returns
                sliceResult(false, listOf(10 to "2"))

            Then("거부한다 — 추측해서 저장하지 않는다") {
                val exception = shouldThrow<CustomException> { serviceWith().recognize(ByteArray(1)) }
                exception.errorCode shouldBe DispatchErrorCode.ROSTER_NOT_FOUND
            }
        }

        Given("표 인원이 바뀐 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            every { visionClient.read(any(), null, 2) } returns
                sliceResult(false, listOf(10 to "2"), rowCount = 14)

            val result = serviceWith().recognize(ByteArray(1))

            Then("경고를 단다 — 행 매칭이 조용히 어긋나는 것을 막는다") {
                result.warnings shouldBe listOf("ROW_COUNT_CHANGED")
            }
        }

        Given("겹친 구간에서 두 조각의 답이 갈린 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(5 to "1", 6 to "2"))
            every { visionClient.read(match { it.index == 1 }, "홍길동", null) } returns
                sliceResult(true, listOf(6 to "", 7 to "3"))

            val result = serviceWith().recognize(ByteArray(1))

            Then("왼쪽 조각 값을 쓰되 conflict를 단다") {
                val day6 = result.days.first { it.day == 6 }
                day6.slot shouldBe 2
                day6.conflict shouldBe true
            }

            Then("갈리지 않은 날은 conflict가 아니다") {
                result.days.first { it.day == 5 }.conflict shouldBe false
            }
        }

        Given("visibleDays와 cells가 어긋난 조각") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                // 보이는 날짜는 1~3인데 99일 칸을 냈다 — 집계 컬럼을 날짜로 센 흔적이다.
                sliceResult(true, listOf(1 to "1", 99 to "4"), visibleDays = listOf(1, 2, 3))
            every { visionClient.read(match { it.index == 1 }, "홍길동", null) } returns
                sliceResult(true, listOf(4 to "2"))

            val result = serviceWith().recognize(ByteArray(1))

            Then("범위 밖 칸은 버린다") {
                result.days.none { it.day == 99 } shouldBe true
            }
        }

        Given("모든 조각이 인식에 실패한 경우") {
            every { rosterRepository.findByYearMonth(any()) } returns null
            every { visionClient.read(any(), any(), any()) } returns null

            Then("인식 실패를 알린다") {
                val exception = shouldThrow<CustomException> { serviceWith().recognize(ByteArray(1)) }
                exception.errorCode shouldBe DispatchErrorCode.VISION_UNAVAILABLE
            }
        }

        Given("대상 이름이 설정되지 않은 경우") {
            Then("거부한다 — 이름 없이 부르면 아무 행이나 읽어 온다") {
                val exception = shouldThrow<CustomException> { serviceWith(name = "").recognize(ByteArray(1)) }
                exception.errorCode shouldBe DispatchErrorCode.TARGET_NAME_NOT_CONFIGURED
            }
        }
    })
