package com.toy.backend.dispatch

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * 아빠 배차표의 월별 행 위치. **잘린 사진에는 성명 컬럼이 없어 이 값이 유일한 단서다.**
 *
 * `rowCount`는 표의 전체 데이터 행 수다. 인원이 바뀌어 행 순서가 밀리면 엉뚱한 기사의
 * 근무가 조용히 들어오는데, 이 값을 비교해 경고를 띄운다.
 *
 * **실명·차량번호는 두지 않는다.** 조회 API가 무인증으로 열려 있다.
 */
@Entity
@Table(name = "dispatch_roster")
class DispatchRoster(
    @field:Column(name = "year_month_value", nullable = false, unique = true, length = 7)
    val yearMonth: String,
    @field:Column(name = "row_index", nullable = false)
    var rowIndex: Int,
    @field:Column(name = "row_count", nullable = false)
    var rowCount: Int,
) : BaseEntity()
