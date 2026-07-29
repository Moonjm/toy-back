package com.toy.backend.diet.meal

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface MealItemRepository : JpaRepository<MealItem, Long> {
    /**
     * 기간 내 먹은 항목을 **최근순**으로 준다. 정렬이 계약이다 — 호출자가 묶음의 첫 건을
     * 대표로 쓴다. `join fetch`로 `meal`을 함께 읽는다(날짜를 봐야 하고, LAZY면 항목마다 쿼리가 난다).
     */
    @Query(
        """
        select i from MealItem i
        join fetch i.meal m
        where m.user = :user and m.date between :from and :to
        order by m.date desc, i.id desc
        """,
    )
    fun findEatenBetween(
        @Param("user") user: User,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<MealItem>
}
