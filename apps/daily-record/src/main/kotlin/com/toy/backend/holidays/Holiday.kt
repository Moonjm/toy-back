package com.toy.backend.holidays

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate

@Entity
@Table(
    name = "holidays",
    uniqueConstraints = [UniqueConstraint(columnNames = ["date", "name"])],
    indexes = [Index(name = "idx_holidays_date", columnList = "date")],
)
class Holiday(
    @Column(nullable = false)
    val date: LocalDate,
    @Column(nullable = false, length = 50)
    val name: String,
) : BaseEntity()
