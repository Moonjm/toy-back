package com.toy.backend.maintenance

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.math.BigDecimal

/**
 * 관리비 고지서 한 장. 한 달에 한 장이라 `yearMonth`가 unique이고, `2026-07` 형태의
 * 문자열이라 사전순이 곧 시간순이다(`dispatch_roster` 선례).
 *
 * **사용량은 컬럼, 항목은 테이블이다.** 사용량은 5종 고정이라 테이블로 빼면 추이 쿼리마다
 * 피벗해야 하고, 항목은 달마다 개수와 이름이 바뀐다. 여름 난방처럼 없는 사용량은 null이다.
 */
@Entity
@Table(name = "maintenance_bills")
class MaintenanceBill(
    @field:Column(name = "year_month_value", nullable = false, unique = true, length = 7)
    val yearMonth: String,
    @field:Column(name = "charged_amount", nullable = false, precision = 19, scale = 4)
    var chargedAmount: BigDecimal = BigDecimal.ZERO,
    @field:Column(length = 20)
    var dong: String? = null,
    @field:Column(length = 20)
    var ho: String? = null,
    @field:Column(name = "area_m2", precision = 19, scale = 4)
    var areaM2: BigDecimal? = null,
    @field:Column(name = "discount_total", nullable = false, precision = 19, scale = 4)
    var discountTotal: BigDecimal = BigDecimal.ZERO,
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
