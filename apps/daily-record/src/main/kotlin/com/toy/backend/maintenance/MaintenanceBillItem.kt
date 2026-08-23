package com.toy.backend.maintenance

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal

/**
 * 고지서의 항목 한 줄.
 *
 * **금액에 음수 제약을 걸지 않는다.** `관리비차감`이 `-13,790`으로 들어온다.
 * 제약을 걸면 그 달의 저장이 통째로 실패한다.
 */
@Entity
@Table(name = "maintenance_bill_items")
class MaintenanceBillItem(
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "bill_id", nullable = false)
    val bill: MaintenanceBill,
    @field:Column(nullable = false, length = NAME_MAX_LENGTH)
    val name: String,
    @field:Column(nullable = false, precision = 19, scale = 4)
    val amount: BigDecimal,
    /** 영수증에 적힌 순서. 화면이 영수증과 같은 차례로 보여 주는 데 쓴다. */
    @field:Column(name = "display_order", nullable = false)
    val displayOrder: Int,
) : BaseEntity() {
    companion object {
        /** 가장 긴 실측 항목명이 `작은도서관운영비`(9자)다. 새 항목이 생길 것에 대비해 넉넉히 둔다. */
        const val NAME_MAX_LENGTH = 50
    }
}
