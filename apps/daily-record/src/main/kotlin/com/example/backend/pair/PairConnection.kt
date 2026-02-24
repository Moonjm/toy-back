package com.example.backend.pair

import com.example.backend.common.entity.BaseEntity
import com.example.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(
    name = "pairs",
    indexes = [
        Index(name = "idx_pairs_inviter", columnList = "inviter_id"),
        Index(name = "idx_pairs_partner", columnList = "partner_id"),
        Index(name = "idx_pairs_invite_code", columnList = "invite_code", unique = true),
    ],
)
class PairConnection(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inviter_id", nullable = false)
    var inviter: User,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_id", nullable = true)
    var partner: User? = null,
    @Column(name = "invite_code", nullable = false, length = 6, unique = true)
    var inviteCode: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PairStatus = PairStatus.PENDING,
    @Column(name = "connected_at", nullable = true)
    var connectedAt: LocalDateTime? = null,
) : BaseEntity() {
    fun accept(partner: User) {
        this.partner = partner
        this.status = PairStatus.CONNECTED
        this.connectedAt = LocalDateTime.now()
    }
}
