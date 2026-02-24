package com.toy.backend.pair.event

import com.toy.backend.pair.PairConnection
import org.springframework.data.jpa.repository.JpaRepository

interface PairEventRepository : JpaRepository<PairEvent, Long> {
    fun findByPairOrderByEventDate(pair: PairConnection): List<PairEvent>
}
