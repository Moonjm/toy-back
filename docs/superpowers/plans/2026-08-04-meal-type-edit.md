# 저장된 끼니의 타입 수정 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `PATCH /diet/meals/{id}`로 저장된 끼니의 종류를 바꾸고, 대상 종류에 이미 끼니가 있으면 항목·사진을 옮겨 합친 뒤 살아남은 끼니를 `Location`으로 가리킨다.

**Architecture:** 스키마 변경이 없다. `Meal.mealType`은 이미 `var`이고 유니크 제약도 없다. 도메인(`Meal.changeMealType`) → 서비스(`MealService.changeType`, 갈래 셋) → 컨트롤러(`PATCH`) 순으로 아래에서 위로 쌓는다. 병합은 **옮기기가 아니라 베껴 붙이고 원본을 지우는 것**이다 — `items`·`photos`가 둘 다 `orphanRemoval = true`라 원본 컬렉션에서 빼는 순간 삭제 대상이 된다.

**Tech Stack:** Kotlin / Spring Boot / JPA(Hibernate) / Kotest `BehaviorSpec` + MockK / Gradle(Spotless+ktlint)

**설계 문서:** `docs/superpowers/specs/2026-08-04-meal-type-edit-design.md` — **함정 다섯을 먼저 읽는다.**

## Global Constraints

- **스키마 변경 없음.** 마이그레이션 파일을 만들지 않는다.
- **병합 삭제에서 `fileService.detachFiles`를 부르지 않는다**(함정 2). 부르면 옮겨 붙인 사진이 `TEMP`로 돌아가 04:00 정리 배치에 수거되고, `FileService.attachFile`이 재연결을 거부해 되돌릴 수도 없다.
- **항목을 옮길 때 `replaceItems`를 쓰지 않는다**(함정 1). `addItems`만 쓴다.
- **사진 `sortOrder`는 대상의 최대값 다음부터**: `(target.photos.maxOfOrNull { it.sortOrder } ?: -1) + 1`.
- **점수는 `DietScoreCalculator.scoreMeal(carbsG, proteinG, fatG)`** — `mealType`을 쓰지 않으므로 ②에서는 재계산하지 않는다.
- **하루 피드백 캐시를 직접 지우지 않는다.** `dailyFeedbackRepository.deleteByUserAndDate`를 부르지 않는다 — `contentUpdatedAt`이 올라 무효화 조건에 걸린다.
- 커밋 전 `./gradlew spotlessApply`. 커밋 메시지는 이 저장소 관례(한국어 현재형 제목 + 왜를 적는 본문).
- 테스트는 Kotest `BehaviorSpec` + MockK. **이 저장소에는 MockMvc·`@SpringBootTest`가 하나도 없다** — 서비스 단위 테스트만 쓴다.

---

### Task 1: `Meal.changeMealType` — 도메인에서 타입을 바꾸고 내용 판을 올린다

`contentUpdatedAt`은 하루 피드백 캐시의 무효화 기준이다. 지금은 `recalculateTotals` 한 곳에서만 오르는데, **끼니 종류는 항목이 아닌데도 하루 프롬프트가 읽는다**(`DietFeedbackPrompts.day`의 `"- ${meal.mealType}: ..."`). 타입만 바꾸는 갈래에서 이 값을 안 올리면 하루 피드백이 「간식: 치킨」이라고 쓴 채로 남는다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/Meal.kt` (`contentUpdatedAt` KDoc 102-118행, `recalculateTotals` 주석 149-165행, 메서드 추가)
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/MealContentVersionTest.kt`

**Interfaces:**
- Consumes: 없음(첫 태스크)
- Produces: `Meal.changeMealType(newType: MealType): Unit` — 같은 값이면 아무것도 하지 않고, 다르면 `mealType`을 바꾸고 `contentUpdatedAt`을 지금으로 올린다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`MealContentVersionTest.kt`의 마지막 `Given("점수만 다시 매기면") { ... }` 블록 **뒤에** 붙인다(닫는 `})` 앞):

```kotlin
        // 끼니 종류는 항목이 아니지만 하루 프롬프트가 읽는다(`DietFeedbackPrompts.day`).
        // 판단 기준이 「항목이 바뀌었나」가 아니라 「하루 프롬프트가 읽는 값이 바뀌었나」라는 것을
        // 이 둘이 함께 못 박는다.
        Given("끼니 종류를 바꾸면") {
            val meal = meal()
            meal.changeMealType(MealType.DINNER)

            Then("내용 판이 올라간다 — 안 오르면 하루 피드백이 「간식: 치킨」인 채로 남는다") {
                meal.mealType shouldBe MealType.DINNER
                meal.contentUpdatedAt shouldBeGreaterThan past
            }
        }

        Given("같은 종류로 다시 바꾸면") {
            val meal = meal()
            meal.changeMealType(MealType.LUNCH)

            Then("내용 판은 그대로다 — 값이 안 바뀌었으면 하루 피드백을 다시 만들 이유가 없다") {
                meal.contentUpdatedAt shouldBe past
            }
        }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.meal.MealContentVersionTest"`
