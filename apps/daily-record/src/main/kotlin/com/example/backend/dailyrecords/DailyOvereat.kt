package com.example.backend.dailyrecords

import com.example.backend.common.entity.BaseEntity
import com.example.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate

@Entity
@Table(
    name = "daily_overeats",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_daily_overeats_date_user", columnNames = ["date", "user_id"]),
    ],
    indexes = [
        Index(name = "idx_daily_overeats_user", columnList = "user_id"),
    ],
)
class DailyOvereat(
    @Column(nullable = false)
    var date: LocalDate,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var overeatLevel: OvereatLevel = OvereatLevel.NONE,
) : BaseEntity()
