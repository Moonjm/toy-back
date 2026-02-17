package com.example.backend.familytree.person

import com.example.backend.common.entity.BaseEntity
import com.example.backend.familytree.tree.FamilyTree
import com.example.backend.user.Gender
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDate

@Entity
@Table(name = "persons")
class Person(
    @ManyToOne(fetch = FetchType.LAZY)
    val familyTree: FamilyTree,
    @Column(nullable = false, length = 50)
    var name: String,
    @Column(nullable = true)
    var birthDate: LocalDate? = null,
    @Column(nullable = true)
    var deathDate: LocalDate? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = true, length = 10)
    var gender: Gender? = null,
    @Column(name = "profile_image_id", nullable = true)
    var profileImageId: Long? = null,
    @Column(nullable = true, length = 500)
    var memo: String? = null,
) : BaseEntity() {
    fun updateDetails(
        name: String,
        birthDate: LocalDate?,
        deathDate: LocalDate?,
        gender: Gender?,
        profileImageId: Long?,
        memo: String?,
    ) {
        this.name = name
        this.birthDate = birthDate
        this.deathDate = deathDate
        this.gender = gender
        this.profileImageId = profileImageId
        this.memo = memo
    }
}
