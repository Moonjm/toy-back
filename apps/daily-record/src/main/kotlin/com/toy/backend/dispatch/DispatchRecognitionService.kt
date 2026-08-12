package com.toy.backend.dispatch

import com.toy.backend.common.exception.CustomException
import com.toy.backend.dispatch.image.DispatchImageSlicer
import com.toy.backend.dispatch.image.ImageSlice
import com.toy.backend.dispatch.llm.DispatchVisionClient
import com.toy.backend.dispatch.llm.DispatchVisionProperties
import com.toy.backend.dispatch.llm.RecognizedSlice
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.YearMonth

private val log = KotlinLogging.logger {}

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
     * **연월은 선택이다.** 사진 제목에서 읽은 값을 기준으로 쓴다 — probe가 이미 `year`·`month`를
     * 주므로 「사진을 읽기 전에는 어느 달 기준을 조회할지 모른다」는 순환은 읽는 순서를 바꿔
     * 푼다. 요청으로 받은 값이 있으면 그쪽이 기준이고 사진 제목은 교차 확인용이다.
     *
     * 둘 다 없으면 **미상**으로 둔다. 날짜 범위를 좁힐 수 없고 줄 위치도 갱신할 수 없으므로,
     * 응답 연월을 비워 검수 화면이 채우게 한다.
     *
     * **트랜잭션으로 감싸지 않는다.** 조각당 최대 2회, 최대 3조각을 `timeoutSeconds = 120`으로
     * 읽으므로 감싸면 최악의 경우 DB 커넥션을 12분 붙잡는다. DB를 건드리는 것은 기준 조회
     * 한 번과 `DispatchRosterUpdater` 호출 한 번뿐이고, 각자 자기 트랜잭션에서 돈다.
     */
    fun recognize(
        bytes: ByteArray,
        yearMonth: YearMonth?,
    ): RecognitionResponse {
        val targetName =
            properties.fatherName.takeIf { it.isNotBlank() }
                // 이름 없이 부른 프롬프트는 아무 행이나 읽어 온다.
                ?: throw CustomException(DispatchErrorCode.TARGET_NAME_NOT_CONFIGURED)

        val slices = slicer.slice(bytes)

        // 첫 조각을 이름으로 물어 「성명 컬럼이 보이는가」를 판별한다. 왼쪽 조각에 성명 컬럼이 있다.
        val probe =
            visionClient.read(slices.first(), targetName, null)
                ?: throw CustomException(DispatchErrorCode.VISION_UNAVAILABLE)

        // **`YearMonth.of`에 넣기 전에 범위를 확인한다.** strict 스키마는 정수라는 것만 보장하므로
        // `month = 13` 같은 값이 오면 `DateTimeException`이 나는데, 이는 `DispatchVisionClient`의
        // 실패 처리 바깥이라 그대로 500이 된다. 사진 제목을 잘못 읽은 것뿐인데 서버 결함처럼 보인다.
        //
        // **`0`은 「제목을 못 읽었다」는 약속이다** — `DispatchVisionClient`의 프롬프트가 그렇게
        // 지시한다. 스키마가 `year`·`month`를 required 정수로 두어 모델은 무엇이든 채워야 하므로,
        // 저쪽 지시가 빠지면 지어낸 연월이 그대로 기준이 되어 다른 달의 줄 위치를 불러온다.
        val photoYearMonth =
            if (probe.month in 1..12 && probe.year in PLAUSIBLE_YEARS) {
                YearMonth.of(probe.year, probe.month)
            } else {
                if (probe.year != 0 || probe.month != 0) {
                    log.warn { "배차표 제목의 연월을 해석할 수 없다: year=${probe.year}, month=${probe.month}" }
                }
                null
            }

        // 요청값이 있으면 그쪽이 기준이다. 없으면 사진에서 읽은 값을 쓴다. 둘 다 없으면 미상이다.
        val effectiveYearMonth = yearMonth ?: photoYearMonth

        // 에러 메시지의 `%s` 자리. 미상이어도 문장이 성립해야 한다.
        val yearMonthLabel = effectiveYearMonth?.toString() ?: "연월 미상"

        // **「컬럼이 보이는가」와 「그 안에서 대상을 찾았는가」는 다른 질문이다.** 성명 컬럼은
        // 멀쩡히 보이는데 그 달 표에 그 사람이 없거나(배차표에서 빠졌거나) 이름이 흐려 판독이
        // 안 되는 경우가 있다. 그때 모델은 스키마가 정수를 요구하는 `rowIndex`에 아무 값이나
        // 채우므로, 이것을 걸러내지 않으면 NAME 모드로 통과해 그 행 번호가 `DispatchRoster`에
        // 저장까지 되고 이후 잘린 사진이 전부 다른 기사의 근무를 읽어 온다.
        //
        // **저장된 기준으로 폴백하지 않는다.** 이름이 표에 없다는 것은 그 사람이 그 달 표에서
        // 빠졌거나 판독이 실패했다는 뜻이고, 어느 쪽이든 저장된 행 위치를 쓰면 다른 사람 데이터가
        // 들어온다. 「추측해서 저장하느니 거부한다」는 이 기능의 원칙 그대로 거부한다.
        if (probe.hasNameColumn && !probe.targetFound) {
            throw CustomException(DispatchErrorCode.TARGET_NOT_FOUND, yearMonthLabel)
        }

        // **「찾았다」고 답하면서 이상한 숫자를 주는 경우가 남는다.** 스키마는 `rowIndex`·`rowCount`가
        // 정수라는 것만 보장하므로 `rowIndex = 99, rowCount = 13` 같은 앞뒤가 안 맞는 답이 그대로
        // 통과할 수 있다. 그러면 존재하지 않는 행 번호가 `DispatchRoster`에 저장되고, 이후 잘린
        // 사진이 전부 없는 행을 읽는다 — 한 번 저장되면 사람이 눈치채기 전까지 계속 틀린다.
        //
        // **다른 조각을 읽기 전에** 막는다. `rowIndex`는 나머지 조각에 `knownRowIndex`로 실려
        // 나가므로, 뒤에서 걸러 봐야 이미 조각당 $0.017을 쓴 뒤다.
        if (probe.hasNameColumn && !isRowWithinTable(probe.rowIndex, probe.rowCount)) {
            log.warn { "인식 결과의 행 위치가 표 범위를 벗어났다: rowIndex=${probe.rowIndex}, rowCount=${probe.rowCount}" }
            throw CustomException(DispatchErrorCode.VISION_UNAVAILABLE)
        }

        // **기준 조회는 사진을 읽은 뒤다.** 연월을 사진에서 읽으려면 이 순서여야 한다
        // — 반대로 두면 「연월을 알아야 기준을 찾고, 기준을 찾아야 읽는다」가 된다.
        //
        // **연월을 모르는 사진이 곧 기준이 가장 필요한 사진이다** — 시간표만 잘라 찍으면
        // 제목과 성명 컬럼이 함께 잘린다. 연월로는 찾을 수 없으니 가장 최근 기준을 대신 쓴다.
        // 잘린 변경분은 같은 달 배차표의 일부이므로 직전 기준이 맞다.
        //
        // 연월을 아는데 그 달 기준이 없는 경우는 **다른 달 것으로 대신하지 않는다.** 그때는
        // 성명 컬럼이 보이는 사진을 먼저 올리라고 거부하는 편이 맞다(`ROSTER_NOT_FOUND`).
        //
        // **이름으로 행을 찾을 수 있으면 다른 달 기준을 대신 쓰지 않는다.** 써 두면 쓰지도
        // 않는 다른 달의 `rowCount`가 `ROW_COUNT_CHANGED`로 새어 나온다 — 이름으로 찾은 행은
        // 인원이 바뀌어도 밀리지 않으므로 근거 없는 경고다. 경고로 지탱하는 설계에서 근거
        // 없는 경고는 사람이 경고를 무시하는 법을 배우게 한다.
        val roster =
            when {
                effectiveYearMonth != null -> rosterRepository.findByYearMonth(effectiveYearMonth.toString())
                !probe.hasNameColumn -> rosterRepository.findTopByOrderByYearMonthDesc()
                else -> null
            }

        val matchedBy = if (probe.hasNameColumn) MatchedBy.NAME else MatchedBy.ROW_INDEX
        val rowIndex =
            when {
                probe.hasNameColumn -> probe.rowIndex

                roster != null -> roster.rowIndex

                // 성명 컬럼도 없고 기준도 없으면 어느 줄이 대상인지 알 방법이 없다.
                else -> throw CustomException(DispatchErrorCode.ROSTER_NOT_FOUND, yearMonthLabel)
            }

        // **조각 하나라도 실패하면 거부한다.** 실패한 조각을 조용히 버리면 그 달 후반부가
        // 통째로 빠진 결과가 경고 없이 성공으로 나가고, 검수 화면에서는 「잘린 사진이라
        // 원래 일부만 나온 것」과 구분되지 않는다. 조각이 둘뿐이라 하나 실패하면 절반이
        // 날아가는데, 절반짜리를 검수하느니 다시 올리는 편이 빠르다. 결정론적 실패
        // (4xx·잘림)는 재시도 없이 이미 걸러지므로 남은 실패는 대개 재시도로 풀린다.
        fun readOrFail(slice: ImageSlice) =
            visionClient.read(slice, null, rowIndex)
                ?: throw CustomException(DispatchErrorCode.VISION_UNAVAILABLE)

        // **probe 뒤로는 언제나 행 위치로 읽는다.** 성명 컬럼은 표 맨 왼쪽에 있어 두 번째
        // 조각(폭의 45~100%)에는 없다. 없는 이름을 찾으라고 물으면 모델은 임의의 행을 읽고,
        // 그 값이 왼쪽 조각과 합쳐져 그 달 후반부가 다른 기사의 근무로 채워진다.
        // 성명 컬럼이 아예 없으면 **첫 조각도 행 위치로 다시 읽는다** — 같은 이유다.
        val results =
            if (probe.hasNameColumn) {
                listOf(probe) + slices.drop(1).map(::readOrFail)
            } else {
                slices.map(::readOrFail)
            }

        val rowCount = results.first().rowCount

        // **행 위치 모드는 여기서야 검증할 수 있다** — 저장된 `rowIndex`가 지금 표에 있는지는
        // 이번에 읽은 `rowCount`를 알아야 판단된다. 인원이 줄어 그 행이 사라졌다면 모델은
        // 「없는 행을 읽으라」는 지시를 받은 것이라 무엇을 돌려주든 지어낸 값이다.
        //
        // **경고가 아니라 거부다.** `ROW_COUNT_CHANGED`는 「인원이 바뀌어 행이 밀렸을 수 있으니
        // 검수하라」는 신호이고 값 자체는 볼 만하다. 반면 행이 아예 없으면 볼 값이 없다 —
        // 검수 화면에 지어낸 값을 띄우면 사람이 그것을 확정해 버릴 수 있다.
        if (matchedBy == MatchedBy.ROW_INDEX && !isRowWithinTable(rowIndex, rowCount)) {
            log.warn { "저장된 행 위치가 지금 표에 없다: rowIndex=$rowIndex, rowCount=$rowCount" }
            throw CustomException(DispatchErrorCode.VISION_UNAVAILABLE)
        }

        val warnings = mutableListOf<String>()
        // 인원이 바뀌면 행 순서가 밀려 엉뚱한 기사의 근무가 들어온다.
        if (roster != null && roster.rowCount != rowCount) {
            warnings += "ROW_COUNT_CHANGED"
        }
        // **다른 달 기준을 대신 써서 읽었을 때만 경고한다.** 이름으로 행을 찾았으면 저장된 기준을
        // 쓰지 않았으므로 경고할 것이 없다.
        if (effectiveYearMonth == null && matchedBy == MatchedBy.ROW_INDEX) {
            warnings += "ROSTER_FROM_OTHER_MONTH"
        }
        // 사진의 달과 요청한 달이 다르면 엉뚱한 달에 저장된다. 요청값이 없으면 비교할 것이
        // 없다 — 사진값이 곧 기준이므로 어긋날 수가 없다. 사진 제목을 못 읽었을 때도 마찬가지로
        // 검사만 건너뛴다(경고를 달지 않는다).
        if (yearMonth != null && photoYearMonth != null && photoYearMonth != yearMonth) {
            warnings += "YEAR_MONTH_MISMATCH"
        }

        // **새로 배운 것이 있고 의심할 근거가 없을 때만 갱신한다.** 경고가 붙은 사진(9월 사진을
        // 8월로 올린 경우 등)에서 읽은 값으로 덮으면 검수 화면에서 취소해도 되돌아가지 않고,
        // 이후 잘린 사진이 전부 틀린 행을 읽는다. `ROW_INDEX` 모드는 기존 기준을 되쓴 것이라
        // 새로 배운 것이 없다 — 여기서 `rowCount`를 덮으면 경고가 한 번만 뜨고 사라진다.
        //
        // **연월이 미상이면 갱신하지 않는다.** 기준은 연월로 찾으므로 어느 달의 것인지
        // 적을 수 없다.
        if (matchedBy == MatchedBy.NAME && warnings.isEmpty() && effectiveYearMonth != null) {
            rosterUpdater.upsert(effectiveYearMonth.toString(), rowIndex, rowCount)
        }

        return RecognitionResponse(
            yearMonth = effectiveYearMonth?.toString(),
            hasNameColumn = probe.hasNameColumn,
            matchedBy = matchedBy,
            rowIndex = rowIndex,
            rowCount = rowCount,
            warnings = warnings,
            days = merge(results, effectiveYearMonth),
        )
    }

    /**
     * 조각들을 날짜 기준으로 합친다. **겹친 구간이 공짜 교차검증이다** — 값이 갈리면
     * 어느 쪽도 고르지 않고 왼쪽 조각 값을 쓰되 `conflict`를 달아 사람에게 넘긴다.
     */
    private fun merge(
        results: List<RecognizedSlice>,
        yearMonth: YearMonth?,
    ): List<RecognitionDay> {
        val merged = LinkedHashMap<Int, RecognitionDay>()
        // 연월이 미상이면 그 달의 마지막 날을 알 수 없다. 좁게 잡으면 31일이 있는 달의
        // 마지막 날이 조용히 사라진다.
        val lastDay = yearMonth?.lengthOfMonth() ?: 31

        results.forEach { slice ->
            val visible = slice.visibleDays.toSet()
            slice.cells.forEach { cell ->
                // 집계 컬럼을 날짜로 셌으면 visibleDays 밖의 날짜가 나온다. 그 칸은 버린다.
                if (cell.day !in visible) return@forEach
                if (cell.day !in 1..lastDay) return@forEach

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

    /**
     * 행 위치가 그 표 안에 실제로 있는가. `rowCount`가 0 이하면 표를 못 읽은 것이고,
     * `rowIndex`가 그 밖이면 존재하지 않는 행을 가리킨다 — 어느 쪽도 저장하면 안 된다.
     */
    private fun isRowWithinTable(
        rowIndex: Int,
        rowCount: Int,
    ): Boolean = rowCount > 0 && rowIndex in 0 until rowCount

    companion object {
        /** 배차표 제목에서 읽은 연도로 납득할 수 있는 범위. 밖이면 제목을 잘못 읽은 것이다. */
        private val PLAUSIBLE_YEARS = 2000..2100
    }
}
