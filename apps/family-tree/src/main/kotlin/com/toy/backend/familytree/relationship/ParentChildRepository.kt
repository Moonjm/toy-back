package com.toy.backend.familytree.relationship

import com.toy.backend.familytree.tree.FamilyTree
import org.springframework.data.jpa.repository.JpaRepository

interface ParentChildRepository : JpaRepository<ParentChild, Long> {
    fun findAllByFamilyTree(familyTree: FamilyTree): List<ParentChild>

    fun findByParentIdAndChildId(
        parentId: Long,
        childId: Long,
    ): ParentChild?

    fun countByChildId(childId: Long): Long

    fun deleteAllByParentIdOrChildId(
        parentId: Long,
        childId: Long,
    )

    fun deleteAllByFamilyTree(familyTree: FamilyTree)
}
