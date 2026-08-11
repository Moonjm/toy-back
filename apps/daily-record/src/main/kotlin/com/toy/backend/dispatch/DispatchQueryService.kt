package com.toy.backend.dispatch

import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
    private val motherPattern: MotherPatternProperties,
) {
    /**
     * **무인증으로 들어오는 파라미터라 기간을 먼저 막는다.** 엄마 몫은 저장 여부와 무관하게
     * 하루씩 만들어 내므로 `from`·`to`를 그대로 믿으면 요청 한 번이 수백만 건을 만든다.
     */
    fun findRange(
        from: LocalDate,
        to: LocalDate,
    ): ShiftRangeResponse {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw CustomException(DispatchErrorCode.INVALID_RANGE, MAX_RANGE_DAYS)
        }

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
                            working = DispatchPatternExpander.isWorking(motherPattern, date),
                            slot = null,
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
            note = note,
        )

    companion object {
        /** 달력은 한 번에 한 달치를 부른다. 400일이면 넉넉하다. */
        private const val MAX_RANGE_DAYS = 400L
    }
}
