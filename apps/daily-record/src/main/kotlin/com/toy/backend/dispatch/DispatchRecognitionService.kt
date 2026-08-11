package com.toy.backend.dispatch

import com.toy.backend.common.exception.CustomException
import com.toy.backend.dispatch.image.DispatchImageSlicer
import com.toy.backend.dispatch.llm.DispatchVisionClient
import com.toy.backend.dispatch.llm.DispatchVisionProperties
import com.toy.backend.dispatch.llm.RecognizedSlice
import org.springframework.stereotype.Service
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
    private val rosterUpdater: DispatchRosterUpdater,
) {
    /**
     * **연·월을 인자로 받는다.** 사진에서 읽은 값으로 정하면 「사진을 읽기 전에는 어느 달
     * 기준을 조회할지 모른다」는 순환에 빠지고, 현재 달로 대신하면 8월 말에 9월 배차표를
     * 미리 올릴 때 엉뚱한 달의 기준을 본다. 앱은 어느 달 배차표인지 알고 있다.
     *
     * **트랜잭션으로 감싸지 않는다.** 조각당 최대 2회, 최대 3조각을 `timeoutSeconds = 120`으로
     * 읽으므로 감싸면 최악의 경우 DB 커넥션을 12분 붙잡는다. DB를 건드리는 것은 기준 조회
     * 한 번과 `DispatchRosterUpdater` 호출 한 번뿐이고, 각자 자기 트랜잭션에서 돈다.
     */
    fun recognize(
        bytes: ByteArray,
        yearMonth: YearMonth,
    ): RecognitionResponse {
        val targetName =
            properties.fatherName.takeIf { it.isNotBlank() }
                // 이름 없이 부른 프롬프트는 아무 행이나 읽어 온다.
                ?: throw CustomException(DispatchErrorCode.TARGET_NAME_NOT_CONFIGURED)

        val slices = slicer.slice(bytes)
        val roster = rosterRepository.findByYearMonth(yearMonth.toString())

        // 첫 조각을 이름으로 물어 「성명 컬럼이 보이는가」를 판별한다. 왼쪽 조각에 성명 컬럼이 있다.
        val probe =
            visionClient.read(slices.first(), targetName, null)
                ?: throw CustomException(DispatchErrorCode.VISION_UNAVAILABLE)

        val matchedBy = if (probe.hasNameColumn) MatchedBy.NAME else MatchedBy.ROW_INDEX
        val rowIndex =
            when {
                probe.hasNameColumn -> probe.rowIndex

                roster != null -> roster.rowIndex

                // 성명 컬럼도 없고 기준도 없으면 어느 줄이 대상인지 알 방법이 없다.
                else -> throw CustomException(DispatchErrorCode.ROSTER_NOT_FOUND, yearMonth.toString())
            }

        // **probe 뒤로는 언제나 행 위치로 읽는다.** 성명 컬럼은 표 맨 왼쪽에 있어 두 번째
        // 조각(폭의 45~100%)에는 없다. 없는 이름을 찾으라고 물으면 모델은 임의의 행을 읽고,
        // 그 값이 왼쪽 조각과 합쳐져 그 달 후반부가 다른 기사의 근무로 채워진다.
        // 성명 컬럼이 아예 없으면 **첫 조각도 행 위치로 다시 읽는다** — 같은 이유다.
        val results =
            if (probe.hasNameColumn) {
                listOf(probe) + slices.drop(1).mapNotNull { visionClient.read(it, null, rowIndex) }
            } else {
                slices.mapNotNull { visionClient.read(it, null, rowIndex) }
            }
        if (results.isEmpty()) throw CustomException(DispatchErrorCode.VISION_UNAVAILABLE)

        val rowCount = results.first().rowCount
        val warnings = mutableListOf<String>()
        // 인원이 바뀌면 행 순서가 밀려 엉뚱한 기사의 근무가 들어온다.
        if (roster != null && roster.rowCount != rowCount) {
            warnings += "ROW_COUNT_CHANGED"
        }
        // 사진의 달과 요청한 달이 다르면 엉뚱한 달에 저장된다.
        if (probe.year != 0 && probe.month != 0 &&
            YearMonth.of(probe.year, probe.month) != yearMonth
        ) {
            warnings += "YEAR_MONTH_MISMATCH"
        }

        // **새로 배운 것이 있고 의심할 근거가 없을 때만 갱신한다.** 경고가 붙은 사진(9월 사진을
        // 8월로 올린 경우 등)에서 읽은 값으로 덮으면 검수 화면에서 취소해도 되돌아가지 않고,
        // 이후 잘린 사진이 전부 틀린 행을 읽는다. `ROW_INDEX` 모드는 기존 기준을 되쓴 것이라
        // 새로 배운 것이 없다 — 여기서 `rowCount`를 덮으면 경고가 한 번만 뜨고 사라진다.
        if (matchedBy == MatchedBy.NAME && warnings.isEmpty()) {
            rosterUpdater.upsert(yearMonth.toString(), rowIndex, rowCount)
        }

        return RecognitionResponse(
            yearMonth = yearMonth.toString(),
            hasNameColumn = probe.hasNameColumn,
            matchedBy = matchedBy,
            rowIndex = rowIndex,
            rowCount = rowCount,
            warnings = warnings,
            days = merge(results, yearMonth),
        )
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
