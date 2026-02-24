package com.example.backend.pair.event

import com.example.backend.common.entity.BaseEntity
import com.example.backend.pair.PairConnection
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDate

@Entity
@Table(
    name = "pair_events",
    indexes = [
        Index(name = "idx_pair_events_pair", columnList = "pair_id"),
        Index(name = "idx_pair_events_date", columnList = "event_date"),
    ],
)
class PairEvent(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pair_id", nullable = false)
    var pair: PairConnection,
    @Column(nullable = false, length = 30)
    var title: String,
    @Column(nullable = false, length = 10)
    var emoji: String,
    @Column(name = "event_date", nullable = false)
    var eventDate: LocalDate,
    @Column(nullable = false)
    var recurring: Boolean = true,
) : BaseEntity()
