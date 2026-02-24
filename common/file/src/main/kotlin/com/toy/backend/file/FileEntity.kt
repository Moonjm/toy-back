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
}
