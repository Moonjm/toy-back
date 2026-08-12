package com.toy.backend.dispatch

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 행 위치 갱신만 담당한다. **별도 빈이라야 트랜잭션이 걸린다** — 인식 서비스 안의
 * `private` 메서드로 두면 자기 호출이라 프록시를 타지 않는다.
 *
 * 인식은 LLM을 최대 12분까지 부를 수 있어 트랜잭션 밖에서 돈다. 그래서 여기서 **다시 조회한다**
 * — 인식 서비스가 읽어 둔 엔티티는 준영속이라 필드를 바꿔도 반영되지 않는다.
 */
@Service
class DispatchRosterUpdater(
    private val rosterRepository: DispatchRosterRepository,
) {
    @Transactional
    fun upsert(
        yearMonth: String,
        rowIndex: Int,
        rowCount: Int,
    ) {
        val existing = rosterRepository.findByYearMonth(yearMonth)
        if (existing == null) {
            rosterRepository.save(DispatchRoster(yearMonth, rowIndex, rowCount))
        } else {
            existing.rowIndex = rowIndex
            existing.rowCount = rowCount
        }
    }
}