Expected: **컴파일 실패** — `Unresolved reference: changeMealType`

- [ ] **Step 3: 최소 구현 + 주석 갱신**

`Meal.kt`의 `replaceItems` **위에** 메서드를 추가한다:

```kotlin
    /**
     * 끼니 종류를 바꾼다. **실제로 바뀔 때만 [contentUpdatedAt]을 올린다.**
     *
     * 종류는 항목이 아닌데도 하루 프롬프트가 읽는다(`DietFeedbackPrompts.day`) — 안 올리면
     * 하루 피드백이 「간식: 치킨」이라고 쓴 채로 남는다. 같은 값이면 아무것도 하지 않는 것도
     * 같은 이유의 뒷면이다. 읽는 값이 안 바뀌었는데 판을 올리면 유료 호출만 한 번 더 나간다.
     */
    fun changeMealType(newType: MealType) {
        if (mealType == newType) return
        mealType = newType
        contentUpdatedAt = LocalDateTime.now()
    }
```

`contentUpdatedAt`의 KDoc에서 **첫 줄**을 고친다:

```kotlin
     * **하루 피드백 캐시의 무효화 기준.** 하루 프롬프트가 읽는 값이 바뀔 때만 오른다 —
     * 항목·합계(`recalculateTotals`)와 끼니 종류(`changeMealType`)다.
```

(원래 문장: `**하루 피드백 캐시의 무효화 기준.** 항목·합계가 바뀔 때만 오른다.`)

`recalculateTotals` 안의 주석도 고친다:

```kotlin
        // 하루 피드백 캐시가 보는 값이다. **항목이 바뀌는 경로는 교체·얹기 둘뿐이고 둘 다 이
        // 메서드를 지나므로** 한쪽만 갱신되는 일이 없다. 항목 밖에서 올리는 자리는 하나뿐이다 —
        // `changeMealType`. 늘리기 전에 「하루 프롬프트가 그 값을 읽는가」를 먼저 본다.
        contentUpdatedAt = LocalDateTime.now()
```

(원래 문장: `// 하루 피드백 캐시가 보는 값이다. **여기 한 곳에서만 올린다** — 항목이 바뀌는 경로가 / // 교체·얹기 둘뿐이고 둘 다 이 메서드를 지나므로, 한쪽만 갱신되는 일이 없다.`)

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.meal.MealContentVersionTest"`
Expected: PASS (기존 6개 + 새 2개)

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/Meal.kt \
        apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/MealContentVersionTest.kt
git commit -m "feat: 끼니 종류를 바꾸면 내용 판을 올린다

종류는 항목이 아니지만 하루 프롬프트가 읽는다. 안 올리면 하루 피드백이
「간식: 치킨」이라고 쓴 채로 남는다.

같은 값이면 올리지 않는다 — 읽는 값이 안 바뀌었는데 판을 올리면 유료 호출만
한 번 더 나간다. 「항목이 바뀔 때만 오른다」던 주석 둘도 새 기준(하루 프롬프트가
읽는 값)으로 고쳤다."
```

---

### Task 2: 갈래 ①② — 같은 타입이면 아무것도 안 하고, 합칠 대상이 없으면 타입만 바꾼다

`MealService.changeType`을 만든다. 이 태스크에서는 **합치지 않는 두 갈래**만 낸다.

**⚠️ 순서 함정:** 대상을 찾기 **전에** `meal.changeMealType`을 부르면 안 된다. Hibernate가 쿼리 전에 auto-flush 하므로 `findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc`가 **방금 타입을 바꾼 자기 자신**을 병합 대상으로 돌려줄 수 있다. 그러면 자기 항목을 자기에게 붙이고 자기를 지운다. **조회가 끝난 뒤에만 바꾼다.**

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealDtos.kt` (`MealItemsRequest` 아래에 요청 DTO 추가)
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealService.kt` (`updateItems` 아래에 `changeType` 추가, `mergeTargetOf` 시그니처 변경)
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/MealTypeChangeTest.kt` (신규)

**Interfaces:**
- Consumes: `Meal.changeMealType(newType: MealType)` (Task 1)
- Produces:
  - `data class MealTypeRequest(val mealType: MealType)`
  - `MealService.changeType(username: String, id: Long, request: MealTypeRequest): Long` — **살아남은 끼니의 id**를 돌려준다
  - `MealService.mergeTargetOf(user: User, date: LocalDate, mealType: MealType): Meal?` (기존 private 메서드의 새 시그니처)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`MealTypeChangeTest.kt`를 새로 만든다:

