package com.toy.backend.dispatch

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DispatchCommandService(
    private val shiftRepository: DispatchShiftRepository,
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
}
