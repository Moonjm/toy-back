package com.example.backend.familytree.person

import com.example.backend.familytree.tree.FamilyTree
import org.springframework.data.jpa.repository.JpaRepository

interface PersonRepository : JpaRepository<Person, Long> {
    fun findAllByFamilyTree(familyTree: FamilyTree): List<Person>

    fun existsByIdAndFamilyTree(
        id: Long,
        familyTree: FamilyTree,
    ): Boolean
}