```kotlin
package com.toy.backend.diet.meal

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.analysis.MealAnalysisRepository
import com.toy.backend.diet.analysis.MealAnalysisService
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.DietFeedbackGenerator
import com.toy.backend.diet.profile.NutritionProfileService
import com.toy.backend.file.FileService
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 저장된 끼니의 종류를 고친다. 저녁을 간식으로 저장하면 되돌릴 길이 없던 것을 연다 —
 * 앱에 사진 바이트가 없어 「지우고 다시 만들기」로는 찍어 둔 사진이 사라진다.
 */
class MealTypeChangeTest :
    BehaviorSpec({
        val repository = mockk<MealRepository>()
        val userRepository = mockk<UserRepository>()
        val profileService = mockk<NutritionProfileService>()
        val analysisService = mockk<MealAnalysisService>()
        val analysisRepository = mockk<MealAnalysisRepository>()
        val fileService = mockk<FileService>()
        val objectMapper = jacksonObjectMapper()
        val feedbackGenerator = mockk<DietFeedbackGenerator>()
        val dailyFeedbackRepository = mockk<DailyDietFeedbackRepository>()
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
                dailyFeedbackRepository,
            )

        val user = dietUser()
        val date = LocalDate.of(2026, 8, 4)
        val past = LocalDateTime.of(2026, 8, 4, 8, 0)

        /** 확정돼 피드백까지 받은 끼니 하나. 밥 한 그릇, 점수 59. */
        fun savedMeal(
            id: Long,
            mealType: MealType,
            owner: User = user,
        ): Meal {
            val meal =
                Meal(
                    user = owner,
                    date = date,
                    mealType = mealType,
                    weightKg = 65.0,
                    targetKcal = 2000,
                    targetCarbsG = 300,
                    targetProteinG = 80,
                    targetFatG = 70,
                    targetSugarG = 100,
                    targetSodiumMg = 1500,
                    targetFiberG = 25,
                    status = AnalysisStatus.COMPLETED,
                    feedback = "기존 피드백",
                ).withId(id)
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
                        sugarG = 2.0,
                        sodiumMg = 300.0,
                        fiberG = 1.0,
                        source = NutritionSource.LLM_ESTIMATED,
                    ).withId(id * 10),
                ),
            )
            meal.applyScore(59)
            meal.contentUpdatedAt = past
            return meal
        }

        // **호출 기록을 Given마다 지운다.** 이 스펙은 `verify(exactly = 0)`을 여러 갈래에서
        // 거는데, 목이 스펙 전체에서 공유돼 앞 Given의 호출이 그대로 남는다 — 안 지우면
        // 「합칠 대상을 찾아보지도 않는다」가 앞 블록의 조회에 걸려 빨개진다. `answers = false`라
        // 스텁은 남는다.
        beforeContainer {
            clearMocks(repository, fileService, feedbackGenerator, dailyFeedbackRepository, answers = false)
            every { userRepository.findByUsername("testuser") } returns user
            justRun { feedbackGenerator.generateForMeal(any()) }
        }

        // 앱이 실수로 같은 값을 보내도 유료 호출이 나가면 안 된다.
        Given("지금과 같은 종류로 바꾸면") {
            val meal = savedMeal(70L, MealType.LUNCH)
            every { repository.findByIdOrNull(70L) } returns meal

            val id = service.changeType("testuser", 70L, MealTypeRequest(MealType.LUNCH))

            Then("요청한 id를 그대로 돌려준다") {
                id shouldBe 70L
            }

            Then("피드백을 다시 만들지 않는다 — 같은 값에 유료 호출을 걸지 않는다") {
                meal.status shouldBe AnalysisStatus.COMPLETED
                meal.feedback shouldBe "기존 피드백"
                verify(exactly = 0) { feedbackGenerator.generateForMeal(any()) }
            }

            Then("내용 판도 그대로다") {
                meal.contentUpdatedAt shouldBe past
            }

            Then("합칠 대상을 찾아보지도 않는다") {
                verify(exactly = 0) {
                    repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(any(), any(), any())
                }
            }
        }

        Given("그날 대상 종류의 끼니가 없으면") {
            val meal = savedMeal(71L, MealType.SNACK)
            every { repository.findByIdOrNull(71L) } returns meal
            every {
                repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(user, date, MealType.DINNER)
            } returns null

            val id = service.changeType("testuser", 71L, MealTypeRequest(MealType.DINNER))

            Then("같은 행의 종류만 바뀐다") {
                id shouldBe 71L
                meal.mealType shouldBe MealType.DINNER
            }

            Then("항목과 점수는 그대로다 — 점수는 종류를 읽지 않는다") {
                meal.items.size shouldBe 1
                meal.totalKcal shouldBe 300.0
                meal.score shouldBe 59
            }

            // 하루 프롬프트가 종류를 읽으므로 안 올리면 「간식: 밥」인 채로 남는다.
            Then("내용 판이 올라간다") {
                meal.contentUpdatedAt shouldBeGreaterThan past
            }

            // `DietFeedbackPrompts.meal`이 [이번 끼니] ${meal.mealType}을 읽는다.
            Then("끼니 피드백은 다시 만든다") {
                meal.status shouldBe AnalysisStatus.PENDING
                meal.feedback shouldBe null
                verify { feedbackGenerator.generateForMeal(71L) }
            }

            Then("아무것도 지우지 않는다") {
                verify(exactly = 0) { repository.delete(any()) }
                verify(exactly = 0) { fileService.detachFiles(any()) }
                verify(exactly = 0) { dailyFeedbackRepository.deleteByUserAndDate(any(), any()) }
            }
        }

        // 간식만 `mergesWithinDay = false`다. 오전 과자와 밤 아이스크림을 한 카드에 합치면
        // 끼니 점수가 뒤섞인다 — 합치기는 대칭이 아니다.
        Given("간식으로 바꾸면") {
            val meal = savedMeal(72L, MealType.DINNER)
            every { repository.findByIdOrNull(72L) } returns meal

            val id = service.changeType("testuser", 72L, MealTypeRequest(MealType.SNACK))

            Then("그날 간식이 이미 있어도 합치지 않는다 — 대상을 찾아보지도 않는다") {
                id shouldBe 72L
                meal.mealType shouldBe MealType.SNACK
                verify(exactly = 0) {
                    repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(any(), any(), any())
                }
                verify(exactly = 0) { repository.delete(any()) }
            }
        }

        Given("남의 끼니면") {
            val other = dietUser(username = "other", id = 2L)
            every { repository.findByIdOrNull(73L) } returns savedMeal(73L, MealType.LUNCH, owner = other)

            Then("RESOURCE_NOT_FOUND") {
                val e =
                    shouldThrow<CustomException> {
                        service.changeType("testuser", 73L, MealTypeRequest(MealType.DINNER))
                    }
                e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
            }
        }

        Given("없는 id면") {
            every { repository.findByIdOrNull(999L) } returns null

            Then("RESOURCE_NOT_FOUND") {
                val e =
                    shouldThrow<CustomException> {
                        service.changeType("testuser", 999L, MealTypeRequest(MealType.DINNER))
                    }
                e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
            }
        }
    })
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.meal.MealTypeChangeTest"`
Expected: **컴파일 실패** — `Unresolved reference: MealTypeRequest`, `Unresolved reference: changeType`

