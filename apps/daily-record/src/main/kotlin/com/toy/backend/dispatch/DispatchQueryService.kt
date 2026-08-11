package com.toy.backend.dispatch

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

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
    private val patternRepository: DispatchPatternRepository,
) {
    fun findRange(
        from: LocalDate,
        to: LocalDate,
    ): ShiftRangeResponse {
        val stored = shiftRepository.findByWorkDateBetween(from, to)
        val storedByKey = stored.associateBy { it.role to it.workDate }

        val fatherDays =
            stored
                .filter { it.role == DispatchRole.FATHER }
                .map { it.toResponse() }

        val motherDays =
            patternRepository.findByRole(DispatchRole.MOTHER)?.let { pattern ->
                generateSequence(from) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(to) }
                    .map { date ->
                        // 예외가 있으면 패턴 계산을 덮어쓴다.
                        storedByKey[DispatchRole.MOTHER to date]?.toResponse()
                            ?: ShiftDayResponse(
                                date = date,
                                role = DispatchRole.MOTHER,
                                working = DispatchPatternExpander.isWorking(pattern, date),
                                slot = null,
                                note = null,
                            )
                    }.toList()
            } ?: emptyList()

        return ShiftRangeResponse((fatherDays + motherDays).sortedWith(compareBy({ it.date }, { it.role })))
    }

    private fun DispatchShift.toResponse() =
        ShiftDayResponse(
            date = workDate,
            role = role,
            working = working,
            slot = slot,
            note = note,
        )
}
