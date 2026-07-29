package com.toy.backend.diet.food

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FoodRepository : JpaRepository<Food, Long> {
    /** 완전일치는 (dataset, normalized_name) 인덱스를 탄다. 가공식품 30만 행이어도 비용이 일정하다. */
    fun findFirstByDatasetAndNormalizedName(
        dataset: FoodDataset,
        normalizedName: String,
    ): Food?

    /**
     * 부분일치 후보를 **이름이 짧은 순**으로 준다. 인식 파이프라인은 `DISH`로만 부른다 —
     * 브랜드명 위주인 `PROCESSED`를 여기에 넣으면 "라면" 한 마디가 수천 건을 긁어오고,
     * 그중 가장 짧은 것이 사용자가 먹은 것과 무관한 제품이 된다.
     */
    @Query(
        """
        select f from Food f
        where f.dataset = :dataset
          and f.normalizedName like concat('%', :normalized, '%')
        order by length(f.normalizedName) asc, f.id asc
        """,
    )
    fun searchByDatasetAndNormalizedName(
        @Param("dataset") dataset: FoodDataset,
        @Param("normalized") normalized: String,
        pageable: Pageable,
    ): List<Food>

    /** 사용자가 직접 고르는 화면(`GET /diet/foods`)용 — 두 데이터셋을 모두 뒤진다. */
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
