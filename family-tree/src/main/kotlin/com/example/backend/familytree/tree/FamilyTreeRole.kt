package com.example.backend.familytree.tree

enum class FamilyTreeRole(
    val priority: Int,
) {
    OWNER(100),
    EDITOR(50),
    VIEWER(10),
    ;

    fun hasAtLeast(required: FamilyTreeRole): Boolean = this.priority >= required.priority
}
