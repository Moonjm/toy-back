package com.toy.backend.familytree.tree

import org.springframework.data.jpa.repository.JpaRepository

interface FamilyTreeRepository : JpaRepository<FamilyTree, Long>
