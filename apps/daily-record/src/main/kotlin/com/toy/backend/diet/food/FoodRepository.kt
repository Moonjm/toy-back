package com.toy.backend.diet.food

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FoodRepository : JpaRepository<Food, Long> {
    /**
     * 적재 여부를 **데이터셋별로** 판단한다(`FoodSeeder`). 전체 행 수로 보면, 두 CSV 중 하나만
     * 있는 상태로 처음 기동한 뒤 나머지를 채워 넣어도 영영 적재되지 않는다.
     *
     * `(dataset, normalized_name)` 인덱스의 선두 컬럼이라 30만 행이어도 비용이 일정하다.
     */
    fun existsByDataset(dataset: FoodDataset): Boolean

    /**
     * 완전일치는 (dataset, normalized_name) 인덱스를 탄다. 가공식품 30만 행이어도 비용이 일정하다.
     *
     * **추정으로 채운 행을 뒤로 민다.** 같은 이름이 여러 건일 때(DISH 6,090행 중 1,084종
     * 3,452행이 중복이다 — 상추겉절이 8건) 원본 값을 가진 행이 이겨야 한다. 정렬이 없으면
     * 어느 행이 걸릴지 DB 순서에 달려 있어, 되살린 추정 행이 멀쩡한 원본 행을 밀어낸다.
     *
     * **`Food?`를 돌려주면 안 된다.** 파생 쿼리였을 때는 `findFirst` 접두사가 `LIMIT 1`을
     * 붙여 줬지만 `@Query`를 달면 그 접두사가 무시되고, 위의 중복 때문에 곧바로
     * `IncorrectResultSizeDataAccessException`이 난다. 호출자가 `firstOrNull()` 한다.
     */
    @Query(
        """
        select f from Food f
        where f.dataset = :dataset and f.normalizedName = :normalized
        order by case when f.estimatedFields is null then 0 else 1 end, f.id asc
        """,
    )
    fun findBestByDatasetAndNormalizedName(
        @Param("dataset") dataset: FoodDataset,
        @Param("normalized") normalized: String,
        pageable: Pageable,
    ): List<Food>

    /**
     * 부분일치 후보를 **이름이 짧은 순**으로 준다. 인식 파이프라인은 `DISH`로만 부른다 —
     * 브랜드명 위주인 `PROCESSED`를 여기에 넣으면 "라면" 한 마디가 수천 건을 긁어오고,
     * 그중 가장 짧은 것이 사용자가 먹은 것과 무관한 제품이 된다.
     *
     * **인식 전용이라 브랜드(`normalizedMaker`)를 보지 않는다.** 모델이 브랜드를 붙여 부르는지가
     * 일정하지 않아, 여기에 섞으면 튜닝해 둔 이름 매칭이 흔들린다. 사람이 직접 고르는 검색은
     * 아래 `searchByText`/`searchByDatasetAndText`가 따로 담당한다.
     */
    @Query(
        """
        select f from Food f
        where f.dataset = :dataset
          and f.normalizedName like concat('%', :normalized, '%')
        order by case when f.estimatedFields is null then 0 else 1 end,
                 length(f.normalizedName) asc, f.id asc
        """,
    )
    fun searchByDatasetAndNormalizedName(
        @Param("dataset") dataset: FoodDataset,
        @Param("normalized") normalized: String,
        pageable: Pageable,
    ): List<Food>

    /**
     * 사용자가 직접 고르는 화면(`GET /diet/foods`)용 — 세 데이터셋을 모두 뒤지고
     * **이름과 브랜드 양쪽**을 본다.
     *
     * 브랜드를 봐야 하는 이유 — 식품명에 브랜드가 안 들어 있다. 도미노피자 318건이 전부
     * `피자_뉴욕 오리진 피자 오리지널 (L)` 같은 이름이라, 이름만 보면 「도미노」로 한 건도
     * 못 찾는다. 검색어가 브랜드인지 음식인지 사용자에게 묻지 않고 둘 다 맞춰 본다.
     */
    @Query(
        """
        select f from Food f
        where f.normalizedName like concat('%', :normalized, '%')
           or f.normalizedMaker like concat('%', :normalized, '%')
        order by case when f.estimatedFields is null then 0 else 1 end,
                 length(f.normalizedName) asc, f.id asc
        """,
    )
    fun searchByText(
        @Param("normalized") normalized: String,
        pageable: Pageable,
    ): List<Food>

    /** 위와 같되 데이터셋 칩으로 좁힌 것. */
    @Query(
        """
        select f from Food f
        where f.dataset = :dataset
          and (f.normalizedName like concat('%', :normalized, '%')
               or f.normalizedMaker like concat('%', :normalized, '%'))
        order by case when f.estimatedFields is null then 0 else 1 end,
                 length(f.normalizedName) asc, f.id asc
        """,
    )
    fun searchByDatasetAndText(
        @Param("dataset") dataset: FoodDataset,
        @Param("normalized") normalized: String,
        pageable: Pageable,
    ): List<Food>
}
