package com.toy.backend.dispatch

import com.toy.backend.common.exception.CustomException
import com.toy.backend.dispatch.image.DispatchImageSlicer
import com.toy.backend.dispatch.image.ImageSlice
import com.toy.backend.dispatch.llm.DispatchVisionClient
import com.toy.backend.dispatch.llm.DispatchVisionProperties
import com.toy.backend.dispatch.llm.RecognizedSlice
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth

/**
 * 사진 한 장을 조각으로 나눠 읽고 하나로 합친다. **아무것도 저장하지 않는다** —
 * `DispatchRoster`(행 위치)만 갱신하고, 근무 값은 검수를 거쳐 별도 API로 저장된다.
 * 인식 결과를 바로 저장하면 틀린 값이 조용히 박히고 달력은 틀렸다는 사실조차 알려주지 않는다.
 *
 * `DispatchImageSlicer`를 주입받는다 — 테스트에서 실제 이미지 처리를 건너뛰기 위해서다.
 * 람다에 기본값을 주는 형태로 두면 Spring이 함수 타입 빈을 찾다 기동에 실패한다.
 */
@Service
class DispatchRecognitionService(
    private val rosterRepository: DispatchRosterRepository,
    private val visionClient: DispatchVisionClient,
    private val properties: DispatchVisionProperties,
    private val slicer: DispatchImageSlicer,
) {
    @Transactional
    fun recognize(bytes: ByteArray): RecognitionResponse {
        val targetName =
            properties.fatherName.takeIf { it.isNotBlank() }
                // 이름 없이 부른 프롬프트는 아무 행이나 읽어 온다.
                ?: throw CustomException(DispatchErrorCode.TARGET_NAME_NOT_CONFIGURED)

        val slices = slicer.slice(bytes)

        // 사진은 항상 이번 달 배차표다. 이번 달 행 위치를 이미 알고 있으면 성명 없이 위치로만
        // 읽는다 — 이름 검색보다 싸고, 모델이 이름을 못 찾는 위험도 없다. 그래서 어떤 조각을
        // 부르기도 전에, 이번 달 기준이 있는지부터 확인한다.
        val knownRoster = rosterRepository.findByYearMonth(YearMonth.now().toString())

        fun readSlice(slice: ImageSlice): RecognizedSlice? =
            if (knownRoster != null) {
                visionClient.read(slice, null, knownRoster.rowIndex)
            } else {
                visionClient.read(slice, targetName, null)
            }

        // 첫 조각으로 연·월과 「성명 컬럼이 보이는가」를 확정한다.
        val first = readSlice(slices.first()) ?: throw CustomException(DispatchErrorCode.VISION_UNAVAILABLE)

        val yearMonth = YearMonth.of(first.year, first.month)
        val matchedBy = if (first.hasNameColumn) MatchedBy.NAME else MatchedBy.ROW_INDEX
        val rowIndex =
            when {
                first.hasNameColumn -> first.rowIndex

                knownRoster != null -> knownRoster.rowIndex

                // 성명 컬럼도 없고 기준도 없으면 어느 줄이 대상인지 알 방법이 없다.
                else -> throw CustomException(DispatchErrorCode.ROSTER_NOT_FOUND, yearMonth.toString())
            }

        val rest = slices.drop(1).mapNotNull { readSlice(it) }
        val results = listOf(first) + rest

        val warnings = mutableListOf<String>()
        // 인원이 바뀌면 행 순서가 밀려 엉뚱한 기사의 근무가 들어온다.
        if (knownRoster != null && knownRoster.rowCount != first.rowCount) {
            warnings += "ROW_COUNT_CHANGED"
        }

        upsertRoster(yearMonth.toString(), rowIndex, first.rowCount, knownRoster)

        return RecognitionResponse(
            yearMonth = yearMonth.toString(),
            hasNameColumn = first.hasNameColumn,
            matchedBy = matchedBy,
            rowIndex = rowIndex,
            rowCount = first.rowCount,
            warnings = warnings,
            days = merge(results, yearMonth),
        )
    }

    private fun upsertRoster(
        yearMonth: String,
        rowIndex: Int,
        rowCount: Int,
        existing: DispatchRoster?,
    ) {
        if (existing == null) {
            rosterRepository.save(DispatchRoster(yearMonth, rowIndex, rowCount))
        } else {
            existing.rowIndex = rowIndex
            existing.rowCount = rowCount
        }
    }

    /**
     * 조각들을 날짜 기준으로 합친다. **겹친 구간이 공짜 교차검증이다** — 값이 갈리면
     * 어느 쪽도 고르지 않고 왼쪽 조각 값을 쓰되 `conflict`를 달아 사람에게 넘긴다.
     */
    private fun merge(
        results: List<RecognizedSlice>,
        yearMonth: YearMonth,
    ): List<RecognitionDay> {
        val merged = LinkedHashMap<Int, RecognitionDay>()

        results.forEach { slice ->
            val visible = slice.visibleDays.toSet()
            slice.cells.forEach { cell ->
                // 집계 컬럼을 날짜로 셌으면 visibleDays 밖의 날짜가 나온다. 그 칸은 버린다.
                if (cell.day !in visible) return@forEach
                if (cell.day !in 1..yearMonth.lengthOfMonth()) return@forEach

                val parsed = toDay(cell.day, cell.value)
                val existing = merged[cell.day]
                merged[cell.day] =
                    when {
                        existing == null -> parsed

                        existing.working == parsed.working &&
                            existing.slot == parsed.slot &&
                            existing.note == parsed.note -> existing

                        // 왼쪽 조각 값을 유지하고 갈렸다는 사실만 남긴다.
                        else -> existing.copy(conflict = true)
                    }
            }
        }

        return merged.values.sortedBy { it.day }
    }

    /**
     * 칸 하나를 해석한다. 숫자면 근무 순번, 글자면 휴무 + 원문, 빈 칸이면 휴무다.
     * `휴`·`간담회`·`예비군`이 근무가 아니라는 것은 합의된 규칙이다.
     */
    private fun toDay(
        day: Int,
        raw: String,
    ): RecognitionDay {
        val value = raw.trim()
        val slot = value.toIntOrNull()
        return RecognitionDay(
            day = day,
            working = slot != null,
            slot = slot,
            note = value.takeIf { it.isNotEmpty() && slot == null },
            conflict = false,
        )
    }
}
