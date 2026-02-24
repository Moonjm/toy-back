package com.toy.backend.familytree.tree

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "family_tree_members",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_family_tree_member",
            columnNames = ["family_tree_id", "user_id"],
        ),
    ],
)
class FamilyTreeMember(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "family_tree_id", nullable = false)
    var familyTree: FamilyTree,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: FamilyTreeRole,
) : BaseEntity() {
    fun updateRole(role: FamilyTreeRole) {
        this.role = role
    }
}
