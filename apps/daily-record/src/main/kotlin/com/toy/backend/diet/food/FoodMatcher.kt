package com.toy.backend.diet.food

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(readOnly = true)
class FoodMatcher(
    private val repository: FoodRepository,
) {
    /** 1) 완전일치 → 2) 부분일치 중 가장 짧은 이름 → 3) null(호출자가 LLM 추정값으로 fallback). */
    fun match(foodName: String): Food? {
        val normalized = FoodNameNormalizer.normalize(foodName)
        if (normalized.isBlank()) return null
        return repository.findFirstByNormalizedName(normalized)
            ?: repository.searchByNormalizedName(normalized, PageRequest.of(0, 1)).firstOrNull()
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
