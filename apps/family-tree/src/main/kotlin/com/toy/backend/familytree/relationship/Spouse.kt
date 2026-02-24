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
    name = "spouses",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_spouse_person_a", columnNames = ["person_a_id"]),
        UniqueConstraint(name = "uk_spouse_person_b", columnNames = ["person_b_id"]),
    ],
)
class Spouse(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "family_tree_id", nullable = false)
    var familyTree: FamilyTree,
    @Column(name = "person_a_id", nullable = false)
    var personAId: Long,
    @Column(name = "person_b_id", nullable = false)
    var personBId: Long,
) : BaseEntity()
