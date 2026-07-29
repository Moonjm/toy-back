package com.toy.backend.diet.meal

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(
    name = "meal_photo",
    indexes = [
        Index(name = "idx_meal_photo_meal", columnList = "meal_id"),
    ],
)
class MealPhoto(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_id", nullable = false)
    var meal: Meal,
    @Column(name = "file_id", nullable = false)
    var fileId: Long,
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int,
) : BaseEntity()
