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

    /**
     * 병합 대상 조회 — 같은 날 같은 종류의 끼니가 이미 있으면 새로 만들지 않고 거기 합친다
     * (`MealService.confirm`). **간식에는 부르지 않는다**(`MealType.mergesWithinDay`).
     *
     * 유니크 제약이 없어 `First`를 붙이지 않으면 중복 행이 있을 때 예외가 난다. 병합을 넣기 전에
     * 생긴 중복은 그대로 두기로 했으므로(소급 병합하면 과거 점수가 바뀐다) 실제로 있을 수 있다.
     * 그때는 가장 먼저 만든 것에 합친다 — 하루 목표를 읽는 「첫 끼니」와 같은 행이다.
     */
    fun findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(
        user: User,
        date: LocalDate,
        mealType: MealType,
    ): Meal?
}
