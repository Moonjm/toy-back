package com.toy.backend.dispatch

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.LocalDate

/** 무인증으로 나가는 응답이다 — **실명·차량번호를 넣지 않는다.** */
data class ShiftDayResponse(
    val date: LocalDate,
    val role: DispatchRole,
    val working: Boolean,
    /** 아빠 배차 순번. 엄마는 늘 null이다. */
    val slot: Int?,
    /**
     * 엄마 근무조(`A`·`B`·`C`). 아빠는 늘 null이고, 패턴에서 만들어진 엄마 기본값도 null이다.
     *
     * **역할마다 응답 모양을 가르지 않는다** — 조회는 두 사람을 같은 모양으로 합쳐 내보내는 것이 계약이다.
     */
    val slotCode: String?,
    /** 자유 입력이 무인증 응답에 그대로 실려 나간다. **개인 식별 정보를 넣지 않는다.** */
    val note: String?,
)

data class ShiftRangeResponse(
    val days: List<ShiftDayResponse>,
)

data class ShiftSaveRequest(
    val role: DispatchRole,
    @field:NotEmpty val days: List<@Valid ShiftSaveDay>,
)

data class ShiftSaveDay(
    val date: LocalDate,
    val working: Boolean,
    val slot: Int?,
    /**
     * **개인 식별 정보를 넣지 않는다** — 그대로 저장돼 무인증 `GET /dispatch/shifts`로 나간다.
     * 컬럼 길이와 맞춰 두어 초과 입력이 DB 오류(500)가 아니라 400으로 걸리게 한다.
     */
    @field:Size(max = DispatchShift.NOTE_MAX_LENGTH) val note: String?,
)

/**
 * 날짜 하나의 두 역할을 한 요청으로 고친다(`PUT /dispatch/shifts/{date}`).
 *
 * **손대지 않은 역할은 아예 보내지 않는다 — 서버도 건드리지 않는다.** 두 역할을 모두 요구하면
 * 아빠 배차표를 아직 올리지 않은 달에 엄마만 고칠 때 호출자가 아빠 값을 지어내야 하고,
 * 그 달 아빠의 첫 레코드가 사람이 고른 적 없는 값으로 생긴다. 조회가 「저장된 적 없음」과
 * 「휴무」를 구분하고 있으므로 그 구분을 저장 쪽에서 무너뜨리지 않는다.
 */
data class DayEditRequest(
    @field:Valid val father: RoleEditRequest?,
    @field:Valid val mother: RoleEditRequest?,
)

/**
 * **순번은 역할마다 다른 필드다.** 한 필드에 정수와 문자를 겹쳐 담지 않는다 —
 * `A`를 `1`로 접어 저장하면 같은 숫자의 뜻이 역할마다 갈리고, 엄마 순번이 `D`로 늘어나는 순간 무너진다.
 *
 * 역할과 필드가 어긋나면 400이다. 조용히 무시하면 호출자는 값이 들어간 줄 안다.
 */
data class RoleEditRequest(
    val working: Boolean,
    /** 아빠 배차 순번. **엄마에게 보내면 400이다.** */
    @field:PositiveOrZero val slot: Int? = null,
    /** 엄마 근무조. **아빠에게 보내면 400이다.** */
    @field:Pattern(regexp = SLOT_CODE_PATTERN, message = "근무조는 A·B·C 중 하나여야 합니다")
    val slotCode: String? = null,
) {
    companion object {
        /** 실제 허용 범위는 여기가 정한다. 컬럼(`SLOT_CODE_MAX_LENGTH`)은 표기가 늘어날 것에 대비해 넉넉히 열어 둔다. */
        const val SLOT_CODE_PATTERN = "^[A-C]$"
    }
}

enum class MatchedBy { NAME, ROW_INDEX }

data class RecognitionDay(
    val day: Int,
    val working: Boolean,
    val slot: Int?,
    val note: String?,
    /** 겹친 구간에서 두 조각의 답이 갈렸다. 검수 화면이 강조한다. */
    val conflict: Boolean,
)

/** **실명·차량번호를 싣지 않는다.** 모델이 읽더라도 버린다 — 앱 로그·캐시에 남는다. */
data class RecognitionResponse(
    /** 사진에서도 읽지 못하고 요청에도 없으면 `null`이다. 앱의 검수 화면이 채운다. */
    val yearMonth: String?,
    val hasNameColumn: Boolean,
    val matchedBy: MatchedBy,
    val rowIndex: Int,
    val rowCount: Int,
    val warnings: List<String>,
    val days: List<RecognitionDay>,
)
