package com.toy.backend.dailyrecords

import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import org.springframework.data.jpa.repository.JpaRepository

interface DailyRecordRepository :
    JpaRepository<DailyRecord, Long>,
    KotlinJdslJpqlExecutor
