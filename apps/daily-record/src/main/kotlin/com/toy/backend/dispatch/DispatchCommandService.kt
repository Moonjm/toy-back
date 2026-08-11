package com.toy.backend.dispatch

import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DispatchCommandService(
    private val shiftRepository: DispatchShiftRepository,
    private val patternRepository: DispatchPatternRepository,
) {
    /**
     * **보낸 날짜만 갱신한다.** 월 전체를 지우고 다시 넣으면 이전에 확정한 날짜가 사라진다 —
     * 잘린 변경분 사진은 그 달의 일부만 담는다.
     */
    fun saveShifts(request: ShiftSaveRequest) {
        request.days.forEach { day ->
            val existing = shiftRepository.findByRoleAndWorkDate(request.role, day.date)
            if (existing == null) {
                shiftRepository.save(
                    DispatchShift(
                        role = request.role,
                        workDate = day.date,
                        working = day.working,
                        slot = day.slot,
                        note = day.note,
                    ),
                )
            } else {
                existing.working = day.working
                existing.slot = day.slot
                existing.note = day.note
            }
        }
    }

    fun savePattern(
        role: DispatchRole,
        request: PatternSaveRequest,
    ) {
        // 주기 밖 오프셋은 영원히 도달하지 않아 조용히 무시된다. 저장 시점에 막는다.
        val invalid = request.workingOffsets.filterNot { it in 0 until request.cycleDays }
        if (invalid.isNotEmpty()) {
            throw CustomException(DispatchErrorCode.INVALID_PATTERN, "주기(${request.cycleDays}) 밖 오프셋 $invalid")
        }

        val offsets = request.workingOffsets.sorted().joinToString(",")
        val pattern = patternRepository.findByRole(role)
        if (pattern == null) {
            patternRepository.save(
                DispatchPattern(
                    role = role,
                    cycleDays = request.cycleDays,
                    workingOffsets = offsets,
                    anchorDate = request.anchorDate,
                ),
            )
        } else {
            pattern.cycleDays = request.cycleDays
            pattern.workingOffsets = offsets
            pattern.anchorDate = request.anchorDate
        }
    }
}
