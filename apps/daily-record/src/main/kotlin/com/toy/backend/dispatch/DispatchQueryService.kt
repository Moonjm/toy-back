package com.toy.backend.dispatch

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth

/**
 * 아빠·엄마를 **같은 모양으로 합쳐** 내보낸다. 읽는 쪽은 데이터가 사진에서 왔는지
 * 패턴에서 왔는지 알 필요가 없다.
 *
 * **아빠는 확정 저장된 날짜만 나간다.** 아직 인식·검수하지 않은 날을 휴무로 채우면
 * 「쉬는 날」과 「모르는 날」이 구분되지 않는다. 달력은 그 자리를 비운다.
 */
@Service
@Transactional(readOnly = true)
class DispatchQueryService(
    private val shiftRepository: DispatchShiftRepository,
) {
    /**
     * **기간이 아니라 연월 하나를 받는다.** 이 조회는 무인증으로 열려 있고 엄마 몫은 저장
     * 여부와 무관하게 하루씩 만들어 내므로, 임의의 `from`·`to`를 받으면 요청 한 번이
     * 수백만 건을 만들어 라즈베리파이 힙을 터뜨린다. 달력은 어차피 월 단위로만 보므로
     * 범위를 **구조적으로 한 달로 못 박는다** — 상한 검사도 `from > to` 검사도 필요 없어진다.
     */
    fun findMonth(yearMonth: YearMonth): ShiftRangeResponse {
        val from = yearMonth.atDay(1)
        val to = yearMonth.atEndOfMonth()

        val stored = shiftRepository.findByWorkDateBetween(from, to)
        val storedByKey = stored.associateBy { it.role to it.workDate }

        val fatherDays =
            stored
                .filter { it.role == DispatchRole.FATHER }
                .map { it.toResponse() }

        // 엄마는 등록 절차 없이 항상 계산된다. 예외가 저장돼 있으면 그것이 이긴다.
        val motherDays =
            generateSequence(from) { it.plusDays(1) }
                .takeWhile { !it.isAfter(to) }
                .map { date ->
                    storedByKey[DispatchRole.MOTHER to date]?.toResponse()
                        ?: ShiftDayResponse(
                            date = date,
                            role = DispatchRole.MOTHER,
                            working = DispatchPatternExpander.isWorking(date),
                            slot = null,
                            slotCode = null,
                            note = null,
                        )
                }.toList()

        return ShiftRangeResponse((fatherDays + motherDays).sortedWith(compareBy({ it.date }, { it.role })))
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
