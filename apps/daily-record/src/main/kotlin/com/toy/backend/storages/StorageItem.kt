package com.toy.backend.storages

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDate

@Entity
@Table(
    name = "storage_items",
    indexes = [
        Index(name = "idx_storage_items_section", columnList = "section_id"),
        Index(name = "idx_storage_items_expiry", columnList = "expiry_date"),
    ],
)
class StorageItem(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id", nullable = false)
    var section: StorageSection,
    @Column(nullable = false, length = 30)
    var name: String,
    @Column(nullable = false)
    var quantity: Int = 1,
    @Column(name = "expiry_date", nullable = true)
    var expiryDate: LocalDate? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    var createdByUser: User,
) : BaseEntity() {
    fun updateDetails(name: String, quantity: Int, expiryDate: LocalDate?, section: StorageSection) {
        this.name = name
        this.quantity = quantity
        this.expiryDate = expiryDate
        this.section = section
    }
}
