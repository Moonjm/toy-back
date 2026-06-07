package com.toy.backend.pair

import com.linecorp.kotlinjdsl.querymodel.jpql.path.Paths.path
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.toy.backend.common.utils.findAllNotNull
import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository

interface PairRepository :
    JpaRepository<PairConnection, Long>,
    KotlinJdslJpqlExecutor {
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

    /** 사용자가 inviter이든 partner이든 CONNECTED 상태의 짝을 단일 쿼리로 조회한다. */
    fun findConnectedPair(user: User): PairConnection? =
        findAllNotNull {
            val pair = entity(PairConnection::class)
            select(pair)
                .from(pair)
                .whereAnd(
                    path(pair, PairConnection::status).eq(PairStatus.CONNECTED),
                    or(
                        path(pair, PairConnection::inviter).eq(user),
                        path(pair, PairConnection::partner).eq(user),
                    ),
                )
        }.firstOrNull()
}
