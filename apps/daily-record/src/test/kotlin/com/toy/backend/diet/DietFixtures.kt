package com.toy.backend.diet

import com.toy.backend.common.entity.withId
import com.toy.backend.diet.food.Food
import com.toy.backend.diet.food.FoodDataset
import com.toy.backend.diet.food.FoodNameNormalizer
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealItem
import com.toy.backend.diet.meal.MealType
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
    targetSugarG: Int = 125,
    targetSodiumMg: Int = 2300,
    targetFiberG: Int = 30,
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
        targetSugarG = targetSugarG,
        targetSodiumMg = targetSodiumMg,
        targetFiberG = targetFiberG,
    ).withId(id)

fun dummyFood(
    code: String = "D000",
    name: String = "제육볶음",
    normalizedName: String = FoodNameNormalizer.normalize(name),
    dataset: FoodDataset = FoodDataset.DISH,
    servingSizeG: Double = 200.0,
    servingSizeKnown: Boolean = true,
    kcalPer100g: Double = 180.0,
    carbsPer100g: Double = 12.0,
    proteinPer100g: Double = 15.0,
    fatPer100g: Double = 8.0,
    sugarPer100g: Double = 3.0,
    sodiumMgPer100g: Double = 500.0,
    fiberPer100g: Double = 2.0,
    id: Long = 1L,
): Food =
    Food(
        code = code,
        name = name,
        normalizedName = normalizedName,
        dataset = dataset,
        servingSizeG = servingSizeG,
        servingSizeKnown = servingSizeKnown,
        kcalPer100g = kcalPer100g,
        carbsPer100g = carbsPer100g,
        proteinPer100g = proteinPer100g,
        fatPer100g = fatPer100g,
        sugarPer100g = sugarPer100g,
        sodiumMgPer100g = sodiumMgPer100g,
        fiberPer100g = fiberPer100g,
    ).withId(id)

fun dummyMeal(
    user: User = dietUser(),
    date: LocalDate = LocalDate.of(2026, 7, 29),
    mealType: MealType = MealType.LUNCH,
    weightKg: Double = 70.0,
    targetKcal: Int = 2509,
    targetCarbsG: Int = 345,
    targetProteinG: Int = 94,
    targetFatG: Int = 84,
    targetSugarG: Int = 125,
    targetSodiumMg: Int = 2300,
    targetFiberG: Int = 30,
    id: Long = 1L,
): Meal =
    Meal(
        user = user,
        date = date,
        mealType = mealType,
        weightKg = weightKg,
        targetKcal = targetKcal,
        targetCarbsG = targetCarbsG,
        targetProteinG = targetProteinG,
        targetFatG = targetFatG,
        targetSugarG = targetSugarG,
        targetSodiumMg = targetSodiumMg,
        targetFiberG = targetFiberG,
    ).withId(id)

fun dummyMealItem(
    meal: Meal = dummyMeal(),
    foodName: String = "제육볶음",
    foodCode: String? = "D000",
    quantityG: Double = 200.0,
    kcal: Double = 360.0,
    carbsG: Double = 24.0,
    proteinG: Double = 30.0,
    fatG: Double = 16.0,
    source: NutritionSource = NutritionSource.DB_MATCHED,
    id: Long = 1L,
): MealItem =
    MealItem(
        meal = meal,
        foodName = foodName,
        foodCode = foodCode,
        quantityG = quantityG,
        kcal = kcal,
        carbsG = carbsG,
        proteinG = proteinG,
        fatG = fatG,
        source = source,
    ).withId(id)
