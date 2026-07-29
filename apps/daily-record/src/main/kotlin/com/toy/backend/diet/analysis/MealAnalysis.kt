package com.toy.backend.diet.analysis

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * 확정 전 인식 결과. 확정되면 삭제하고, 확인하지 않고 버려진 것은 TTL 24시간 배치가 지운다.
 * 사진 파일은 `attachFile`이 호출되지 않아 `TEMP`로 남고 파일 정리 배치가 따로 수거한다.
 */
@Entity
@Table(
    name = "meal_analysis",
    indexes = [
        Index(name = "idx_meal_analysis_created_at", columnList = "created_at"),
    ],
)
class MealAnalysis(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    var status: AnalysisStatus = AnalysisStatus.PENDING,
    @Column(name = "result_json", nullable = false, columnDefinition = "text")
    var resultJson: String,
) : BaseEntity() {
    fun updateResult(
        status: AnalysisStatus,
        resultJson: String,
    ) {
        this.status = status
        this.resultJson = resultJson
    }

    fun markPending() {
        this.status = AnalysisStatus.PENDING
    }
}
