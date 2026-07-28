package com.toy.backend.file

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

@Entity
@Table(name = "files")
class FileEntity(
    @Column(nullable = false, length = 255)
    var originalName: String,
    @Column(nullable = false, length = 255)
    var storedName: String,
    @Column(nullable = false, length = 100)
    var contentType: String,
    @Column(nullable = false)
    var fileSize: Long,
    @Column(nullable = false, length = 100)
    var bucketName: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: FileStatus = FileStatus.TEMP,
) : BaseEntity() {
    fun attach(newStoredName: String) {
        this.storedName = newStoredName
        this.status = FileStatus.ATTACHED
    }

    /**
     * 도메인 연결이 끊긴 파일을 수거 대상(TEMP)으로 되돌린다. 물리 삭제를 정리 배치로 미뤄
     * 도메인 트랜잭션이 롤백되면 파일도 함께 살아나게 한다. 경로는 그대로 두므로 정리 배치는
     * storedName 기준으로 지운다.
     */
    fun detach() {
        this.status = FileStatus.TEMP
    }
}
