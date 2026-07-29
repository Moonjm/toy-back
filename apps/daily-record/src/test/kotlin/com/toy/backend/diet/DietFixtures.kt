package com.toy.backend.diet

import com.toy.backend.common.entity.withId
import com.toy.backend.diet.profile.ActivityLevel
import com.toy.backend.diet.profile.DietGoal
import com.toy.backend.diet.profile.NutritionProfile
import com.toy.backend.user.Gender
import com.toy.backend.user.User
import com.toy.backend.user.entity.dummyUser
import java.time.LocalDate

/** 목표 계산에는 성별·생년월일이 필요하다 — 공용 dummyUser()는 둘 다 null이라 여기서 채운다. */
fun dietUser(
    username: String = "testuser",
    gender: Gender = Gender.MALE,
    birthDate: LocalDate = LocalDate.of(1990, 1, 1),
    id: Long = 1L,
): User = dummyUser(username = username, gender = gender, birthDate = birthDate, id = id)

fun dummyProfile(
    user: User = dietUser(),
    heightCm: Double = 175.0,
    weightKg: Double = 70.0,
    activityLevel: ActivityLevel = ActivityLevel.MODERATE,
    goal: DietGoal = DietGoal.MAINTAIN,
    targetKcal: Int = 2509,
    targetCarbsG: Int = 345,
    targetProteinG: Int = 94,
    targetFatG: Int = 84,
    id: Long = 1L,
): NutritionProfile =
    NutritionProfile(
        user = user,
        heightCm = heightCm,
        weightKg = weightKg,
        activityLevel = activityLevel,
        goal = goal,
        targetKcal = targetKcal,
        targetCarbsG = targetCarbsG,
        targetProteinG = targetProteinG,
        targetFatG = targetFatG,
    ).withId(id)
