package com.toy.backend.dispatch

import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

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
                // 사진이 그 날짜의 원본이다 — 사진에 없는 값은 남기지 않는다. 하루 편집으로
                // 들어간 근무조가 남으면 휴무인데 근무조가 붙은 행이 조회로 나간다.
                // **엄마 배차표 인식이 붙으면 `= day.slotCode`로 바꾸는 자리다.**
                existing.slotCode = null
                existing.note = day.note
            }
        }
    }

    /**
     * 날짜 하나의 두 역할을 **한 트랜잭션으로** upsert한다. 역할마다 따로 호출하면 그 사이에
     * 실패했을 때 아빠만 저장된 상태가 남아 뒷수습이 호출자로 넘어간다.
     *
     * 사진 검수 확정(`saveShifts`)과 규칙이 셋 다르다.
     * - `note`를 **읽지도 쓰지도 않는다.** 덮으면 사진에서 읽은 원문이 하루 편집 한 번에 사라진다.
     * - `working`이 false면 순번을 `null`로 눕힌다.
     * - 역할에 맞지 않는 필드는 **저장 전에** 걸러 400으로 낸다.
     */
    fun editDay(
        date: LocalDate,
        request: DayEditRequest,
    ) {
        validate(request)

        request.father?.let { upsert(DispatchRole.FATHER, date, it) }
        request.mother?.let { upsert(DispatchRole.MOTHER, date, it) }
    }

    /**
     * **저장 전에 한자리에서 모아 던진다.** 저장 로직 속에 흩어 두면 어떤 조합이 막히는지
     * 읽히지 않을뿐더러, 아빠가 저장된 뒤 엄마에서 걸리면 롤백에 기대게 된다.
     */
    private fun validate(request: DayEditRequest) {
        if (request.father == null && request.mother == null) {
            throw CustomException(DispatchErrorCode.DAY_EDIT_ROLE_REQUIRED)
        }
        if (request.father?.slotCode != null) {
            throw CustomException(DispatchErrorCode.FATHER_SLOT_CODE_NOT_ALLOWED)
        }
        if (request.mother?.slot != null) {
            throw CustomException(DispatchErrorCode.MOTHER_SLOT_NOT_ALLOWED)
        }
    }

    private fun upsert(
        role: DispatchRole,
        date: LocalDate,
        edit: RoleEditRequest,
    ) {
        // 근무↔휴무를 오갈 때 직전에 고른 순번이 폼에 남아 함께 실려 오기 쉽다. 거절하는 대신
        // **휴무면 순번이 없다**는 규칙을 서버가 지킨다 — 화면 한쪽에서만 막으면 다른 호출자가
        // 모순된 레코드를 만든다.
        val slot = edit.slot.takeIf { edit.working }
        val slotCode = edit.slotCode.takeIf { edit.working }

        val existing = shiftRepository.findByRoleAndWorkDate(role, date)
        if (existing == null) {
            shiftRepository.save(
                DispatchShift(role = role, workDate = date, working = edit.working, slot = slot, slotCode = slotCode),
            )
        } else {
            existing.working = edit.working
            existing.slot = slot
            existing.slotCode = slotCode
        }
    }
}
