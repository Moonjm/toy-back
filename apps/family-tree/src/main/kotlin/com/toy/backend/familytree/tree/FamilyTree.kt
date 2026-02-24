package com.toy.backend.familytree.tree

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "family_trees")
class FamilyTree(
    @Column(nullable = false, length = 100)
    var name: String,
    @Column(nullable = true, length = 500)
    var description: String? = null,
) : BaseEntity() {
    fun updateDetails(
        name: String,
        description: String?,
    ) {
        this.name = name
        this.description = description
    }
}
