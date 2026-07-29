package com.toy.backend.diet.meal

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface MealRepository : JpaRepository<Meal, Long> {
    fun findByUserAndDateBetweenOrderByDateAscIdAsc(
        user: User,
        from: LocalDate,
        to: LocalDate,
    ): List<Meal>

    /** 하루 목표는 **첫 끼니의 스냅샷**에서 읽으므로 정렬이 의미를 갖는다. */
    fun findByUserAndDateOrderByCreatedAtAscIdAsc(
        user: User,
        date: LocalDate,
    ): List<Meal>
}
