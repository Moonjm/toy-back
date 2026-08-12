package com.toy.backend.diet.profile

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository

interface NutritionProfileRepository : JpaRepository<NutritionProfile, Long> {
    fun findByUser(user: User): NutritionProfile?
}