- [ ] **Step 3: 요청 DTO를 추가한다**

`MealDtos.kt`의 `MealItemsRequest` 바로 아래:

```kotlin
/**
 * 끼니 종류만 고친다. **날짜 변경은 범위 밖이다** — 하루 집계·점수·피드백이 이틀에 걸쳐 다시
 * 계산돼야 해서 훨씬 크다.
 *
 * `mealType`이 널이 될 수 없는 타입이라 누락·모르는 값은 Jackson이 던지고
 * `CustomExceptionHandler`가 400으로 옮긴다 — 따로 검증 애너테이션을 붙이지 않는다.
 */
data class MealTypeRequest(
    val mealType: MealType,
)
```

- [ ] **Step 4: `mergeTargetOf`를 재사용할 수 있게 고친다**

`MealService.kt`의 기존 `mergeTargetOf`를 통째로 바꾼다(`confirm`도 같이 고친다):

```kotlin
    /** 합칠 기존 끼니. 간식은 본래 여러 번이라 묶지 않는다(`MealType.mergesWithinDay`). */
    private fun mergeTargetOf(
        user: User,
        date: LocalDate,
        mealType: MealType,
    ): Meal? =
        if (mealType.mergesWithinDay) {
            repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(user, date, mealType)
        } else {
            null
        }
```

`confirm` 안의 호출부(`MealService.kt:66`)를 바꾼다:

```kotlin
        val existing = mergeTargetOf(user, request.date, request.mealType)
```

- [ ] **Step 5: `changeType`의 갈래 ①②를 구현한다**

`MealService.kt`의 `updateItems` 아래에 넣는다:

```kotlin
    /**
     * 저장된 끼니의 **종류만** 고친다. 저녁을 간식으로 저장하면 되돌릴 길이 없던 것을 연다 —
     * 앱에 사진 바이트가 없어 「지우고 다시 만들기」로는 찍어 둔 사진이 사라진다.
     *
     * 세 갈래다. ① 같은 종류면 아무것도 하지 않는다 — 앱이 실수로 같은 값을 보내도 유료 호출이
     * 나가면 안 된다. ② 대상 종류의 끼니가 그날 없으면 종류만 바꾼다. ③ 있으면 그쪽으로 합친다.
     *
     * **대상을 찾기 전에 종류를 바꾸면 안 된다.** Hibernate가 쿼리 전에 auto-flush 하므로
     * 병합 대상 조회가 **방금 바꾼 자기 자신**을 돌려줄 수 있다 — 자기 항목을 자기에게 붙이고
     * 자기를 지우게 된다.
     *
     * 점수는 다시 계산하지 않는다 — `DietScoreCalculator`는 종류를 쓰지 않는다. 피드백은 다시
     * 만든다 — `DietFeedbackPrompts.meal`이 `[이번 끼니] ${meal.mealType}`을 읽는다.
     *
     * 돌려주는 것은 **살아남은 끼니의 id**다. 합쳤으면 대상, 아니면 요청한 id 그대로다.
     */
    @Transactional
    fun changeType(
        username: String,
        id: Long,
        request: MealTypeRequest,
    ): Long {
        val user = findUser(username)
        val meal = requireOwned(user, id)
        if (meal.mealType == request.mealType) return id

        meal.changeMealType(request.mealType)
        meal.markFeedbackPending()
        runAfterCommit { feedbackGenerator.generateForMeal(id) }
        return id
    }
```

