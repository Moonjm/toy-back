package com.example.backend.pair.event

import com.example.backend.pair.PairConnection
import org.springframework.data.jpa.repository.JpaRepository

interface PairEventRepository : JpaRepository<PairEvent, Long> {
    fun findByPairOrderByEventDate(pair: PairConnection): List<PairEvent>
}
