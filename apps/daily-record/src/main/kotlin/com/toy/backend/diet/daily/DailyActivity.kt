package com.toy.backend.diet.daily

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate

/** iOS가 HealthKit에서 읽어 올린 하루 활동 에너지. 목표 계산에는 반영하지 않는다. */
@Entity
@Table(
    name = "daily_activity",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_daily_activity_user_date", columnNames = ["user_id", "date"]),
    ],
)
class DailyActivity(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(nullable = false)
    var date: LocalDate,
    @Column(name = "active_energy_kcal", nullable = false)
    var activeEnergyKcal: Int,
) : BaseEntity() {
    fun updateEnergy(activeEnergyKcal: Int) {
        this.activeEnergyKcal = activeEnergyKcal
    }
}
