package com.toy.backend.dispatch

import org.springframework.data.jpa.repository.JpaRepository

interface DispatchPatternRepository : JpaRepository<DispatchPattern, Long> {
    fun findByRole(role: DispatchRole): DispatchPattern?
}