(③은 Task 3에서 이 메서드 안에 끼워 넣는다.)

- [ ] **Step 6: 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.meal.MealTypeChangeTest"`
Expected: PASS

Run: `./gradlew :daily-record:test`
Expected: PASS — 특히 `MealMergeTest`·`MealConfirmTest`가 `mergeTargetOf` 시그니처 변경에도 그대로 통과해야 한다.

- [ ] **Step 7: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealDtos.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealService.kt \
        apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/MealTypeChangeTest.kt
git commit -m "feat: 저장된 끼니의 종류를 바꾼다 — 합치지 않는 두 갈래

같은 종류면 아무것도 하지 않는다. 앱이 실수로 같은 값을 보내도 유료 호출이
나가면 안 된다.

그날 대상 종류의 끼니가 없으면 종류만 바꾸고 내용 판을 올린다. 점수는 그대로
둔다 — 계산기가 종류를 읽지 않는다. 피드백은 다시 만든다 — 프롬프트가 읽는다.

대상 조회 전에는 종류를 바꾸지 않는다. Hibernate가 쿼리 전에 auto-flush 해서,
먼저 바꾸면 조회가 방금 바꾼 자기 자신을 병합 대상으로 돌려줄 수 있다.

mergeTargetOf가 확정 요청 대신 (user, date, mealType)을 받게 했다 — 확정과
종류 변경이 같은 기준으로 대상을 고르기 위해서다."
```

---

### Task 3: 갈래 ③ — 대상이 있으면 항목·사진을 베껴 붙이고 원본을 지운다

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealItem.kt` (파일 끝에 `copyTo` 추가)
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealService.kt` (`changeType`에 ③ 갈래 + `mergeInto` 추가)
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/MealTypeChangeTest.kt`

**Interfaces:**
- Consumes: `MealService.changeType`, `mergeTargetOf(user, date, mealType)` (Task 2), `Meal.addItems`/`addPhoto`/`applyScore`/`markFeedbackPending` (기존)
- Produces: `MealItem.copyTo(meal: Meal): MealItem` — `meal`만 바꾼 새 인스턴스

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`MealTypeChangeTest.kt`의 `Given("남의 끼니면")` **앞에** 넣는다. 항목 수치는 `MealMergeTest`와 같은 값이라 합계 1000kcal·점수 82가 이미 검증된 조합이다:

