# 식단 사진 분석·점수·피드백 (백엔드) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 식사 사진을 여러 장 올리면 서버가 음식을 인식하고, 사용자가 확인·수정한 뒤 확정하면 KDRIs 기준 점수와 LLM 피드백을 돌려주는 API를 `apps/daily-record`에 만든다.

**Architecture:** LLM(OpenRouter)은 *음식 식별*과 *문장 생성*만 맡고, 영양소는 식품DB(`Food`) 조회로, 점수는 룰 기반 순수 함수(`DietScoreCalculator`)로 구한다. 인식은 `MealAnalysis`(임시)에 쌓고 사용자가 확인한 뒤 `Meal`로 확정한다. 확정 시점의 몸무게·목표를 `Meal`에 스냅샷으로 복사해 과거 점수가 소급 변경되지 않게 한다. 사진은 `common-file`(MinIO)을 재사용하고 삭제는 detach로 처리한다.

**Tech Stack:** Kotlin 2.4 / Spring Boot 4.1 / JPA(`ddl-auto: update`) / PostgreSQL / WebClient(webflux) / Jackson 3(`tools.jackson`) / kotest `BehaviorSpec` + mockk

**설계 문서:** `docs/superpowers/specs/2026-07-27-diet-tracking-backend-design.md` (2026-07-29 개정판)

## Global Constraints

- 패키지 루트는 `com.toy.backend.diet`. 앱 전용 에러 코드는 `DietErrorCode`(`Code` 구현 enum).
- 모든 enum 컬럼에 `columnDefinition = "varchar(N)"`을 명시한다(저장소 관례). **CHECK 제약은 실제로는 생기므로**, enum 값을 나중에 추가하면 배포 시 `ALTER TABLE <표> DROP CONSTRAINT <표>_<컬럼>_check`가 필요하다.
- 응답 규칙: 생성은 `@ResponseCreated("/경로/{id}")`로 201 + Location, 수정·삭제는 204, 조회는 `DataResponseBody`.
- **타인 소유 리소스는 `ErrorCode.RESOURCE_NOT_FOUND`(404)로 존재를 숨긴다.** 403을 쓰지 않는다.
- 수치 타입: 영양소·몸무게·키·kcal 합계는 `Double`, 목표치(`targetKcal` 등)와 점수는 `Int`. 금액이 아니므로 `BigDecimal`을 쓰지 않는다.
- 커밋 전 `./gradlew spotlessApply` 필수. 테스트는 `./gradlew :daily-record:test`.
- 테스트는 kotest `BehaviorSpec` + mockk, 픽스처는 `testFixtures`의 `dummyUser()`·`withId()`·`withAudit()`를 쓴다. 격리 모드가 `InstancePerLeaf`라 `beforeTest`는 컨테이너 노드에서도 발화한다 — 리프에서만 초기화하려면 `beforeContainer`.
- 동시성 방어(락·낙관적 버저닝)를 넣지 않는다. 단일 인스턴스·사용자 2명 전제.
- `Meal`·`MealAnalysis` 조회는 반드시 소유자 확인을 거친다. 사용자 데이터 격리는 규모와 무관하게 엄격히 다룬다.

## File Structure

```
apps/daily-record/src/main/kotlin/com/toy/backend/diet/
  DietErrorCode.kt              앱 전용 에러 코드
  AfterCommit.kt                runAfterCommit — 커밋 후 @Async 트리거
  AnalysisStatus.kt             PENDING/COMPLETED/FAILED (MealAnalysis·Meal 공용)
  NutritionSource.kt            DB_MATCHED/LLM_ESTIMATED (분석 결과·MealItem 공용)
  profile/                      NutritionProfile, 목표 계산(BMR·TDEE·매크로), 프로필 API
  score/                        DietScorePolicy(상수), DietScoreCalculator(순수 함수), 점수 근거 DTO
  food/                         Food, 이름 정규화, FoodMatcher, CSV 파서·시더, 식품 검색 API
  llm/                          OpenRouterProperties/Config/Client (키 있을 때만 빈 등록)
  analysis/                     MealAnalysis, 인식 파이프라인(@Async), 분석 API, 정리 배치
  meal/                         Meal·MealItem·MealPhoto, 확정·수정·삭제 API
  feedback/                     DietFeedbackGenerator, DailyDietFeedback 캐시
  daily/                        DailyActivity, 하루 집계 API
apps/daily-record/src/main/resources/food/food-nutrition.csv   식약처 `음식` 표준데이터 정제본
scripts/build-food-csv.py                                      원본 → 위 CSV 정제 스크립트
```

한 파일은 한 책임만 맡는다. 엔티티 파일에는 그 엔티티 전용 enum만 함께 둔다(`ledger/entries/LedgerEntry.kt` 관례). 두 도메인이 공유하는 enum(`AnalysisStatus`·`NutritionSource`)만 `diet/` 최상단에 독립 파일로 둔다.

---

### Task 1: 영양 프로필과 목표 계산

`NutritionProfile` 엔티티, BMR·TDEE·매크로 목표 계산, 프로필 조회/저장/몸무게 갱신 API. 이 도메인의 모든 점수가 여기서 나온 목표치를 기준으로 삼는다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/DietErrorCode.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionProfile.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionTargetCalculator.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionProfileRepository.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionProfileService.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionProfileDtos.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionProfileController.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/DietFixtures.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/profile/NutritionTargetCalculatorTest.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/profile/NutritionProfileServiceTest.kt`

**Interfaces:**
- Consumes: `com.toy.backend.user.User`(`gender: Gender?`, `birthDate: LocalDate?`), `UserRepository.findByUsername(String): User?`, `BaseEntity.requiredId`
- Produces:
  - `enum class ActivityLevel(val factor: Double)` — SEDENTARY 1.2 / LIGHT 1.375 / MODERATE 1.55 / ACTIVE 1.725 / VERY_ACTIVE 1.9
  - `enum class DietGoal(val calorieFactor: Double, val carbsPercent: Int, val proteinPercent: Int, val fatPercent: Int)`
  - `data class NutritionTargets(val kcal: Int, val carbsG: Int, val proteinG: Int, val fatG: Int)`
  - `NutritionTargetCalculator.calculate(gender, birthDate, heightCm, weightKg, activityLevel, goal, today): NutritionTargets`
  - `class NutritionProfile(user, heightCm, weightKg, activityLevel, goal, targetKcal, targetCarbsG, targetProteinG, targetFatG)` — `updateDetails(...)`, `updateWeight(Double)`, `applyTargets(NutritionTargets)`
  - `NutritionProfileRepository.findByUser(User): NutritionProfile?`
  - `NutritionProfileService.get/save/updateWeight`
  - `DietErrorCode` (전 Task 공용)

- [ ] **Step 1: 목표 계산 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/profile/NutritionTargetCalculatorTest.kt`:

```kotlin
package com.toy.backend.diet.profile

import com.toy.backend.user.Gender
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class NutritionTargetCalculatorTest :
    BehaviorSpec({
        val today = LocalDate.of(2026, 7, 29)

        Given("남성 · MODERATE · MAINTAIN") {
            When("175cm 70kg, 1990-01-01생(36세)") {
                val targets =
                    NutritionTargetCalculator.calculate(
                        gender = Gender.MALE,
                        birthDate = LocalDate.of(1990, 1, 1),
                        heightCm = 175.0,
                        weightKg = 70.0,
                        activityLevel = ActivityLevel.MODERATE,
                        goal = DietGoal.MAINTAIN,
                        today = today,
                    )

                Then("BMR 1618.75 × 1.55 = 2509kcal, KDRIs 55/15/30 배분") {
                    targets.kcal shouldBe 2509
                    targets.carbsG shouldBe 345
                    targets.proteinG shouldBe 94
                    targets.fatG shouldBe 84
                }
            }
        }

        Given("여성 · LIGHT · LOSE") {
            When("162cm 55kg, 1992-03-01생(34세)") {
                val targets =
                    NutritionTargetCalculator.calculate(
                        gender = Gender.FEMALE,
                        birthDate = LocalDate.of(1992, 3, 1),
                        heightCm = 162.0,
                        weightKg = 55.0,
                        activityLevel = ActivityLevel.LIGHT,
                        goal = DietGoal.LOSE,
                        today = today,
                    )

                Then("BMR 1231.5 × 1.375 × 0.85 = 1439kcal, KDRIs 50/20/30 배분") {
                    targets.kcal shouldBe 1439
                    targets.carbsG shouldBe 180
                    targets.proteinG shouldBe 72
                    targets.fatG shouldBe 48
                }
            }
        }

        Given("목표별 매크로 비율") {
            When("세 목표의 비율을 더하면") {
                Then("모두 100%이고 KDRIs 범위 안이다") {
                    DietGoal.entries.forEach { goal ->
                        (goal.carbsPercent + goal.proteinPercent + goal.fatPercent) shouldBe 100
                        (goal.carbsPercent in 50..65) shouldBe true
                        (goal.proteinPercent in 10..20) shouldBe true
                        (goal.fatPercent in 15..30) shouldBe true
                    }
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :daily-record:test --tests "*NutritionTargetCalculatorTest*"`
Expected: FAIL — `Unresolved reference: NutritionTargetCalculator`

- [ ] **Step 3: enum과 목표 계산기 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionProfile.kt`:

```kotlin
package com.toy.backend.diet.profile

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/** PAL(신체활동수준) 관례값 1.2~1.9 — FAO/WHO/UNU 에너지 요구량 보고서 계열의 통용값. */
enum class ActivityLevel(
    val factor: Double,
) {
    SEDENTARY(1.2),
    LIGHT(1.375),
    MODERATE(1.55),
    ACTIVE(1.725),
    VERY_ACTIVE(1.9),
}

/**
 * 매크로 비율은 2025 한국인 영양소 섭취기준(KDRIs) 에너지적정비율(탄 50~65 · 단 10~20 · 지 15~30)
 * 안에서 목표별로 고른 값이다. 세 비율의 합이 100이어야 해서 범위 중앙값을 그대로 쓸 수 없다.
 * 칼로리 계수(0.85/1.0/1.1)는 자체 설정값으로 공개 근거가 없다.
 */
enum class DietGoal(
    val calorieFactor: Double,
    val carbsPercent: Int,
    val proteinPercent: Int,
    val fatPercent: Int,
) {
    LOSE(0.85, 50, 20, 30),
    MAINTAIN(1.0, 55, 15, 30),
    GAIN(1.1, 60, 15, 25),
}

@Entity
@Table(
    name = "nutrition_profiles",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_nutrition_profiles_user", columnNames = ["user_id"]),
    ],
)
class NutritionProfile(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(name = "height_cm", nullable = false)
    var heightCm: Double,
    @Column(name = "weight_kg", nullable = false)
    var weightKg: Double,
    // columnDefinition 명시로 enum CHECK 제약 생성을 막는다 (ddl-auto:update가 제약을 갱신하지 못함)
    @Enumerated(EnumType.STRING)
    @Column(name = "activity_level", nullable = false, columnDefinition = "varchar(20)")
    var activityLevel: ActivityLevel,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(10)")
    var goal: DietGoal,
    @Column(name = "target_kcal", nullable = false)
    var targetKcal: Int = 0,
    @Column(name = "target_carbs_g", nullable = false)
    var targetCarbsG: Int = 0,
    @Column(name = "target_protein_g", nullable = false)
    var targetProteinG: Int = 0,
    @Column(name = "target_fat_g", nullable = false)
    var targetFatG: Int = 0,
) : BaseEntity() {
    fun updateDetails(
        heightCm: Double,
        weightKg: Double,
        activityLevel: ActivityLevel,
        goal: DietGoal,
    ) {
        this.heightCm = heightCm
        this.weightKg = weightKg
        this.activityLevel = activityLevel
        this.goal = goal
    }

    /** 몸무게는 매일 갱신된다 — 키·활동량·목표를 함께 보내게 하면 클라이언트가 낡은 값을 되돌려 쓴다. */
    fun updateWeight(weightKg: Double) {
        this.weightKg = weightKg
    }

    fun applyTargets(targets: NutritionTargets) {
        this.targetKcal = targets.kcal
        this.targetCarbsG = targets.carbsG
        this.targetProteinG = targets.proteinG
        this.targetFatG = targets.fatG
    }

    fun targets(): NutritionTargets = NutritionTargets(targetKcal, targetCarbsG, targetProteinG, targetFatG)
}
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionTargetCalculator.kt`:

```kotlin
package com.toy.backend.diet.profile

import com.toy.backend.user.Gender
import java.time.LocalDate
import java.time.Period
import kotlin.math.roundToInt

data class NutritionTargets(
    val kcal: Int,
    val carbsG: Int,
    val proteinG: Int,
    val fatG: Int,
)

/**
 * 목표 섭취량 산출. BMR은 Mifflin-St Jeor(1990) 공식이고, 활동 계수는 PAL 관례값,
 * 매크로 배분은 KDRIs 에너지적정비율 안에서 고른 값이다(`DietGoal` 주석 참조).
 *
 * 매크로 g은 **반올림된 kcal**에서 다시 계산한다 — 사용자에게 보이는 목표 칼로리와
 * 매크로 목표가 서로 맞아야 "2509kcal의 55%가 탄수화물 345g"이라고 설명할 수 있다.
 */
object NutritionTargetCalculator {
    private const val KCAL_PER_G_CARBS = 4.0
    private const val KCAL_PER_G_PROTEIN = 4.0
    private const val KCAL_PER_G_FAT = 9.0

    fun calculate(
        gender: Gender,
        birthDate: LocalDate,
        heightCm: Double,
        weightKg: Double,
        activityLevel: ActivityLevel,
        goal: DietGoal,
        today: LocalDate,
    ): NutritionTargets {
        val age = Period.between(birthDate, today).years
        val bmr =
            when (gender) {
                Gender.MALE -> 10 * weightKg + 6.25 * heightCm - 5 * age + 5
                Gender.FEMALE -> 10 * weightKg + 6.25 * heightCm - 5 * age - 161
            }
        val kcal = (bmr * activityLevel.factor * goal.calorieFactor).roundToInt()
        return NutritionTargets(
            kcal = kcal,
            carbsG = (kcal * goal.carbsPercent / 100.0 / KCAL_PER_G_CARBS).roundToInt(),
            proteinG = (kcal * goal.proteinPercent / 100.0 / KCAL_PER_G_PROTEIN).roundToInt(),
            fatG = (kcal * goal.fatPercent / 100.0 / KCAL_PER_G_FAT).roundToInt(),
        )
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*NutritionTargetCalculatorTest*"`
Expected: PASS (3 Given, 4 Then)

- [ ] **Step 5: 서비스 실패 테스트 작성**

먼저 픽스처 `apps/daily-record/src/test/kotlin/com/toy/backend/diet/DietFixtures.kt`:

```kotlin
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
```

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/profile/NutritionProfileServiceTest.kt`:

```kotlin
package com.toy.backend.diet.profile

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyProfile
import com.toy.backend.user.UserRepository
import com.toy.backend.user.entity.dummyUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class NutritionProfileServiceTest :
    BehaviorSpec({
        val repository = mockk<NutritionProfileRepository>()
        val userRepository = mockk<UserRepository>()
        val service = NutritionProfileService(repository, userRepository)

        val user = dietUser()

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("프로필 저장") {
            When("처음 저장하면") {
                every { repository.findByUser(user) } returns null
                every { repository.save(any()) } answers { (firstArg() as NutritionProfile).withId(1L) }

                service.save(
                    "testuser",
                    NutritionProfileRequest(
                        heightCm = 175.0,
                        weightKg = 70.0,
                        activityLevel = ActivityLevel.MODERATE,
                        goal = DietGoal.MAINTAIN,
                    ),
                )

                Then("목표 4개가 계산되어 함께 저장된다") {
                    verify {
                        repository.save(
                            match {
                                it.targetKcal > 0 &&
                                    it.targetCarbsG > 0 &&
                                    it.targetProteinG > 0 &&
                                    it.targetFatG > 0
                            },
                        )
                    }
                }
            }

            When("생년월일이 없는 사용자면") {
                val noBirth = dummyUser(username = "nobirth", id = 2L)
                every { userRepository.findByUsername("nobirth") } returns noBirth
                every { repository.findByUser(noBirth) } returns null

                Then("INVALID_REQUEST — BMR을 계산할 수 없다") {
                    val e =
                        shouldThrow<CustomException> {
                            service.save(
                                "nobirth",
                                NutritionProfileRequest(175.0, 70.0, ActivityLevel.MODERATE, DietGoal.MAINTAIN),
                            )
                        }
                    e.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }
        }

        Given("몸무게만 갱신") {
            When("매일 잰 몸무게를 올리면") {
                val profile = dummyProfile(user = user, weightKg = 70.0)
                every { repository.findByUser(user) } returns profile

                service.updateWeight("testuser", WeightUpdateRequest(weightKg = 68.0))

                Then("몸무게가 바뀌고 목표가 재계산된다") {
                    profile.weightKg shouldBe 68.0
                    // 나이가 LocalDate.now()에 달려 있어 정확한 값을 못 박는다(그 검증은
                    // NutritionTargetCalculatorTest가 한다). 몸무게가 줄면 목표도 줄어든다는
                    // 관계는 나이와 무관하게 성립한다.
                    profile.targetKcal shouldBeLessThan 2509
                    profile.targetCarbsG shouldBeLessThan 345
                }

                Then("키·활동량·목표는 보존된다") {
                    profile.heightCm shouldBe 175.0
                    profile.activityLevel shouldBe ActivityLevel.MODERATE
                    profile.goal shouldBe DietGoal.MAINTAIN
                }
            }

            When("프로필이 없으면") {
                every { repository.findByUser(user) } returns null

                Then("PROFILE_NOT_FOUND — 키·활동량을 모르면 목표를 못 만든다") {
                    val e = shouldThrow<CustomException> { service.updateWeight("testuser", WeightUpdateRequest(68.0)) }
                    e.errorCode shouldBe DietErrorCode.PROFILE_NOT_FOUND
                }
            }
        }

        Given("프로필 조회") {
            When("저장된 프로필이 있으면") {
                every { repository.findByUser(user) } returns dummyProfile(user = user)

                val response = service.get("testuser")

                Then("계산된 목표를 함께 반환") {
                    response.targetKcal shouldBe 2509
                    response.targetCarbsG shouldBe 345
                }
            }
        }
    })
```

> 서비스는 `LocalDate.now()`로 나이를 구하므로 이 테스트에서 목표 kcal의 정확한 값을 못 박지
> 않는다 — 해가 바뀌면 나이가 달라져 깨진다. 정확한 수치 검증은 `NutritionTargetCalculatorTest`가
> 맡고, 여기서는 **몸무게를 줄이면 목표도 준다**는 관계(나이와 무관하게 성립)만 확인한다.

- [ ] **Step 6: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*NutritionProfileServiceTest*"`
Expected: FAIL — `Unresolved reference: NutritionProfileService`

- [ ] **Step 7: 에러 코드·리포지토리·DTO·서비스 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/DietErrorCode.kt`:

```kotlin
package com.toy.backend.diet

import com.toy.backend.common.constant.Code
import org.springframework.http.HttpStatus

enum class DietErrorCode(
    private val httpStatus: HttpStatus,
    private val message: String,
) : Code {
    PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "식단 프로필이 없습니다. 프로필을 먼저 저장해 주세요."),
    PHOTO_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "끼니당 사진은 최대 %s장입니다."),
    LLM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "LLM 연동이 설정되지 않았습니다. openrouter.api-key를 설정해 주세요."),
    ANALYSIS_NOT_CONFIRMABLE(HttpStatus.BAD_REQUEST, "인식이 끝나지 않은 분석은 확정할 수 없습니다: %s"),
    ANALYSIS_NOT_RETRYABLE(HttpStatus.BAD_REQUEST, "재인식할 실패한 사진이 없습니다: %s"),
    FEEDBACK_NOT_RETRYABLE(HttpStatus.BAD_REQUEST, "실패 상태의 끼니만 피드백을 재생성할 수 있습니다: %s"),
    ;

    override fun getHttpStatus(): HttpStatus = httpStatus

    override fun getMessage(): String = message

    override fun getStatusName(): String = httpStatus.name

    override fun getCodeName(): String = name
}
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionProfileRepository.kt`:

```kotlin
package com.toy.backend.diet.profile

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository

interface NutritionProfileRepository : JpaRepository<NutritionProfile, Long> {
    fun findByUser(user: User): NutritionProfile?
}
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionProfileDtos.kt`:

```kotlin
package com.toy.backend.diet.profile

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin

data class NutritionProfileRequest(
    @field:DecimalMin("100.0") @field:DecimalMax("250.0")
    val heightCm: Double,
    @field:DecimalMin("20.0") @field:DecimalMax("300.0")
    val weightKg: Double,
    val activityLevel: ActivityLevel,
    val goal: DietGoal,
)

data class WeightUpdateRequest(
    @field:DecimalMin("20.0") @field:DecimalMax("300.0")
    val weightKg: Double,
)

data class NutritionProfileResponse(
    val heightCm: Double,
    val weightKg: Double,
    val activityLevel: ActivityLevel,
    val goal: DietGoal,
    val targetKcal: Int,
    val targetCarbsG: Int,
    val targetProteinG: Int,
    val targetFatG: Int,
)

fun NutritionProfile.toResponse(): NutritionProfileResponse =
    NutritionProfileResponse(
        heightCm = heightCm,
        weightKg = weightKg,
        activityLevel = activityLevel,
        goal = goal,
        targetKcal = targetKcal,
        targetCarbsG = targetCarbsG,
        targetProteinG = targetProteinG,
        targetFatG = targetFatG,
    )
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionProfileService.kt`:

```kotlin
package com.toy.backend.diet.profile

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class NutritionProfileService(
    private val repository: NutritionProfileRepository,
    private val userRepository: UserRepository,
) {
    fun get(username: String): NutritionProfileResponse = requireProfile(findUser(username)).toResponse()

    @Transactional
    fun save(
        username: String,
        request: NutritionProfileRequest,
    ) {
        val user = findUser(username)
        val existing = repository.findByUser(user)
        val profile =
            existing?.apply {
                updateDetails(request.heightCm, request.weightKg, request.activityLevel, request.goal)
            } ?: NutritionProfile(
                user = user,
                heightCm = request.heightCm,
                weightKg = request.weightKg,
                activityLevel = request.activityLevel,
                goal = request.goal,
            )
        profile.applyTargets(calculateTargets(user, profile))
        if (existing == null) repository.save(profile)
    }

    /** 매일 재는 몸무게만 갱신하고 목표를 다시 계산한다. 과거 점수는 `Meal` 스냅샷을 쓰므로 영향받지 않는다. */
    @Transactional
    fun updateWeight(
        username: String,
        request: WeightUpdateRequest,
    ) {
        val user = findUser(username)
        val profile = requireProfile(user)
        profile.updateWeight(request.weightKg)
        profile.applyTargets(calculateTargets(user, profile))
    }

    fun requireProfile(user: User): NutritionProfile =
        repository.findByUser(user)
            ?: throw CustomException(DietErrorCode.PROFILE_NOT_FOUND)

    private fun calculateTargets(
        user: User,
        profile: NutritionProfile,
    ): NutritionTargets {
        val gender = user.gender
        val birthDate = user.birthDate
        // 나이·성별이 없으면 Mifflin-St Jeor 식을 세울 수 없다. 추정으로 목표를 만들면 점수를 설명할 수 없다.
        if (gender == null || birthDate == null) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "생년월일과 성별을 먼저 등록해 주세요")
        }
        return NutritionTargetCalculator.calculate(
            gender = gender,
            birthDate = birthDate,
            heightCm = profile.heightCm,
            weightKg = profile.weightKg,
            activityLevel = profile.activityLevel,
            goal = profile.goal,
            today = LocalDate.now(),
        )
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
```

- [ ] **Step 8: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*NutritionProfile*"`
Expected: PASS

