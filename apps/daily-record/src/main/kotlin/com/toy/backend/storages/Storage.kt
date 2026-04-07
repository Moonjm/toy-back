package com.toy.backend.storages

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

@Entity
@Table(
    name = "storages",
    indexes = [
        Index(name = "idx_storages_user", columnList = "user_id"),
        Index(name = "idx_storages_pair", columnList = "pair_id"),
    ],
)
class Storage(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = true)
    var user: User? = null,
    @Column(name = "pair_id", nullable = true)
    var pairId: Long? = null,
    @Column(nullable = false, length = 30)
    var name: String,
    @Column(name = "storage_type", nullable = true, length = 20)
    var storageType: String? = null,
    @Column(nullable = false)
    var sortOrder: Int = 0,
    @OneToMany(mappedBy = "storage", cascade = [CascadeType.REMOVE], orphanRemoval = true)
    val sections: MutableList<StorageSection> = mutableListOf(),
) : BaseEntity() {
    fun update(name: String, storageType: String?) {
        this.name = name
        this.storageType = storageType
    }

    fun updateSortOrder(sortOrder: Int) {
        this.sortOrder = sortOrder
    }

    fun transferToPair(pairId: Long) {
        this.pairId = pairId
        this.user = null
    }
}