```kotlin
        // 대상 끼니는 이미 사진 2장을 갖고 있다. `Meal.photos`가 @OrderBy("sortOrder asc")라
        // 0부터 다시 매기면 0,1,0,1이 되어 앱의 사진 순서가 뒤섞인다.
        Given("그날 대상 종류의 끼니가 이미 있으면") {
            val target = savedMeal(80L, MealType.DINNER)
            repeat(2) { index ->
                target.addPhoto(MealPhoto(meal = target, fileId = 20L + index, sortOrder = index).withId(800L + index))
            }
            val source = savedMeal(81L, MealType.SNACK)
            source.replaceItems(
                listOf(
                    MealItem(
                        meal = source,
                        foodName = "제육볶음",
                        foodCode = "D1",
                        quantityG = 168.75,
                        kcal = 700.0,
                        carbsG = 168.75,
                        proteinG = 18.0,
                        fatG = 17.0,
                        sugarG = 5.0,
                        sodiumMg = 620.0,
                        fiberG = 3.0,
                        source = NutritionSource.DB_MATCHED,
                    ).withId(810L),
                ),
            )
            repeat(2) { index ->
                source.addPhoto(MealPhoto(meal = source, fileId = 31L + index, sortOrder = index).withId(811L + index))
            }
            target.contentUpdatedAt = past

            every { repository.findByIdOrNull(81L) } returns source
            every {
                repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(user, date, MealType.DINNER)
            } returns target
            justRun { repository.delete(source) }

            val id = service.changeType("testuser", 81L, MealTypeRequest(MealType.DINNER))

            // 앱은 이 id로 폴링 대상을 바꾼다. 이 갈래의 핵심 계약이다.
            Then("살아남은 대상의 id를 돌려준다 — 요청한 id가 아니다") {
                id shouldBe 80L
            }

            Then("항목이 대상으로 간다 — 기존 항목 위에 얹힌다") {
                target.items.size shouldBe 2
                target.items[0].foodName shouldBe "밥"
                target.items[1].foodName shouldBe "제육볶음"
            }

            Then("합계가 두 벌의 합이다") {
                target.totalKcal shouldBe 1000.0
                target.carbsG shouldBe 238.75
                target.proteinG shouldBe 23.0
                target.fatG shouldBe 18.0
            }

            // 이 도메인에서 조용히 0이 됐던 전력이 있는 세 필드다.
            Then("주의 영양소도 합으로 맞는다") {
                target.sugarG shouldBe 7.0
                target.sodiumMg shouldBe 920.0
                target.fiberG shouldBe 4.0
            }

            // source가 떨어지면 하루 응답의 estimatedItemCount가, foodCode가 떨어지면
            // 음식 빈도 집계가 조용히 샌다.
            Then("옮긴 항목의 필드가 보존된다") {
                target.items[1].foodCode shouldBe "D1"
                target.items[1].source shouldBe NutritionSource.DB_MATCHED
                target.items[1].quantityG shouldBe 168.75
            }

            Then("새 인스턴스로 베껴 붙인다 — 원본 컬렉션은 그대로다") {
                source.items.size shouldBe 1
                target.items[1] shouldNotBeSameInstanceAs source.items[0]
                target.items[1].meal shouldBeSameInstanceAs target
            }

            Then("사진도 가고 sortOrder가 대상의 최대값 다음부터 이어진다") {
                target.photos.map { it.sortOrder } shouldBe listOf(0, 1, 2, 3)
                target.photos.map { it.fileId } shouldBe listOf(20L, 21L, 31L, 32L)
            }

            // detach 하면 파일이 TEMP로 돌아가 04:00 배치에 수거되고, attachFile이 재연결을
            // 거부해 되돌릴 수도 없다. 화면에는 그날 멀쩡히 보이다가 며칠 뒤 깨진다.
            Then("파일을 detach 하지 않는다 — 소유가 옮겨 간 것이지 안 쓰이게 된 게 아니다") {
                verify(exactly = 0) { fileService.detachFiles(any()) }
            }

            Then("원본을 지운다") {
                verify(exactly = 1) { repository.delete(source) }
            }

            Then("점수를 합쳐진 매크로로 다시 계산한다 — 59에서 바뀐다") {
                target.score shouldBe 82
            }

            Then("대상의 내용 판이 올라가고 피드백을 다시 만든다") {
                target.contentUpdatedAt shouldBeGreaterThan past
                target.status shouldBe AnalysisStatus.PENDING
                target.feedback shouldBe null
                verify { feedbackGenerator.generateForMeal(80L) }
            }

            // ②③ 모두 contentUpdatedAt이 올라 무효화 조건에 그대로 걸린다.
            Then("하루 피드백 캐시는 직접 지우지 않는다") {
                verify(exactly = 0) { dailyFeedbackRepository.deleteByUserAndDate(any(), any()) }
            }
        }
```

import 두 개를 파일 상단에 더한다:

```kotlin
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.meal.MealTypeChangeTest"`
Expected: FAIL — 대상이 있는데도 ② 갈래를 타서 `id shouldBe 80L`이 `81L`로 어긋난다

- [ ] **Step 3: `MealItem.copyTo`를 만든다**

`MealItem.kt` 파일 **끝**(클래스 밖)에 붙인다:

```kotlin
/**
 * 다른 끼니로 **베껴 붙일** 사본. 옮기지 않는 이유는 `Meal.addItems` 주석과 같다 — `items`가
 * `orphanRemoval = true`라 원본 컬렉션에서 빼는 순간 그 행이 삭제 대상이 되고, 같은 인스턴스를
 * 다른 끼니에 붙이면 Hibernate가 「삭제된 엔티티를 다시 저장」으로 보고 던진다.
 *
 * **`meal`만 바꾸고 나머지는 전부 그대로 옮긴다.** 「이름·수량·탄단지」만 챙기면 조용히 새는
 * 것이 있다 — `source`가 떨어지면 하루 응답의 `estimatedItemCount`가 「추정이 섞였다」 표시를
 * 잃고, `foodCode`가 떨어지면 음식 빈도 집계에서 빠지며, 당·나트륨·식이섬유는 이 도메인에서
 * 조용히 0이 됐던 전력이 있는 자리다.
 */
fun MealItem.copyTo(meal: Meal): MealItem =
    MealItem(
        meal = meal,
        foodName = foodName,
        foodCode = foodCode,
        quantityG = quantityG,
        kcal = kcal,
        carbsG = carbsG,
        proteinG = proteinG,
        fatG = fatG,
        sugarG = sugarG,
        sodiumMg = sodiumMg,
        fiberG = fiberG,
        source = source,
    )
```

- [ ] **Step 4: `changeType`에 ③ 갈래를 끼우고 `mergeInto`를 만든다**

