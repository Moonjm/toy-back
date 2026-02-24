package com.toy.backend.familytree.tree

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface FamilyTreeMemberRepository : JpaRepository<FamilyTreeMember, Long> {
    fun findByFamilyTreeAndUser(
        familyTree: FamilyTree,
        user: User,
    ): FamilyTreeMember?

    @Query("SELECT m FROM FamilyTreeMember m JOIN FETCH m.user WHERE m.familyTree = :familyTree")
    fun findAllByFamilyTree(familyTree: FamilyTree): List<FamilyTreeMember>

    @Query("SELECT m FROM FamilyTreeMember m JOIN FETCH m.familyTree WHERE m.user = :user")
    fun findAllByUser(user: User): List<FamilyTreeMember>

    fun countByFamilyTreeAndRole(
        familyTree: FamilyTree,
        role: FamilyTreeRole,
    ): Long
}
