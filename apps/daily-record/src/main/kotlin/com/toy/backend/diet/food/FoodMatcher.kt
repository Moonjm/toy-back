package com.toy.backend.diet.food

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(readOnly = true)
class FoodMatcher(
    private val repository: FoodRepository,
) {
    /**
     * 1) 음식 완전일치 → 2) 원재료 완전일치 → 3) 가공식품 완전일치 → 4) 음식 부분일치 → 5) null.
     *
     * **음식을 먼저 보는 이유**는 이름이 겹칠 때(배추김치·스파게티 등 2,818건) 조리된 음식 쪽이
     * 사진에 찍힌 것에 가깝기 때문이다. 순서가 곧 우선순위다.
     *
     * **원재료가 가공식품보다 앞이다.** 실기동에서 과일 접시 사진의 「복숭아」가 가공식품
     * 완전일치에 걸려 200g이 450kcal로 잡혔다(생복숭아는 80kcal). 바나나는 실제의 5배였고,
     * 원재료 데이터셋이 없던 탓에 계란·고구마는 아예 다른 음식(계란빵·고구마전)이 잡혔다.
     * **원재료를 뒤에 두면 같은 일이 반복된다** — 이름이 정확히 같을 때 「말린 복숭아」보다
     * 「복숭아」가 사진에 찍힌 것에 가깝다.
     *
     * 조리 음식(1)을 원재료(2)보다 앞에 두는 것은 유지한다. 완전일치라 이름이 같을 때만
     * 겨루는데, 그때는 조리된 쪽이 맞다(밥·쌀처럼 조리 전후로 열량 밀도가 크게 다르다).
     */
    fun match(foodName: String): Food? {
        val normalized = FoodNameNormalizer.normalize(foodName)
        if (normalized.isBlank()) return null
        return repository.findFirstByDatasetAndNormalizedName(FoodDataset.DISH, normalized)
            ?: repository.findFirstByDatasetAndNormalizedName(FoodDataset.RAW, normalized)
            ?: repository.findFirstByDatasetAndNormalizedName(FoodDataset.PROCESSED, normalized)
            ?: repository
                .searchByDatasetAndNormalizedName(FoodDataset.DISH, normalized, PageRequest.of(0, 1))
                .firstOrNull()
    }

    /** iOS 항목 수정 화면용 — 자동 선택 없이 후보 목록을 그대로 준다. */
    fun search(
        keyword: String,
        size: Int,
    ): List<Food> {
        val normalized = FoodNameNormalizer.normalize(keyword)
        if (normalized.isBlank()) return emptyList()
        return repository.searchByNormalizedName(normalized, PageRequest.of(0, size.coerceIn(1, MAX_SEARCH_SIZE)))
    }

    companion object {
        private const val MAX_SEARCH_SIZE = 50
    }
}
