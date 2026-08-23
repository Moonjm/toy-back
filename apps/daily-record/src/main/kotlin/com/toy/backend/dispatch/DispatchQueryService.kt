package com.toy.backend.dispatch

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth

/**
 * 아빠·엄마를 **같은 모양으로 합쳐** 내보낸다. 읽는 쪽은 그 값이 사진 인식에서 왔는지
 * 손으로 넣은 것인지 알 필요가 없다.
 *
 * **저장된 날짜만 나간다.** 저장되지 않은 날을 휴무로 채우면 「쉬는 날」과 「아직 모르는 날」이
 * 구분되지 않는다. 달력은 그 자리를 비운다.
 *
 * 한때 엄마 몫은 3일 주기 패턴으로 계산해 저장 없이도 달 전체를 채웠다. 근무 형태가 바뀌면서
 * 걷어냈고, 이제 두 사람 모두 저장된 것만 나간다.
 */
@Service
@Transactional(readOnly = true)
class DispatchQueryService(
    private val shiftRepository: DispatchShiftRepository,
) {
    /**
     * **기간이 아니라 연월 하나를 받는다.** 이 조회는 무인증으로 열려 있으므로 임의의
     * `from`·`to`를 받으면 요청 한 번으로 범위를 무한정 넓힐 수 있다. 달력은 어차피 월
     * 단위로만 보므로 범위를 **구조적으로 한 달로 못 박는다** — 상한 검사도 `from > to`
     * 검사도 필요 없어진다.
     */
    fun findMonth(yearMonth: YearMonth): ShiftRangeResponse {
        val stored = shiftRepository.findByWorkDateBetween(yearMonth.atDay(1), yearMonth.atEndOfMonth())
        return ShiftRangeResponse(
            stored
                .map { it.toResponse() }
                .sortedWith(compareBy({ it.date }, { it.role })),
        )
    }

    private fun DispatchShift.toResponse() =
        ShiftDayResponse(
            date = workDate,
            role = role,
            working = working,
            slot = slot,
            slotCode = slotCode,
            note = note,
        )
}
