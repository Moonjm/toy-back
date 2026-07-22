package com.toy.backend.ledger.recurring

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository

interface RecurringRuleRepository : JpaRepository<RecurringRule, Long> {
    fun findAllByUser(user: User): List<RecurringRule>

    fun findAllByActiveTrue(): List<RecurringRule>
}
