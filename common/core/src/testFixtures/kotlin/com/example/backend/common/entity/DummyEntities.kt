package com.example.backend.common.entity

import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

fun <T : BaseEntity> T.withId(id: Long? = 1L): T =
    apply {
        ReflectionTestUtils.setField(this, "id", id)
    }

fun <T : BaseEntity> T.withAudit(
    createdAt: LocalDateTime = LocalDateTime.now(),
    updatedAt: LocalDateTime = createdAt,
    createdBy: String = "tester",
    updatedBy: String = "tester",
): T =
    apply {
        ReflectionTestUtils.setField(this, "createdAt", createdAt)
        ReflectionTestUtils.setField(this, "updatedAt", updatedAt)
        ReflectionTestUtils.setField(this, "createdBy", createdBy)
        ReflectionTestUtils.setField(this, "updatedBy", updatedBy)
    }