`changeType`의 본문에서 `if (meal.mealType == request.mealType) return id` **아래**를 바꾼다:

```kotlin
        val target = mergeTargetOf(user, meal.date, request.mealType)
        if (target != null) return mergeInto(target, meal)

        meal.changeMealType(request.mealType)
        meal.markFeedbackPending()
        runAfterCommit { feedbackGenerator.generateForMeal(id) }
        return id
```

`changeType` 아래에 `mergeInto`를 추가한다:

```kotlin
    /**
     * 원본을 대상에 합치고 원본을 지운다. **옮기는 게 아니라 베껴 붙이고 통째로 지우는 것이다**
     * (`MealItem.copyTo` 주석). 어차피 지울 것이라 「옮기기」로 볼 이유가 없다.
     *
     * **`detachFiles`를 부르지 않는다.** `delete`를 재사용하면 방금 대상에 붙인 사진의 `fileId`가
     * 함께 `TEMP`로 돌아가 04:00 정리 배치가 S3 객체를 수거한다. 화면에는 그날 멀쩡히 보이다가
     * 며칠 뒤 깨지고, `FileService.attachFile`이 detach된 파일의 재연결을 거부해 되돌릴 수도 없다.
     * 파일의 소유가 원본에서 대상으로 넘어간 것이지 안 쓰이게 된 것이 아니다.
     *
     * `meal_photo`에 `file_id` 유니크 제약이 없어(인덱스는 `meal_id`뿐) 같은 `fileId`를 가리키는
     * 행이 한 트랜잭션 안에 잠깐 둘이어도 문제없다.
     *
     * 하루 피드백 캐시는 직접 지우지 않는다 — 대상의 항목이 늘어 `contentUpdatedAt`이 오르므로
     * 무효화 조건에 그대로 걸린다. `delete`가 그것을 명시적으로 부르는 것은 **남는 끼니가
     * 아무것도 안 바뀌는** 경우라서다.
     */
    private fun mergeInto(
        target: Meal,
        source: Meal,
    ): Long {
        // 합계·내용 판은 여기서 함께 오른다(`Meal.recalculateTotals`).
        target.addItems(source.items.map { it.copyTo(target) })
        // 0부터 다시 매기면 @OrderBy("sortOrder asc") 때문에 0,1,0,1이 되어 앱의 사진 순서가 뒤섞인다.
        val startOrder = (target.photos.maxOfOrNull { it.sortOrder } ?: -1) + 1
        source.photos.forEachIndexed { index, photo ->
            target.addPhoto(MealPhoto(meal = target, fileId = photo.fileId, sortOrder = startOrder + index))
        }
        target.applyScore(DietScoreCalculator.scoreMeal(target.carbsG, target.proteinG, target.fatG).score)
        target.markFeedbackPending()
        repository.delete(source)
        runAfterCommit { feedbackGenerator.generateForMeal(target.requiredId) }
        return target.requiredId
    }
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.meal.MealTypeChangeTest"`
Expected: PASS

Run: `./gradlew :daily-record:test`
Expected: PASS (전체)

- [ ] **Step 6: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealItem.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealService.kt \
        apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/MealTypeChangeTest.kt
git commit -m "feat: 종류를 바꿀 때 그날 그 종류가 있으면 합친다

옮기지 않고 베껴 붙인 뒤 원본을 통째로 지운다. items·photos가 둘 다
orphanRemoval이라 원본 컬렉션에서 빼는 순간 삭제 대상이 되고, 같은 인스턴스를
대상에 붙이면 Hibernate가 삭제된 엔티티를 되살리는 것으로 보고 던진다.

detachFiles를 부르지 않는다. delete를 재사용하면 방금 대상에 붙인 사진이
TEMP로 돌아가 04:00 배치에 수거되고, attachFile이 재연결을 거부해 되돌릴 수도
없다. 그날은 멀쩡히 보이다가 며칠 뒤 깨진다.

항목을 복사할 때 meal만 바꾸고 나머지는 전부 옮긴다. source가 떨어지면
estimatedItemCount가, foodCode가 떨어지면 음식 빈도 집계가 조용히 샌다.

사진 sortOrder는 대상의 최대값 다음부터 매긴다 — 0부터 다시 매기면 0,1,0,1이
되어 앱의 사진 순서가 뒤섞인다."
```

---

### Task 4: `PATCH /diet/meals/{id}` — 200 + `Location`

**`@ResponseCreated`를 쓰지 않는다.** `ResponseCreatedAspect`가 반환값에서 본문(id)만 꺼내고 상태코드는 버린 뒤 `ResponseEntity.created(...)`를 새로 만들고, 애너테이션에도 `@ResponseStatus(HttpStatus.CREATED)`가 붙어 있다 — 재사용하면 `PATCH`가 201로 나간다. 만들지 않았는데 201은 거짓말이므로 컨트롤러에서 직접 조립한다. `@ResponseLocation`을 새로 두지도 않는다 — 애스펙트가 있는 이유는 여러 엔드포인트의 중복 제거인데 소비자가 한 곳뿐이다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealController.kt` (`updateItems` 아래)

