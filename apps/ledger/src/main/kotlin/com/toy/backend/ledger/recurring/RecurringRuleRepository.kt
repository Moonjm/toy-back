package com.toy.backend.ledger.recurring

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface RecurringRuleRepository : JpaRepository<RecurringRule, Long> {
    fun findAllByUser(user: User): List<RecurringRule>

    fun findAllByActiveTrue(): List<RecurringRule>

    /**
     * 해당 달의 생성 권한을 원자적으로 선점한다. lastGeneratedMonth가 아직 그 달이 아닐 때만
     * 갱신되므로, 스케줄러가 동시에 돌아도 하나만 1을 돌려받아 내역을 생성한다(나머지는 0).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update RecurringRule r
        set r.lastGeneratedMonth = :month
        where r.id = :id
          and r.active = true
          and (r.lastGeneratedMonth is null or r.lastGeneratedMonth <> :month)
        """,
    )
    fun claimMonth(
        @Param("id") id: Long,
        @Param("month") month: String,
    ): Int
}
