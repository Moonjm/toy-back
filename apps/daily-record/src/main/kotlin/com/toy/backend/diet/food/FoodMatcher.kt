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
     * 1) 음식 완전일치 → 2) 가공식품 완전일치 → 3) 음식 부분일치(가장 짧은 이름) → 4) null.
     *
     * **음식을 먼저 보는 이유**는 이름이 겹칠 때(배추김치·스파게티 등 2,818건) 조리된 음식 쪽이
     * 사진에 찍힌 것에 가깝기 때문이다. 순서가 곧 우선순위다.
     */
    fun match(foodName: String): Food? {
        val normalized = FoodNameNormalizer.normalize(foodName)
        if (normalized.isBlank()) return null
        return repository.findFirstByDatasetAndNormalizedName(FoodDataset.DISH, normalized)
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
