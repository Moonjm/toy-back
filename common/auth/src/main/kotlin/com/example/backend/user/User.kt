package com.example.backend.user

import com.example.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDate

@Entity
@Table(name = "users")
class User(
    @Column(nullable = false, length = 50, unique = true)
    var username: String,
    @Column(nullable = false, length = 50)
    var name: String,
    @Column(nullable = false, length = 100)
    var passwordHash: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var authority: Authority = Authority.USER,
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    var gender: Gender? = null,
    @Column(name = "birth_date")
    var birthDate: LocalDate? = null,
    @Column(nullable = false)
    var failedLoginAttempts: Int = 0,
    @Column(nullable = false)
    var locked: Boolean = false,
) : BaseEntity() {
    fun resetFailedAttempts() {
        failedLoginAttempts = 0
    }

    fun incrementFailedAttempts() {
        failedLoginAttempts++
        if (failedLoginAttempts >= 5) {
            locked = true
        }
    }

    fun unlock() {
        locked = false
        failedLoginAttempts = 0
    }

    fun updateCredentials(
        passwordHash: String,
        authority: Authority,
    ) {
        this.passwordHash = passwordHash
        this.authority = authority
    }

    fun updateProfile(
        name: String? = null,
        gender: Gender? = null,
        birthDate: LocalDate? = null,
    ) {
        name?.let { this.name = it }
        this.gender = gender
        this.birthDate = birthDate
    }

    fun updatePassword(passwordHash: String) {
        this.passwordHash = passwordHash
    }
}
