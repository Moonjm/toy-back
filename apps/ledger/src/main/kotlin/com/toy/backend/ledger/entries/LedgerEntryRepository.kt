package com.toy.backend.ledger.entries

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.LocalDateTime

interface LedgerEntryRepository : JpaRepository<LedgerEntry, Long> {
    /**
     * 내역 조회: 연월 구간([start, end))과 검색어(구매처/내용 부분 일치)는 각각 선택 조건이다.
     * 둘 다 null이면 전체가 나오므로 서비스에서 최소 한 조건을 강제한다.
     *
     * null 파라미터는 cast로 타입을 명시한다 — 없으면 PostgreSQL이 타입을 추론하지 못해
     * `character varying ~~ bytea` 오류가 난다.
     */
    @Query(
        """
        select e from LedgerEntry e
        where e.user = :user
          and (cast(:start as timestamp) is null or (e.entryAt >= :start and e.entryAt < :end))
          and (
            cast(:keyword as string) is null
            or e.merchant like concat('%', cast(:keyword as string), '%')
            or e.description like concat('%', cast(:keyword as string), '%')
          )
        order by e.entryAt desc, e.id desc
        """,
    )
    fun search(
        @Param("user") user: User,
        @Param("start") start: LocalDateTime?,
        @Param("end") end: LocalDateTime?,
        @Param("keyword") keyword: String?,
    ): List<LedgerEntry>

    /**
     * 취소 매칭 대상: 같은 사용자·금액·통화·가맹점·source의 [after, before] 구간 내 최신 건.
     * 상한(before)은 취소 시각 — 취소보다 나중에 발생한 승인이 삭제되는 것을 막는다.
     */
    @Query(
        """
        select e from LedgerEntry e
        where e.user = :user
          and e.amount = :amount
          and e.currency = :currency
          and e.merchant = :merchant
          and e.source = :source
          and e.entryAt > :after
          and e.entryAt <= :before
        order by e.entryAt desc
        limit 1
        """,
    )
    fun findLatestCancellable(
        @Param("user") user: User,
        @Param("amount") amount: BigDecimal,
        @Param("currency") currency: String,
        @Param("merchant") merchant: String,
        @Param("source") source: EntrySource,
        @Param("after") after: LocalDateTime,
        @Param("before") before: LocalDateTime,
    ): LedgerEntry?
}
