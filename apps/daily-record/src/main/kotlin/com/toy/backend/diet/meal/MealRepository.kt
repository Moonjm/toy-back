package com.toy.backend.diet.meal

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface MealRepository : JpaRepository<Meal, Long> {
    /** 표시 순서 조회 — 목록 화면이 쓴다. 첫 끼니 판정에는 쓰지 않는다. */
    fun findByUserAndDateBetweenOrderByDateAscIdAsc(
        user: User,
        from: LocalDate,
        to: LocalDate,
    ): List<Meal>

    /**
     * 기간 조회. **정렬에 `createdAt`이 들어가는 이유** — 통계가 날짜별로 묶은 뒤 그날 첫 끼니의
     * 스냅샷을 목표로 쓰는데, 하루 집계(`findByUserAndDateOrderByCreatedAtAscIdAsc`)와 「첫 끼니」의
     * 정의가 같아야 두 화면이 같은 날에 같은 점수를 낸다.
     */
    fun findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(
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
