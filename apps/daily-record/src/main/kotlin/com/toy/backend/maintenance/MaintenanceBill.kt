package com.toy.backend.maintenance

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 관리비 고지서 한 장. **한 달에 한 장이다** — `yearMonth`가 unique다.
 *
 * `yearMonth`를 `2026-07` 형태의 문자열로 둔다. 사전순 정렬이 시간순과 같아 추이 조회가
 * 범위 비교 하나로 끝나고, `dispatch_roster`가 이미 같은 방식을 쓴다.
 * 컬럼명이 `year_month_value`인 것도 그쪽 선례를 따른 것이다.
 *
 * **사용량 5종은 컬럼으로 둔다.** 종류가 고정이고 목적이 추이 그래프다. 별도 테이블로 빼면
 * 추이 쿼리마다 피벗해야 한다. 항목(`items`)을 반대로 판단한 이유는 그쪽이 달마다 개수와
 * 이름이 바뀌기 때문이다(`관리비차감`·`선거관리운영비`).
 *
 * 여름에는 난방이 없다 — 사용량은 전부 null을 허용한다.
 */
@Entity
@Table(name = "maintenance_bills")
class MaintenanceBill(
    @field:Column(name = "year_month_value", nullable = false, unique = true, length = 7)
    val yearMonth: String,
    @field:Column(name = "charged_amount", nullable = false, precision = 19, scale = 4)
    var chargedAmount: BigDecimal,
    @field:Column(name = "due_amount", nullable = false, precision = 19, scale = 4)
    var dueAmount: BigDecimal,
    @field:Column(length = 20)
    var dong: String? = null,
    @field:Column(length = 20)
    var ho: String? = null,
    @field:Column(name = "area_m2", precision = 19, scale = 4)
    var areaM2: BigDecimal? = null,
    @field:Column(name = "discount_total", nullable = false, precision = 19, scale = 4)
    var discountTotal: BigDecimal = BigDecimal.ZERO,
    @field:Column(name = "unpaid_amount", nullable = false, precision = 19, scale = 4)
    var unpaidAmount: BigDecimal = BigDecimal.ZERO,
    @field:Column(name = "unpaid_late_fee", nullable = false, precision = 19, scale = 4)
    var unpaidLateFee: BigDecimal = BigDecimal.ZERO,
    /** 앱 화면 캡처에서 옮겨 온 과거 넉 달은 납기일이 없다. */
    @field:Column(name = "due_date")
    var dueDate: LocalDate? = null,
    @field:Column(name = "electricity_kwh", precision = 19, scale = 4)
    var electricityKwh: BigDecimal? = null,
    @field:Column(name = "water_m3", precision = 19, scale = 4)
    var waterM3: BigDecimal? = null,
    @field:Column(name = "hot_water_m3", precision = 19, scale = 4)
    var hotWaterM3: BigDecimal? = null,
    @field:Column(name = "heating_gcal", precision = 19, scale = 4)
    var heatingGcal: BigDecimal? = null,
    @field:Column(name = "food_kg", precision = 19, scale = 4)
    var foodKg: BigDecimal? = null,
) : BaseEntity() {
    @OneToMany(mappedBy = "bill", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("displayOrder asc")
    var items: MutableList<MaintenanceBillItem> = mutableListOf()

    /** 보낸 순서를 그대로 `displayOrder`로 새긴다. 영수증에 적힌 차례가 곧 화면의 차례다. */
    fun replaceItems(items: List<Pair<String, BigDecimal>>) {
        this.items.clear()
        items.forEachIndexed { index, (name, amount) ->
            this.items.add(MaintenanceBillItem(this, name, amount, index))
        }
    }

    /**
     * 항목 합계. **음수 항목을 그대로 더한다** — `관리비차감`을 빼먹거나 절댓값으로 더하면
     * 「합계 == 당월부과액」 검사가 통과할 리 없는 달에 통과하거나 그 반대가 된다.
     */
    fun itemTotal(): BigDecimal = items.fold(BigDecimal.ZERO) { acc, item -> acc + item.amount }
}
