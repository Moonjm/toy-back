package com.example.backend.familytree.relationship

import com.example.backend.familytree.tree.FamilyTree
import org.springframework.data.jpa.repository.JpaRepository

interface SpouseRepository : JpaRepository<Spouse, Long> {
    fun findAllByFamilyTree(familyTree: FamilyTree): List<Spouse>

    fun findByPersonAIdAndPersonBId(
        personAId: Long,
        personBId: Long,
    ): Spouse?

    fun deleteByPersonAIdOrPersonBId(
        personAId: Long,
        personBId: Long,
    )

    fun findByPersonAIdOrPersonBId(
        personAId: Long,
        personBId: Long,
    ): List<Spouse>

    fun deleteAllByFamilyTree(familyTree: FamilyTree)
}
