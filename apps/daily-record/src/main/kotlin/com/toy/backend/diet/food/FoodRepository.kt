package com.toy.backend.diet.food

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FoodRepository : JpaRepository<Food, Long> {
    fun findFirstByNormalizedName(normalizedName: String): Food?

    /**
     * 부분일치 후보를 **이름이 짧은 순**으로 준다. "제육볶음"으로 검색하면 "제육볶음(급식용)"·
     * "제육볶음덮밥"이 같이 걸리는데, 짧은 쪽이 더 일반적인 항목이라 실제로 먹은 것에 가깝다.
     *
     * pg_trgm 같은 확장은 쓰지 않는다 — 후보가 수만 건이고 조회는 하루 수십 건이라 LIKE 스캔으로 충분하다.
     */
    @Query(
        """
        select f from Food f
        where f.normalizedName like concat('%', :normalized, '%')
        order by length(f.normalizedName) asc, f.id asc
        """,
    )
    fun searchByNormalizedName(
        @Param("normalized") normalized: String,
        pageable: Pageable,
    ): List<Food>
}
