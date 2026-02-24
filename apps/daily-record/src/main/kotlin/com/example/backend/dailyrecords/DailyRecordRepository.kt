package com.example.backend.dailyrecords

import com.example.backend.user.User
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import org.springframework.data.jpa.repository.JpaRepository

interface DailyRecordRepository :
    JpaRepository<DailyRecord, Long>,
    KotlinJdslJpqlExecutor {
    fun findByIdAndUser(
        id: Long,
        user: User,
    ): DailyRecord?
}
