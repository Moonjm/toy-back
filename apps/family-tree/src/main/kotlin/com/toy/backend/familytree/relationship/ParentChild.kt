package com.toy.backend.familytree.relationship

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.familytree.tree.FamilyTree
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "parent_child",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_parent_child", columnNames = ["parent_id", "child_id"]),
    ],
)
class ParentChild(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "family_tree_id", nullable = false)
    var familyTree: FamilyTree,
    @Column(name = "parent_id", nullable = false)
    var parentId: Long,
    @Column(name = "child_id", nullable = false)
    var childId: Long,
) : BaseEntity()
