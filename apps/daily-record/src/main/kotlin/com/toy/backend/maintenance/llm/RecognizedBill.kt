package com.toy.backend.maintenance.llm

import java.math.BigDecimal

/**
 * 영수증에서 읽어 낸 값. **모델이 본 그대로이고 해석은 하지 않는다** — 범위 검사도 이름
 * 매핑도 `MaintenanceRecognitionService`가 한다. 섞으면 인식이 틀렸을 때 어느 쪽 잘못인지
 * 가릴 수 없다. 세 타입은 함께 바뀌므로 한 파일에 둔다.
 */
data class RecognizedBill(
    val year: Int,
    val month: Int,
    val dong: String,
    val ho: String,
    val areaM2: BigDecimal,
    val items: List<RecognizedItem>,
    val usages: List<RecognizedUsage>,
    val chargedAmount: BigDecimal,
    val discountTotal: BigDecimal,
    val unpaidAmount: BigDecimal,
    val unpaidLateFee: BigDecimal,
    val dueAmount: BigDecimal,
    /** 모델이 읽은 그대로의 `YYYY-MM-DD`. 해석은 서비스가 한다 — 못 읽으면 빈 문자열이다. */
    val dueDate: String,
)

data class RecognizedItem(
    val name: String,
    /** **음수가 온다.** `관리비차감`이 `-13,790`이다. */
    val amount: BigDecimal,
)

data class RecognizedUsage(
    val name: String,
    val value: BigDecimal,
    /** 영수증에 보이는 그대로. 표기가 없으면 빈 문자열이다. */
    val unit: String,
)
