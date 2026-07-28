package com.toy.backend.file

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

interface FileRepository : JpaRepository<FileEntity, Long> {
    fun findAllByStatusAndUpdatedAtBefore(
        status: FileStatus,
        cutoff: LocalDateTime,
    ): List<FileEntity>

    /**
     * 조회~삭제 사이에 뒤늦게 첨부된 파일이 함께 지워지지 않도록 TEMP 상태를 가드로 건다.
     * 최악의 경우를 "레코드 소실"에서 "S3 객체만 소실"로 낮춘다.
     */
    @Modifying
    @Transactional
    @Query("delete from FileEntity f where f.id in :ids and f.status = :status")
    fun deleteByIdInAndStatus(
        @Param("ids") ids: List<Long>,
        @Param("status") status: FileStatus,
    )
}
