package com.example.backend.pair

import com.example.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository

interface PairRepository : JpaRepository<PairConnection, Long> {
    fun findByInviterAndStatus(
        inviter: User,
        status: PairStatus,
    ): PairConnection?

    fun findByInviterAndStatusIn(
        inviter: User,
        statuses: List<PairStatus>,
    ): PairConnection?

    fun findByPartnerAndStatus(
        partner: User,
        status: PairStatus,
    ): PairConnection?

    fun findByInviteCode(inviteCode: String): PairConnection?
}