**Interfaces:**
- Consumes: `MealService.changeType(username, id, request): Long`, `MealTypeRequest` (Task 2·3)
- Produces: `PATCH /diet/meals/{id}` → `200 OK` + `Location: /diet/meals/{살아남은 id}`, 본문 없음

- [ ] **Step 1: 엔드포인트를 추가한다**

`MealController.kt`의 `updateItems` 아래에 넣는다:

```kotlin
    /**
     * `Location`이 가리키는 것은 **살아남은 끼니**다 — 합쳐졌으면 대상, 아니면 요청한 id 그대로다.
     * 앱은 이 값으로 피드백 폴링 대상을 바꾼다.
     *
     * **`@ResponseCreated`를 쓰지 않는다.** 그 애스펙트는 상태코드를 버리고 201을 새로 만드는데,
     * `PATCH`는 만들지 않으므로 201이 거짓말이 된다. 본문 봉투(`DataResponseBody`)를 쓰지 않는
     * 것은 id 하나를 위해 새 응답 DTO를 만들 이유가 없어서다.
     */
    @PatchMapping("/{id}")
    @Operation(summary = "끼니 종류 변경 — 그날 그 종류가 이미 있으면 합치고 살아남은 끼니를 Location으로 가리킨다")
    fun changeType(
        @PathVariable id: Long,
        @Valid @RequestBody request: MealTypeRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        val survivorId = service.changeType(authentication.name, id, request)
        return ResponseEntity.ok().location(URI.create("/diet/meals/$survivorId")).build()
    }
```

import 두 개를 더한다:

```kotlin
import org.springframework.web.bind.annotation.PatchMapping
import java.net.URI
```

- [ ] **Step 2: 빌드와 전체 테스트를 확인한다**

**이 저장소에는 MockMvc·`@SpringBootTest`가 하나도 없어 컨트롤러 단위 테스트를 쓸 수 없다.** 상태코드·`Location`·`mealType` 누락 400은 프레임워크가 보장하는 부분이라, 여기서는 컴파일·전체 테스트·수동 확인으로 대신한다. (설계 문서의 테스트 목록 중 「`mealType`이 없거나 모르는 값이면 400」이 이 이유로 자동화되지 않는다.)

Run: `./gradlew :daily-record:build`
Expected: BUILD SUCCESSFUL (spotless 포함)

- [ ] **Step 3: 앱을 띄워 손으로 확인한다**

```bash
./gradlew :daily-record:bootRun
```

토큰을 얻은 뒤(기존 로그인 절차) 같은 날 저녁과 간식을 하나씩 만들어 두고:

```bash
# ③ 합치기 — 살아남은 저녁의 id가 Location에 온다
curl -i -X PATCH localhost:8080/diet/meals/{간식id} \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"mealType":"DINNER"}'
# 기대: HTTP/1.1 200, Location: /diet/meals/{저녁id}, 본문 없음

# 모르는 종류 — 400
curl -i -X PATCH localhost:8080/diet/meals/{id} \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"mealType":"BRUNCH"}'
# 기대: HTTP/1.1 400
```

`GET /diet/meals/{저녁id}`로 항목·사진이 합쳐졌는지, 사진 URL이 여전히 열리는지(detach 안 됨) 확인한다.

- [ ] **Step 4: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealController.kt
git commit -m "feat: PATCH /diet/meals/{id}로 끼니 종류를 연다

200 + Location이다. 합쳐졌으면 대상, 아니면 요청한 id 그대로 — 앱이 이 값으로
피드백 폴링 대상을 바꾼다.

@ResponseCreated를 쓰지 않는다. 그 애스펙트는 컨트롤러 반환값에서 본문만 꺼내고
상태코드를 버린 뒤 201을 새로 만드는데, PATCH는 만들지 않으므로 201이
거짓말이 된다. 소비자가 한 곳뿐이라 @ResponseLocation을 새로 두지도 않았다."
```

---

## 남는 것 — 앱과 맞춰야 하는 하나

`woori-haru`의 `APIClient.postCreated`가 201을 기대한다면 200을 받는 경로가 따로 필요하다. **서버가 먼저 나가야 하므로** 이 계획은 200으로 낸다 — 앱 설계에서 받는 쪽을 맞춘다.

## 범위 밖(설계 문서와 같다)

- 날짜 변경 · 합친 것 되돌리기 · 여러 끼니 한 번에 옮기기 · 이미 만들어진 중복 행의 소급 정리
- **원본이 그날 첫 끼니였을 때 하루 목표 스냅샷이 두 번째 끼니 것으로 넘어가는 것**(설계 문서 함정 5) — 그대로 둔다. 대상 스냅샷을 원본 것으로 덮으면 살아남은 끼니의 과거 점수 근거가 바뀐다.
