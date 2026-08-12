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
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.YearMonth

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
        val rosterUpdater = mockk<DispatchRosterUpdater>(relaxed = true)
        val visionClient = mockk<DispatchVisionClient>()

        val slicer = mockk<DispatchImageSlicer>()
        every { slicer.slice(any()) } returns listOf(ImageSlice(0, "A"), ImageSlice(1, "B"))

        fun serviceWith(name: String = "홍길동") =
            DispatchRecognitionService(
                rosterRepository,
                visionClient,
                DispatchVisionProperties(apiKey = "sk-test", fatherName = name),
                slicer,
                rosterUpdater,
            )

        fun sliceResult(
            hasName: Boolean,
            cells: List<Pair<Int, String>>,
            rowCount: Int = 13,
            visibleDays: List<Int> = cells.map { it.first },
            // 대부분의 시나리오는 「대상을 찾았다」가 전제다. 못 찾은 경우만 명시적으로 끈다.
            targetFound: Boolean = true,
            year: Int = 2026,
            month: Int = 8,
            rowIndex: Int = 2,
        ) = RecognizedSlice(
            hasNameColumn = hasName,
            targetFound = targetFound,
            rowIndex = rowIndex,
            rowCount = rowCount,
            year = year,
            month = month,
            visibleDays = visibleDays,
            cells = cells.map { RecognizedCell(it.first, it.second) },
        )

        Given("성명 컬럼이 보이는 사진") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1", 2 to "", 3 to "*97"))
            // **오른쪽 조각에는 성명 컬럼이 없다**(폭의 45~100%). 이름으로 물으면 모델이
            // 없는 이름을 찾다 임의의 행을 읽는다. probe로 확정한 행 위치로만 읽어야 한다.
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(4 to "2", 5 to ""))

            val result = serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

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
                io.mockk.verify { rosterUpdater.upsert("2026-08", 2, 13) }
            }
        }

        Given("성명 컬럼이 없는 사진과 저장된 기준") {
            every { rosterRepository.findByYearMonth("2026-08") } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            // 첫 조각은 이름으로 물어본다. 모델이 「성명 컬럼이 없다」고 답하면
            // 저장된 행 위치로 **그 조각까지 다시** 읽는다.
            every { visionClient.read(any(), "홍길동", null) } returns
                sliceResult(false, emptyList())
            every { visionClient.read(any(), null, 2) } returns
                sliceResult(false, listOf(10 to "2", 11 to ""))

            val result = serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

            Then("행 위치로 매칭했다고 알린다") {
                result.matchedBy shouldBe MatchedBy.ROW_INDEX
            }

            Then("이름 모드로 읽은 빈 결과가 아니라 행 위치로 다시 읽은 값이 나온다") {
                result.days.first { it.day == 10 }.slot shouldBe 2
            }
        }

        Given("성명 컬럼이 없는데 저장된 기준도 없는 경우") {
            every { rosterRepository.findByYearMonth(any()) } returns null
            every { visionClient.read(any(), "홍길동", null) } returns
                sliceResult(false, listOf(10 to "2"))

            Then("거부한다 — 추측해서 저장하지 않는다") {
                val exception =
                    shouldThrow<CustomException> {
                        serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))
                    }
                exception.errorCode shouldBe DispatchErrorCode.ROSTER_NOT_FOUND
            }
        }

        // **「컬럼이 보이는가」와 「그 안에서 대상을 찾았는가」는 다른 질문이다.** 그 달 표에서
        // 빠졌거나 이름이 흐려 못 읽으면 모델은 정수를 요구하는 rowIndex에 아무 값이나 채운다.
        // 걸러내지 않으면 NAME 모드로 통과해 그 행 번호가 기준으로 저장되고, 이후 잘린 사진이
        // 전부 다른 기사의 근무를 읽어 온다.
        Given("성명 컬럼은 보이는데 대상 이름을 찾지 못한 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            every { visionClient.read(any(), "홍길동", null) } returns
                sliceResult(true, emptyList(), targetFound = false)

            Then("거부한다 — 저장된 기준으로 폴백하지 않는다") {
                // 폴백하면 그 사람이 이번 달 표에서 빠진 경우 다른 기사의 근무가 들어온다.
                val exception =
                    shouldThrow<CustomException> {
                        serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))
                    }
                exception.errorCode shouldBe DispatchErrorCode.TARGET_NOT_FOUND
            }

            Then("기준을 갱신하지 않는다") {
                shouldThrow<CustomException> {
                    serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))
                }
                io.mockk.verify(exactly = 0) { rosterUpdater.upsert(any(), any(), any()) }
            }

            Then("나머지 조각을 읽지도 않는다 — 행 위치가 확정되지 않았다") {
                shouldThrow<CustomException> {
                    serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))
                }
                io.mockk.verify(exactly = 0) { visionClient.read(any(), null, any()) }
            }
        }

        // **「찾았다」고 답하면서 이상한 숫자를 주는 경우.** 스키마는 정수라는 것만 보장하므로
        // rowIndex=99, rowCount=13 같은 앞뒤가 안 맞는 답이 통과할 수 있었다. 그러면 존재하지
        // 않는 행 번호가 기준으로 저장되고 이후 잘린 사진이 전부 없는 행을 읽는다.
        Given("대상을 찾았다면서 rowIndex가 rowCount 이상인 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(any(), "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), rowCount = 13, rowIndex = 99)

            Then("인식 실패로 거부한다 — 존재하지 않는 행이다") {
                val exception =
                    shouldThrow<CustomException> {
                        serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))
                    }
                exception.errorCode shouldBe DispatchErrorCode.VISION_UNAVAILABLE
            }

            Then("기준을 저장하지 않는다") {
                shouldThrow<CustomException> {
                    serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))
                }
                io.mockk.verify(exactly = 0) { rosterUpdater.upsert(any(), any(), any()) }
            }

            Then("나머지 조각을 읽지도 않는다 — 틀린 행 위치를 넘기지 않는다") {
                // rowIndex는 나머지 조각에 knownRowIndex로 실려 나간다. 뒤에서 걸러 봐야
                // 이미 조각당 $0.017을 쓴 뒤다.
                shouldThrow<CustomException> {
                    serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))
                }
                io.mockk.verify(exactly = 0) { visionClient.read(any(), null, any()) }
            }
        }

        Given("rowCount를 0으로 준 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(any(), "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), rowCount = 0, rowIndex = 0)

            Then("인식 실패로 거부한다 — 표를 못 읽은 것이다") {
                val exception =
                    shouldThrow<CustomException> {
                        serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))
                    }
                exception.errorCode shouldBe DispatchErrorCode.VISION_UNAVAILABLE
            }
        }

        Given("rowIndex를 음수로 준 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(any(), "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), rowCount = 13, rowIndex = -1)

            Then("인식 실패로 거부한다") {
                val exception =
                    shouldThrow<CustomException> {
                        serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))
                    }
                exception.errorCode shouldBe DispatchErrorCode.VISION_UNAVAILABLE
            }
        }

        Given("경계값 — rowIndex가 rowCount - 1인 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), rowCount = 13, rowIndex = 12)
            every { visionClient.read(match { it.index == 1 }, null, 12) } returns
                sliceResult(true, listOf(2 to "2"), rowCount = 13, rowIndex = 12)

            val result = serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

            Then("정상 범위이므로 통과한다 — 마지막 행도 유효하다") {
                result.rowIndex shouldBe 12
                result.days.first { it.day == 1 }.slot shouldBe 1
            }
        }

        // **저장된 행 위치가 지금 표에 없는 경우.** 인원이 줄어 그 행이 사라지면 모델은 「없는
        // 행을 읽으라」는 지시를 받은 것이라 무엇을 돌려주든 지어낸 값이다. ROW_COUNT_CHANGED
        // 경고로는 부족하다 — 그 경고는 「행이 밀렸을 수 있으니 검수하라」는 뜻이고 값 자체는
        // 볼 만하다는 전제인데, 여기서는 볼 값이 아예 없다.
        Given("성명 컬럼이 없고 저장된 행이 줄어든 표 밖인 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 12, rowCount = 13)
            every { visionClient.read(any(), "홍길동", null) } returns
                sliceResult(false, emptyList(), rowCount = 5)
            every { visionClient.read(any(), null, 12) } returns
                sliceResult(false, listOf(10 to "2"), rowCount = 5)

            Then("경고가 아니라 거부한다 — 지어낸 값을 검수 화면에 띄우지 않는다") {
                val exception =
                    shouldThrow<CustomException> {
                        serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))
                    }
                exception.errorCode shouldBe DispatchErrorCode.VISION_UNAVAILABLE
            }
        }

        // 성명 컬럼이 아예 안 보이는 경우는 targetFound가 false여도 기존 갈래(저장된 기준 사용)
        // 그대로다 — 애초에 이름으로 찾는 모드가 아니다.
        Given("성명 컬럼이 없고 targetFound도 false인 사진") {
            every { rosterRepository.findByYearMonth("2026-08") } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            every { visionClient.read(any(), "홍길동", null) } returns
                sliceResult(false, emptyList(), targetFound = false)
            every { visionClient.read(any(), null, 2) } returns
                sliceResult(false, listOf(10 to "2"), targetFound = false)

            val result = serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

            Then("TARGET_NOT_FOUND가 아니라 저장된 기준으로 읽는다") {
                result.matchedBy shouldBe MatchedBy.ROW_INDEX
                result.days.first { it.day == 10 }.slot shouldBe 2
            }
        }

        Given("표 인원이 바뀐 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            every { visionClient.read(any(), "홍길동", null) } returns
                sliceResult(false, emptyList(), rowCount = 14)
            every { visionClient.read(any(), null, 2) } returns
                sliceResult(false, listOf(10 to "2"), rowCount = 14)

            val result = serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

            Then("경고를 단다 — 행 매칭이 조용히 어긋나는 것을 막는다") {
                result.warnings shouldBe listOf("ROW_COUNT_CHANGED")
            }
        }

        Given("겹친 구간에서 두 조각의 답이 갈린 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(5 to "1", 6 to "2"))
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(6 to "", 7 to "3"))

            val result = serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

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
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(4 to "2"))

            val result = serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

            Then("범위 밖 칸은 버린다") {
                result.days.none { it.day == 99 } shouldBe true
            }
        }

        // strict 스키마는 year·month가 **정수라는 것만** 보장한다. month=13이면 YearMonth.of가
        // DateTimeException을 던지는데, 이는 DispatchVisionClient의 실패 처리 바깥이라 그대로
        // 500이 됐다. 사진 제목을 잘못 읽은 것뿐인데 서버 결함처럼 보인다.
        // 연월은 요청 값이 기준이고 사진 제목은 교차 확인용이라, 못 읽으면 검사만 건너뛴다.
        Given("모델이 13월을 준 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(any(), "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), month = 13)
            every { visionClient.read(any(), null, 2) } returns
                sliceResult(true, listOf(2 to "2"), month = 13)

            val result = serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

            Then("500이 아니라 정상 응답이 나온다") {
                result.yearMonth shouldBe "2026-08"
                result.days.first { it.day == 1 }.slot shouldBe 1
            }

            Then("YEAR_MONTH_MISMATCH를 달지 않는다 — 못 읽은 것이지 어긋난 것이 아니다") {
                result.warnings shouldBe emptyList()
            }
        }

        // 연도는 month와 달리 YearMonth.of가 받아 준다(99999도 유효한 연도다). 그래서 500이
        // 아니라 **엉뚱한 경고**가 문제였다 — 99999-08 != 2026-08이라 YEAR_MONTH_MISMATCH가
        // 붙고, 그 경고 때문에 멀쩡한 사진인데도 행 위치 갱신이 통째로 막혔다.
        Given("모델이 말도 안 되는 연도를 준 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(any(), "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), year = 99999)
            every { visionClient.read(any(), null, 2) } returns
                sliceResult(true, listOf(2 to "2"), year = 99999)

            val result = serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

            Then("제목을 못 읽은 것으로 보고 엉뚱한 경고를 달지 않는다") {
                result.yearMonth shouldBe "2026-08"
                result.warnings shouldBe emptyList()
            }

            Then("경고가 없으므로 행 위치는 정상적으로 갱신된다") {
                io.mockk.verify { rosterUpdater.upsert("2026-08", 2, 13) }
            }
        }

        Given("모델이 연월을 아예 못 읽어 0을 준 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(any(), "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), year = 0, month = 0)
            every { visionClient.read(any(), null, 2) } returns
                sliceResult(true, listOf(2 to "2"), year = 0, month = 0)

            val result = serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

            Then("기존대로 검사를 건너뛴다") {
                result.warnings shouldBe emptyList()
            }
        }

        Given("요청한 달과 사진의 달이 다른 경우") {
            every { rosterRepository.findByYearMonth("2026-09") } returns null
            every { visionClient.read(any(), "홍길동", null) } returns
                // 사진은 8월인데 9월로 요청했다
                sliceResult(true, listOf(1 to "1"))
            every { visionClient.read(any(), null, 2) } returns
                sliceResult(true, listOf(1 to "1"))

            val result = serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 9))

            Then("경고를 단다 — 엉뚱한 달에 저장되는 것을 막는다") {
                result.warnings shouldBe listOf("YEAR_MONTH_MISMATCH")
            }

            Then("요청한 달을 기준으로 삼는다") {
                result.yearMonth shouldBe "2026-09"
            }

            Then("행 위치를 갱신하지 않는다 — 믿을 수 없다고 판정한 사진이다") {
                // 갱신해 버리면 검수 화면에서 취소해도 되돌아가지 않고,
                // 이후 그 달의 잘린 사진이 전부 틀린 행을 읽는다.
                io.mockk.verify(exactly = 0) { rosterUpdater.upsert(any(), any(), any()) }
            }
        }

        Given("표 인원이 바뀌었고 성명 컬럼도 보이는 사진") {
            every { rosterRepository.findByYearMonth("2026-08") } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), rowCount = 14)
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(2 to "2"), rowCount = 14)

            val result = serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

            Then("경고를 단다") {
                result.warnings shouldBe listOf("ROW_COUNT_CHANGED")
            }

            Then("경고가 붙었으므로 기준을 덮지 않는다") {
                // 여기서 rowCount를 덮으면 ROW_COUNT_CHANGED가 한 번만 뜨고 다음 업로드부터 사라진다.
                io.mockk.verify(exactly = 0) { rosterUpdater.upsert(any(), any(), any()) }
            }
        }

        Given("성명 컬럼 없이 기존 기준으로만 읽은 사진") {
            every { rosterRepository.findByYearMonth("2026-08") } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            every { visionClient.read(any(), "홍길동", null) } returns sliceResult(false, emptyList())
            every { visionClient.read(any(), null, 2) } returns sliceResult(false, listOf(10 to "2"))

            serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

            Then("기준을 덮지 않는다 — 새로 배운 행 위치가 없다") {
                io.mockk.verify(exactly = 0) { rosterUpdater.upsert(any(), any(), any()) }
            }
        }

        Given("모든 조각이 인식에 실패한 경우") {
            every { rosterRepository.findByYearMonth(any()) } returns null
            every { visionClient.read(any(), any(), any()) } returns null

            Then("인식 실패를 알린다") {
                val exception =
                    shouldThrow<CustomException> {
                        serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))
                    }
                exception.errorCode shouldBe DispatchErrorCode.VISION_UNAVAILABLE
            }
        }

        // **일부만 성공한 것을 성공으로 내보내면 안 된다.** 실패한 조각을 조용히 버리면 그 달
        // 후반부가 통째로 빠진 결과가 경고 하나 없이 나가고, 검수 화면에서는 「잘린 사진이라
        // 원래 일부만 나온 것」과 구분되지 않는다. 실측에서 파싱 실패가 5회 중 1회 났다.
        Given("첫 조각은 성공했지만 둘째 조각이 실패한 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1", 2 to ""))
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns null

            Then("절반짜리를 성공으로 내보내지 않고 거부한다") {
                val exception =
                    shouldThrow<CustomException> {
                        serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))
                    }
                exception.errorCode shouldBe DispatchErrorCode.VISION_UNAVAILABLE
            }

            Then("행 위치도 갱신하지 않는다") {
                shouldThrow<CustomException> {
                    serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))
                }
                io.mockk.verify(exactly = 0) { rosterUpdater.upsert(any(), any(), any()) }
            }
        }

        Given("대상 이름이 설정되지 않은 경우") {
            Then("거부한다 — 이름 없이 부르면 아무 행이나 읽어 온다") {
                val exception =
                    shouldThrow<CustomException> {
                        serviceWith(name = "").recognize(ByteArray(1), YearMonth.of(2026, 8))
                    }
                exception.errorCode shouldBe DispatchErrorCode.TARGET_NAME_NOT_CONFIGURED
            }
        }

        Given("사진을 읽기 전에는 기준을 조회할 수 없는 구조") {
            // 연월을 사진에서 읽으려면 **읽은 뒤에** 그 달의 기준을 찾아야 한다.
            // 순서가 뒤집혀 있으면 「연월을 알아야 기준을 찾고, 기준을 찾아야 읽는다」는
            // 순환에 다시 빠진다.
            //
            // mock은 spec 전체가 공유하므로 앞선 블록들의 호출 기록이 쌓여 있다.
            // 지우지 않으면 「앞 블록의 read → 뒤 블록의 조회」가 순서를 만족해 버려
            // 순서가 뒤집혀 있어도 통과한다.
            clearMocks(rosterRepository, visionClient, answers = false)
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"))
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(4 to "2"))

            serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

            Then("첫 조각을 읽은 뒤에 기준을 조회한다") {
                verifyOrder {
                    visionClient.read(match { it.index == 0 }, "홍길동", null)
                    rosterRepository.findByYearMonth("2026-08")
                }
            }
        }

        Given("연월 없이 올린 사진") {
            every { rosterRepository.findByYearMonth("2026-09") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), year = 2026, month = 9)
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(4 to "2"), year = 2026, month = 9)

            val result = serviceWith().recognize(ByteArray(1), null)

            Then("사진에서 읽은 연월이 기준이 된다") {
                result.yearMonth shouldBe "2026-09"
            }

            Then("그 달의 기준을 조회한다") {
                verify { rosterRepository.findByYearMonth("2026-09") }
            }

            Then("연월이 어긋났다는 경고는 붙지 않는다") {
                // 비교할 요청값이 없다. 사진값이 곧 기준이다.
                result.warnings shouldBe emptyList()
            }
        }

        Given("요청 연월과 사진 연월이 둘 다 있고 서로 다른 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), year = 2026, month = 9)
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(4 to "2"), year = 2026, month = 9)

            val result = serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

            Then("요청값이 기준이다") {
                // 앱이 명시했으면 그 뜻을 따른다. 사진 제목은 교차 확인용이다.
                result.yearMonth shouldBe "2026-08"
            }

            Then("어긋났다고 경고한다") {
                result.warnings shouldBe listOf("YEAR_MONTH_MISMATCH")
            }
        }

        Given("연월도 없고 사진에서도 못 읽은 경우") {
            clearMocks(rosterUpdater, answers = false)
            every { rosterRepository.findByYearMonth(any()) } returns null
            // 제목이 잘린 사진. 모델이 0을 돌려준다.
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1", 31 to "2"), year = 0, month = 0)
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(4 to "2"), year = 0, month = 0)

            val result = serviceWith().recognize(ByteArray(1), null)

            Then("응답 연월이 비어 있다") {
                // 앱의 검수 화면이 채운다. 채우기 전에는 저장이 잠긴다.
                result.yearMonth shouldBe null
            }

            Then("31일도 살아남는다") {
                // 그 달의 마지막 날을 알 수 없으므로 1..31로 둔다. 좁게 잡으면
                // 31일이 있는 달의 마지막 날이 조용히 사라진다.
                result.days.map { it.day } shouldBe listOf(1, 4, 31)
            }

            Then("줄 위치를 갱신하지 않는다") {
                // 어느 달의 기준인지 적을 수 없다. 미상인 채로 저장하면 이후 사진이
                // 전부 그 값을 되쓴다.
                verify(exactly = 0) { rosterUpdater.upsert(any(), any(), any()) }
            }
        }

        Given("연월도 성명 컬럼도 없는 잘린 사진") {
            // 중간에 바뀐 부분만 잘라 온 사진. 제목도 성명 컬럼도 없다.
            clearMocks(rosterUpdater, answers = false)
            every { rosterRepository.findTopByOrderByYearMonthDesc() } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(false, listOf(20 to "1"), year = 0, month = 0)
            every { visionClient.read(match { it.index == 0 }, null, 2) } returns
                sliceResult(false, listOf(20 to "1"), year = 0, month = 0)
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(false, listOf(21 to "2"), year = 0, month = 0)

            val result = serviceWith().recognize(ByteArray(1), null)

            Then("최근 기준의 행 위치로 읽는다") {
                result.matchedBy shouldBe MatchedBy.ROW_INDEX
                result.rowIndex shouldBe 2
            }

            Then("다른 달 기준을 대신 썼다고 경고한다") {
                // 인원이 그 사이 바뀌었으면 순번이 밀린다. 사람이 사진과 대조해야 한다.
                result.warnings shouldBe listOf("ROSTER_FROM_OTHER_MONTH")
            }

            Then("줄 위치를 갱신하지 않는다") {
                verify(exactly = 0) { rosterUpdater.upsert(any(), any(), any()) }
            }
        }

        Given("연월은 미상이지만 성명 컬럼이 보이는 사진") {
            clearMocks(rosterUpdater, answers = false)
            every { rosterRepository.findTopByOrderByYearMonthDesc() } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), year = 0, month = 0)
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(4 to "2"), year = 0, month = 0)

            val result = serviceWith().recognize(ByteArray(1), null)

            Then("다른 달 기준을 대신 썼다는 경고가 붙지 않는다") {
                // 이름으로 행을 찾았으므로 저장된 기준을 쓰지 않았다. 경고할 것이 없다.
                result.warnings shouldBe emptyList()
            }
        }

        // 위 블록은 최근 기준과 사진의 인원수가 같아 우연히 통과한다. 다르면 어떻게 되는가가
        // 진짜 질문이다 — 다른 달의 인원수는 이름으로 찾은 이 사진과 아무 상관이 없다.
        Given("연월은 미상이고 성명 컬럼이 보이는데 최근 기준의 인원이 다른 경우") {
            clearMocks(rosterRepository, rosterUpdater, answers = false)
            every { rosterRepository.findTopByOrderByYearMonthDesc() } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 9)
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), rowCount = 13, year = 0, month = 0)
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(4 to "2"), rowCount = 13, year = 0, month = 0)

            val result = serviceWith().recognize(ByteArray(1), null)

            Then("인원이 바뀌었다는 경고를 달지 않는다") {
                // 이름으로 행을 찾았으므로 행이 밀릴 일이 없다. 여기서 경고를 달면
                // 사람이 경고를 무시하는 법을 배운다 — 경고로 지탱하는 설계가 무너진다.
                result.warnings shouldBe emptyList()
            }

            Then("쓰지도 않을 기준을 조회하지 않는다") {
                verify(exactly = 0) { rosterRepository.findTopByOrderByYearMonthDesc() }
            }
        }

        Given("연월이 미상이고 저장된 기준도 없는 경우") {
            every { rosterRepository.findTopByOrderByYearMonthDesc() } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(false, listOf(20 to "1"), year = 0, month = 0)

            Then("거부한다") {
                // 어느 줄이 대상인지 알 방법이 없다. 추측해서 저장하느니 거부한다.
                shouldThrow<CustomException> {
                    serviceWith().recognize(ByteArray(1), null)
                }
            }
        }
    })