- [ ] **Step 9: 컨트롤러 작성**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionProfileController.kt`:

```kotlin
package com.toy.backend.diet.profile

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "식단 프로필", description = "키·몸무게·활동량·목표와 계산된 목표 섭취량")
@RestController
@RequestMapping("/diet/profile")
class NutritionProfileController(
    private val service: NutritionProfileService,
) {
    @GetMapping
    @Operation(summary = "내 프로필 + 계산된 목표 조회")
    fun get(authentication: Authentication): ResponseEntity<DataResponseBody<NutritionProfileResponse>> =
        ResponseEntity.ok(DataResponseBody(service.get(authentication.name)))

    @PutMapping
    @Operation(summary = "프로필 저장 — 서버가 목표를 재계산한다")
    fun save(
        @Valid @RequestBody request: NutritionProfileRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.save(authentication.name, request)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/weight")
    @Operation(summary = "몸무게만 갱신 — 매일 호출한다. 목표를 재계산한다")
    fun updateWeight(
        @Valid @RequestBody request: WeightUpdateRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.updateWeight(authentication.name, request)
        return ResponseEntity.noContent().build()
    }
}
```

- [ ] **Step 10: 포맷·전체 테스트·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet apps/daily-record/src/test/kotlin/com/toy/backend/diet
git commit -m "feat: 식단 영양 프로필과 목표 섭취량 계산 추가"
```

---

### Task 2: 끼니 점수 계산 (KDRIs 범위 기반)

룰 기반 순수 함수로 끼니 점수를 내고, 앱이 그대로 표시할 수 있는 `scoreBasis`를 함께 만든다. LLM에 점수를 묻지 않는 이 설계의 핵심이다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/score/DietScorePolicy.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/score/ScoreDtos.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/score/DietScoreCalculator.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/score/DietScoreCalculatorTest.kt`

**Interfaces:**
- Consumes: 없음 (순수 함수)
- Produces:
  - `object DietScorePolicy` — `STANDARD_NAME`, `DAY_STANDARD_NAME`, `CARBS_RANGE`/`PROTEIN_RANGE`/`FAT_RANGE`(`ClosedFloatingPointRange<Double>`), 감점 상수
  - `enum class MacroStatus { UNDER, IN_RANGE, OVER }`
  - `data class MacroBasis(name, percent, rangeMin, rangeMax, status, penalty)`
  - `data class MealScoreBasis(standard: String, macros: List<MacroBasis>)`
  - `data class MealScore(score: Int?, basis: MealScoreBasis?)`
  - `DietScoreCalculator.scoreMeal(carbsG: Double, proteinG: Double, fatG: Double): MealScore`

- [ ] **Step 1: 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/score/DietScoreCalculatorTest.kt`:

```kotlin
package com.toy.backend.diet.score

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class DietScoreCalculatorTest :
    BehaviorSpec({
        Given("끼니 점수 — KDRIs 범위를 벗어난 만큼만 감점") {
            When("탄 75% · 단 8% · 지 17% (설계 문서 예시)") {
                // 168.75×4 + 18×4 + 17×9 = 675 + 72 + 153 = 900kcal
                val result = DietScoreCalculator.scoreMeal(carbsG = 168.75, proteinG = 18.0, fatG = 17.0)

                Then("초과 10%p + 미달 2%p → 100 − 2.0 × 12 = 76점") {
                    result.score shouldBe 76
                }

                Then("근거에 항목별 status와 penalty가 실린다") {
                    val macros = result.basis!!.macros
                    macros[0].name shouldBe "탄수화물"
                    macros[0].percent shouldBe 75.0
                    macros[0].status shouldBe MacroStatus.OVER
                    macros[0].penalty shouldBe 20.0
                    macros[1].status shouldBe MacroStatus.UNDER
                    macros[1].penalty shouldBe 4.0
                    macros[2].status shouldBe MacroStatus.IN_RANGE
                    macros[2].penalty shouldBe 0.0
                }

                Then("근거의 감점 합이 실제 점수와 일치한다") {
                    val total = result.basis!!.macros.sumOf { it.penalty }
                    result.score shouldBe (100 - total).toInt()
                }
            }

            When("KDRIs 범위 하한 경계 — 탄 50 · 단 20 · 지 30") {
                // 112.5×4 + 45×4 + 30×9 = 450 + 180 + 270 = 900kcal
                val result = DietScoreCalculator.scoreMeal(carbsG = 112.5, proteinG = 45.0, fatG = 30.0)

                Then("경계는 범위 안이라 100점") {
                    result.score shouldBe 100
                    result.basis!!.macros.all { it.status == MacroStatus.IN_RANGE } shouldBe true
                }
            }

            When("KDRIs 범위 상한 경계 — 탄 65 · 단 10 · 지 25") {
                // 146.25×4 + 22.5×4 + 25×9 = 585 + 90 + 225 = 900kcal
                val result = DietScoreCalculator.scoreMeal(carbsG = 146.25, proteinG = 22.5, fatG = 25.0)

                Then("경계는 범위 안이라 100점") {
                    result.score shouldBe 100
                }
            }

            When("밥만 먹었을 때 — 탄 100 · 단 0 · 지 0") {
                val result = DietScoreCalculator.scoreMeal(carbsG = 100.0, proteinG = 0.0, fatG = 0.0)

                Then("감점이 100을 넘어도 0점 아래로 내려가지 않는다") {
                    result.score shouldBe 0
                }
            }

            When("물·커피처럼 매크로가 0이면") {
                val result = DietScoreCalculator.scoreMeal(carbsG = 0.0, proteinG = 0.0, fatG = 0.0)

                Then("비율을 정의할 수 없으므로 점수도 근거도 null") {
                    result.score shouldBe null
                    result.basis shouldBe null
                }
            }

            When("기준 문구를 확인하면") {
                val result = DietScoreCalculator.scoreMeal(carbsG = 100.0, proteinG = 30.0, fatG = 20.0)

                Then("응답에 국가 기준 이름이 실린다") {
                    result.basis!!.standard shouldBe "2025 한국인 영양소 섭취기준(KDRIs) 에너지적정비율"
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*DietScoreCalculatorTest*"`
Expected: FAIL — `Unresolved reference: DietScoreCalculator`

- [ ] **Step 3: 정책 상수 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/score/DietScorePolicy.kt`:

```kotlin
package com.toy.backend.diet.score

/**
 * 점수 기준 상수. **어느 값이 국가 기준이고 어느 값이 우리가 정한 것인지 반드시 구분해 둔다** —
 * 사용자에게 점수 근거를 보여주는 기능이 있어서, 이 구분이 흐려지면 근거를 설명할 수 없게 된다.
 */
object DietScorePolicy {
    // ── 국가 기준: 2025 한국인 영양소 섭취기준(KDRIs) 에너지적정비율 ──
    // 기준이 개정되면(5년 주기) 아래 문구와 범위를 함께 바꾼다.
    const val STANDARD_NAME = "2025 한국인 영양소 섭취기준(KDRIs) 에너지적정비율"
    val CARBS_RANGE = 50.0..65.0
    val PROTEIN_RANGE = 10.0..20.0
    val FAT_RANGE = 15.0..30.0

    // ── 자체 설정값: 공개 근거 없음. 초기 추정치이며 튜닝 대상이다 ──
    const val DAY_STANDARD_NAME = "개인 목표 대비 총량 (자체 기준)"

    /** 끼니 점수 — 범위를 1%p 벗어날 때마다 깎는 점수 */
    const val MEAL_PENALTY_PER_PERCENT = 2.0

    /** 하루 칼로리 — 목표 대비 이 구간 안이면 만점 */
    const val CALORIE_TOLERANCE_LOW = 0.9
    const val CALORIE_TOLERANCE_HIGH = 1.1

    /** 하루 점수 — 허용 구간을 벗어난 비율 1.0당 깎는 점수 */
    const val PENALTY_SLOPE = 200.0

    /** 하루 매크로 — 목표의 이 배까지는 초과를 감점하지 않는다 */
    const val MACRO_OVER_TOLERANCE = 1.1

    /** 하루 점수 가중치 — 총량보다 구성이 조금 더 중요하다는 판단일 뿐이다 */
    const val CALORIE_WEIGHT = 0.4
    const val MACRO_WEIGHT = 0.6

    // ── 물리 상수 ──
    const val KCAL_PER_G_CARBS = 4.0
    const val KCAL_PER_G_PROTEIN = 4.0
    const val KCAL_PER_G_FAT = 9.0

    const val CARBS_LABEL = "탄수화물"
    const val PROTEIN_LABEL = "단백질"
    const val FAT_LABEL = "지방"
}
```

- [ ] **Step 4: 점수 DTO 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/score/ScoreDtos.kt`:

```kotlin
package com.toy.backend.diet.score

enum class MacroStatus { UNDER, IN_RANGE, OVER }

/**
 * status와 penalty는 **서버가 계산해 내려준다.** 앱이 percent와 범위만 받아 판정하면 감점 규칙이
 * 두 곳에 생기고, 서버가 기울기를 튜닝했을 때 앱 표시와 실제 점수가 어긋난다.
 */
data class MacroBasis(
    val name: String,
    val percent: Double,
    val rangeMin: Int,
    val rangeMax: Int,
    val status: MacroStatus,
    val penalty: Double,
)

data class MealScoreBasis(
    val standard: String,
    val macros: List<MacroBasis>,
)

data class MealScore(
    val score: Int?,
    val basis: MealScoreBasis?,
)
```

- [ ] **Step 5: 끼니 점수 계산기 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/score/DietScoreCalculator.kt`:

```kotlin
package com.toy.backend.diet.score

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 점수는 전부 여기서만 계산한다. LLM에게 점수를 묻지 않는 이유가 결정성과 테스트 가능성이므로,
 * 계산이 여러 곳으로 흩어지면 그 이점이 사라진다.
 */
object DietScoreCalculator {
    /**
     * 끼니 점수 — 개인 목표가 아니라 **KDRIs 범위**만 본다. 개인 목표를 점으로 놓고 편차를 감점하면
     * 권장 범위 한가운데인 식사도 감점되어 사용자에게 설명할 수 없다. 칼로리는 넣지 않는다 —
     * 아침을 가볍게 먹은 것을 감점하면 안 되고, 총량은 하루 단위에서만 평가한다.
     */
    fun scoreMeal(
        carbsG: Double,
        proteinG: Double,
        fatG: Double,
    ): MealScore {
        // 비율의 분모는 Meal.totalKcal이 아니라 매크로에서 역산한 값이다 — 식품DB의 kcal은
        // 탄단지 합산과 정확히 일치하지 않아(알코올·식이섬유·측정 오차) 세 비율의 합이 100%가 되지 않는다.
        val macroKcal =
            carbsG * DietScorePolicy.KCAL_PER_G_CARBS +
                proteinG * DietScorePolicy.KCAL_PER_G_PROTEIN +
                fatG * DietScorePolicy.KCAL_PER_G_FAT
        if (macroKcal <= 0.0) return MealScore(score = null, basis = null)

        val macros =
            listOf(
                basisOf(
                    DietScorePolicy.CARBS_LABEL,
                    carbsG * DietScorePolicy.KCAL_PER_G_CARBS / macroKcal * 100,
                    DietScorePolicy.CARBS_RANGE,
                ),
                basisOf(
                    DietScorePolicy.PROTEIN_LABEL,
                    proteinG * DietScorePolicy.KCAL_PER_G_PROTEIN / macroKcal * 100,
                    DietScorePolicy.PROTEIN_RANGE,
                ),
                basisOf(
                    DietScorePolicy.FAT_LABEL,
                    fatG * DietScorePolicy.KCAL_PER_G_FAT / macroKcal * 100,
                    DietScorePolicy.FAT_RANGE,
                ),
            )

        val score = max(0.0, 100.0 - macros.sumOf { it.penalty }).roundToInt()
        return MealScore(score = score, basis = MealScoreBasis(DietScorePolicy.STANDARD_NAME, macros))
    }

    private fun basisOf(
        name: String,
        rawPercent: Double,
        range: ClosedFloatingPointRange<Double>,
    ): MacroBasis {
        // 표시값과 감점 근거가 어긋나지 않도록 소수 첫째 자리로 맞춘 값을 그대로 쓴다.
        val percent = (rawPercent * 10).roundToInt() / 10.0
        val excess = max(0.0, max(range.start - percent, percent - range.endInclusive))
        val status =
            when {
                percent < range.start -> MacroStatus.UNDER
                percent > range.endInclusive -> MacroStatus.OVER
                else -> MacroStatus.IN_RANGE
            }
        return MacroBasis(
            name = name,
            percent = percent,
            rangeMin = range.start.toInt(),
            rangeMax = range.endInclusive.toInt(),
            status = status,
            penalty = excess * DietScorePolicy.MEAL_PENALTY_PER_PERCENT,
        )
    }
}
```

- [ ] **Step 6: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*DietScoreCalculatorTest*"`
Expected: PASS (6 When)

- [ ] **Step 7: 포맷·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/score apps/daily-record/src/test/kotlin/com/toy/backend/diet/score
git commit -m "feat: KDRIs 범위 기반 끼니 점수 계산 추가"
```

---

### Task 3: 하루 점수 계산 (칼로리 40% + 매크로 60%)

끼니가 「균형」이라면 하루는 「목표 대비 총량」이다. 목표는 그날 첫 `Meal`의 스냅샷에서 오지만, 계산기 자체는 목표를 인자로 받는 순수 함수라 그 출처를 모른다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/score/ScoreDtos.kt` (하루 근거 DTO 추가)
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/score/DietScoreCalculator.kt` (`scoreDay` 추가)
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/score/DietDayScoreCalculatorTest.kt`

**Interfaces:**
- Consumes: `NutritionTargets`(Task 1), `DietScorePolicy`·`MealScore` 계열(Task 2)
- Produces:
  - `data class CalorieBasis(intakeKcal: Double, targetKcal: Int, ratio: Double, calorieScore: Int)`
  - `data class MacroAmountBasis(name: String, intakeG: Double, targetG: Int, ratio: Double, score: Int)`
  - `data class DayScoreBasis(standard, calorie, macros, calorieWeight, macroWeight)`
  - `data class DayScore(score: Int, basis: DayScoreBasis)`
  - `DietScoreCalculator.scoreDay(intakeKcal, carbsG, proteinG, fatG, targets): DayScore`

- [ ] **Step 1: 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/score/DietDayScoreCalculatorTest.kt`:

```kotlin
package com.toy.backend.diet.score

import com.toy.backend.diet.profile.NutritionTargets
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class DietDayScoreCalculatorTest :
    BehaviorSpec({
        val targets = NutritionTargets(kcal = 2000, carbsG = 275, proteinG = 75, fatG = 67)

        Given("하루 점수") {
            When("칼로리·매크로가 목표와 같으면") {
                val result =
                    DietScoreCalculator.scoreDay(
                        intakeKcal = 2000.0,
                        carbsG = 275.0,
                        proteinG = 75.0,
                        fatG = 67.0,
                        targets = targets,
                    )

                Then("100점") {
                    result.score shouldBe 100
                    result.basis.calorie.calorieScore shouldBe 100
                }
            }

            When("칼로리가 목표의 90%면") {
                val result = DietScoreCalculator.scoreDay(1800.0, 275.0, 75.0, 67.0, targets)

                Then("허용 구간 경계라 칼로리 만점") {
                    result.basis.calorie.calorieScore shouldBe 100
                }
            }

            When("칼로리가 목표의 110%면") {
                val result = DietScoreCalculator.scoreDay(2200.0, 275.0, 75.0, 67.0, targets)

                Then("허용 구간 경계라 칼로리 만점") {
                    result.basis.calorie.calorieScore shouldBe 100
                }
            }

            When("칼로리가 목표의 85%면") {
                val result = DietScoreCalculator.scoreDay(1700.0, 275.0, 75.0, 67.0, targets)

                Then("100 − 200 × 0.05 = 90점") {
                    result.basis.calorie.calorieScore shouldBe 90
                }
            }

            When("단백질을 목표의 2배 먹으면") {
                val result = DietScoreCalculator.scoreDay(2000.0, 275.0, 150.0, 67.0, targets)

                Then("단백질 초과는 감점하지 않는다 — 고기를 충분히 먹고 점수가 깎이면 안 된다") {
                    result.basis.macros.first { it.name == "단백질" }.score shouldBe 100
                    result.score shouldBe 100
                }
            }

            When("지방을 목표의 2배 넘게 먹으면") {
                val result = DietScoreCalculator.scoreDay(2000.0, 275.0, 75.0, 150.0, targets)

                Then("지방은 초과를 감점한다 — 매크로 평균 (100+100+0)/3, 하루 80점") {
                    result.basis.macros.first { it.name == "지방" }.score shouldBe 0
                    result.score shouldBe 80
                }
            }

            When("탄수화물이 목표의 절반이면") {
                val result = DietScoreCalculator.scoreDay(2000.0, 137.5, 75.0, 67.0, targets)

                Then("미달은 비례 점수 — 50점") {
                    result.basis.macros.first { it.name == "탄수화물" }.score shouldBe 50
                }
            }

            When("근거를 확인하면") {
                val result = DietScoreCalculator.scoreDay(2000.0, 275.0, 75.0, 67.0, targets)

                Then("자체 기준임을 밝히고 가중치를 함께 싣는다") {
                    result.basis.standard shouldBe "개인 목표 대비 총량 (자체 기준)"
                    result.basis.calorieWeight shouldBe 0.4
                    result.basis.macroWeight shouldBe 0.6
                    result.basis.calorie.targetKcal shouldBe 2000
                    result.basis.macros.size shouldBe 3
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*DietDayScoreCalculatorTest*"`
Expected: FAIL — `Unresolved reference: scoreDay`

- [ ] **Step 3: 하루 근거 DTO 추가**

`ScoreDtos.kt` 끝에 덧붙인다:

```kotlin
data class CalorieBasis(
    val intakeKcal: Double,
    val targetKcal: Int,
    val ratio: Double,
    val calorieScore: Int,
)

data class MacroAmountBasis(
    val name: String,
    val intakeG: Double,
    val targetG: Int,
    val ratio: Double,
    val score: Int,
)

/**
 * 하루 점수 근거. 끼니 근거와 달리 `standard`가 자체 기준이라고 밝힌다 —
 * 가중치와 기울기는 우리가 정한 값이라 국가 기준과 같은 무게로 제시하면 안 된다.
 */
data class DayScoreBasis(
    val standard: String,
    val calorie: CalorieBasis,
    val macros: List<MacroAmountBasis>,
    val calorieWeight: Double,
    val macroWeight: Double,
)

data class DayScore(
    val score: Int,
    val basis: DayScoreBasis,
)
```

- [ ] **Step 4: `scoreDay` 구현**

`DietScoreCalculator.kt`의 `object` 안, `scoreMeal` 아래에 덧붙인다:

```kotlin
    /**
     * 하루 점수 — 칼로리(총량) 40% + 매크로(구성) 60%. 목표는 호출자가 넘긴다.
     * 호출자(`DailyDietService`)는 **현재 프로필이 아니라 그날 첫 `Meal`의 스냅샷**을 넘겨야
     * 몸무게를 갱신했을 때 과거 점수가 흔들리지 않는다.
     *
     * 각 요소 점수를 먼저 Int로 반올림한 뒤 가중 합산한다 — 앱이 근거에 실린 숫자만으로
     * 최종 점수를 그대로 재현할 수 있어야 하기 때문이다.
     */
    fun scoreDay(
        intakeKcal: Double,
        carbsG: Double,
        proteinG: Double,
        fatG: Double,
        targets: NutritionTargets,
    ): DayScore {
        val calorieRatio = ratioOf(intakeKcal, targets.kcal)
        val calorieScore =
            when {
                calorieRatio < DietScorePolicy.CALORIE_TOLERANCE_LOW ->
                    penalized(DietScorePolicy.CALORIE_TOLERANCE_LOW - calorieRatio)
                calorieRatio > DietScorePolicy.CALORIE_TOLERANCE_HIGH ->
                    penalized(calorieRatio - DietScorePolicy.CALORIE_TOLERANCE_HIGH)
                else -> 100
            }

        val macros =
            listOf(
                macroBasis(DietScorePolicy.CARBS_LABEL, carbsG, targets.carbsG, penalizeOver = true),
                // 단백질만 초과를 감점하지 않는다 — 단백질 과다는 실질적 문제가 아니어서,
                // 감점하면 "고기를 충분히 먹었더니 점수가 깎이는" 잘못된 신호를 준다.
                macroBasis(DietScorePolicy.PROTEIN_LABEL, proteinG, targets.proteinG, penalizeOver = false),
                macroBasis(DietScorePolicy.FAT_LABEL, fatG, targets.fatG, penalizeOver = true),
            )

        val macroScore = macros.sumOf { it.score }.toDouble() / macros.size
        val dayScore =
            (DietScorePolicy.CALORIE_WEIGHT * calorieScore + DietScorePolicy.MACRO_WEIGHT * macroScore).roundToInt()

        return DayScore(
            score = dayScore,
            basis =
                DayScoreBasis(
                    standard = DietScorePolicy.DAY_STANDARD_NAME,
                    calorie =
                        CalorieBasis(
                            intakeKcal = intakeKcal,
                            targetKcal = targets.kcal,
                            ratio = calorieRatio,
                            calorieScore = calorieScore,
                        ),
                    macros = macros,
                    calorieWeight = DietScorePolicy.CALORIE_WEIGHT,
                    macroWeight = DietScorePolicy.MACRO_WEIGHT,
                ),
        )
    }

    private fun macroBasis(
        name: String,
        intakeG: Double,
        targetG: Int,
        penalizeOver: Boolean,
    ): MacroAmountBasis {
        val ratio = ratioOf(intakeG, targetG)
        val score =
            when {
                ratio < 1.0 -> (100 * ratio).roundToInt()
                penalizeOver && ratio > DietScorePolicy.MACRO_OVER_TOLERANCE ->
                    penalized(ratio - DietScorePolicy.MACRO_OVER_TOLERANCE)
                else -> 100
            }
        return MacroAmountBasis(name = name, intakeG = intakeG, targetG = targetG, ratio = ratio, score = score)
    }

    private fun penalized(excessRatio: Double): Int = max(0.0, 100.0 - DietScorePolicy.PENALTY_SLOPE * excessRatio).roundToInt()

    /** 목표는 프로필 계산 결과라 항상 양수지만, 0으로 나누는 사고는 막아 둔다. */
    private fun ratioOf(
        intake: Double,
        target: Int,
    ): Double = intake / max(1, target).toDouble()
```

`import com.toy.backend.diet.profile.NutritionTargets`를 파일 상단에 추가한다.

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*DietDayScoreCalculatorTest*"`
Expected: PASS (8 When)

- [ ] **Step 6: 포맷·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/score apps/daily-record/src/test/kotlin/com/toy/backend/diet/score
git commit -m "feat: 목표 대비 하루 점수 계산 추가"
```

---

### Task 4: 식품DB 엔티티와 이름 매칭

LLM이 준 음식명을 식품DB에 붙여 영양소를 결정적으로 구한다. iOS 항목 수정 화면용 검색 API도 같은 정규화 규칙을 쓴다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/Food.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodNameNormalizer.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodRepository.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodMatcher.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodDtos.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodController.kt`
- Modify: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/DietFixtures.kt` (`dummyFood()` 추가)
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/food/FoodMatcherTest.kt`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `class Food(code, name, normalizedName, servingSizeG, kcalPer100g, carbsPer100g, proteinPer100g, fatPer100g)`
  - `data class NutritionAmount(quantityG, kcal, carbsG, proteinG, fatG)` / `Food.nutritionFor(portion: Double): NutritionAmount`
  - `object FoodPolicy { const val DEFAULT_SERVING_SIZE_G = 200.0 }`
  - `object FoodNameNormalizer { fun normalize(String): String }`
  - `FoodRepository.findFirstByNormalizedName(String): Food?`, `.searchByNormalizedName(String, Pageable): List<Food>`
  - `FoodMatcher.match(foodName: String): Food?`, `.search(keyword: String, size: Int): List<Food>`

- [ ] **Step 1: 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/food/FoodMatcherTest.kt`:

```kotlin
package com.toy.backend.diet.food

import com.toy.backend.diet.dummyFood
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.domain.Pageable

class FoodMatcherTest :
    BehaviorSpec({
        val repository = mockk<FoodRepository>()
        val matcher = FoodMatcher(repository)

        Given("이름 정규화") {
            When("공백·괄호·특수문자가 섞인 이름이면") {
                Then("모두 제거하고 소문자로 만든다") {
                    FoodNameNormalizer.normalize("제육볶음 (급식용)") shouldBe "제육볶음급식용"
                    FoodNameNormalizer.normalize("Chicken-Breast") shouldBe "chickenbreast"
                }
            }
        }

        Given("음식명 매칭") {
            When("정규화된 이름이 완전일치하면") {
                val food = dummyFood(name = "제육볶음", normalizedName = "제육볶음")
                every { repository.findFirstByNormalizedName("제육볶음") } returns food

                val matched = matcher.match("제육 볶음")

                Then("그 항목을 쓰고 유사도 검색은 하지 않는다") {
                    matched shouldBe food
                    verify(exactly = 0) { repository.searchByNormalizedName(any(), any()) }
                }
            }

            When("완전일치가 없으면") {
                val shortest = dummyFood(code = "D001", name = "제육볶음", normalizedName = "제육볶음", id = 2L)
                every { repository.findFirstByNormalizedName("제육볶음") } returns null
                every { repository.searchByNormalizedName("제육볶음", any<Pageable>()) } returns listOf(shortest)

                val matched = matcher.match("제육볶음")

                Then("부분일치 후보 중 이름이 가장 짧은 것을 고른다") {
                    // 정렬은 쿼리(length asc)가 책임지므로 첫 건을 그대로 쓴다
                    matched shouldBe shortest
                }
            }

            When("후보가 아예 없으면") {
                every { repository.findFirstByNormalizedName("없는음식") } returns null
                every { repository.searchByNormalizedName("없는음식", any<Pageable>()) } returns emptyList()

                Then("null — 호출자가 LLM 추정값으로 fallback 한다") {
                    matcher.match("없는 음식") shouldBe null
                }
            }

            When("정규화하면 빈 문자열이 되는 이름이면") {
                Then("조회하지 않고 null") {
                    matcher.match("!!!") shouldBe null
                    verify(exactly = 0) { repository.findFirstByNormalizedName("") }
                }
            }
        }

        Given("1인분 배수로 영양소 산출") {
            When("1인분 300g · 100g당 150kcal인 음식을 0.5인분 먹으면") {
                val food = dummyFood(servingSizeG = 300.0, kcalPer100g = 150.0, carbsPer100g = 20.0, proteinPer100g = 10.0, fatPer100g = 5.0)

                val amount = food.nutritionFor(portion = 0.5)

                Then("150g 기준으로 환산된다") {
                    amount.quantityG shouldBe 150.0
                    amount.kcal shouldBe 225.0
                    amount.carbsG shouldBe 30.0
                    amount.proteinG shouldBe 15.0
                    amount.fatG shouldBe 7.5
                }
            }
        }
    })
```

`DietFixtures.kt`에 추가:

```kotlin
fun dummyFood(
    code: String = "D000",
    name: String = "제육볶음",
    normalizedName: String = FoodNameNormalizer.normalize(name),
    servingSizeG: Double = 200.0,
    kcalPer100g: Double = 180.0,
    carbsPer100g: Double = 12.0,
    proteinPer100g: Double = 15.0,
    fatPer100g: Double = 8.0,
    id: Long = 1L,
): Food =
    Food(
        code = code,
        name = name,
        normalizedName = normalizedName,
        servingSizeG = servingSizeG,
        kcalPer100g = kcalPer100g,
        carbsPer100g = carbsPer100g,
        proteinPer100g = proteinPer100g,
        fatPer100g = fatPer100g,
    ).withId(id)
```

(import `com.toy.backend.diet.food.Food`·`FoodNameNormalizer` 추가)

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*FoodMatcherTest*"`
Expected: FAIL — `Unresolved reference: FoodMatcher`

- [ ] **Step 3: 엔티티·정규화·리포지토리 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/Food.kt`:

```kotlin
package com.toy.backend.diet.food

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

object FoodPolicy {
    /**
     * `1인(회)분량 참고량`이 비어 있는 행과, 식품DB에 없어 LLM 추정으로 넘어간 음식의 기본 1인분.
     * **실제 CSV의 결측률을 확인한 뒤 조정해야 하는 초기 추정치다.**
     */
    const val DEFAULT_SERVING_SIZE_G = 200.0
}

/** 식약처 `전국통합식품영양성분정보(음식)` 표준데이터를 100g 기준으로 정규화해 적재한 표. */
@Entity
@Table(
    name = "foods",
    indexes = [
        Index(name = "idx_foods_normalized_name", columnList = "normalized_name"),
    ],
)
class Food(
    @Column(nullable = false, length = 30, unique = true)
    var code: String,
    @Column(nullable = false, length = 200)
    var name: String,
    @Column(name = "normalized_name", nullable = false, length = 200)
    var normalizedName: String,
    @Column(name = "serving_size_g", nullable = false)
    var servingSizeG: Double,
    @Column(name = "kcal_per_100g", nullable = false)
    var kcalPer100g: Double,
    @Column(name = "carbs_per_100g", nullable = false)
    var carbsPer100g: Double,
    @Column(name = "protein_per_100g", nullable = false)
    var proteinPer100g: Double,
    @Column(name = "fat_per_100g", nullable = false)
    var fatPer100g: Double,
) : BaseEntity()

data class NutritionAmount(
    val quantityG: Double,
    val kcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
)

/** LLM이 주는 `portion`은 1인분 대비 배수다(0.5 = 반 인분). 이를 g으로 바꿔 100g당 값에 곱한다. */
fun Food.nutritionFor(portion: Double): NutritionAmount {
    val quantityG = servingSizeG * portion
    val ratio = quantityG / 100.0
    return NutritionAmount(
        quantityG = quantityG,
        kcal = kcalPer100g * ratio,
        carbsG = carbsPer100g * ratio,
        proteinG = proteinPer100g * ratio,
        fatG = fatPer100g * ratio,
    )
}
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodNameNormalizer.kt`:

```kotlin
package com.toy.backend.diet.food

/**
 * 적재 시점과 조회 시점이 같은 규칙을 써야 매칭이 성립한다 — 규칙을 바꾸면
 * `foods` 테이블을 비우고 다시 적재해야 한다(`FoodSeeder`는 비어 있을 때만 돈다).
 */
object FoodNameNormalizer {
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]")

    fun normalize(name: String): String = NON_ALPHANUMERIC.replace(name, "").lowercase()
}
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodRepository.kt`:

```kotlin
package com.toy.backend.diet.food

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FoodRepository : JpaRepository<Food, Long> {
    fun findFirstByNormalizedName(normalizedName: String): Food?

    /**
     * 부분일치 후보를 **이름이 짧은 순**으로 준다. "제육볶음"으로 검색하면 "제육볶음(급식용)"·
     * "제육볶음덮밥"이 같이 걸리는데, 짧은 쪽이 더 일반적인 항목이라 실제로 먹은 것에 가깝다.
     *
     * pg_trgm 같은 확장은 쓰지 않는다 — 후보가 수만 건이고 조회는 하루 수십 건이라 LIKE 스캔으로 충분하다.
     */
    @Query(
        """
        select f from Food f
        where f.normalizedName like concat('%', :normalized, '%')
        order by length(f.normalizedName) asc, f.id asc
        """,
    )
    fun searchByNormalizedName(
        @Param("normalized") normalized: String,
        pageable: Pageable,
    ): List<Food>
}
```

- [ ] **Step 4: 매처 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodMatcher.kt`:

```kotlin
package com.toy.backend.diet.food

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(readOnly = true)
class FoodMatcher(
    private val repository: FoodRepository,
) {
    /** 1) 완전일치 → 2) 부분일치 중 가장 짧은 이름 → 3) null(호출자가 LLM 추정값으로 fallback). */
    fun match(foodName: String): Food? {
        val normalized = FoodNameNormalizer.normalize(foodName)
        if (normalized.isBlank()) return null
        return repository.findFirstByNormalizedName(normalized)
            ?: repository.searchByNormalizedName(normalized, PageRequest.of(0, 1)).firstOrNull()
    }

    /** iOS 항목 수정 화면용 — 자동 선택 없이 후보 목록을 그대로 준다. */
    fun search(
        keyword: String,
        size: Int,
    ): List<Food> {
        val normalized = FoodNameNormalizer.normalize(keyword)
        if (normalized.isBlank()) return emptyList()
        return repository.searchByNormalizedName(normalized, PageRequest.of(0, size.coerceIn(1, MAX_SEARCH_SIZE)))
    }

    companion object {
        private const val MAX_SEARCH_SIZE = 50
    }
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*FoodMatcherTest*"`
Expected: PASS (4 Given)

- [ ] **Step 6: 검색 API 작성**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodDtos.kt`:

```kotlin
package com.toy.backend.diet.food

data class FoodResponse(
    val code: String,
    val name: String,
    val servingSizeG: Double,
    val kcalPer100g: Double,
    val carbsPer100g: Double,
    val proteinPer100g: Double,
    val fatPer100g: Double,
)

fun Food.toResponse(): FoodResponse =
    FoodResponse(
        code = code,
        name = name,
        servingSizeG = servingSizeG,
        kcalPer100g = kcalPer100g,
        carbsPer100g = carbsPer100g,
        proteinPer100g = proteinPer100g,
        fatPer100g = fatPer100g,
    )
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodController.kt`:

```kotlin
package com.toy.backend.diet.food

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "식품DB", description = "식품 영양성분 검색 (항목 수정 화면용)")
@RestController
@RequestMapping("/diet/foods")
class FoodController(
    private val matcher: FoodMatcher,
) {
    @GetMapping
    @Operation(summary = "식품 검색 — 이름 부분일치, 짧은 이름 우선")
    fun search(
        @Parameter(description = "검색어", example = "제육")
        @RequestParam q: String,
        @Parameter(description = "최대 건수 (1~50)", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<DataResponseBody<List<FoodResponse>>> =
        ResponseEntity.ok(DataResponseBody(matcher.search(q, size).map { it.toResponse() }))
}
```

- [ ] **Step 7: 포맷·전체 테스트·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/food apps/daily-record/src/test/kotlin/com/toy/backend/diet
git commit -m "feat: 식품DB 엔티티와 음식명 매칭 추가"
```

---

### Task 5: 식품DB 적재 (정제 스크립트 + 시더)

식약처 `전국통합식품영양성분정보(음식)` 표준데이터를 정제해 저장소에 커밋하고, 기동 시 1회 적재한다. 외부 공공 API를 요청마다 호출하지 않는 이유는 지연·장애·트래픽 제한을 그대로 떠안기 때문이다.

**Files:**
- Create: `scripts/build-food-csv.py`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodCsvParser.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodSeeder.kt`
- Create: `apps/daily-record/src/main/resources/food/food-nutrition.csv` (스크립트 산출물, 커밋 대상)
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/food/FoodCsvParserTest.kt`

**Interfaces:**
- Consumes: `Food`·`FoodPolicy`·`FoodNameNormalizer`·`FoodRepository`(Task 4)
- Produces:
  - `object FoodCsvParser { fun parse(lines: Sequence<String>): List<Food> }`
  - `class FoodSeeder(repository) : ApplicationRunner`
  - CSV 컬럼 순서: `code,servingSizeG,kcalPer100g,carbsPer100g,proteinPer100g,fatPer100g,name`

- [ ] **Step 1: 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/food/FoodCsvParserTest.kt`:

```kotlin
package com.toy.backend.diet.food

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodCsvParserTest :
    BehaviorSpec({
        val header = "code,servingSizeG,kcalPer100g,carbsPer100g,proteinPer100g,fatPer100g,name"

        Given("정상 행") {
            When("한 줄을 파싱하면") {
                val foods = FoodCsvParser.parse(sequenceOf(header, "D000001,300,180.5,12.3,15.1,8.2,제육볶음"))

                Then("Food로 변환되고 이름이 정규화된다") {
                    foods.size shouldBe 1
                    foods[0].code shouldBe "D000001"
                    foods[0].name shouldBe "제육볶음"
                    foods[0].normalizedName shouldBe "제육볶음"
                    foods[0].servingSizeG shouldBe 300.0
                    foods[0].kcalPer100g shouldBe 180.5
                }
            }
        }

        Given("이름에 쉼표가 든 행") {
            When("파싱하면") {
                val foods = FoodCsvParser.parse(sequenceOf(header, "D000002,200,100,10,5,2,밥, 국"))

                Then("이름 컬럼이 마지막이라 쉼표가 그대로 살아난다") {
                    foods[0].name shouldBe "밥, 국"
                }
            }
        }

        Given("1인분 기준량이 비어 있는 행") {
            When("파싱하면") {
                val foods = FoodCsvParser.parse(sequenceOf(header, "D000003,,150,20,10,3,김치찌개"))

                Then("기본값 200g으로 채운다 — 없다고 버리면 매칭 자체가 안 된다") {
                    foods[0].servingSizeG shouldBe 200.0
                }
            }
        }

        Given("망가진 행") {
            When("컬럼 수가 모자라거나 숫자가 아니면") {
                val foods =
                    FoodCsvParser.parse(
                        sequenceOf(
                            header,
                            "D000004,200,150",
                            "D000005,200,없음,20,10,3,된장찌개",
                            "",
                            "D000006,200,150,20,10,3,비빔밥",
                        ),
                    )

                Then("그 행만 버리고 나머지는 살린다") {
                    foods.size shouldBe 1
                    foods[0].code shouldBe "D000006"
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*FoodCsvParserTest*"`
Expected: FAIL — `Unresolved reference: FoodCsvParser`

- [ ] **Step 3: 파서 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodCsvParser.kt`:

```kotlin
package com.toy.backend.diet.food

import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * `scripts/build-food-csv.py`가 만든 정제본을 읽는다.
 *
 * **이름 컬럼을 마지막에 둔 이유** — 음식명에는 쉼표가 들어갈 수 있다. 이름이 마지막이면
 * `split(',', limit = 7)`의 7번째 조각이 나머지 전부라 인용부호 처리 없이 안전하다.
 */
object FoodCsvParser {
    private const val COLUMN_COUNT = 7

    fun parse(lines: Sequence<String>): List<Food> {
        var dropped = 0
        val foods =
            lines
                .drop(1) // 헤더
                .mapNotNull { line ->
                    val food = parseLine(line)
                    if (food == null && line.isNotBlank()) dropped++
                    food
                }.toList()
        if (dropped > 0) log.warn { "식품 CSV에서 파싱할 수 없는 행 ${dropped}건을 건너뛴다" }
        return foods
    }

    private fun parseLine(line: String): Food? {
        if (line.isBlank()) return null
        val columns = line.split(',', limit = COLUMN_COUNT)
        if (columns.size < COLUMN_COUNT) return null

        val code = columns[0].trim().takeIf { it.isNotBlank() } ?: return null
        val name = columns[6].trim().takeIf { it.isNotBlank() } ?: return null
        // 기준량이 비면 기본값으로 채운다. 영양소 값이 없는 행은 틀린 값을 넣느니 버리고
        // LLM 추정에 맡긴다.
        val servingSizeG = columns[1].trim().toDoubleOrNull() ?: FoodPolicy.DEFAULT_SERVING_SIZE_G
        val kcal = columns[2].trim().toDoubleOrNull() ?: return null
        val carbs = columns[3].trim().toDoubleOrNull() ?: return null
        val protein = columns[4].trim().toDoubleOrNull() ?: return null
        val fat = columns[5].trim().toDoubleOrNull() ?: return null

        return Food(
            code = code,
            name = name,
            normalizedName = FoodNameNormalizer.normalize(name),
            servingSizeG = if (servingSizeG > 0) servingSizeG else FoodPolicy.DEFAULT_SERVING_SIZE_G,
            kcalPer100g = kcal,
            carbsPer100g = carbs,
            proteinPer100g = protein,
            fatPer100g = fat,
        )
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*FoodCsvParserTest*"`
Expected: PASS (4 Given)

- [ ] **Step 5: 시더 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodSeeder.kt`:

```kotlin
package com.toy.backend.diet.food

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

/**
 * `foods`가 비어 있을 때만 CSV를 적재한다. 이름 정규화 규칙을 바꿨다면 테이블을 비워야 다시 돈다.
 * 배치로 나눠 넣는 이유는 라즈베리파이 메모리다 — 수만 건을 한 번에 flush 하면 힙이 튄다.
 */
@Component
class FoodSeeder(
    private val repository: FoodRepository,
) : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        if (repository.count() > 0) return

        val resource = ClassPathResource(CSV_PATH)
        if (!resource.exists()) {
            // 데이터셋을 아직 받지 않았어도 앱은 떠야 한다 — 매칭이 전부 실패해 LLM 추정으로 넘어갈 뿐이다.
            log.warn { "식품DB CSV가 없어 적재를 건너뛴다: $CSV_PATH" }
            return
        }

        var total = 0
        resource.inputStream.bufferedReader().use { reader ->
            FoodCsvParser.parse(reader.lineSequence()).chunked(BATCH_SIZE).forEach { chunk ->
                repository.saveAll(chunk)
                repository.flush()
                total += chunk.size
            }
        }
        log.info { "식품DB 적재 완료: ${total}건" }
    }

    companion object {
        private const val CSV_PATH = "food/food-nutrition.csv"
        private const val BATCH_SIZE = 500
    }
}
```

> `FoodCsvParser.parse`는 `lineSequence()`를 소비하므로 `use` 블록 안에서 끝까지 읽어야 한다.
> `chunked`가 시퀀스를 즉시 소비하는 위치라 스트림이 닫히기 전에 처리가 끝난다.

- [ ] **Step 6: 정제 스크립트 작성**

`scripts/build-food-csv.py`:

```python
#!/usr/bin/env python3
"""식약처 `전국통합식품영양성분정보(음식)` 원본 CSV를 적재용 CSV로 정제한다.

준비 단계에서 1회만 돌린다(런타임 호출이 아니다).

  1. https://www.data.go.kr/data/15100070/standard.do 에서 CSV를 내려받는다.
     그리드 다운로드는 5만 건 제한이 있어 전량이 안 나오면 같은 페이지의 오픈API로 페이징해 덤프한다.
  2. python3 scripts/build-food-csv.py 원본.csv \
       apps/daily-record/src/main/resources/food/food-nutrition.csv

`영양성분함량 기준량` 컬럼이 따로 있다는 것은 값이 항상 100g 기준이 아니라는 뜻이다.
기준량이 200g인 행을 100g당으로 착각하면 그 음식만 칼로리가 2배로 잡힌다. 여기서 전부 100g
기준으로 환산하고, 기준량을 파싱할 수 없는 행은 버린다 — 틀린 값을 넣느니 매칭 실패로
LLM 추정에 맡기는 편이 낫다.
"""

import csv
import re
import sys

OUT_HEADER = [
    "code", "servingSizeG", "kcalPer100g",
    "carbsPer100g", "proteinPer100g", "fatPer100g", "name",
]

# 원본 헤더는 공백·괄호 표기가 판번마다 조금씩 달라 부분 문자열로 찾는다.
COLUMN_HINTS = {
    "code": ["식품코드"],
    "name": ["식품명"],
    "basis": ["영양성분함량기준량", "기준량"],
    "kcal": ["에너지(kcal)", "에너지"],
    "carbs": ["탄수화물"],
    "protein": ["단백질"],
    "fat": ["지방"],
    "serving": ["1인(회)분량참고량", "1회분량", "분량참고량"],
}

AMOUNT_PATTERN = re.compile(r"([0-9]+(?:\.[0-9]+)?)\s*(g|ml|mL|ML|㎖)")


def find_column(fieldnames, hints):
    squeezed = {name.replace(" ", ""): name for name in fieldnames}
    for hint in hints:
        for key, original in squeezed.items():
            if hint.replace(" ", "") in key:
                return original
    return None


def parse_amount(text):
    """`200g`, `1회 제공량(200g)`, `100 mL` → 200.0 / 100.0. 못 읽으면 None."""
    if not text:
        return None
    match = AMOUNT_PATTERN.search(text.replace(",", ""))
    if match:
        return float(match.group(1))
    # 단위 없이 숫자만 있는 경우도 g으로 본다.
    bare = text.strip().replace(",", "")
    try:
        return float(bare)
    except ValueError:
        return None


def to_float(text):
    if text is None:
        return None
    cleaned = text.strip().replace(",", "")
    if cleaned in ("", "-", "N/A"):
        return None
    try:
        return float(cleaned)
    except ValueError:
        return None


def main(src_path, dst_path):
    with open(src_path, encoding="utf-8-sig", newline="") as src:
        reader = csv.DictReader(src)
        columns = {key: find_column(reader.fieldnames, hints) for key, hints in COLUMN_HINTS.items()}
        missing = [key for key, value in columns.items() if value is None and key != "serving"]
        if missing:
            sys.exit(f"원본에서 컬럼을 찾지 못했습니다: {missing}\n헤더: {reader.fieldnames}")

        rows, dropped, serving_missing = [], 0, 0
        for row in reader:
            basis = parse_amount(row.get(columns["basis"]))
            kcal = to_float(row.get(columns["kcal"]))
            carbs = to_float(row.get(columns["carbs"]))
            protein = to_float(row.get(columns["protein"]))
            fat = to_float(row.get(columns["fat"]))
            code = (row.get(columns["code"]) or "").strip()
            name = (row.get(columns["name"]) or "").strip().replace("\n", " ")

            if not code or not name or not basis or basis <= 0 or None in (kcal, carbs, protein, fat):
                dropped += 1
                continue

            factor = 100.0 / basis
            serving = parse_amount(row.get(columns["serving"])) if columns["serving"] else None
            if not serving:
                serving_missing += 1

            rows.append([
                code,
                f"{serving:.1f}" if serving else "",
                f"{kcal * factor:.2f}",
                f"{carbs * factor:.2f}",
                f"{protein * factor:.2f}",
                f"{fat * factor:.2f}",
                name,
            ])

    # 같은 식품코드가 중복되면 뒤엣것을 버린다(코드에 unique 제약이 있다).
    seen, unique_rows = set(), []
    for row in rows:
        if row[0] in seen:
            continue
        seen.add(row[0])
        unique_rows.append(row)

    with open(dst_path, "w", encoding="utf-8", newline="") as dst:
        writer = csv.writer(dst, lineterminator="\n", quoting=csv.QUOTE_NONE, escapechar=None)
        writer.writerow(OUT_HEADER)
        writer.writerows(unique_rows)

    print(f"적재 대상 {len(unique_rows)}건, 버린 행 {dropped}건, 1인분량 결측 {serving_missing}건")
    print("※ 결측률이 높으면 FoodPolicy.DEFAULT_SERVING_SIZE_G(200g) 가정을 재검토할 것")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: build-food-csv.py <원본.csv> <출력.csv>")
    main(sys.argv[1], sys.argv[2])
```

> `QUOTE_NONE`으로 쓰는 이유: 파서가 `split(',')`이라 인용부호를 해석하지 않는다. 이름은
> 마지막 컬럼이라 쉼표가 있어도 안전하지만, **다른 컬럼에 쉼표가 들어가면 안 되므로**
> 숫자는 전부 `,` 없는 포맷으로 쓴다.

- [ ] **Step 7: 실제 데이터셋 정제 (사람이 하는 단계)**

1. <https://www.data.go.kr/data/15100070/standard.do>에서 `음식` 표준데이터 CSV를 내려받는다. **`가공식품`·`원재료성식품`은 받지 않는다** — 브랜드명 위주라 LLM이 내놓는 "라면"과 오히려 매칭되지 않는다.
2. 정제한다:

```bash
python3 scripts/build-food-csv.py ~/Downloads/전국통합식품영양성분정보_음식.csv \
  apps/daily-record/src/main/resources/food/food-nutrition.csv
head -3 apps/daily-record/src/main/resources/food/food-nutrition.csv
wc -l apps/daily-record/src/main/resources/food/food-nutrition.csv
```

3. 출력의 **1인분량 결측 건수**를 확인한다. 결측률이 30%를 넘으면 `FoodPolicy.DEFAULT_SERVING_SIZE_G`가 대부분의 음식을 지배하게 되므로, 계획을 진행하기 전에 값을 재검토하고 결정을 이 문서에 남긴다(설계 문서 리스크 1).

- [ ] **Step 8: 포맷·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add scripts/build-food-csv.py apps/daily-record/src/main/kotlin/com/toy/backend/diet/food apps/daily-record/src/main/resources/food apps/daily-record/src/test/kotlin/com/toy/backend/diet/food
git commit -m "feat: 식품DB CSV 정제 스크립트와 기동 시 적재 추가"
```

---

### Task 6: OpenRouter 클라이언트 (API 키가 있을 때만 빈 등록)

LLM 게이트웨이 연동. **키가 없으면 빈을 등록하지 않아 로컬에서 키 없이 앱이 뜬다.**

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/llm/OpenRouterProperties.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/llm/OpenRouterClient.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/llm/OpenRouterConfig.kt`
- Modify: `apps/daily-record/src/main/resources/application.yml` (`openrouter` 블록)
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/DailyRecordApplication.kt` (`@EnableAsync`)
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/llm/OpenRouterConfigTest.kt`

**Interfaces:**
- Consumes: `WebClient`(webflux, 이미 의존성에 있음)
- Produces:
  - `data class OpenRouterProperties(apiKey, baseUrl, visionModel, textModel, timeoutSeconds)`
  - `data class RecognizedFood(name: String, portion: Double, estimatedKcal: Double, estimatedCarbsG: Double, estimatedProteinG: Double, estimatedFatG: Double)`
  - `class OpenRouterClient` — `recognizeFoods(base64Image: String, contentType: String): List<RecognizedFood>?`, `generateText(systemPrompt: String, userPrompt: String): String?` (실패 시 null)

- [ ] **Step 1: 조건부 등록 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/llm/OpenRouterConfigTest.kt`:

```kotlin
package com.toy.backend.diet.llm

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class OpenRouterConfigTest :
    BehaviorSpec({
        // OpenRouterConfig는 WebClient를 직접 만들므로 WebClient 자동설정이 필요 없다.
        val runner =
            ApplicationContextRunner()
                .withUserConfiguration(TestPropertiesConfig::class.java, OpenRouterConfig::class.java)

        Given("openrouter.api-key가 비어 있으면") {
            When("컨텍스트를 띄우면") {
                Then("OpenRouterClient 빈이 없다 — 키 없이 로컬을 띄울 수 있어야 한다") {
                    runner.withPropertyValues("openrouter.api-key=").run { context ->
                        context.getBeanNamesForType(OpenRouterClient::class.java).size shouldBe 0
                    }
                }
            }

            When("프로퍼티 자체가 없으면") {
                Then("역시 빈이 없다") {
                    runner.run { context ->
                        context.getBeanNamesForType(OpenRouterClient::class.java).size shouldBe 0
                    }
                }
            }
        }

        Given("openrouter.api-key가 설정되면") {
            When("컨텍스트를 띄우면") {
                Then("OpenRouterClient 빈이 등록된다") {
                    runner.withPropertyValues("openrouter.api-key=sk-test").run { context ->
                        context.getBeanNamesForType(OpenRouterClient::class.java).size shouldBe 1
                    }
                }
            }
        }
    })

@Configuration
@EnableConfigurationProperties(OpenRouterProperties::class)
private class TestPropertiesConfig
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*OpenRouterConfigTest*"`
Expected: FAIL — `Unresolved reference: OpenRouterConfig`

- [ ] **Step 3: 프로퍼티와 클라이언트 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/llm/OpenRouterProperties.kt`:

```kotlin
package com.toy.backend.diet.llm

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 모델은 환경변수로만 정한다 — 코드에 박지 않아야 한식 인식 정확도를 모델별로 비교하며 교체할 수 있다.
 * 교체할 때는 **`json_schema` strict를 지원하는 모델인지 반드시 확인한다**(미지원 모델은 파싱이 불안정하다).
 */
@ConfigurationProperties(prefix = "openrouter")
data class OpenRouterProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://openrouter.ai/api/v1",
    /** 음식 식별은 정확도가 결과 전체를 좌우한다 */
    val visionModel: String = "google/gemini-2.5-flash",
    /** 피드백은 수치를 다 넘겨받아 문장만 만드는 쉬운 작업이라 더 싼 모델로 충분하다 */
    val textModel: String = "google/gemini-2.5-flash-lite",
    val timeoutSeconds: Long = 60,
)
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/llm/OpenRouterClient.kt`:

```kotlin
package com.toy.backend.diet.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import tools.jackson.databind.JsonNode

private val log = KotlinLogging.logger {}

data class RecognizedFood(
    /** 한국어 음식명 */
    val name: String,
    /** 1인분 대비 배수 (0.5 = 반 인분) */
    val portion: Double,
    val estimatedKcal: Double,
    val estimatedCarbsG: Double,
    val estimatedProteinG: Double,
    val estimatedFatG: Double,
)

/**
 * OpenRouter `chat/completions` 래퍼. **영양소 수치나 점수를 LLM에게 묻지 않는다** —
 * 같은 사진에서도 호출마다 값이 달라지기 때문이다. 여기서 얻는 것은 *음식 식별*과 *문장*뿐이고,
 * `estimated*` 값은 식품DB 매칭이 실패했을 때만 쓰는 fallback이다.
 *
 * 호출 실패는 전부 null로 돌려준다 — 자동 재시도를 하지 않는다(실패 반복이 곧 비용 폭주 경로).
 */
class OpenRouterClient(
    private val properties: OpenRouterProperties,
    private val webClient: WebClient,
) {
    fun recognizeFoods(
        base64Image: String,
        contentType: String,
    ): List<RecognizedFood>? {
        val body =
            mapOf(
                "model" to properties.visionModel,
                "messages" to
                    listOf(
                        mapOf(
                            "role" to "user",
                            "content" to
                                listOf(
                                    mapOf("type" to "text", "text" to VISION_PROMPT),
                                    mapOf(
                                        "type" to "image_url",
                                        "image_url" to mapOf("url" to "data:$contentType;base64,$base64Image"),
                                    ),
                                ),
                        ),
                    ),
                "response_format" to RESPONSE_FORMAT,
            )

        val content = post(body) ?: return null
        return try {
            parseItems(content)
        } catch (e: Exception) {
            log.error(e) { "음식 인식 응답 파싱 실패: $content" }
            null
        }
    }

    fun generateText(
        systemPrompt: String,
        userPrompt: String,
    ): String? {
        val body =
            mapOf(
                "model" to properties.textModel,
                "messages" to
                    listOf(
                        mapOf("role" to "system", "content" to systemPrompt),
                        mapOf("role" to "user", "content" to userPrompt),
                    ),
            )
        return post(body)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** `choices[0].message.content` 문자열을 꺼낸다. 실패는 로그를 남기고 null. */
    private fun post(body: Map<String, Any>): String? =
        try {
            val response =
                webClient
                    .post()
                    .uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono<JsonNode>()
                    .block()
            response
                ?.path("choices")
                ?.path(0)
                ?.path("message")
                ?.path("content")
                ?.asString()
                ?.takeIf { it.isNotBlank() }
                ?: run {
                    log.error { "OpenRouter 응답에 content가 없다: $response" }
                    null
                }
        } catch (e: Exception) {
            log.error(e) { "OpenRouter 호출 실패" }
            null
        }

    private fun parseItems(content: String): List<RecognizedFood> {
        // strict json_schema 응답이라 형태가 고정이다. JsonNode로 직접 읽어 매퍼 설정 의존을 없앤다.
        val root = tools.jackson.databind.json.JsonMapper.builder().build().readTree(content)
        return root.path("items").map { item ->
            RecognizedFood(
                name = item.path("name").asString(),
                portion = item.path("portion").asDouble(),
                estimatedKcal = item.path("estimatedKcal").asDouble(),
                estimatedCarbsG = item.path("estimatedCarbsG").asDouble(),
                estimatedProteinG = item.path("estimatedProteinG").asDouble(),
                estimatedFatG = item.path("estimatedFatG").asDouble(),
            )
        }
    }

    companion object {
        private const val VISION_PROMPT =
            "사진 속 음식을 하나씩 식별해 주세요. 각 음식의 한국어 이름과, 1인분 대비 양(portion, 0.5는 반 인분), " +
                "그리고 대략적인 영양소 추정치를 알려 주세요. 음식이 아닌 물건은 넣지 마세요."

        private val NUMBER = mapOf("type" to "number")

        private val RESPONSE_FORMAT =
            mapOf(
                "type" to "json_schema",
                "json_schema" to
                    mapOf(
                        "name" to "meal_items",
                        "strict" to true,
                        "schema" to
                            mapOf(
                                "type" to "object",
                                "properties" to
                                    mapOf(
                                        "items" to
                                            mapOf(
                                                "type" to "array",
                                                "items" to
                                                    mapOf(
                                                        "type" to "object",
                                                        "properties" to
                                                            mapOf(
                                                                "name" to mapOf("type" to "string"),
                                                                "portion" to NUMBER,
                                                                "estimatedKcal" to NUMBER,
                                                                "estimatedCarbsG" to NUMBER,
                                                                "estimatedProteinG" to NUMBER,
                                                                "estimatedFatG" to NUMBER,
                                                            ),
                                                        "required" to
                                                            listOf(
                                                                "name",
                                                                "portion",
                                                                "estimatedKcal",
                                                                "estimatedCarbsG",
                                                                "estimatedProteinG",
                                                                "estimatedFatG",
                                                            ),
                                                        "additionalProperties" to false,
                                                    ),
                                            ),
                                    ),
                                "required" to listOf("items"),
                                "additionalProperties" to false,
                            ),
                    ),
            )
    }
}
```

- [ ] **Step 4: 조건부 설정 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/llm/OpenRouterConfig.kt`:

```kotlin
package com.toy.backend.diet.llm

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * API 키가 있을 때만 클라이언트를 등록한다. 키 없이 로컬을 띄우면 인식 요청만
 * `LLM_UNAVAILABLE`(503)로 막히고 프로필·확정·점수·집계는 전부 정상 동작한다.
 *
 * **`@ConditionalOnProperty`를 쓰면 안 된다.** `api-key: ${OPENROUTER_API_KEY:}`는 환경변수가
 * 없어도 프로퍼티가 빈 문자열로 *존재*하고, `havingValue`를 비워 둔 `ConditionalOnProperty`는
 * "존재하고 false가 아니면 참"이라 항상 매칭된다. 값이 비었는지를 봐야 하므로 SpEL을 쓴다.
 */
@Configuration
@ConditionalOnExpression("'\${openrouter.api-key:}'.trim().length() > 0")
class OpenRouterConfig(
    private val properties: OpenRouterProperties,
) {
    @Bean
    fun openRouterClient(): OpenRouterClient = OpenRouterClient(properties, openRouterWebClient())

    /**
     * 공휴일 API용 `webClientBuilder`(응답 타임아웃 10초)를 재사용하지 않는다 —
     * 이미지 인식은 수십 초가 걸릴 수 있어 타임아웃 요구가 다르다.
     */
    private fun openRouterWebClient(): WebClient =
        WebClient
            .builder()
            .baseUrl(properties.baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient.create().responseTimeout(Duration.ofSeconds(properties.timeoutSeconds)),
                ),
            ).codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES) }
            .build()

    companion object {
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*OpenRouterConfigTest*"`
Expected: PASS (3 When)

- [ ] **Step 6: 설정과 `@EnableAsync` 추가**

`apps/daily-record/src/main/resources/application.yml`의 `holiday:` 블록 앞에 추가:

```yaml
openrouter:
  api-key: ${OPENROUTER_API_KEY:}
  base-url: https://openrouter.ai/api/v1
  vision-model: ${OPENROUTER_VISION_MODEL:google/gemini-2.5-flash}
  text-model: ${OPENROUTER_TEXT_MODEL:google/gemini-2.5-flash-lite}
  timeout-seconds: 60
```

`apps/daily-record/src/main/kotlin/com/toy/backend/DailyRecordApplication.kt`:

```kotlin
package com.toy.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
@EnableScheduling
// 사진 인식·피드백 생성을 응답에서 떼어낸다. 큐는 쓰지 않는다 — 사용자 2명·하루 수십 건 규모다.
@EnableAsync
class DailyRecordApplication

fun main(args: Array<String>) {
    runApplication<DailyRecordApplication>(*args)
}
```

- [ ] **Step 7: 키 없이 앱이 뜨는지 확인**

```bash
OPENROUTER_API_KEY= ./gradlew :daily-record:bootRun
```

Expected: 기동 성공. 로그에 `OpenRouterClient` 관련 실패가 없어야 한다. 확인 후 `Ctrl+C`.

- [ ] **Step 8: 포맷·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src/main/kotlin/com/toy/backend apps/daily-record/src/main/resources/application.yml apps/daily-record/src/test/kotlin/com/toy/backend/diet/llm
git commit -m "feat: OpenRouter 클라이언트를 API 키가 있을 때만 등록하도록 추가"
```

---

### Task 7: 인식 파이프라인 (`MealAnalyzer`, `@Async`)

사진 1장 = LLM 호출 1회. 사진별로 독립적으로 돌려 확인 화면에서 "이 항목은 몇 번째 사진에서 나왔다"를 보여줄 수 있게 한다.

**Files:**
- Modify: `common/file/src/main/kotlin/com/toy/backend/file/FileService.kt` (`download` 추가)
- Test: `common/file/src/test/kotlin/com/toy/backend/file/FileServiceTest.kt` (다운로드 케이스 추가)
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/AnalysisStatus.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/NutritionSource.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalysis.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/AnalysisResult.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalysisRepository.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalyzer.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/analysis/MealAnalyzerTest.kt`

**Interfaces:**
- Consumes: `FoodMatcher.match`(Task 4), `Food.nutritionFor`(Task 4), `FoodPolicy.DEFAULT_SERVING_SIZE_G`, `OpenRouterClient.recognizeFoods`(Task 6)
- Produces:
  - `enum class AnalysisStatus { PENDING, COMPLETED, FAILED }`
  - `enum class NutritionSource { DB_MATCHED, LLM_ESTIMATED }`
  - `data class FileContent(val bytes: ByteArray, val contentType: String)` / `FileService.download(id: Long): FileContent`
  - `class MealAnalysis(user, status, resultJson)` — `updateResult(AnalysisStatus, String)`
  - `data class AnalysisResult(photos: List<AnalyzedPhoto>)`, `AnalyzedPhoto(fileId, failed, items)`, `AnalyzedItem(name, foodCode, quantityG, kcal, carbsG, proteinG, fatG, source)`
  - `MealAnalyzer.isAvailable: Boolean`, `.analyze(analysisId: Long)`, `.retryFailed(analysisId: Long)`

- [ ] **Step 1: `FileService.download` 실패 테스트 추가**

`common/file/src/test/kotlin/com/toy/backend/file/FileServiceTest.kt`의 마지막 `Given` 뒤에 추가한다(기존 목 변수명은 파일을 열어 그대로 쓴다). 파일 상단에 import를 더한다:

```kotlin
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.services.s3.model.GetObjectResponse
```


```kotlin
        Given("파일 내용 다운로드") {
            When("저장된 파일이면") {
                val entity = FileEntity("a.jpg", "temp/a.jpg", "image/jpeg", 3L, "daily").withId(30L)
                every { repository.findByIdOrNull(30L) } returns entity
                every { s3Client.getObjectAsBytes(any<GetObjectRequest>()) } returns
                    ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), byteArrayOf(1, 2, 3))

                val content = service.download(30L)

                Then("바이트와 contentType을 함께 준다") {
                    content.bytes.size shouldBe 3
                    content.contentType shouldBe "image/jpeg"
                }
            }

            When("없는 파일이면") {
                every { repository.findByIdOrNull(99L) } returns null

                Then("RESOURCE_NOT_FOUND") {
                    val e = shouldThrow<CustomException> { service.download(99L) }
                    e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :common-file:test --tests "*FileServiceTest*"`
Expected: FAIL — `Unresolved reference: download`

- [ ] **Step 3: `FileService.download` 구현**

`common/file/src/main/kotlin/com/toy/backend/file/FileService.kt`의 `getPresignedUrls` 아래에 추가한다:

```kotlin
    /**
     * 저장된 객체의 바이트를 그대로 읽는다. 이미지 인식처럼 서버가 파일 내용을 직접 다뤄야 하는
     * 경우에만 쓴다 — 클라이언트에게 줄 때는 presigned URL이 맞다.
     */
    fun download(id: Long): FileContent {
        val entity =
            repository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        val bytes =
            s3Client.getObjectAsBytes(
                GetObjectRequest
                    .builder()
                    .bucket(entity.bucketName)
                    .key(entity.storedName)
                    .build(),
            )
        return FileContent(bytes = bytes.asByteArray(), contentType = entity.contentType)
    }
```

같은 파일 맨 아래(클래스 밖)에 추가:

```kotlin
data class FileContent(
    val bytes: ByteArray,
    val contentType: String,
)
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :common-file:test`
Expected: PASS

- [ ] **Step 5: 인식 파이프라인 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/analysis/MealAnalyzerTest.kt`:

```kotlin
package com.toy.backend.diet.analysis

import com.toy.backend.common.entity.withId
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyFood
import com.toy.backend.diet.food.FoodMatcher
import com.toy.backend.diet.llm.OpenRouterClient
import com.toy.backend.diet.llm.RecognizedFood
import com.toy.backend.file.FileContent
import com.toy.backend.file.FileService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

class MealAnalyzerTest :
    BehaviorSpec({
        val repository = mockk<MealAnalysisRepository>()
        val fileService = mockk<FileService>()
        val foodMatcher = mockk<FoodMatcher>()
        val client = mockk<OpenRouterClient>()
        val objectMapper = jacksonObjectMapper()

        val user = dietUser()
        val recognized =
            RecognizedFood(
                name = "제육볶음",
                portion = 0.5,
                estimatedKcal = 400.0,
                estimatedCarbsG = 30.0,
                estimatedProteinG = 25.0,
                estimatedFatG = 18.0,
            )

        fun pendingAnalysis(vararg fileIds: Long): MealAnalysis =
            MealAnalysis(
                user = user,
                resultJson = objectMapper.writeValueAsString(AnalysisResult(fileIds.map { AnalyzedPhoto(fileId = it) })),
            ).withId(1L)

        Given("사진 2장 인식") {
            When("한 장은 성공하고 한 장은 호출이 실패하면") {
                val analyzer = MealAnalyzer(repository, fileService, foodMatcher, objectMapper, client)
                val analysis = pendingAnalysis(10L, 11L)
                every { repository.findByIdOrNull(1L) } returns analysis
                every { fileService.download(10L) } returns FileContent(byteArrayOf(1), "image/jpeg")
                every { fileService.download(11L) } returns FileContent(byteArrayOf(2), "image/jpeg")
                every { client.recognizeFoods(any(), "image/jpeg") } returns listOf(recognized) andThen null
                every { foodMatcher.match("제육볶음") } returns
                    dummyFood(servingSizeG = 300.0, kcalPer100g = 200.0, carbsPer100g = 10.0, proteinPer100g = 20.0, fatPer100g = 5.0)

                analyzer.analyze(1L)

                val result = objectMapper.readValue<AnalysisResult>(analysis.resultJson)

                Then("부분 실패를 허용한다 — 나머지 결과로 확인 화면을 띄운다") {
                    analysis.status shouldBe AnalysisStatus.COMPLETED
                    result.photos[0].failed shouldBe false
                    result.photos[1].failed shouldBe true
                }

                Then("식품DB 매칭에 성공하면 1인분 배수로 환산한다 — 300g × 0.5 = 150g") {
                    val item = result.photos[0].items[0]
                    item.foodName shouldBe "제육볶음"
                    item.quantityG shouldBe 150.0
                    item.kcal shouldBe 300.0
                    item.source shouldBe NutritionSource.DB_MATCHED
                }
            }

            When("모든 사진에서 실패하면") {
                val analyzer = MealAnalyzer(repository, fileService, foodMatcher, objectMapper, client)
                val analysis = pendingAnalysis(20L)
                every { repository.findByIdOrNull(1L) } returns analysis
                every { fileService.download(20L) } returns FileContent(byteArrayOf(1), "image/jpeg")
                every { client.recognizeFoods(any(), any()) } returns null

                analyzer.analyze(1L)

                Then("status = FAILED. 자동 재시도는 하지 않는다") {
                    analysis.status shouldBe AnalysisStatus.FAILED
                }
            }

            When("식품DB에 없는 음식이면") {
                val analyzer = MealAnalyzer(repository, fileService, foodMatcher, objectMapper, client)
                val analysis = pendingAnalysis(30L)
                every { repository.findByIdOrNull(1L) } returns analysis
                every { fileService.download(30L) } returns FileContent(byteArrayOf(1), "image/jpeg")
                every { client.recognizeFoods(any(), any()) } returns listOf(recognized)
                every { foodMatcher.match("제육볶음") } returns null

                analyzer.analyze(1L)

                val item = objectMapper.readValue<AnalysisResult>(analysis.resultJson).photos[0].items[0]

                Then("LLM 추정값을 그대로 쓰고 출처를 남긴다") {
                    item.kcal shouldBe 400.0
                    item.carbsG shouldBe 30.0
                    item.foodCode shouldBe null
                    item.source shouldBe NutritionSource.LLM_ESTIMATED
                    item.quantityG shouldBe 100.0 // 기본 1인분 200g × 0.5
                }
            }
        }

        Given("실패한 사진만 재인식") {
            When("2장 중 뒤엣것만 실패한 상태에서 retry 하면") {
                val analyzer = MealAnalyzer(repository, fileService, foodMatcher, objectMapper, client)
                val analysis =
                    MealAnalysis(
                        user = user,
                        resultJson =
                            objectMapper.writeValueAsString(
                                AnalysisResult(
                                    listOf(
                                        AnalyzedPhoto(
                                            fileId = 40L,
                                            failed = false,
                                            items =
                                                listOf(
                                                    AnalyzedItem(
                                                        foodName = "김치찌개",
                                                        foodCode = "D1",
                                                        quantityG = 200.0,
                                                        kcal = 300.0,
                                                        carbsG = 10.0,
                                                        proteinG = 20.0,
                                                        fatG = 15.0,
                                                        source = NutritionSource.DB_MATCHED,
                                                    ),
                                                ),
                                        ),
                                        AnalyzedPhoto(fileId = 41L, failed = true),
                                    ),
                                ),
                            ),
                    ).withId(1L)
                every { repository.findByIdOrNull(1L) } returns analysis
                every { fileService.download(41L) } returns FileContent(byteArrayOf(2), "image/jpeg")
                every { client.recognizeFoods(any(), any()) } returns listOf(recognized)
                every { foodMatcher.match("제육볶음") } returns dummyFood()

                analyzer.retryFailed(1L)

                val result = objectMapper.readValue<AnalysisResult>(analysis.resultJson)

                Then("성공한 사진은 다시 호출하지 않는다 — 비용이 이중으로 나가고 결과가 흔들린다") {
                    verify(exactly = 0) { fileService.download(40L) }
                    verify(exactly = 1) { client.recognizeFoods(any(), any()) }
                }

                Then("기존 결과는 그대로 남고 실패했던 사진만 채워진다") {
                    result.photos[0].items[0].foodName shouldBe "김치찌개"
                    result.photos[1].failed shouldBe false
                    result.photos[1].items.size shouldBe 1
                }
            }
        }

        Given("API 키가 없어 클라이언트 빈이 없으면") {
            When("인식을 돌리면") {
                val analyzer = MealAnalyzer(repository, fileService, foodMatcher, objectMapper, null)

                Then("isAvailable이 false다 — 서비스가 요청 단계에서 막는다") {
                    analyzer.isAvailable shouldBe false
                }
            }
        }
    })
```

- [ ] **Step 6: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*MealAnalyzerTest*"`
Expected: FAIL — `Unresolved reference: MealAnalyzer`

- [ ] **Step 7: 공용 enum과 엔티티 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/AnalysisStatus.kt`:

```kotlin
package com.toy.backend.diet

/**
 * `MealAnalysis`에서는 *인식* 진행 상태, `Meal`에서는 *피드백 생성* 상태를 뜻한다.
 * 값 집합과 전이 모양이 같아 따로 만들 이유가 없다.
 */
enum class AnalysisStatus { PENDING, COMPLETED, FAILED }
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/NutritionSource.kt`:

```kotlin
package com.toy.backend.diet

/** 영양소 수치의 출처. iOS는 LLM_ESTIMATED에 「추정」 배지를 띄운다. */
enum class NutritionSource { DB_MATCHED, LLM_ESTIMATED }
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/AnalysisResult.kt`:

```kotlin
package com.toy.backend.diet.analysis

import com.toy.backend.diet.NutritionSource

/**
 * `MealAnalysis.resultJson`에 통째로 담기는 구조. **자식 테이블로 쪼개지 않는다** —
 * 확인 전 임시 데이터라 이걸로 질의할 일이 없고, 확정되면 `MealItem`으로 옮겨가며 통째로 버려진다.
 */
data class AnalysisResult(
    val photos: List<AnalyzedPhoto>,
)

data class AnalyzedPhoto(
    val fileId: Long,
    val failed: Boolean = false,
    val items: List<AnalyzedItem> = emptyList(),
)

data class AnalyzedItem(
    val foodName: String,
    val foodCode: String?,
    val quantityG: Double,
    val kcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val source: NutritionSource,
)
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalysis.kt`:

```kotlin
package com.toy.backend.diet.analysis

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * 확정 전 인식 결과. 확정되면 삭제하고, 확인하지 않고 버려진 것은 TTL 24시간 배치가 지운다.
 * 사진 파일은 `attachFile`이 호출되지 않아 `TEMP`로 남고 파일 정리 배치가 따로 수거한다.
 */
@Entity
@Table(
    name = "meal_analyses",
    indexes = [
        Index(name = "idx_meal_analyses_created_at", columnList = "created_at"),
    ],
)
class MealAnalysis(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    var status: AnalysisStatus = AnalysisStatus.PENDING,
    @Column(name = "result_json", nullable = false, columnDefinition = "text")
    var resultJson: String,
) : BaseEntity() {
    fun updateResult(
        status: AnalysisStatus,
        resultJson: String,
    ) {
        this.status = status
        this.resultJson = resultJson
    }

    fun markPending() {
        this.status = AnalysisStatus.PENDING
    }
}
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalysisRepository.kt`:

```kotlin
package com.toy.backend.diet.analysis

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface MealAnalysisRepository : JpaRepository<MealAnalysis, Long> {
    fun deleteByCreatedAtBefore(cutoff: LocalDateTime): Long
}
```

- [ ] **Step 8: 인식 파이프라인 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalyzer.kt`:

```kotlin
package com.toy.backend.diet.analysis

import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.food.FoodMatcher
import com.toy.backend.diet.food.FoodPolicy
import com.toy.backend.diet.food.nutritionFor
import com.toy.backend.diet.llm.OpenRouterClient
import com.toy.backend.diet.llm.RecognizedFood
import com.toy.backend.file.FileService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.Base64

private val log = KotlinLogging.logger {}

/**
 * 사진마다 독립적으로 이미지 LLM을 호출한다. 한 호출에 여러 장을 넣지 않는 이유는 확인 화면에서
 * "이 항목은 몇 번째 사진에서 나왔다"를 보여주기 위해서다 — 사용자가 중복을 판단하려면 출처가 보여야 한다.
 *
 * **여기까지가 인식이다. 점수도 피드백도 만들지 않는다** — 사용자가 항목을 고칠 수 있으므로
 * 확정 전 수치로 계산하면 버려진다.
 */
@Component
class MealAnalyzer(
    private val repository: MealAnalysisRepository,
    private val fileService: FileService,
    private val foodMatcher: FoodMatcher,
    private val objectMapper: ObjectMapper,
    // API 키가 없으면 빈이 등록되지 않는다 — 로컬에서 키 없이 앱을 띄우기 위한 설계다.
    @Autowired(required = false) private val client: OpenRouterClient?,
) {
    val isAvailable: Boolean get() = client != null

    /**
     * `@Async`는 별도 트랜잭션이므로 **엔티티가 아니라 id를 받아 다시 조회한다.**
     * 호출 측에서 넘긴 엔티티를 그대로 쓰면 준영속 상태 문제가 생긴다.
     */
    @Async
    @Transactional
    fun analyze(analysisId: Long) {
        val analysis = repository.findByIdOrNull(analysisId) ?: return log.warn { "분석 대상이 없다: id=$analysisId" }
        val photos = readResult(analysis).photos.map { analyzePhoto(it.fileId) }
        writeResult(analysis, photos)
    }

    /** 실패한 사진만 다시 부른다 — 성공한 사진을 재호출하면 비용이 이중으로 나가고 결과가 흔들린다. */
    @Async
    @Transactional
    fun retryFailed(analysisId: Long) {
        val analysis = repository.findByIdOrNull(analysisId) ?: return log.warn { "재인식 대상이 없다: id=$analysisId" }
        val photos = readResult(analysis).photos.map { if (it.failed) analyzePhoto(it.fileId) else it }
        writeResult(analysis, photos)
    }

    private fun analyzePhoto(fileId: Long): AnalyzedPhoto {
        val openRouter = client ?: return AnalyzedPhoto(fileId = fileId, failed = true)
        return try {
            val content = fileService.download(fileId)
            // 리사이즈는 하지 않는다 — iOS가 업로드 전에 장변 1024px로 줄여 보낸다.
            // 라즈베리파이에서 이미지를 재인코딩하는 건 낭비다.
            val base64 = Base64.getEncoder().encodeToString(content.bytes)
            val recognized =
                openRouter.recognizeFoods(base64, content.contentType)
                    ?: return AnalyzedPhoto(fileId = fileId, failed = true)
            AnalyzedPhoto(fileId = fileId, failed = false, items = recognized.map { toItem(it) })
        } catch (e: Exception) {
            log.error(e) { "사진 인식 실패: fileId=$fileId" }
            AnalyzedPhoto(fileId = fileId, failed = true)
        }
    }

    /** 식품DB에 붙으면 결정적인 값을, 못 붙으면 LLM 추정값을 쓴다. */
    private fun toItem(recognized: RecognizedFood): AnalyzedItem {
        val matched = foodMatcher.match(recognized.name)
        if (matched == null) {
            return AnalyzedItem(
                foodName = recognized.name,
                foodCode = null,
                quantityG = FoodPolicy.DEFAULT_SERVING_SIZE_G * recognized.portion,
                kcal = recognized.estimatedKcal,
                carbsG = recognized.estimatedCarbsG,
                proteinG = recognized.estimatedProteinG,
                fatG = recognized.estimatedFatG,
                source = NutritionSource.LLM_ESTIMATED,
            )
        }
        val amount = matched.nutritionFor(recognized.portion)
        return AnalyzedItem(
            foodName = matched.name,
            foodCode = matched.code,
            quantityG = amount.quantityG,
            kcal = amount.kcal,
            carbsG = amount.carbsG,
            proteinG = amount.proteinG,
            fatG = amount.fatG,
            source = NutritionSource.DB_MATCHED,
        )
    }

    private fun readResult(analysis: MealAnalysis): AnalysisResult = objectMapper.readValue<AnalysisResult>(analysis.resultJson)

    private fun writeResult(
        analysis: MealAnalysis,
        photos: List<AnalyzedPhoto>,
    ) {
        // 전부 실패했을 때만 FAILED다. 사진 한 장 때문에 나머지 인식 결과를 버리면 전부 다시 올려야 한다.
        val status = if (photos.all { it.failed }) AnalysisStatus.FAILED else AnalysisStatus.COMPLETED
        analysis.updateResult(status, objectMapper.writeValueAsString(AnalysisResult(photos)))
    }
}
```

- [ ] **Step 9: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*MealAnalyzerTest*"`
Expected: PASS (3 Given)

- [ ] **Step 10: 포맷·커밋**

```bash
./gradlew spotlessApply :common-file:test :daily-record:test
git add common/file apps/daily-record/src/main/kotlin/com/toy/backend/diet apps/daily-record/src/test/kotlin/com/toy/backend/diet
git commit -m "feat: 사진별 식단 인식 파이프라인 추가"
```

---

### Task 8: 분석 API (생성·조회·재시도·취소)

`POST /diet/analyses`가 즉시 201을 반환하고 인식은 뒤에서 돈다. 사진 5장이면 동기 처리 시 수십 초를 응답 대기로 잡아먹는다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/AfterCommit.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalysisDtos.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalysisService.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalysisController.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/analysis/MealAnalysisServiceTest.kt`

**Interfaces:**
- Consumes: `MealAnalyzer`·`MealAnalysisRepository`·`AnalysisResult`(Task 7), `FileService.getPresignedUrls`, `DietErrorCode`(Task 1)
- Produces:
  - `fun runAfterCommit(action: () -> Unit)`
  - `data class AnalysisCreateRequest(fileIds: List<Long>)`
  - `data class AnalysisResponse(id, status, photos: List<AnalysisPhotoResponse>)`, `AnalysisPhotoResponse(fileId, url, failed, items: List<AnalyzedItem>)`
  - `MealAnalysisService.create/get/retry/delete`, `.requireOwned(user, id): MealAnalysis`

- [ ] **Step 1: 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/analysis/MealAnalysisServiceTest.kt`:

```kotlin
package com.toy.backend.diet.analysis

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.dietUser
import com.toy.backend.file.FileService
import com.toy.backend.user.UserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import tools.jackson.module.kotlin.jacksonObjectMapper

class MealAnalysisServiceTest :
    BehaviorSpec({
        val repository = mockk<MealAnalysisRepository>()
        val userRepository = mockk<UserRepository>()
        val fileService = mockk<FileService>()
        val analyzer = mockk<MealAnalyzer>()
        val objectMapper = jacksonObjectMapper()
        val service = MealAnalysisService(repository, userRepository, fileService, analyzer, objectMapper)

        val user = dietUser()

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            every { analyzer.isAvailable } returns true
        }

        Given("분석 생성") {
            When("사진 3장을 올리면") {
                every { repository.save(any()) } answers { (firstArg() as MealAnalysis).withId(1L) }
                justRun { analyzer.analyze(1L) }

                val id = service.create("testuser", AnalysisCreateRequest(fileIds = listOf(1L, 2L, 3L)))

                Then("PENDING으로 저장하고 id를 즉시 반환한다") {
                    id shouldBe 1L
                    verify { repository.save(match { it.status == AnalysisStatus.PENDING }) }
                }
            }

            When("사진이 6장이면") {
                Then("PHOTO_LIMIT_EXCEEDED — 장수가 곧 비용·지연이다") {
                    val e =
                        shouldThrow<CustomException> {
                            service.create("testuser", AnalysisCreateRequest(fileIds = (1L..6L).toList()))
                        }
                    e.errorCode shouldBe DietErrorCode.PHOTO_LIMIT_EXCEEDED
                }
            }

            When("사진이 하나도 없으면") {
                Then("INVALID_REQUEST") {
                    val e = shouldThrow<CustomException> { service.create("testuser", AnalysisCreateRequest(emptyList())) }
                    e.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }

            When("API 키가 없어 인식기가 준비되지 않았으면") {
                every { analyzer.isAvailable } returns false

                Then("LLM_UNAVAILABLE — 진행시켜 봐야 FAILED 레코드만 쌓인다") {
                    val e = shouldThrow<CustomException> { service.create("testuser", AnalysisCreateRequest(listOf(1L))) }
                    e.errorCode shouldBe DietErrorCode.LLM_UNAVAILABLE
                }
            }
        }

        Given("분석 조회") {
            val analysis =
                MealAnalysis(
                    user = user,
                    status = AnalysisStatus.COMPLETED,
                    resultJson =
                        objectMapper.writeValueAsString(
                            AnalysisResult(
                                listOf(
                                    AnalyzedPhoto(
                                        fileId = 7L,
                                        failed = false,
                                        items =
                                            listOf(
                                                AnalyzedItem(
                                                    foodName = "제육볶음",
                                                    foodCode = "D1",
                                                    quantityG = 150.0,
                                                    kcal = 300.0,
                                                    carbsG = 15.0,
                                                    proteinG = 30.0,
                                                    fatG = 8.0,
                                                    source = NutritionSource.DB_MATCHED,
                                                ),
                                            ),
                                    ),
                                    AnalyzedPhoto(fileId = 8L, failed = true),
                                ),
                            ),
                        ),
                ).withId(5L)

            When("본인 분석이면") {
                every { repository.findByIdOrNull(5L) } returns analysis
                every { fileService.getPresignedUrls(listOf(7L, 8L)) } returns mapOf(7L to "https://u7", 8L to "https://u8")

                val response = service.get("testuser", 5L)

                Then("사진별 결과와 10분 만료 URL을 함께 준다") {
                    response.status shouldBe AnalysisStatus.COMPLETED
                    response.photos[0].url shouldBe "https://u7"
                    response.photos[0].items[0].foodName shouldBe "제육볶음"
                    response.photos[1].failed shouldBe true
                    response.photos[1].items.size shouldBe 0
                }
            }

            When("타인 분석이면") {
                val other = dietUser(username = "other", id = 2L)
                every { repository.findByIdOrNull(6L) } returns MealAnalysis(user = other, resultJson = "{\"photos\":[]}").withId(6L)

                Then("RESOURCE_NOT_FOUND — 존재 자체를 숨긴다") {
                    val e = shouldThrow<CustomException> { service.get("testuser", 6L) }
                    e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("재인식") {
            When("실패한 사진이 있으면") {
                val analysis =
                    MealAnalysis(
                        user = user,
                        status = AnalysisStatus.COMPLETED,
                        resultJson =
                            objectMapper.writeValueAsString(
                                AnalysisResult(listOf(AnalyzedPhoto(fileId = 9L, failed = true))),
                            ),
                    ).withId(7L)
                every { repository.findByIdOrNull(7L) } returns analysis
                justRun { analyzer.retryFailed(7L) }

                service.retry("testuser", 7L)

                Then("PENDING으로 되돌리고 실패한 사진만 다시 돌린다") {
                    analysis.status shouldBe AnalysisStatus.PENDING
                    verify { analyzer.retryFailed(7L) }
                }
            }

            When("실패한 사진이 없으면") {
                val analysis =
                    MealAnalysis(
                        user = user,
                        status = AnalysisStatus.COMPLETED,
                        resultJson =
                            objectMapper.writeValueAsString(
                                AnalysisResult(listOf(AnalyzedPhoto(fileId = 9L, failed = false))),
                            ),
                    ).withId(8L)
                every { repository.findByIdOrNull(8L) } returns analysis

                Then("ANALYSIS_NOT_RETRYABLE — 성공한 사진을 재호출하지 않는다") {
                    val e = shouldThrow<CustomException> { service.retry("testuser", 8L) }
                    e.errorCode shouldBe DietErrorCode.ANALYSIS_NOT_RETRYABLE
                }
            }
        }

        Given("확인 취소") {
            When("본인 분석을 삭제하면") {
                val analysis = MealAnalysis(user = user, resultJson = "{\"photos\":[]}").withId(9L)
                every { repository.findByIdOrNull(9L) } returns analysis
                justRun { repository.delete(analysis) }

                service.delete("testuser", 9L)

                Then("레코드만 지운다 — 사진은 TEMP로 남아 정리 배치가 수거한다") {
                    verify { repository.delete(analysis) }
                    verify(exactly = 0) { fileService.detachFiles(any()) }
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*MealAnalysisServiceTest*"`
Expected: FAIL — `Unresolved reference: MealAnalysisService`

- [ ] **Step 3: 커밋 후 실행 헬퍼 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/AfterCommit.kt`:

```kotlin
package com.toy.backend.diet

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * `@Async` 작업을 **커밋 뒤에** 시작한다. 트랜잭션 안에서 바로 부르면 비동기 스레드가
 * 아직 커밋되지 않은 행을 조회해 "대상이 없다"로 끝난다. 트랜잭션이 없으면 그냥 실행한다.
 */
fun runAfterCommit(action: () -> Unit) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return action()
    TransactionSynchronizationManager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCommit() = action()
        },
    )
}
```

- [ ] **Step 4: DTO와 서비스 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalysisDtos.kt`:

```kotlin
package com.toy.backend.diet.analysis

import com.toy.backend.diet.AnalysisStatus

data class AnalysisCreateRequest(
    val fileIds: List<Long>,
)

data class AnalysisPhotoResponse(
    val fileId: Long,
    val url: String?,
    val failed: Boolean,
    val items: List<AnalyzedItem>,
)

data class AnalysisResponse(
    val id: Long,
    val status: AnalysisStatus,
    val photos: List<AnalysisPhotoResponse>,
)
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalysisService.kt`:

```kotlin
package com.toy.backend.diet.analysis

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.runAfterCommit
import com.toy.backend.file.FileService
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

@Service
@Transactional(readOnly = true)
class MealAnalysisService(
    private val repository: MealAnalysisRepository,
    private val userRepository: UserRepository,
    private val fileService: FileService,
    private val analyzer: MealAnalyzer,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun create(
        username: String,
        request: AnalysisCreateRequest,
    ): Long {
        val fileIds = request.fileIds.distinct()
        if (fileIds.isEmpty()) throw CustomException(ErrorCode.INVALID_REQUEST, "사진을 한 장 이상 올려주세요")
        // 사진마다 이미지 LLM을 호출하므로 장수가 곧 비용·지연이다.
        if (fileIds.size > MAX_PHOTOS) throw CustomException(DietErrorCode.PHOTO_LIMIT_EXCEEDED, MAX_PHOTOS)
        // 인식은 LLM 없이 대체 경로가 없다 — 진행시켜 봐야 FAILED 레코드만 쌓인다.
        if (!analyzer.isAvailable) throw CustomException(DietErrorCode.LLM_UNAVAILABLE)

        val analysis =
            MealAnalysis(
                user = findUser(username),
                status = AnalysisStatus.PENDING,
                resultJson = objectMapper.writeValueAsString(AnalysisResult(fileIds.map { AnalyzedPhoto(fileId = it) })),
            )
        val id = repository.save(analysis).requiredId
        runAfterCommit { analyzer.analyze(id) }
        return id
    }

    fun get(
        username: String,
        id: Long,
    ): AnalysisResponse {
        val analysis = requireOwned(findUser(username), id)
        val result = objectMapper.readValue<AnalysisResult>(analysis.resultJson)
        // 끼니 목록에서 N+1을 만들지 않도록 URL은 한 번에 받는다.
        val urls = fileService.getPresignedUrls(result.photos.map { it.fileId })
        return AnalysisResponse(
            id = analysis.requiredId,
            status = analysis.status,
            photos =
                result.photos.map {
                    AnalysisPhotoResponse(
                        fileId = it.fileId,
                        url = urls[it.fileId],
                        failed = it.failed,
                        items = it.items,
                    )
                },
        )
    }

    @Transactional
    fun retry(
        username: String,
        id: Long,
    ) {
        val analysis = requireOwned(findUser(username), id)
        val result = objectMapper.readValue<AnalysisResult>(analysis.resultJson)
        if (result.photos.none { it.failed }) throw CustomException(DietErrorCode.ANALYSIS_NOT_RETRYABLE, id)
        analysis.markPending()
        runAfterCommit { analyzer.retryFailed(id) }
    }

    /** 확인 취소 — 레코드만 지운다. 사진은 attach된 적이 없어 TEMP로 남고 파일 정리 배치가 수거한다. */
    @Transactional
    fun delete(
        username: String,
        id: Long,
    ) {
        repository.delete(requireOwned(findUser(username), id))
    }

    fun requireOwned(
        user: User,
        id: Long,
    ): MealAnalysis {
        val analysis =
            repository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        if (analysis.user.requiredId != user.requiredId) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }
        return analysis
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    companion object {
        /** 상한 5장은 초기 추정치다 — 실제로 평균 몇 장을 올리는지 관찰해 조정한다. */
        const val MAX_PHOTOS = 5
    }
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*MealAnalysisServiceTest*"`
Expected: PASS (4 Given)

- [ ] **Step 6: 컨트롤러 작성**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalysisController.kt`:

```kotlin
package com.toy.backend.diet.analysis

import com.toy.backend.common.annotation.ResponseCreated
import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "식단 인식", description = "사진 인식 → 확인 → 확정 흐름의 인식 단계")
@RestController
@RequestMapping("/diet/analyses")
class MealAnalysisController(
    private val service: MealAnalysisService,
) {
    @PostMapping
    @ResponseCreated("/diet/analyses/{id}")
    @Operation(summary = "사진 인식 요청 — 즉시 201을 주고 인식은 뒤에서 돈다 (최대 5장)")
    fun create(
        @Valid @RequestBody request: AnalysisCreateRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.create(authentication.name, request))

    @GetMapping("/{id}")
    @Operation(summary = "인식 상태·결과 조회 (확인 화면·폴링용)")
    fun get(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<AnalysisResponse>> =
        ResponseEntity.ok(DataResponseBody(service.get(authentication.name, id)))

    @PostMapping("/{id}/retry")
    @Operation(summary = "실패한 사진만 재인식")
    fun retry(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.retry(authentication.name, id)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "확인 취소")
    fun delete(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.delete(authentication.name, id)
        return ResponseEntity.noContent().build()
    }
}
```

- [ ] **Step 7: 포맷·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet apps/daily-record/src/test/kotlin/com/toy/backend/diet
git commit -m "feat: 식단 사진 분석 API 추가"
```

---

### Task 9: 끼니 확정 (`Meal`·`MealItem`·`MealPhoto`)

사용자가 고친 항목을 최종본으로 받아 `Meal`을 만든다. 이 시점의 몸무게·목표를 스냅샷으로 복사해 하루 점수가 나중에 흔들리지 않게 한다. **피드백 연결은 Task 11에서 붙인다** — 여기서는 점수까지만 만든다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/Meal.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealItem.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealPhoto.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealRepository.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealDtos.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealService.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealController.kt`
- Modify: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/DietFixtures.kt` (`dummyMeal()`·`dummyMealItem()` 추가)
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/MealConfirmTest.kt`

**Interfaces:**
- Consumes: `NutritionProfileService.requireProfile`(Task 1), `DietScoreCalculator.scoreMeal`(Task 2), `MealAnalysisService.requireOwned`(Task 8), `FileService.attachFile`/`getPresignedUrls`
- Produces:
  - `enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK }`
  - `class Meal(user, date, mealType, status, score, totalKcal, carbsG, proteinG, fatG, feedback, weightKg, targetKcal, targetCarbsG, targetProteinG, targetFatG)` — `replaceItems(List<MealItem>)`, `applyScore(Int?)`, `addPhoto(MealPhoto)`, `markFeedback(String?)`, `targets(): NutritionTargets`
  - `class MealItem(meal, foodName, foodCode, quantityG, kcal, carbsG, proteinG, fatG, source)`
  - `class MealPhoto(meal, fileId, sortOrder)`
  - `MealRepository.findByUserAndDateBetweenOrderByDateAscIdAsc`, `.findByUserAndDateOrderByCreatedAtAscIdAsc`
  - `MealService.confirm(username, MealConfirmRequest): Long`, `.get(username, id): MealResponse`, `.list(username, from, to): List<MealResponse>`

- [ ] **Step 1: 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/MealConfirmTest.kt`:

```kotlin
package com.toy.backend.diet.meal

import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.analysis.AnalysisResult
import com.toy.backend.diet.analysis.AnalyzedItem
import com.toy.backend.diet.analysis.AnalyzedPhoto
import com.toy.backend.diet.analysis.MealAnalysis
import com.toy.backend.diet.analysis.MealAnalysisRepository
import com.toy.backend.diet.analysis.MealAnalysisService
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyProfile
import com.toy.backend.diet.profile.NutritionProfileService
import com.toy.backend.file.FileService
import com.toy.backend.user.UserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate

class MealConfirmTest :
    BehaviorSpec({
        val repository = mockk<MealRepository>()
        val userRepository = mockk<UserRepository>()
        val profileService = mockk<NutritionProfileService>()
        val analysisService = mockk<MealAnalysisService>()
        val analysisRepository = mockk<MealAnalysisRepository>()
        val fileService = mockk<FileService>()
        val objectMapper = jacksonObjectMapper()
        val service =
            MealService(
                repository,
                userRepository,
                profileService,
                analysisService,
                analysisRepository,
                fileService,
                objectMapper,
            )

        val user = dietUser()
        val profile = dummyProfile(user = user, weightKg = 70.0)

        fun completedAnalysis(vararg fileIds: Long): MealAnalysis =
            MealAnalysis(
                user = user,
                status = AnalysisStatus.COMPLETED,
                resultJson =
                    objectMapper.writeValueAsString(
                        AnalysisResult(
                            fileIds.map { fileId ->
                                AnalyzedPhoto(
                                    fileId = fileId,
                                    failed = false,
                                    items =
                                        listOf(
                                            AnalyzedItem(
                                                foodName = "인식된음식",
                                                foodCode = "X1",
                                                quantityG = 100.0,
                                                kcal = 999.0,
                                                carbsG = 99.0,
                                                proteinG = 99.0,
                                                fatG = 99.0,
                                                source = NutritionSource.DB_MATCHED,
                                            ),
                                        ),
                                )
                            },
                        ),
                    ),
            ).withId(3L)

        val userItems =
            listOf(
                MealItemRequest(
                    foodName = "제육볶음",
                    foodCode = "D1",
                    quantityG = 168.75,
                    kcal = 700.0,
                    carbsG = 168.75,
                    proteinG = 18.0,
                    fatG = 17.0,
                    source = NutritionSource.DB_MATCHED,
                ),
            )

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            every { profileService.requireProfile(user) } returns profile
        }

        Given("끼니 확정") {
            When("사진 2장짜리 분석을 확정하면") {
                val analysis = completedAnalysis(11L, 12L)
                every { analysisService.requireOwned(user, 3L) } returns analysis
                every { fileService.attachFile(any(), "meals/") } returnsArgument 0
                every { repository.save(any()) } answers { (firstArg() as Meal).withId(50L) }
                justRun { analysisRepository.delete(analysis) }

                val id =
                    service.confirm(
                        "testuser",
                        MealConfirmRequest(
                            date = LocalDate.of(2026, 7, 29),
                            mealType = MealType.LUNCH,
                            analysisId = 3L,
                            items = userItems,
                        ),
                    )

                Then("사진 수만큼 attachFile을 부르고 MealPhoto를 만든다") {
                    id shouldBe 50L
                    verify(exactly = 1) { fileService.attachFile(11L, "meals/") }
                    verify(exactly = 1) { fileService.attachFile(12L, "meals/") }
                    verify { repository.save(match { it.photos.size == 2 && it.photos[1].sortOrder == 1 }) }
                }

                Then("인식 결과가 아니라 사용자가 고친 항목이 저장된다") {
                    verify {
                        repository.save(
                            match { meal ->
                                meal.items.size == 1 &&
                                    meal.items[0].foodName == "제육볶음" &&
                                    meal.totalKcal == 700.0
                            },
                        )
                    }
                }

                Then("점수는 동기로 계산되고 피드백 상태는 PENDING이다") {
                    verify {
                        repository.save(
                            match { it.score == 76 && it.status == AnalysisStatus.PENDING && it.feedback == null },
                        )
                    }
                }

                Then("확정 시점의 몸무게·목표가 스냅샷으로 복사된다") {
                    verify {
                        repository.save(
                            match { it.weightKg == 70.0 && it.targetKcal == 2509 && it.targetCarbsG == 345 },
                        )
                    }
                }

                Then("임시 분석 레코드는 지운다") {
                    verify { analysisRepository.delete(analysis) }
                }
            }

            When("인식이 아직 끝나지 않은 분석이면") {
                val pending =
                    MealAnalysis(user = user, status = AnalysisStatus.PENDING, resultJson = "{\"photos\":[]}").withId(4L)
                every { analysisService.requireOwned(user, 4L) } returns pending

                Then("ANALYSIS_NOT_CONFIRMABLE — 확인하지 않은 결과를 확정할 수 없다") {
                    val e =
                        shouldThrow<CustomException> {
                            service.confirm(
                                "testuser",
                                MealConfirmRequest(LocalDate.of(2026, 7, 29), MealType.LUNCH, 4L, userItems),
                            )
                        }
                    e.errorCode shouldBe DietErrorCode.ANALYSIS_NOT_CONFIRMABLE
                }
            }

            When("항목이 비어 있으면") {
                val analysis = completedAnalysis(13L)
                every { analysisService.requireOwned(user, 3L) } returns analysis

                Then("INVALID_REQUEST — 빈 끼니는 만들지 않는다") {
                    shouldThrow<CustomException> {
                        service.confirm(
                            "testuser",
                            MealConfirmRequest(LocalDate.of(2026, 7, 29), MealType.LUNCH, 3L, emptyList()),
                        )
                    }
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*MealConfirmTest*"`
Expected: FAIL — `Unresolved reference: MealService`

- [ ] **Step 3: 엔티티 3종 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/Meal.kt`:

```kotlin
package com.toy.backend.diet.meal

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.profile.NutritionTargets
import com.toy.backend.user.User
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.LocalDate

enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK }

/**
 * **확정된 끼니만 존재한다.** 인식만 되고 확정되지 않은 결과는 `MealAnalysis`에 있고 `Meal`이 되지
 * 않는다 — 덕분에 하루 집계·점수·음식 빈도 쿼리에 "확정된 것만" 조건을 붙일 필요가 없다.
 *
 * `status`는 **피드백 생성 상태**다(PENDING → COMPLETED/FAILED). 확정 시점에 점수는 동기로
 * 계산되고 피드백만 뒤에서 생성되므로, iOS는 이 값으로 피드백 도착을 폴링한다.
 */
@Entity
@Table(
    name = "meals",
    indexes = [
        Index(name = "idx_meals_user_date", columnList = "user_id, date"),
    ],
)
class Meal(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(nullable = false)
    var date: LocalDate,
    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, columnDefinition = "varchar(20)")
    var mealType: MealType,
    /** 확정 시점 몸무게 — 이 끼니가 몇 kg 기준으로 채점됐는지 설명할 수 있어야 한다. */
    @Column(name = "weight_kg", nullable = false)
    var weightKg: Double,
    @Column(name = "target_kcal", nullable = false)
    var targetKcal: Int,
    @Column(name = "target_carbs_g", nullable = false)
    var targetCarbsG: Int,
    @Column(name = "target_protein_g", nullable = false)
    var targetProteinG: Int,
    @Column(name = "target_fat_g", nullable = false)
    var targetFatG: Int,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    var status: AnalysisStatus = AnalysisStatus.PENDING,
    @Column
    var score: Int? = null,
    @Column(name = "total_kcal", nullable = false)
    var totalKcal: Double = 0.0,
    @Column(name = "carbs_g", nullable = false)
    var carbsG: Double = 0.0,
    @Column(name = "protein_g", nullable = false)
    var proteinG: Double = 0.0,
    @Column(name = "fat_g", nullable = false)
    var fatG: Double = 0.0,
    @Column(columnDefinition = "text")
    var feedback: String? = null,
) : BaseEntity() {
    @OneToMany(mappedBy = "meal", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder asc")
    var photos: MutableList<MealPhoto> = mutableListOf()

    @OneToMany(mappedBy = "meal", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("id asc")
    var items: MutableList<MealItem> = mutableListOf()

    /** 항목은 항상 전체 교체다 — 확정과 수정이 같은 경로를 쓰도록 편집 로직을 한 벌만 만든다. */
    fun replaceItems(newItems: List<MealItem>) {
        items.clear()
        items.addAll(newItems)
        totalKcal = items.sumOf { it.kcal }
        carbsG = items.sumOf { it.carbsG }
        proteinG = items.sumOf { it.proteinG }
        fatG = items.sumOf { it.fatG }
    }

    fun addPhoto(photo: MealPhoto) {
        photos.add(photo)
    }

    fun applyScore(score: Int?) {
        this.score = score
    }

    /** 피드백 호출이 실패해도 점수는 살린다 — 점수가 피드백보다 중요하다. */
    fun markFeedback(feedback: String?) {
        this.feedback = feedback
        this.status = if (feedback == null) AnalysisStatus.FAILED else AnalysisStatus.COMPLETED
    }

    fun markFeedbackPending() {
        this.feedback = null
        this.status = AnalysisStatus.PENDING
    }

    fun targets(): NutritionTargets = NutritionTargets(targetKcal, targetCarbsG, targetProteinG, targetFatG)
}
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealItem.kt`:

```kotlin
package com.toy.backend.diet.meal

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.diet.NutritionSource
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * 끼니 안의 개별 음식. **별도 테이블로 쪼개는 게 이 설계의 핵심이다** — 영양소를 `Meal`에 뭉쳐
 * 저장하면 ① 음식별 빈도 집계("이번 주 제육볶음 3회")가 불가능하고 ② 인식이 틀렸을 때
 * 항목 단위로 수정·재계산할 수 없다.
 */
@Entity
@Table(
    name = "meal_items",
    indexes = [
        Index(name = "idx_meal_items_meal", columnList = "meal_id"),
        Index(name = "idx_meal_items_food_code", columnList = "food_code"),
    ],
)
class MealItem(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_id", nullable = false)
    var meal: Meal,
    @Column(name = "food_name", nullable = false, length = 200)
    var foodName: String,
    @Column(name = "food_code", length = 30)
    var foodCode: String? = null,
    @Column(name = "quantity_g", nullable = false)
    var quantityG: Double,
    @Column(nullable = false)
    var kcal: Double,
    @Column(name = "carbs_g", nullable = false)
    var carbsG: Double,
    @Column(name = "protein_g", nullable = false)
    var proteinG: Double,
    @Column(name = "fat_g", nullable = false)
    var fatG: Double,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    var source: NutritionSource,
) : BaseEntity()
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealPhoto.kt`:

```kotlin
package com.toy.backend.diet.meal

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(
    name = "meal_photos",
    indexes = [
        Index(name = "idx_meal_photos_meal", columnList = "meal_id"),
    ],
)
class MealPhoto(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_id", nullable = false)
    var meal: Meal,
    @Column(name = "file_id", nullable = false)
    var fileId: Long,
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int,
) : BaseEntity()
```

- [ ] **Step 4: 리포지토리와 DTO 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealRepository.kt`:

```kotlin
package com.toy.backend.diet.meal

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface MealRepository : JpaRepository<Meal, Long> {
    fun findByUserAndDateBetweenOrderByDateAscIdAsc(
        user: User,
        from: LocalDate,
        to: LocalDate,
    ): List<Meal>

    /** 하루 목표는 **첫 끼니의 스냅샷**에서 읽으므로 정렬이 의미를 갖는다. */
    fun findByUserAndDateOrderByCreatedAtAscIdAsc(
        user: User,
        date: LocalDate,
    ): List<Meal>
}
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealDtos.kt`:

```kotlin
package com.toy.backend.diet.meal

import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.diet.score.MealScoreBasis
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.LocalDate

/**
 * 확정 요청에 `fileIds`를 다시 보내지 않는다 — `analysisId`로 서버가 사진 목록을 안다.
 * 클라이언트가 파일 목록을 재구성하다 인식에 쓴 사진과 어긋나는 경로를 없앤다.
 */
data class MealConfirmRequest(
    val date: LocalDate,
    val mealType: MealType,
    val analysisId: Long,
    @field:NotEmpty @field:Valid
    val items: List<MealItemRequest>,
)

data class MealItemsRequest(
    @field:NotEmpty @field:Valid
    val items: List<MealItemRequest>,
)

/** 서버는 인식 결과와 대조하지 않고 그대로 신뢰한다 — 확인 단계의 존재 이유가 사용자 판단을 최종으로 삼는 것이다. */
data class MealItemRequest(
    @field:Size(max = 200)
    val foodName: String,
    @field:Size(max = 30)
    val foodCode: String? = null,
    @field:PositiveOrZero val quantityG: Double,
    @field:PositiveOrZero val kcal: Double,
    @field:PositiveOrZero val carbsG: Double,
    @field:PositiveOrZero val proteinG: Double,
    @field:PositiveOrZero val fatG: Double,
    val source: NutritionSource = NutritionSource.LLM_ESTIMATED,
)

data class MealItemResponse(
    val id: Long,
    val foodName: String,
    val foodCode: String?,
    val quantityG: Double,
    val kcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val source: NutritionSource,
)

data class MealPhotoResponse(
    val fileId: Long,
    val url: String?,
    val sortOrder: Int,
)

data class MealResponse(
    val id: Long,
    val date: LocalDate,
    val mealType: MealType,
    val status: AnalysisStatus,
    val score: Int?,
    val scoreBasis: MealScoreBasis?,
    val totalKcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val feedback: String?,
    val weightKg: Double,
    val targetKcal: Int,
    val photos: List<MealPhotoResponse>,
    val items: List<MealItemResponse>,
)

fun MealItemRequest.toEntity(meal: Meal): MealItem =
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
    )

/**
 * 점수 근거는 저장하지 않고 저장된 매크로에서 다시 계산한다 — 같은 입력에서 같은 값이 나오는
 * 순수 함수라 중복 저장할 이유가 없다. 감점 기울기를 튜닝하면 과거 끼니의 근거 표시도 함께 따라온다.
 */
fun Meal.toResponse(urls: Map<Long, String>): MealResponse =
    MealResponse(
        id = requiredId,
        date = date,
        mealType = mealType,
        status = status,
        score = score,
        scoreBasis = DietScoreCalculator.scoreMeal(carbsG, proteinG, fatG).basis,
        totalKcal = totalKcal,
        carbsG = carbsG,
        proteinG = proteinG,
        fatG = fatG,
        feedback = feedback,
        weightKg = weightKg,
        targetKcal = targetKcal,
        photos = photos.map { MealPhotoResponse(fileId = it.fileId, url = urls[it.fileId], sortOrder = it.sortOrder) },
        items =
            items.map {
                MealItemResponse(
                    id = it.requiredId,
                    foodName = it.foodName,
                    foodCode = it.foodCode,
                    quantityG = it.quantityG,
                    kcal = it.kcal,
                    carbsG = it.carbsG,
                    proteinG = it.proteinG,
                    fatG = it.fatG,
                    source = it.source,
                )
            },
    )
```

- [ ] **Step 5: 확정·조회 서비스 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealService.kt`:

```kotlin
package com.toy.backend.diet.meal

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.analysis.AnalysisResult
import com.toy.backend.diet.analysis.MealAnalysisRepository
import com.toy.backend.diet.analysis.MealAnalysisService
import com.toy.backend.diet.profile.NutritionProfileService
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.file.FileService
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class MealService(
    private val repository: MealRepository,
    private val userRepository: UserRepository,
    private val profileService: NutritionProfileService,
    private val analysisService: MealAnalysisService,
    private val analysisRepository: MealAnalysisRepository,
    private val fileService: FileService,
    private val objectMapper: ObjectMapper,
) {
    /**
     * 확정. **점수는 동기, 피드백은 비동기다**(피드백 연결은 별도 단계에서 붙인다) —
     * 점수는 룰 기반이라 즉시 나오고 사용자가 바로 봐야 하는 값이지만, 피드백은 LLM 텍스트 호출이라
     * 수 초 걸린다. 확정 응답을 붙잡을 이유가 없다.
     *
     * `attachFile`이 실패하면 트랜잭션 전체가 롤백된다. detach 전환 덕에 이미 attach된 사진의
     * S3 객체는 사라지지 않고, 커밋되지 않았으므로 `TEMP`로 남아 정리 배치가 수거한다.
     */
    @Transactional
    fun confirm(
        username: String,
        request: MealConfirmRequest,
    ): Long {
        if (request.items.isEmpty()) throw CustomException(ErrorCode.INVALID_REQUEST, "항목이 비어 있습니다")
        val user = findUser(username)
        val profile = profileService.requireProfile(user)
        val analysis = analysisService.requireOwned(user, request.analysisId)
        if (analysis.status == AnalysisStatus.PENDING) {
            throw CustomException(DietErrorCode.ANALYSIS_NOT_CONFIRMABLE, request.analysisId)
        }

        val meal =
            Meal(
                user = user,
                date = request.date,
                mealType = request.mealType,
                // 확정 시점 스냅샷 — 나중에 몸무게를 갱신해도 이 끼니가 속한 날의 점수는 흔들리지 않는다.
                weightKg = profile.weightKg,
                targetKcal = profile.targetKcal,
                targetCarbsG = profile.targetCarbsG,
                targetProteinG = profile.targetProteinG,
                targetFatG = profile.targetFatG,
            )
        applyItems(meal, request.items)

        val photos = objectMapper.readValue<AnalysisResult>(analysis.resultJson).photos
        photos.forEachIndexed { index, photo ->
            fileService.attachFile(photo.fileId, MEAL_FILE_PREFIX)
            meal.addPhoto(MealPhoto(meal = meal, fileId = photo.fileId, sortOrder = index))
        }

        val saved = repository.save(meal)
        analysisRepository.delete(analysis)
        return saved.requiredId
    }

    fun get(
        username: String,
        id: Long,
    ): MealResponse {
        val meal = requireOwned(findUser(username), id)
        return meal.toResponse(fileService.getPresignedUrls(meal.photos.map { it.fileId }))
    }

    fun list(
        username: String,
        from: LocalDate,
        to: LocalDate,
    ): List<MealResponse> {
        if (from.isAfter(to)) throw CustomException(ErrorCode.INVALID_REQUEST, "from이 to보다 이후일 수 없습니다")
        val meals = repository.findByUserAndDateBetweenOrderByDateAscIdAsc(findUser(username), from, to)
        // 목록에서 사진마다 presign 하면 N+1이다 — 한 번에 받는다.
        val urls = fileService.getPresignedUrls(meals.flatMap { meal -> meal.photos.map { it.fileId } })
        return meals.map { it.toResponse(urls) }
    }

    /** 항목 교체 → 영양소 합산 → 점수 재계산. 확정과 수정이 이 한 곳을 공유한다. */
    fun applyItems(
        meal: Meal,
        items: List<MealItemRequest>,
    ) {
        meal.replaceItems(items.map { it.toEntity(meal) })
        meal.applyScore(DietScoreCalculator.scoreMeal(meal.carbsG, meal.proteinG, meal.fatG).score)
    }

    fun requireOwned(
        user: User,
        id: Long,
    ): Meal {
        val meal =
            repository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        if (meal.user.requiredId != user.requiredId) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }
        return meal
    }

    fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    companion object {
        private const val MEAL_FILE_PREFIX = "meals/"
    }
}
```

- [ ] **Step 6: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*MealConfirmTest*"`
Expected: PASS (3 When)

- [ ] **Step 7: 컨트롤러 작성 (확정·조회만)**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealController.kt`:

```kotlin
package com.toy.backend.diet.meal

import com.toy.backend.common.annotation.ResponseCreated
import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "끼니", description = "확정된 끼니 기록")
@RestController
@RequestMapping("/diet/meals")
class MealController(
    private val service: MealService,
) {
    @PostMapping
    @ResponseCreated("/diet/meals/{id}")
    @Operation(summary = "끼니 확정 — 사용자가 확인·수정한 항목을 최종본으로 받는다")
    fun confirm(
        @Valid @RequestBody request: MealConfirmRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.confirm(authentication.name, request))

    @GetMapping("/{id}")
    @Operation(summary = "끼니 단건 조회 (피드백 완료 폴링용)")
    fun get(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<MealResponse>> =
        ResponseEntity.ok(DataResponseBody(service.get(authentication.name, id)))

    @GetMapping
    @Operation(summary = "기간별 끼니 목록")
    fun list(
        @Parameter(description = "시작일", example = "2026-07-01")
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @Parameter(description = "종료일", example = "2026-07-31")
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<List<MealResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.list(authentication.name, from, to)))
}
```

- [ ] **Step 8: 포맷·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet apps/daily-record/src/test/kotlin/com/toy/backend/diet
git commit -m "feat: 끼니 확정과 조회 추가"
```

---

### Task 10: 하루 활동 에너지 (`DailyActivity`)

iOS가 HealthKit에서 읽어 올린 활동 에너지를 날짜별로 upsert 한다. **표시·피드백 맥락으로만 쓰고 목표에는 반영하지 않는다** — 목표가 매일 흔들리면 점수를 설명할 수 없다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyActivity.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyActivityRepository.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyActivityService.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyActivityController.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/daily/DailyActivityServiceTest.kt`

**Interfaces:**
- Consumes: `UserRepository.findByUsername`
- Produces:
  - `class DailyActivity(user, date, activeEnergyKcal: Int)` — `updateEnergy(Int)`
  - `DailyActivityRepository.findByUserAndDate(User, LocalDate): DailyActivity?`
  - `data class ActivityUpsertRequest(date: LocalDate, activeEnergyKcal: Int)`
  - `DailyActivityService.upsert(username, request)`

- [ ] **Step 1: 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/daily/DailyActivityServiceTest.kt`:

```kotlin
package com.toy.backend.diet.daily

import com.toy.backend.common.entity.withId
import com.toy.backend.diet.dietUser
import com.toy.backend.user.UserRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate

class DailyActivityServiceTest :
    BehaviorSpec({
        val repository = mockk<DailyActivityRepository>()
        val userRepository = mockk<UserRepository>()
        val service = DailyActivityService(repository, userRepository)

        val user = dietUser()
        val date = LocalDate.of(2026, 7, 29)

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("활동 에너지 upsert") {
            When("그날 기록이 없으면") {
                every { repository.findByUserAndDate(user, date) } returns null
                every { repository.save(any()) } answers { (firstArg() as DailyActivity).withId(1L) }

                service.upsert("testuser", ActivityUpsertRequest(date = date, activeEnergyKcal = 420))

                Then("새로 저장한다") {
                    verify { repository.save(match { it.activeEnergyKcal == 420 && it.date == date }) }
                }
            }

            When("그날 기록이 있으면") {
                val existing = DailyActivity(user = user, date = date, activeEnergyKcal = 100).withId(2L)
                every { repository.findByUserAndDate(user, date) } returns existing

                service.upsert("testuser", ActivityUpsertRequest(date = date, activeEnergyKcal = 550))

                Then("값만 갱신하고 새로 만들지 않는다") {
                    existing.activeEnergyKcal shouldBe 550
                    verify(exactly = 0) { repository.save(any()) }
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*DailyActivityServiceTest*"`
Expected: FAIL — `Unresolved reference: DailyActivityService`

- [ ] **Step 3: 엔티티·리포지토리·서비스 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyActivity.kt`:

```kotlin
package com.toy.backend.diet.daily

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate

/** iOS가 HealthKit에서 읽어 올린 하루 활동 에너지. 목표 계산에는 반영하지 않는다. */
@Entity
@Table(
    name = "daily_activities",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_daily_activities_user_date", columnNames = ["user_id", "date"]),
    ],
)
class DailyActivity(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(nullable = false)
    var date: LocalDate,
    @Column(name = "active_energy_kcal", nullable = false)
    var activeEnergyKcal: Int,
) : BaseEntity() {
    fun updateEnergy(activeEnergyKcal: Int) {
        this.activeEnergyKcal = activeEnergyKcal
    }
}
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyActivityRepository.kt`:

```kotlin
package com.toy.backend.diet.daily

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface DailyActivityRepository : JpaRepository<DailyActivity, Long> {
    fun findByUserAndDate(
        user: User,
        date: LocalDate,
    ): DailyActivity?
}
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyActivityService.kt`:

```kotlin
package com.toy.backend.diet.daily

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

data class ActivityUpsertRequest(
    val date: LocalDate,
    @field:PositiveOrZero val activeEnergyKcal: Int,
)

@Service
@Transactional(readOnly = true)
class DailyActivityService(
    private val repository: DailyActivityRepository,
    private val userRepository: UserRepository,
) {
    @Transactional
    fun upsert(
        username: String,
        request: ActivityUpsertRequest,
    ) {
        val user = findUser(username)
        val existing = repository.findByUserAndDate(user, request.date)
        if (existing != null) {
            existing.updateEnergy(request.activeEnergyKcal)
            return
        }
        repository.save(
            DailyActivity(user = user, date = request.date, activeEnergyKcal = request.activeEnergyKcal),
        )
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*DailyActivityServiceTest*"`
Expected: PASS

- [ ] **Step 5: 컨트롤러 작성**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyActivityController.kt`:

```kotlin
package com.toy.backend.diet.daily

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "식단 활동 에너지", description = "HealthKit 활동 에너지 upsert")
@RestController
@RequestMapping("/diet/activity")
class DailyActivityController(
    private val service: DailyActivityService,
) {
    @PutMapping
    @Operation(summary = "하루 활동 에너지 저장 (upsert)")
    fun upsert(
        @Valid @RequestBody request: ActivityUpsertRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.upsert(authentication.name, request)
        return ResponseEntity.noContent().build()
    }
}
```

- [ ] **Step 6: 포맷·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily apps/daily-record/src/test/kotlin/com/toy/backend/diet/daily
git commit -m "feat: 하루 활동 에너지 기록 추가"
```

---

### Task 11: 피드백 생성기와 끼니 피드백 연결

텍스트 모델로 문장만 만든다. 수치는 이미 다 계산돼 있으므로 LLM에게 계산을 맡기지 않는다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DietFeedbackPrompts.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DietFeedbackGenerator.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealService.kt` (확정 후 피드백 트리거)
- Modify: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/MealConfirmTest.kt` (생성자 인자 추가 + 트리거 검증)
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/feedback/DietFeedbackGeneratorTest.kt`

**Interfaces:**
- Consumes: `OpenRouterClient.generateText`(Task 6), `MealRepository`(Task 9), `DailyActivityRepository`(Task 10), `runAfterCommit`(Task 8)
- Produces:
  - `object DietFeedbackPrompts` — `SYSTEM_PROMPT`, `meal(meal, cumulative, activeEnergyKcal): String`, `day(meals, totals, targets, dayScore, activeEnergyKcal): String`
  - `data class NutritionTotals(kcal: Double, carbsG: Double, proteinG: Double, fatG: Double)`
  - `DietFeedbackGenerator.generateForMeal(mealId: Long)`, `.generateForDay(meals, totals, targets, dayScore, activeEnergyKcal): String?`

- [ ] **Step 1: 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/feedback/DietFeedbackGeneratorTest.kt`:

```kotlin
package com.toy.backend.diet.feedback

import com.toy.backend.common.entity.withAudit
import com.toy.backend.common.entity.withId
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.daily.DailyActivity
import com.toy.backend.diet.daily.DailyActivityRepository
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.llm.OpenRouterClient
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealItem
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.meal.MealType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.springframework.data.repository.findByIdOrNull
import java.time.LocalDate
import java.time.LocalDateTime

class DietFeedbackGeneratorTest :
    BehaviorSpec({
        val mealRepository = mockk<MealRepository>()
        val activityRepository = mockk<DailyActivityRepository>()
        val client = mockk<OpenRouterClient>()

        val user = dietUser()
        val date = LocalDate.of(2026, 7, 29)

        fun meal(
            id: Long,
            mealType: MealType,
            kcal: Double,
            proteinG: Double,
            createdAt: LocalDateTime,
        ): Meal {
            val meal =
                Meal(
                    user = user,
                    date = date,
                    mealType = mealType,
                    weightKg = 70.0,
                    targetKcal = 2509,
                    targetCarbsG = 345,
                    targetProteinG = 94,
                    targetFatG = 84,
                ).withId(id).withAudit(createdAt = createdAt)
            meal.replaceItems(
                listOf(
                    MealItem(
                        meal = meal,
                        foodName = "제육볶음",
                        foodCode = "D1",
                        quantityG = 200.0,
                        kcal = kcal,
                        carbsG = 40.0,
                        proteinG = proteinG,
                        fatG = 15.0,
                        source = NutritionSource.DB_MATCHED,
                    ),
                ),
            )
            return meal
        }

        Given("끼니 피드백 생성") {
            val lunch = meal(2L, MealType.LUNCH, 600.0, 18.0, LocalDateTime.of(2026, 7, 29, 12, 30))
            val breakfast = meal(1L, MealType.BREAKFAST, 400.0, 10.0, LocalDateTime.of(2026, 7, 29, 8, 0))

            When("호출이 성공하면") {
                val generator = DietFeedbackGenerator(mealRepository, activityRepository, client)
                every { mealRepository.findByIdOrNull(2L) } returns lunch
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(breakfast, lunch)
                every { activityRepository.findByUserAndDate(user, date) } returns
                    DailyActivity(user = user, date = date, activeEnergyKcal = 350).withId(1L)
                val prompt = slot<String>()
                every { client.generateText(any(), capture(prompt)) } returns "잘 드셨어요. 단백질이 부족합니다. 저녁에 닭가슴살을 곁들여 보세요."

                generator.generateForMeal(2L)

                Then("피드백이 채워지고 상태가 COMPLETED가 된다") {
                    lunch.feedback shouldContain "닭가슴살"
                    lunch.status shouldBe AnalysisStatus.COMPLETED
                }

                Then("그날 지금까지의 누적 섭취량이 프롬프트에 담긴다 — 한 끼만 보고 말하지 않는다") {
                    prompt.captured shouldContain "누적"
                    prompt.captured shouldContain "1000" // 400 + 600 kcal
                    prompt.captured shouldContain "28" // 10 + 18 g 단백질
                }

                Then("활동 에너지도 맥락으로 넘긴다") {
                    prompt.captured shouldContain "350"
                }
            }

            When("호출이 실패하면") {
                val generator = DietFeedbackGenerator(mealRepository, activityRepository, client)
                val dinner = meal(3L, MealType.DINNER, 700.0, 30.0, LocalDateTime.of(2026, 7, 29, 19, 0))
                every { mealRepository.findByIdOrNull(3L) } returns dinner
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(dinner)
                every { activityRepository.findByUserAndDate(user, date) } returns null
                every { client.generateText(any(), any()) } returns null

                generator.generateForMeal(3L)

                Then("점수는 살리고 상태만 FAILED — 점수가 피드백보다 중요하다") {
                    dinner.feedback shouldBe null
                    dinner.status shouldBe AnalysisStatus.FAILED
                    dinner.totalKcal shouldBe 700.0
                }
            }

            When("API 키가 없어 클라이언트 빈이 없으면") {
                val generator = DietFeedbackGenerator(mealRepository, activityRepository, null)
                val snack = meal(4L, MealType.SNACK, 200.0, 5.0, LocalDateTime.of(2026, 7, 29, 15, 0))
                every { mealRepository.findByIdOrNull(4L) } returns snack

                generator.generateForMeal(4L)

                Then("호출을 건너뛰고 FAILED로 둔다 — 로컬에서도 확정 자체는 성공해야 한다") {
                    snack.feedback shouldBe null
                    snack.status shouldBe AnalysisStatus.FAILED
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*DietFeedbackGeneratorTest*"`
Expected: FAIL — `Unresolved reference: DietFeedbackGenerator`

- [ ] **Step 3: 프롬프트 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DietFeedbackPrompts.kt`:

```kotlin
package com.toy.backend.diet.feedback

import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.profile.NutritionTargets
import kotlin.math.roundToInt

data class NutritionTotals(
    val kcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
)

fun List<Meal>.totals(): NutritionTotals =
    NutritionTotals(
        kcal = sumOf { it.totalKcal },
        carbsG = sumOf { it.carbsG },
        proteinG = sumOf { it.proteinG },
        fatG = sumOf { it.fatG },
    )

object DietFeedbackPrompts {
    /**
     * **3요소를 강제한다.** ③을 강제하지 않으면 "골고루 드세요"류로 흐른다.
     * 의학적 진단·처방은 금지 항목으로 명시한다 — 앱이 의료기기가 아니다.
     */
    const val SYSTEM_PROMPT =
        "당신은 식단 코치입니다. 아래 형식을 반드시 지켜 한국어 존댓말로 2~3문장만 쓰세요.\n" +
            "① 잘한 점 1개 ② 부족하거나 과다한 점 1개 ③ 구체적인 음식 이름이 담긴 개선 행동 1개.\n" +
            "금지: 의학적 진단·처방, 특정 질환 언급, 영양제 권유, 숫자 나열만 하는 문장, 목록 기호."

    fun meal(
        meal: Meal,
        cumulative: NutritionTotals,
        targets: NutritionTargets,
        activeEnergyKcal: Int?,
    ): String =
        buildString {
            appendLine("[이번 끼니] ${meal.mealType}")
            meal.items.forEach {
                appendLine("- ${it.foodName} ${it.quantityG.roundToInt()}g / ${it.kcal.roundToInt()}kcal " +
                    "(탄 ${it.carbsG.roundToInt()}g, 단 ${it.proteinG.roundToInt()}g, 지 ${it.fatG.roundToInt()}g)")
            }
            appendLine("이번 끼니 합계: ${meal.totalKcal.roundToInt()}kcal, 탄 ${meal.carbsG.roundToInt()}g, " +
                "단 ${meal.proteinG.roundToInt()}g, 지 ${meal.fatG.roundToInt()}g")
            appendLine("이번 끼니 균형 점수: ${meal.score ?: "산출 불가"}")
            appendLine(
                "[오늘 누적] ${cumulative.kcal.roundToInt()}kcal, 탄 ${cumulative.carbsG.roundToInt()}g, " +
                    "단 ${cumulative.proteinG.roundToInt()}g, 지 ${cumulative.fatG.roundToInt()}g",
            )
            appendLine(
                "[오늘 목표] ${targets.kcal}kcal, 탄 ${targets.carbsG}g, 단 ${targets.proteinG}g, 지 ${targets.fatG}g",
            )
            activeEnergyKcal?.let { appendLine("[활동 에너지] ${it}kcal") }
        }

    fun day(
        meals: List<Meal>,
        totals: NutritionTotals,
        targets: NutritionTargets,
        dayScore: Int,
        activeEnergyKcal: Int?,
    ): String =
        buildString {
            appendLine("[오늘 먹은 끼니]")
            meals.forEach { meal ->
                appendLine("- ${meal.mealType}: ${meal.items.joinToString(", ") { it.foodName }} " +
                    "(${meal.totalKcal.roundToInt()}kcal)")
            }
            appendLine(
                "[총 섭취] ${totals.kcal.roundToInt()}kcal, 탄 ${totals.carbsG.roundToInt()}g, " +
                    "단 ${totals.proteinG.roundToInt()}g, 지 ${totals.fatG.roundToInt()}g",
            )
            appendLine("[목표] ${targets.kcal}kcal, 탄 ${targets.carbsG}g, 단 ${targets.proteinG}g, 지 ${targets.fatG}g")
            appendLine("[하루 점수] $dayScore")
            activeEnergyKcal?.let { appendLine("[활동 에너지] ${it}kcal") }
        }
}
```

- [ ] **Step 4: 생성기 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DietFeedbackGenerator.kt`:

```kotlin
package com.toy.backend.diet.feedback

import com.toy.backend.diet.daily.DailyActivityRepository
import com.toy.backend.diet.llm.OpenRouterClient
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.profile.NutritionTargets
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

/**
 * 텍스트 모델로 문장만 만든다. 이미지 호출이 비싼 부분이고 텍스트 호출은 훨씬 저렴하므로,
 * 정확한 수치를 얻은 뒤 2차로 나눠 부르는 비용이 크지 않다.
 */
@Component
class DietFeedbackGenerator(
    private val mealRepository: MealRepository,
    private val activityRepository: DailyActivityRepository,
    @Autowired(required = false) private val client: OpenRouterClient?,
) {
    /**
     * 끼니 피드백은 **확정 시점**에 만든다. 인식 직후가 아니라 사용자가 항목을 고친 뒤라야
     * 실제로 먹은 것에 대한 조언이 된다. `@Async`라 엔티티가 아닌 id를 받아 다시 조회한다.
     */
    @Async
    @Transactional
    fun generateForMeal(mealId: Long) {
        val meal = mealRepository.findByIdOrNull(mealId) ?: return log.warn { "피드백 대상 끼니가 없다: id=$mealId" }
        val openRouter = client
        if (openRouter == null) {
            log.warn { "OpenRouter 미설정 — 끼니 피드백을 건너뛴다: id=$mealId" }
            return meal.markFeedback(null)
        }

        // 누적치를 함께 넘기므로 한 끼만 보고 말하지 않고 하루 맥락이 담긴 조언이 나온다.
        val sameDay = mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(meal.user, meal.date)
        val cumulative = sameDay.filter { !it.createdAt.isAfter(meal.createdAt) }.totals()
        val activeEnergy = activityRepository.findByUserAndDate(meal.user, meal.date)?.activeEnergyKcal

        val prompt = DietFeedbackPrompts.meal(meal, cumulative, meal.targets(), activeEnergy)
        meal.markFeedback(openRouter.generateText(DietFeedbackPrompts.SYSTEM_PROMPT, prompt))
    }

    /** 하루 마감 피드백. 호출자가 캐시를 관리하므로 여기서는 문장만 만들어 돌려준다(실패 시 null). */
    fun generateForDay(
        meals: List<Meal>,
        totals: NutritionTotals,
        targets: NutritionTargets,
        dayScore: Int,
        activeEnergyKcal: Int?,
    ): String? {
        val openRouter = client ?: return null
        return openRouter.generateText(
            DietFeedbackPrompts.SYSTEM_PROMPT,
            DietFeedbackPrompts.day(meals, totals, targets, dayScore, activeEnergyKcal),
        )
    }
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*DietFeedbackGeneratorTest*"`
Expected: PASS (3 When)

- [ ] **Step 6: 확정 흐름에 피드백 연결**

`MealService`에 의존성을 추가한다 — 생성자 마지막 인자로:

```kotlin
    private val feedbackGenerator: DietFeedbackGenerator,
```

`confirm`의 `analysisRepository.delete(analysis)` 다음 줄에 추가한다:

```kotlin
        // 커밋 뒤에 시작해야 비동기 스레드가 저장된 끼니를 볼 수 있다.
        runAfterCommit { feedbackGenerator.generateForMeal(saved.requiredId) }
```

import 추가: `com.toy.backend.diet.feedback.DietFeedbackGenerator`, `com.toy.backend.diet.runAfterCommit`.

- [ ] **Step 7: 기존 확정 테스트에 생성자 인자·검증 추가**

`MealConfirmTest.kt`에서 목을 하나 추가하고 서비스 생성자에 넘긴다:

```kotlin
        val feedbackGenerator = mockk<DietFeedbackGenerator>()
        val service =
            MealService(
                repository,
                userRepository,
                profileService,
                analysisService,
                analysisRepository,
                fileService,
                objectMapper,
                feedbackGenerator,
            )
```

`beforeContainer`에 추가:

```kotlin
            justRun { feedbackGenerator.generateForMeal(any()) }
```

"사진 2장짜리 분석을 확정하면" 블록 끝에 `Then`을 추가한다:

```kotlin
                Then("피드백 생성이 커밋 뒤에 예약된다 — 트랜잭션이 없는 단위 테스트에서는 즉시 실행된다") {
                    verify { feedbackGenerator.generateForMeal(50L) }
                }
```

- [ ] **Step 8: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*MealConfirmTest*" --tests "*DietFeedbackGeneratorTest*"`
Expected: PASS

- [ ] **Step 9: 포맷·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src
git commit -m "feat: 끼니 피드백 생성 추가"
```

---

### Task 12: 끼니 항목 교체·삭제·피드백 재시도

항목을 고치면 영양소·점수·피드백을 다시 만든다. 삭제는 파일을 물리 삭제하지 않고 detach 한다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealService.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealController.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/MealServiceTest.kt`

**Interfaces:**
- Consumes: `MealService.applyItems`·`requireOwned`(Task 9), `FileService.detachFiles`, `DietFeedbackGenerator.generateForMeal`(Task 11)
- Produces: `MealService.updateItems(username, id, MealItemsRequest)`, `.delete(username, id)`, `.retryFeedback(username, id)`

- [ ] **Step 1: 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/MealServiceTest.kt`:

```kotlin
package com.toy.backend.diet.meal

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.analysis.MealAnalysisRepository
import com.toy.backend.diet.analysis.MealAnalysisService
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.feedback.DietFeedbackGenerator
import com.toy.backend.diet.profile.NutritionProfileService
import com.toy.backend.file.FileService
import com.toy.backend.user.UserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate

class MealServiceTest :
    BehaviorSpec({
        val repository = mockk<MealRepository>()
        val userRepository = mockk<UserRepository>()
        val profileService = mockk<NutritionProfileService>()
        val analysisService = mockk<MealAnalysisService>()
        val analysisRepository = mockk<MealAnalysisRepository>()
        val fileService = mockk<FileService>()
        val feedbackGenerator = mockk<DietFeedbackGenerator>()
        val service =
            MealService(
                repository,
                userRepository,
                profileService,
                analysisService,
                analysisRepository,
                fileService,
                jacksonObjectMapper(),
                feedbackGenerator,
            )

        val user = dietUser()

        fun savedMeal(
            id: Long,
            status: AnalysisStatus = AnalysisStatus.COMPLETED,
            owner: com.toy.backend.user.User = user,
        ): Meal {
            val meal =
                Meal(
                    user = owner,
                    date = LocalDate.of(2026, 7, 29),
                    mealType = MealType.LUNCH,
                    weightKg = 70.0,
                    targetKcal = 2509,
                    targetCarbsG = 345,
                    targetProteinG = 94,
                    targetFatG = 84,
                    status = status,
                    feedback = if (status == AnalysisStatus.COMPLETED) "기존 피드백" else null,
                ).withId(id)
            meal.addPhoto(MealPhoto(meal = meal, fileId = 21L, sortOrder = 0).withId(id * 10))
            meal.replaceItems(
                listOf(
                    MealItem(
                        meal = meal,
                        foodName = "밥",
                        foodCode = null,
                        quantityG = 210.0,
                        kcal = 300.0,
                        carbsG = 70.0,
                        proteinG = 5.0,
                        fatG = 1.0,
                        source = NutritionSource.LLM_ESTIMATED,
                    ),
                ),
            )
            meal.applyScore(30)
            return meal
        }

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            justRun { feedbackGenerator.generateForMeal(any()) }
        }

        Given("항목 전체 교체") {
            When("균형 잡힌 항목으로 바꾸면") {
                val meal = savedMeal(60L)
                every { repository.findByIdOrNull(60L) } returns meal

                service.updateItems(
                    "testuser",
                    60L,
                    MealItemsRequest(
                        items =
                            listOf(
                                MealItemRequest(
                                    foodName = "제육볶음",
                                    foodCode = "D1",
                                    quantityG = 168.75,
                                    kcal = 700.0,
                                    carbsG = 168.75,
                                    proteinG = 18.0,
                                    fatG = 17.0,
                                    source = NutritionSource.DB_MATCHED,
                                ),
                            ),
                    ),
                )

                Then("영양소와 점수가 다시 계산된다") {
                    meal.items.size shouldBe 1
                    meal.items[0].foodName shouldBe "제육볶음"
                    meal.totalKcal shouldBe 700.0
                    meal.score shouldBe 76
                }

                Then("피드백은 낡았으므로 비우고 재생성한다") {
                    meal.feedback shouldBe null
                    meal.status shouldBe AnalysisStatus.PENDING
                    verify { feedbackGenerator.generateForMeal(60L) }
                }
            }

            When("타인 끼니면") {
                val other = dietUser(username = "other", id = 2L)
                every { repository.findByIdOrNull(61L) } returns savedMeal(61L, owner = other)

                Then("RESOURCE_NOT_FOUND") {
                    val e =
                        shouldThrow<CustomException> {
                            service.updateItems(
                                "testuser",
                                61L,
                                MealItemsRequest(
                                    listOf(
                                        MealItemRequest("밥", null, 100.0, 100.0, 20.0, 3.0, 1.0, NutritionSource.LLM_ESTIMATED),
                                    ),
                                ),
                            )
                        }
                    e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("끼니 삭제") {
            When("본인 끼니를 지우면") {
                val meal = savedMeal(62L)
                every { repository.findByIdOrNull(62L) } returns meal
                justRun { fileService.detachFiles(listOf(21L)) }
                justRun { repository.delete(meal) }

                service.delete("testuser", 62L)

                Then("사진은 물리 삭제하지 않고 detach 한다 — 롤백되면 파일도 함께 살아난다") {
                    verify { fileService.detachFiles(listOf(21L)) }
                    verify { repository.delete(meal) }
                }
            }
        }

        Given("피드백 재생성") {
            When("FAILED 상태면") {
                val meal = savedMeal(63L, status = AnalysisStatus.FAILED)
                every { repository.findByIdOrNull(63L) } returns meal

                service.retryFeedback("testuser", 63L)

                Then("PENDING으로 되돌리고 다시 생성한다") {
                    meal.status shouldBe AnalysisStatus.PENDING
                    verify { feedbackGenerator.generateForMeal(63L) }
                }
            }

            When("이미 COMPLETED면") {
                val meal = savedMeal(64L, status = AnalysisStatus.COMPLETED)
                every { repository.findByIdOrNull(64L) } returns meal

                Then("FEEDBACK_NOT_RETRYABLE — 비용이 나가는 호출을 중복으로 하지 않는다") {
                    val e = shouldThrow<CustomException> { service.retryFeedback("testuser", 64L) }
                    e.errorCode shouldBe DietErrorCode.FEEDBACK_NOT_RETRYABLE
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*MealServiceTest*"`
Expected: FAIL — `Unresolved reference: updateItems`

- [ ] **Step 3: 서비스에 세 메서드 추가**

`MealService`의 `applyItems` 앞에 추가한다:

```kotlin
    /** 항목을 고치면 영양소·점수를 다시 계산하고 피드백도 재생성한다 — 낡은 조언을 남기지 않는다. */
    @Transactional
    fun updateItems(
        username: String,
        id: Long,
        request: MealItemsRequest,
    ) {
        if (request.items.isEmpty()) throw CustomException(ErrorCode.INVALID_REQUEST, "항목이 비어 있습니다")
        val meal = requireOwned(findUser(username), id)
        applyItems(meal, request.items)
        meal.markFeedbackPending()
        runAfterCommit { feedbackGenerator.generateForMeal(id) }
    }

    /**
     * 삭제. **파일을 물리 삭제하지 않고 detach 한다** — 상태를 `TEMP`로 되돌리기만 하고 S3 객체는
     * 매일 04:00 정리 배치가 수거한다. 트랜잭션이 롤백되면 상태 변경도 함께 되돌아가므로
     * "레코드는 살아났는데 객체는 사라진" 상태가 생기지 않는다.
     */
    @Transactional
    fun delete(
        username: String,
        id: Long,
    ) {
        val meal = requireOwned(findUser(username), id)
        fileService.detachFiles(meal.photos.map { it.fileId })
        repository.delete(meal)
    }

    /** 자동 재시도를 넣지 않는 대신 수동 재시도를 연다. 실패 상태에서만 허용해 중복 호출을 막는다. */
    @Transactional
    fun retryFeedback(
        username: String,
        id: Long,
    ) {
        val meal = requireOwned(findUser(username), id)
        if (meal.status != AnalysisStatus.FAILED) throw CustomException(DietErrorCode.FEEDBACK_NOT_RETRYABLE, id)
        meal.markFeedbackPending()
        runAfterCommit { feedbackGenerator.generateForMeal(id) }
    }
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*MealServiceTest*"`
Expected: PASS (3 Given)

- [ ] **Step 5: 컨트롤러에 엔드포인트 추가**

`MealController`에 추가한다(import: `DeleteMapping`, `PutMapping`):

```kotlin
    @PutMapping("/{id}/items")
    @Operation(summary = "항목 전체 교체 — 영양소·점수·피드백을 재계산한다")
    fun updateItems(
        @PathVariable id: Long,
        @Valid @RequestBody request: MealItemsRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.updateItems(authentication.name, id, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "끼니 삭제 — 사진은 detach 후 정리 배치가 수거한다")
    fun delete(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.delete(authentication.name, id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "피드백 재생성 (FAILED 상태에서만)")
    fun retryFeedback(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.retryFeedback(authentication.name, id)
        return ResponseEntity.noContent().build()
    }
```

- [ ] **Step 6: 포맷·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src
git commit -m "feat: 끼니 항목 교체·삭제·피드백 재시도 추가"
```

---

### Task 13: 하루 집계와 마감 피드백 (lazy 생성 + 캐시 무효화)

하루 집계값은 테이블로 만들지 않고 `Meal` 합산으로 구한다. `DailyDietFeedback`은 LLM 호출 결과를 재사용하기 위한 캐시일 뿐이다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DailyDietFeedback.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DailyDietFeedbackRepository.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyDietDtos.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyDietService.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyDietController.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/daily/DailyDietServiceTest.kt`

**Interfaces:**
- Consumes: `MealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc`(Task 9), `DietScoreCalculator.scoreDay`(Task 3), `DietFeedbackGenerator.generateForDay`(Task 11), `DailyActivityRepository`(Task 10), `FileService.getPresignedUrls`
- Produces:
  - `class DailyDietFeedback(user, date, dayScore, feedback, generatedAt)` — `update(Int, String?, LocalDateTime)`
  - `data class DayResponse(date, dayScore, scoreBasis, feedback, totalKcal, carbsG, proteinG, fatG, activeEnergyKcal, meals)`
  - `DailyDietService.getDay(username, date): DayResponse`

- [ ] **Step 1: 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/daily/DailyDietServiceTest.kt`:

```kotlin
package com.toy.backend.diet.daily

import com.toy.backend.common.entity.withAudit
import com.toy.backend.common.entity.withId
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.feedback.DailyDietFeedback
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.DietFeedbackGenerator
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealItem
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.meal.MealType
import com.toy.backend.file.FileService
import com.toy.backend.user.UserRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalDateTime

class DailyDietServiceTest :
    BehaviorSpec({
        val mealRepository = mockk<MealRepository>()
        val feedbackRepository = mockk<DailyDietFeedbackRepository>()
        val activityRepository = mockk<DailyActivityRepository>()
        val userRepository = mockk<UserRepository>()
        val feedbackGenerator = mockk<DietFeedbackGenerator>()
        val fileService = mockk<FileService>()
        val service =
            DailyDietService(
                mealRepository,
                feedbackRepository,
                activityRepository,
                userRepository,
                feedbackGenerator,
                fileService,
            )

        val user = dietUser()
        val date = LocalDate.of(2026, 7, 29)

        /** 확정 시점 스냅샷이 다른 두 끼니 — 하루 목표는 첫 끼니 것을 써야 한다. */
        fun meal(
            id: Long,
            kcal: Double,
            targetKcal: Int,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime = createdAt,
        ): Meal {
            val meal =
                Meal(
                    user = user,
                    date = date,
                    mealType = MealType.LUNCH,
                    weightKg = 70.0,
                    targetKcal = targetKcal,
                    targetCarbsG = 275,
                    targetProteinG = 75,
                    targetFatG = 67,
                ).withId(id).withAudit(createdAt = createdAt, updatedAt = updatedAt)
            meal.replaceItems(
                listOf(
                    MealItem(
                        meal = meal,
                        foodName = "제육볶음",
                        foodCode = "D1",
                        quantityG = 200.0,
                        kcal = kcal,
                        carbsG = 137.5,
                        proteinG = 37.5,
                        fatG = 33.5,
                        source = NutritionSource.DB_MATCHED,
                    ).withId(id * 100),
                ),
            )
            return meal
        }
        // 항목에 id를 넣는 이유: 응답 변환(MealItemResponse)이 requiredId를 읽는다.

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            every { activityRepository.findByUserAndDate(user, date) } returns null
            every { fileService.getPresignedUrls(any()) } returns emptyMap()
        }

        Given("그날 끼니가 없으면") {
            When("하루를 조회하면") {
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns emptyList()

                val response = service.getDay("testuser", date)

                Then("dayScore는 null이고 피드백도 만들지 않는다") {
                    response.dayScore shouldBe null
                    response.scoreBasis shouldBe null
                    response.feedback shouldBe null
                    verify(exactly = 0) { feedbackGenerator.generateForDay(any(), any(), any(), any(), any()) }
                }
            }
        }

        Given("하루 목표의 출처") {
            When("첫 끼니와 나중 끼니의 목표 스냅샷이 다르면") {
                val first = meal(1L, 1000.0, targetKcal = 2000, createdAt = LocalDateTime.of(2026, 7, 29, 8, 0))
                val second = meal(2L, 1000.0, targetKcal = 1500, createdAt = LocalDateTime.of(2026, 7, 29, 19, 0))
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(first, second)
                every { feedbackRepository.findByUserAndDate(user, date) } returns null
                every { feedbackGenerator.generateForDay(any(), any(), any(), any(), any()) } returns "오늘 잘 드셨어요."
                every { feedbackRepository.save(any()) } answers { (firstArg() as DailyDietFeedback).withId(1L) }

                val response = service.getDay("testuser", date)

                Then("첫 끼니의 스냅샷 목표로 계산한다 — 몸무게를 갱신해도 과거 점수가 흔들리지 않는다") {
                    response.scoreBasis!!.calorie.targetKcal shouldBe 2000
                    response.scoreBasis!!.calorie.intakeKcal shouldBe 2000.0
                    response.dayScore shouldBe 100
                }
            }
        }

        Given("피드백 캐시") {
            // 끼니 하나뿐이라 매크로는 목표의 절반이다 → 칼로리 100, 매크로 평균 50 → 0.4×100 + 0.6×50 = 70점
            val single = meal(3L, 2000.0, targetKcal = 2000, createdAt = LocalDateTime.of(2026, 7, 29, 8, 0))

            When("캐시가 끼니 수정보다 나중에 만들어졌으면") {
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(single)
                every { feedbackRepository.findByUserAndDate(user, date) } returns
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 70,
                        feedback = "캐시된 피드백",
                        generatedAt = LocalDateTime.of(2026, 7, 29, 9, 0),
                    ).withId(1L)

                val response = service.getDay("testuser", date)

                Then("LLM을 다시 부르지 않는다") {
                    response.feedback shouldBe "캐시된 피드백"
                    verify(exactly = 0) { feedbackGenerator.generateForDay(any(), any(), any(), any(), any()) }
                }
            }

            When("끼니가 캐시보다 나중에 수정됐으면") {
                val edited =
                    meal(
                        4L,
                        2000.0,
                        targetKcal = 2000,
                        createdAt = LocalDateTime.of(2026, 7, 29, 8, 0),
                        updatedAt = LocalDateTime.of(2026, 7, 29, 20, 0),
                    )
                val cached =
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 50,
                        feedback = "낡은 피드백",
                        generatedAt = LocalDateTime.of(2026, 7, 29, 9, 0),
                    ).withId(1L)
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(edited)
                every { feedbackRepository.findByUserAndDate(user, date) } returns cached
                every { feedbackGenerator.generateForDay(any(), any(), any(), any(), any()) } returns "새 피드백"

                val response = service.getDay("testuser", date)

                Then("버리고 다시 만든다 — 미완성 데이터로 만든 피드백이 고정되면 안 된다") {
                    response.feedback shouldBe "새 피드백"
                    cached.feedback shouldBe "새 피드백"
                    cached.dayScore shouldBe 70
                }
            }

            When("피드백 생성이 실패하면") {
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(single)
                every { feedbackRepository.findByUserAndDate(user, date) } returns null
                every { feedbackGenerator.generateForDay(any(), any(), any(), any(), any()) } returns null

                val response = service.getDay("testuser", date)

                Then("dayScore만 반환하고 실패를 캐시하지 않는다 — 다음 조회에서 다시 시도한다") {
                    response.dayScore shouldBe 70
                    response.feedback shouldBe null
                    verify(exactly = 0) { feedbackRepository.save(any()) }
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*DailyDietServiceTest*"`
Expected: FAIL — `Unresolved reference: DailyDietService`

- [ ] **Step 3: 캐시 엔티티 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DailyDietFeedback.kt`:

```kotlin
package com.toy.backend.diet.feedback

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 하루 마감 피드백 캐시. **집계값을 저장하는 표가 아니다** — 하루 합계와 점수는 `Meal` 합산으로
 * 언제든 다시 구할 수 있고, 여기 있는 이유는 LLM 호출 결과를 재사용하기 위해서다.
 */
@Entity
@Table(
    name = "daily_diet_feedbacks",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_daily_diet_feedbacks_user_date", columnNames = ["user_id", "date"]),
    ],
)
class DailyDietFeedback(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(nullable = false)
    var date: LocalDate,
    @Column(name = "day_score", nullable = false)
    var dayScore: Int,
    @Column(columnDefinition = "text")
    var feedback: String?,
    @Column(name = "generated_at", nullable = false)
    var generatedAt: LocalDateTime,
) : BaseEntity() {
    fun update(
        dayScore: Int,
        feedback: String?,
        generatedAt: LocalDateTime,
    ) {
        this.dayScore = dayScore
        this.feedback = feedback
        this.generatedAt = generatedAt
    }
}
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DailyDietFeedbackRepository.kt`:

```kotlin
package com.toy.backend.diet.feedback

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface DailyDietFeedbackRepository : JpaRepository<DailyDietFeedback, Long> {
    fun findByUserAndDate(
        user: User,
        date: LocalDate,
    ): DailyDietFeedback?
}
```

- [ ] **Step 4: 하루 집계 DTO·서비스 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyDietDtos.kt`:

```kotlin
package com.toy.backend.diet.daily

import com.toy.backend.diet.meal.MealResponse
import com.toy.backend.diet.score.DayScoreBasis
import java.time.LocalDate

data class DayResponse(
    val date: LocalDate,
    val dayScore: Int?,
    val scoreBasis: DayScoreBasis?,
    val feedback: String?,
    val totalKcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val activeEnergyKcal: Int?,
    val meals: List<MealResponse>,
)
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyDietService.kt`:

```kotlin
package com.toy.backend.diet.daily

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.feedback.DailyDietFeedback
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.DietFeedbackGenerator
import com.toy.backend.diet.feedback.NutritionTotals
import com.toy.backend.diet.feedback.totals
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.meal.toResponse
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.file.FileService
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class DailyDietService(
    private val mealRepository: MealRepository,
    private val feedbackRepository: DailyDietFeedbackRepository,
    private val activityRepository: DailyActivityRepository,
    private val userRepository: UserRepository,
    private val feedbackGenerator: DietFeedbackGenerator,
    private val fileService: FileService,
) {
    /** 조회지만 피드백을 lazy 생성·갱신하므로 쓰기 트랜잭션이다. 크론을 두지 않기 위한 선택이다. */
    @Transactional
    fun getDay(
        username: String,
        date: LocalDate,
    ): DayResponse {
        val user = findUser(username)
        val meals = mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date)
        val activeEnergyKcal = activityRepository.findByUserAndDate(user, date)?.activeEnergyKcal
        val urls = fileService.getPresignedUrls(meals.flatMap { meal -> meal.photos.map { it.fileId } })
        val totals = meals.totals()

        if (meals.isEmpty()) {
            return DayResponse(
                date = date,
                dayScore = null,
                scoreBasis = null,
                feedback = null,
                totalKcal = 0.0,
                carbsG = 0.0,
                proteinG = 0.0,
                fatG = 0.0,
                activeEnergyKcal = activeEnergyKcal,
                meals = emptyList(),
            )
        }

        // 하루 목표는 **그날 첫 끼니의 스냅샷**에서 읽는다. 현재 프로필을 쓰면 오늘 몸무게를
        // 갱신했을 때 지난주 하루 점수가 같이 바뀐다.
        val targets = meals.first().targets()
        val dayScore = DietScoreCalculator.scoreDay(totals.kcal, totals.carbsG, totals.proteinG, totals.fatG, targets)
        val feedback = resolveFeedback(user, date, meals, totals, dayScore.score, activeEnergyKcal)

        return DayResponse(
            date = date,
            dayScore = dayScore.score,
            scoreBasis = dayScore.basis,
            feedback = feedback,
            totalKcal = totals.kcal,
            carbsG = totals.carbsG,
            proteinG = totals.proteinG,
            fatG = totals.fatG,
            activeEnergyKcal = activeEnergyKcal,
            meals = meals.map { it.toResponse(urls) },
        )
    }

    /**
     * 캐시가 없거나 `generatedAt`이 그날 `Meal`의 최종 `updatedAt`보다 이르면 버리고 재생성한다.
     * 당일에는 식사가 계속 추가되므로 이 조건 없이 캐시하면 미완성 데이터로 만든 피드백이 고정된다.
     */
    private fun resolveFeedback(
        user: User,
        date: LocalDate,
        meals: List<Meal>,
        totals: NutritionTotals,
        dayScore: Int,
        activeEnergyKcal: Int?,
    ): String? {
        val cached = feedbackRepository.findByUserAndDate(user, date)
        val latestMealUpdate = meals.maxOf { it.updatedAt }
        if (cached != null && !cached.generatedAt.isBefore(latestMealUpdate)) return cached.feedback

        val generated =
            feedbackGenerator.generateForDay(meals, totals, meals.first().targets(), dayScore, activeEnergyKcal)
        // 실패는 캐시하지 않는다 — 캐시해 버리면 끼니가 바뀌기 전까지 영영 재시도되지 않는다.
        if (generated == null) return null

        val now = LocalDateTime.now()
        if (cached == null) {
            feedbackRepository.save(
                DailyDietFeedback(
                    user = user,
                    date = date,
                    dayScore = dayScore,
                    feedback = generated,
                    generatedAt = now,
                ),
            )
        } else {
            cached.update(dayScore, generated, now)
        }
        return generated
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*DailyDietServiceTest*"`
Expected: PASS (3 Given)

- [ ] **Step 6: 컨트롤러 작성**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyDietController.kt`:

```kotlin
package com.toy.backend.diet.daily

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "식단 하루 집계", description = "하루 총 섭취량·점수·마감 피드백")
@RestController
@RequestMapping("/diet/days")
class DailyDietController(
    private val service: DailyDietService,
) {
    @GetMapping("/{date}")
    @Operation(summary = "하루 집계 조회 — 마감 피드백은 조회 시점에 lazy 생성한다")
    fun getDay(
        @Parameter(description = "조회 날짜", example = "2026-07-29")
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<DayResponse>> =
        ResponseEntity.ok(DataResponseBody(service.getDay(authentication.name, date)))
}
```

- [ ] **Step 7: 포맷·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src
git commit -m "feat: 하루 식단 집계와 마감 피드백 추가"
```

---

### Task 14: 임시 분석 정리 배치와 통합 확인

확인하지 않고 버려진 `MealAnalysis`를 TTL 24시간으로 지운다. 사진 파일은 `common-file`의 정리 배치가 이미 수거하므로 여기서는 레코드만 다룬다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalysisCleanupService.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalysisCleanupScheduler.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/analysis/MealAnalysisCleanupServiceTest.kt`

**Interfaces:**
- Consumes: `MealAnalysisRepository.deleteByCreatedAtBefore`(Task 7)
- Produces: `MealAnalysisCleanupService.purgeExpired(cutoff: LocalDateTime): Long`

- [ ] **Step 1: 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/analysis/MealAnalysisCleanupServiceTest.kt`:

```kotlin
package com.toy.backend.diet.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDateTime

class MealAnalysisCleanupServiceTest :
    BehaviorSpec({
        val repository = mockk<MealAnalysisRepository>()
        val service = MealAnalysisCleanupService(repository)

        Given("만료된 임시 분석 정리") {
            When("cutoff를 넘긴 레코드가 있으면") {
                val cutoff = LocalDateTime.of(2026, 7, 28, 4, 10)
                every { repository.deleteByCreatedAtBefore(cutoff) } returns 3L

                val purged = service.purgeExpired(cutoff)

                Then("삭제 건수를 돌려준다") {
                    purged shouldBe 3L
                    verify { repository.deleteByCreatedAtBefore(cutoff) }
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*MealAnalysisCleanupServiceTest*"`
Expected: FAIL — `Unresolved reference: MealAnalysisCleanupService`

- [ ] **Step 3: 서비스·스케줄러 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalysisCleanupService.kt`:

```kotlin
package com.toy.backend.diet.analysis

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class MealAnalysisCleanupService(
    private val repository: MealAnalysisRepository,
) {
    @Transactional
    fun purgeExpired(cutoff: LocalDateTime): Long = repository.deleteByCreatedAtBefore(cutoff)
}
```

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/analysis/MealAnalysisCleanupScheduler.kt`:

```kotlin
package com.toy.backend.diet.analysis

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * 확인 화면에서 이탈해 확정되지 않은 분석 레코드를 지운다. 사진 파일은 `attachFile`이 호출되지
 * 않아 `TEMP`로 남고, `common-file`의 정리 배치(04:00)가 따로 수거한다. 그 뒤에 돌도록 04:10에 둔다.
 */
@Component
class MealAnalysisCleanupScheduler(
    private val service: MealAnalysisCleanupService,
) {
    // cutoff 는 createdAt(감사 필드)과 같은 시계를 써야 하므로 zone 인자 없는 now() 를 쓴다.
    @Scheduled(cron = "0 10 4 * * *")
    fun purgeExpiredAnalyses() {
        val cutoff = LocalDateTime.now().minus(ANALYSIS_TTL)
        try {
            val purged = service.purgeExpired(cutoff)
            if (purged > 0) logger.info { "만료 식단 분석 정리 완료: ${purged}건 (cutoff=$cutoff)" }
        } catch (e: Exception) {
            logger.error(e) { "만료 식단 분석 정리 실패 (cutoff=$cutoff)" }
        }
    }

    companion object {
        private val ANALYSIS_TTL: Duration = Duration.ofHours(24)
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test`
Expected: 전체 PASS

- [ ] **Step 5: 포맷·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src
git commit -m "feat: 확정되지 않은 식단 분석 정리 배치 추가"
```

- [ ] **Step 6: 실제 앱을 띄워 확인 (단위 테스트로 못 잡는 부분)**

단위 테스트는 리포지토리를 목으로 대체하므로 **트랜잭션 경계·LAZY 로딩·DB 제약을 잡지 못한다.**
이 설계에서 가장 위험한 지점은 `@Async` 인식 경로와 확정 경로(`attachFile` × N + `MealAnalysis` 삭제가 한 트랜잭션)다. 아래를 실제로 확인한다.

```bash
# MinIO·PostgreSQL이 떠 있는 상태에서
OPENROUTER_API_KEY=<실제 키> ./gradlew :daily-record:bootRun
```

1. **로그인 후 프로필 저장** — `PUT /diet/profile`, 이어서 `GET /diet/profile`로 목표 4개가 채워졌는지.
2. **몸무게만 갱신** — `PUT /diet/profile/weight`로 값을 바꾸고 목표가 재계산되는지.
3. **사진 3장 업로드** — `POST /files` × 3 → fileId 3개.
4. **인식 요청** — `POST /diet/analyses {fileIds}` → 201. `GET /diet/analyses/{id}`를 폴링해 `PENDING → COMPLETED`로 바뀌고 사진별 `items`·`url`이 채워지는지. **여기서 "분석 대상이 없다" 경고가 뜨면 `runAfterCommit` 연결이 깨진 것이다.**
5. **부분 실패 확인** — 사진 한 장을 음식이 아닌 사진으로 바꿔 올려 `failed: true`가 그 사진에만 붙는지, 나머지 결과가 살아 있는지. `POST /diet/analyses/{id}/retry`가 실패한 사진만 다시 부르는지(로그의 호출 횟수).
6. **확정** — `POST /diet/meals {date, mealType, analysisId, items}` → 201. MinIO에서 사진 키가 `temp/` → `meals/`로 옮겨졌는지, `files.status`가 `ATTACHED`인지, `meal_analyses` 행이 사라졌는지.
7. **피드백 도착** — `GET /diet/meals/{id}`를 폴링해 `status`가 `COMPLETED`가 되고 `feedback`이 채워지는지.
8. **항목 수정** — `PUT /diet/meals/{id}/items`로 항목을 바꾸면 점수·피드백이 다시 만들어지는지.
9. **하루 집계** — `GET /diet/days/{date}`에서 `dayScore`·`scoreBasis`가 나오고, 두 번째 호출은 LLM을 다시 부르지 않는지(로그).
10. **몸무게를 바꾼 뒤 과거 날짜 재조회** — `PUT /diet/profile/weight`로 몸무게를 크게 바꾸고 위 날짜를 다시 조회해 **`dayScore`와 `targetKcal`이 그대로인지**. 이게 이번 개정의 핵심이다.
11. **삭제** — `DELETE /diet/meals/{id}` 후 `files.status`가 `TEMP`로 돌아갔는지(S3 객체는 남아 있어야 한다).
12. **키 없이 기동** — `OPENROUTER_API_KEY= ./gradlew :daily-record:bootRun`으로 다시 띄워 `POST /diet/analyses`가 503(`LLM_UNAVAILABLE`)인지, `GET /diet/days/{date}`·`GET /diet/meals`는 정상인지.

- [ ] **Step 7: iOS 짝 문서 개정 필요를 남긴다**

설계 문서의 서두 경고대로 `woori-haru/docs/superpowers/specs/2026-07-27-diet-tracking-ios-design.md`는 단일 사진·확인 단계 없는 흐름을 전제하고 있다. 여기에 이번 개정으로 **몸무게 전용 엔드포인트**(`PUT /diet/profile/weight`)와 **키 없는 환경의 503 처리**가 더해졌다. iOS 문서를 고칠 때 함께 반영한다.

---

## 자체 점검 (계획 작성 후 확인한 것)

- **명세 대비 누락 없음** — 도메인 6종(`NutritionProfile`·`Meal`·`MealPhoto`·`MealItem`·`MealAnalysis`·`DailyDietFeedback`·`DailyActivity`·`Food`), API 14개, 점수 두 종류, 인식·확정·피드백 3경로, 실패 처리 표 7행이 모두 어느 Task엔가 대응된다.
- **타입 일관성** — `NutritionTargets`는 Task 1에서 정의해 Task 3·9·11·13이 그대로 쓴다. `AnalysisStatus`는 `MealAnalysis`(인식)와 `Meal`(피드백) 양쪽에서 같은 이름으로 쓴다. `applyItems`는 Task 9에서 정의해 Task 12가 재사용한다.
- **의도적으로 뒤로 미룬 연결** — `MealService`의 피드백 트리거는 Task 11에서 붙인다(Task 9는 점수까지). 각 Task가 그 시점에 독립적으로 테스트 가능하도록 나눈 결과이며, Task 11 Step 6~7에 수정 지점을 명시했다.
- **남은 판단 지점** — Task 5 Step 7의 `servingSizeG` 결측률. 실제 CSV를 받아보기 전에는 `DEFAULT_SERVING_SIZE_G = 200.0`이 타당한지 알 수 없다(설계 문서 리스크 1). 결측률이 높으면 그 자리에서 값을 정하고 문서에 남긴다.
