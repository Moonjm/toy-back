package com.toy.backend.dispatch.llm

/**
 * 배차표 조각 하나에서 읽어 낸 값. **모델이 본 그대로이고 해석은 하지 않는다** —
 * 연월 범위 검사도, 조각 합치기도, 행 위치 판정도 `DispatchRecognitionService`가 한다.
 *
 * 둘을 한 파일에 둔다. `RecognizedSlice`가 `RecognizedCell`을 담고 있어 **함께 바뀐다**.
 */
data class RecognizedCell(
    val day: Int,
    /** 칸에 보이는 그대로. **빈 칸은 빈 문자열이다** — null로 두면 스키마가 strict를 못 건다. */
    val value: String,
)

data class RecognizedSlice(
    val hasNameColumn: Boolean,
    /**
     * 성명 컬럼 안에서 **대상 기사를 실제로 찾았는가.** `hasNameColumn`과 다른 질문이다 —
     * 컬럼은 멀쩡히 보이는데 그 달 표에 그 사람이 없거나 이름이 흐려 못 읽는 경우가 있다.
     * 이 값이 없으면 스키마상 `rowIndex`가 정수를 요구하므로 모델이 아무 행 번호나 채운다.
     */
    val targetFound: Boolean,
    val rowIndex: Int,
    val rowCount: Int,
    val year: Int,
    val month: Int,
    /** 이 조각에서 보이는 날짜 헤더. **집계 컬럼을 날짜로 셌는지 검증**하는 데 쓴다. */
    val visibleDays: List<Int>,
    val cells: List<RecognizedCell>,
)
