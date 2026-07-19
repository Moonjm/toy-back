package com.toy.backend.ledger.apikeys

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(
    name = "ledger_api_keys",
    indexes = [
        Index(name = "idx_ledger_api_keys_key_hash", columnList = "key_hash", unique = true),
    ],
)
class ApiKey(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(name = "key_hash", nullable = false, length = 64)
    var keyHash: String,
    @Column(nullable = false, length = 50)
    var name: String,
) : BaseEntity()
