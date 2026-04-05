package com.toy.backend.storages

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(
    name = "storage_sections",
    indexes = [
        Index(name = "idx_storage_sections_storage", columnList = "storage_id"),
    ],
)
class StorageSection(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_id", nullable = false)
    var storage: Storage,
    @Column(nullable = false, length = 20)
    var name: String,
    @Column(nullable = false)
    var sortOrder: Int = 0,
) : BaseEntity() {
    fun updateName(name: String) {
        this.name = name
    }
}
