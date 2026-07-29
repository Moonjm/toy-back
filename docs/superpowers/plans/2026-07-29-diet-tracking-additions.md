# 식단 기록 보강 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사진 없이도 끼니를 기록하고, 자주 먹는 음식을 한 번에 담고, 당류·나트륨·식이섬유를 기준과 대조해 보여주고, 주·월 통계를 낸다.

**Architecture:** 기존 `com.toy.backend.diet.*` 위에 얹는 증분이다. 새 테이블은 없다 — 주의 영양소는 컬럼만 늘리고 나머지 셋은 스키마를 건드리지 않는다. LLM 호출이 늘어나는 곳도 없다.

**Tech Stack:** Kotlin 2.4 / Spring Boot 4.1 / JPA(`ddl-auto: update`) / PostgreSQL / kotest `BehaviorSpec` + mockk

**설계 문서:** `docs/superpowers/specs/2026-07-29-diet-tracking-additions-design.md`
**바탕 설계:** `docs/superpowers/specs/2026-07-27-diet-tracking-backend-design.md`

## Global Constraints

- 패키지 루트는 `com.toy.backend.diet`. 앱 전용 에러 코드는 `DietErrorCode`.
- **테이블명은 단수형이다**(2026-07-29 관례 변경). 이 계획은 새 테이블을 만들지 않는다.
- 모든 enum 컬럼에 `columnDefinition = "varchar(N)"` 명시.
- 응답 규칙: 생성은 `@ResponseCreated`로 201 + Location, 수정·삭제는 204, 조회는 `DataResponseBody`.
- **타인 소유 리소스는 `ErrorCode.RESOURCE_NOT_FOUND`(404)로 존재를 숨긴다.** 403을 쓰지 않는다.
- 수치 타입: 영양소·나트륨(mg)은 `Double`, 목표치·점수는 `Int`. `BigDecimal` 금지.
- 커밋 전 `./gradlew spotlessApply` 필수. 테스트는 `./gradlew :daily-record:test`.
- 테스트는 kotest `BehaviorSpec` + mockk. 격리 모드가 `InstancePerLeaf`라 리프에서만 초기화하려면 `beforeContainer`.
- 커밋 메시지·코드 주석은 한국어. Jackson 3(`tools.jackson.*`)만 쓴다.
- 저장소 전제(`AGENTS.md`): 라즈베리파이 단일 인스턴스, 사용자 2명, 하루 수십 건. 동시성 방어를 넣지 않는다.

## File Structure

```
diet/meal/       MealDtos·MealService 수정(사진 선택), MealItemRepository·FrequentItem* 신규
diet/food/       Food·FoodCsvParser·FoodSeeder·FoodDtos 수정 (주의 영양소 3종)
diet/profile/    NutrientLimitPolicy 신규, NutritionTargets·Calculator·NutritionProfile 수정
diet/daily/      NutrientLimit 신규, DayResponse·DailyDietService 수정, DietStats* 신규
diet/feedback/   DietFeedbackPrompts 수정 (하루 프롬프트에만 주의 영양소)
scripts/         build-food-csv.py 수정 (컬럼 3개 추가)
```

---

### Task 1: 사진 없는 기록

지금은 사진을 찍지 않으면 끼니를 만들 수 없다. 과자 하나를 기록하려고 사진을 찍게 만들면 기록을 안 하게 된다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealDtos.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealService.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/MealConfirmTest.kt`

**Interfaces:**
- Consumes: 기존 `MealService.confirm` 경로 전부
- Produces: `MealConfirmRequest.analysisId: Long?` (nullable)

- [ ] **Step 1: 실패 테스트 작성**

`MealConfirmTest.kt`의 `Given("끼니 확정")` 블록 끝에 `When`을 추가한다:

```kotlin
            When("사진 없이(analysisId 없이) 확정하면") {
                every { repository.save(any()) } answers { (firstArg() as Meal).withId(51L) }

                val id =
                    service.confirm(
                        "testuser",
                        MealConfirmRequest(
                            date = LocalDate.of(2026, 7, 29),
                            mealType = MealType.SNACK,
                            analysisId = null,
                            items = userItems,
                        ),
                    )

                Then("사진 첨부와 분석 삭제를 아예 하지 않는다") {
                    id shouldBe 51L
                    verify(exactly = 0) { fileService.attachFile(any(), any()) }
                    verify(exactly = 0) { analysisRepository.delete(any()) }
                    verify(exactly = 0) { analysisService.requireOwned(any(), any()) }
                }

                Then("사진 없는 끼니가 저장되고 점수·스냅샷은 그대로 계산된다") {
                    verify {
                        repository.save(
                            match { it.photos.isEmpty() && it.score == 76 && it.targetKcal == 2509 },
                        )
                    }
                }

                Then("피드백 생성은 사진 유무와 무관하게 예약된다") {
                    verify { feedbackGenerator.generateForMeal(51L) }
                }
            }
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*MealConfirmTest*"`
Expected: FAIL — `null can not be a value of a non-null type Long`

- [ ] **Step 3: 요청 DTO를 nullable로**

`MealDtos.kt`의 `MealConfirmRequest`를 고친다:

```kotlin
/**
 * 확정 요청에 `fileIds`를 다시 보내지 않는다 — `analysisId`로 서버가 사진 목록을 안다.
 * 클라이언트가 파일 목록을 재구성하다 인식에 쓴 사진과 어긋나는 경로를 없앤다.
 *
 * **`analysisId`가 없으면 사진 없이 기록한다.** 검색·직접 입력으로 만든 항목만 저장되고
 * 사진 첨부를 건너뛴다 — 과자 하나를 적으려고 사진을 찍게 만들면 기록을 안 하게 된다.
 */
data class MealConfirmRequest(
    val date: LocalDate,
    val mealType: MealType,
    val analysisId: Long? = null,
    @field:NotEmpty @field:Valid
    val items: List<MealItemRequest>,
)
```

- [ ] **Step 4: 확정 경로에서 사진 부분을 분기**

`MealService.confirm`을 아래로 교체한다(주석 포함):

```kotlin
    /**
     * 확정. **점수는 동기, 피드백은 비동기다** — 점수는 룰 기반이라 즉시 나오고 사용자가 바로
     * 봐야 하는 값이지만, 피드백은 LLM 텍스트 호출이라 수 초 걸린다.
     *
     * `analysisId`가 없으면 **사진 없는 기록**이다. 분석 조회·`attachFile`·분석 삭제를 통째로
     * 건너뛴다. 프로필은 사진 유무와 무관하게 필요하다 — 목표 스냅샷을 떠야 하기 때문이다.
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
        // 분석은 끼니를 만들기 전에 검증한다 — 확정할 수 없는 분석이면 아무것도 만들지 않고 끝낸다.
        val analysis = request.analysisId?.let { confirmableAnalysis(user, it) }

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
        analysis?.let { attachPhotos(meal, it) }

        val saved = repository.save(meal)
        analysis?.let { analysisRepository.delete(it) }
        // 커밋 뒤에 시작해야 비동기 스레드가 저장된 끼니를 볼 수 있다.
        runAfterCommit { feedbackGenerator.generateForMeal(saved.requiredId) }
        return saved.requiredId
    }

    private fun confirmableAnalysis(
        user: User,
        analysisId: Long,
    ): MealAnalysis {
        val analysis = analysisService.requireOwned(user, analysisId)
        if (analysis.status == AnalysisStatus.PENDING) {
            throw CustomException(DietErrorCode.ANALYSIS_NOT_CONFIRMABLE, analysisId)
        }
        return analysis
    }

    /** 인식이 실패한 사진도 붙인다 — 인식이 안 됐을 뿐 사용자가 찍은 그 끼니의 사진이다. */
    private fun attachPhotos(
        meal: Meal,
        analysis: MealAnalysis,
    ) {
        objectMapper.readValue<AnalysisResult>(analysis.resultJson).photos.forEachIndexed { index, photo ->
            fileService.attachFile(photo.fileId, MEAL_FILE_PREFIX)
            meal.addPhoto(MealPhoto(meal = meal, fileId = photo.fileId, sortOrder = index))
        }
    }
```

`MealAnalysis` import를 파일 상단에 추가한다(`com.toy.backend.diet.analysis.MealAnalysis`).

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*MealConfirmTest*"`
Expected: PASS — 기존 케이스(사진 2장 확정, PENDING 거절, 빈 항목 거절)가 그대로 통과해야 한다.

- [ ] **Step 6: 포맷·전체 테스트·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src
git commit -m "feat: 사진 없이도 끼니를 기록할 수 있게 한다"
```

---

### Task 2: 자주 먹는 음식

설계가 `MealItem`을 별도 테이블로 쪼갠 첫 번째 근거인데 정작 꺼내는 API가 없었다. 매일 먹는 것을 매번 검색하게 만들면 Task 1을 만든 이유가 사라진다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealItemRepository.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/FrequentItemService.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/FrequentItemController.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/FrequentItemServiceTest.kt`

**Interfaces:**
- Consumes: `MealItem`(`meal`·`foodName`·`foodCode`·수치·`source`), `FoodNameNormalizer.normalize`, `UserRepository.findByUsername`
- Produces:
  - `MealItemRepository.findEatenBetween(User, LocalDate, LocalDate): List<MealItem>`
  - `data class FrequentItemResponse(foodName, foodCode, quantityG, kcal, carbsG, proteinG, fatG, source, count, lastEatenOn)`
  - `FrequentItemService.list(username, days, size)` / `.aggregate(user, from, to): List<FrequentItemResponse>` — **Task 6이 `aggregate`를 재사용한다**

- [ ] **Step 1: 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/FrequentItemServiceTest.kt`:

```kotlin
package com.toy.backend.diet.meal

import com.toy.backend.common.entity.withId
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.dietUser
import com.toy.backend.user.UserRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate

class FrequentItemServiceTest :
    BehaviorSpec({
        val repository = mockk<MealItemRepository>()
        val userRepository = mockk<UserRepository>()
        val service = FrequentItemService(repository, userRepository)

        val user = dietUser()

        /** 리포지토리는 최근순으로 준다(쿼리가 그렇게 정렬한다). 대표 항목은 그 첫 건이다. */
        fun item(
            id: Long,
            name: String,
            date: LocalDate,
            quantityG: Double = 200.0,
            kcal: Double = 300.0,
            foodCode: String? = null,
        ): MealItem {
            val meal =
                Meal(
                    user = user,
                    date = date,
                    mealType = MealType.LUNCH,
                    weightKg = 70.0,
                    targetKcal = 2509,
                    targetCarbsG = 345,
                    targetProteinG = 94,
                    targetFatG = 84,
                ).withId(id * 10)
            return MealItem(
                meal = meal,
                foodName = name,
                foodCode = foodCode,
                quantityG = quantityG,
                kcal = kcal,
                carbsG = 10.0,
                proteinG = 20.0,
                fatG = 5.0,
                source = NutritionSource.DB_MATCHED,
            ).withId(id)
        }

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("자주 먹는 음식 목록") {
            When("제육볶음 3회, 김치찌개 1회를 먹었으면") {
                every { repository.findEatenBetween(user, any(), any()) } returns
                    listOf(
                        item(1L, "제육볶음", LocalDate.of(2026, 7, 28), quantityG = 150.0, kcal = 250.0, foodCode = "D1"),
                        item(2L, "김치찌개", LocalDate.of(2026, 7, 27), foodCode = "D2"),
                        item(3L, "제육볶음", LocalDate.of(2026, 7, 20), quantityG = 300.0, kcal = 500.0, foodCode = "D1"),
                        item(4L, "제육볶음", LocalDate.of(2026, 7, 10), quantityG = 200.0, kcal = 400.0, foodCode = "D1"),
                    )

                val result = service.list("testuser", days = 30, size = 20)

                Then("빈도순으로 온다") {
                    result[0].foodName shouldBe "제육볶음"
                    result[0].count shouldBe 3
                    result[1].foodName shouldBe "김치찌개"
                    result[1].count shouldBe 1
                }

                Then("대표 수치는 가장 최근에 먹은 값이다 — 마지막에 먹은 양이 다음에 먹을 양에 가깝다") {
                    result[0].quantityG shouldBe 150.0
                    result[0].kcal shouldBe 250.0
                    result[0].lastEatenOn shouldBe LocalDate.of(2026, 7, 28)
                }
            }

            When("빈도가 같으면") {
                every { repository.findEatenBetween(user, any(), any()) } returns
                    listOf(
                        item(1L, "비빔밥", LocalDate.of(2026, 7, 28), foodCode = "D3"),
                        item(2L, "국밥", LocalDate.of(2026, 7, 20), foodCode = "D4"),
                    )

                val result = service.list("testuser", days = 30, size = 20)

                Then("최근에 먹은 것이 위로 온다") {
                    result[0].foodName shouldBe "비빔밥"
                    result[1].foodName shouldBe "국밥"
                }
            }

            When("직접 입력해서 foodCode가 없는 항목이면") {
                every { repository.findEatenBetween(user, any(), any()) } returns
                    listOf(
                        item(1L, "엄마 김치", LocalDate.of(2026, 7, 28)),
                        item(2L, "엄마김치", LocalDate.of(2026, 7, 27)),
                    )

                val result = service.list("testuser", days = 30, size = 20)

                Then("정규화한 이름으로 묶여 띄어쓰기 차이를 흡수한다") {
                    result.size shouldBe 1
                    result[0].count shouldBe 2
                    result[0].foodName shouldBe "엄마 김치"
                }
            }

            When("size보다 종류가 많으면") {
                every { repository.findEatenBetween(user, any(), any()) } returns
                    (1L..5L).map { item(it, "음식$it", LocalDate.of(2026, 7, 20), foodCode = "D$it") }

                Then("size만큼만 자른다") {
                    service.list("testuser", days = 30, size = 2).size shouldBe 2
                }
            }

            When("기록이 없으면") {
                every { repository.findEatenBetween(user, any(), any()) } returns emptyList()

                Then("빈 목록 — 오류가 아니다") {
                    service.list("testuser", days = 30, size = 20) shouldBe emptyList()
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*FrequentItemServiceTest*"`
Expected: FAIL — `Unresolved reference: MealItemRepository`

- [ ] **Step 3: 리포지토리 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealItemRepository.kt`:

```kotlin
package com.toy.backend.diet.meal

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface MealItemRepository : JpaRepository<MealItem, Long> {
    /**
     * 기간 내 먹은 항목을 **최근순**으로 준다. 정렬이 계약이다 — 호출자가 묶음의 첫 건을
     * 대표로 쓴다. `join fetch`로 `meal`을 함께 읽는다(날짜를 봐야 하고, LAZY면 항목마다 쿼리가 난다).
     */
    @Query(
        """
        select i from MealItem i
        join fetch i.meal m
        where m.user = :user and m.date between :from and :to
        order by m.date desc, i.id desc
        """,
    )
    fun findEatenBetween(
        @Param("user") user: User,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<MealItem>
}
```

- [ ] **Step 4: 서비스와 DTO 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/FrequentItemService.kt`:

```kotlin
package com.toy.backend.diet.meal

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.food.FoodNameNormalizer
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 응답 한 건이 그대로 `MealItemRequest`가 되도록 필드를 맞춘다 — 앱이 탭 한 번으로 담고
 * 영양소를 다시 계산하지 않는다.
 */
data class FrequentItemResponse(
    val foodName: String,
    val foodCode: String?,
    val quantityG: Double,
    val kcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val source: NutritionSource,
    val count: Int,
    val lastEatenOn: LocalDate,
)

@Service
@Transactional(readOnly = true)
class FrequentItemService(
    private val repository: MealItemRepository,
    private val userRepository: UserRepository,
) {
    fun list(
        username: String,
        days: Int,
        size: Int,
    ): List<FrequentItemResponse> {
        val to = LocalDate.now()
        val from = to.minusDays(days.coerceIn(1, MAX_DAYS).toLong())
        return aggregate(findUser(username), from, to).take(size.coerceIn(1, MAX_SIZE))
    }

    /**
     * 기간 내 항목을 빈도순(동률이면 최근순)으로 묶는다. Task 6의 기간 통계가 그대로 재사용한다.
     *
     * **메모리에서 묶는다.** `group by`로 빈도를 구한 뒤 대표 항목을 다시 찾으면 N+1이 되는데,
     * 사용자 2명·30일이면 많아야 수백 건이라 그 복잡도를 낼 이유가 없다.
     */
    fun aggregate(
        user: User,
        from: LocalDate,
        to: LocalDate,
    ): List<FrequentItemResponse> =
        repository
            .findEatenBetween(user, from, to)
            // foodCode가 없는 직접 입력 항목은 정규화한 이름으로 묶어 띄어쓰기 차이를 흡수한다.
            .groupBy { it.foodCode ?: FoodNameNormalizer.normalize(it.foodName) }
            .map { (_, group) -> group.toResponse() }
            .sortedWith(compareByDescending<FrequentItemResponse> { it.count }.thenByDescending { it.lastEatenOn })

    /** 리포지토리가 최근순으로 주므로 첫 건이 가장 최근에 먹은 것이다. */
    private fun List<MealItem>.toResponse(): FrequentItemResponse {
        val latest = first()
        return FrequentItemResponse(
            foodName = latest.foodName,
            foodCode = latest.foodCode,
            quantityG = latest.quantityG,
            kcal = latest.kcal,
            carbsG = latest.carbsG,
            proteinG = latest.proteinG,
            fatG = latest.fatG,
            source = latest.source,
            count = size,
            lastEatenOn = latest.meal.date,
        )
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    companion object {
        private const val MAX_DAYS = 90
        private const val MAX_SIZE = 50
    }
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*FrequentItemServiceTest*"`
Expected: PASS (5 When)

- [ ] **Step 6: 컨트롤러 작성**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/FrequentItemController.kt`:

```kotlin
package com.toy.backend.diet.meal

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "자주 먹는 음식", description = "내가 저장했던 항목을 빈도순으로")
@RestController
@RequestMapping("/diet/items")
class FrequentItemController(
    private val service: FrequentItemService,
) {
    @GetMapping("/frequent")
    @Operation(summary = "자주 먹는 음식 — 응답 한 건이 그대로 끼니 항목이 된다")
    fun frequent(
        @Parameter(description = "최근 며칠 (1~90)", example = "30")
        @RequestParam(defaultValue = "30") days: Int,
        @Parameter(description = "최대 건수 (1~50)", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<List<FrequentItemResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.list(authentication.name, days, size)))
}
```

- [ ] **Step 7: 포맷·전체 테스트·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src
git commit -m "feat: 자주 먹는 음식 목록 추가"
```

---

### Task 3: 식품DB에 당류·나트륨·식이섬유

원본 두 데이터셋 모두 이 셋을 갖고 있는데 정제 단계에서 버리고 있었다. 스크립트·엔티티·파서·시더를 함께 고치고 CSV를 다시 만든다.

**Files:**
- Modify: `scripts/build-food-csv.py`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/Food.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodCsvParser.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodSeeder.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodDtos.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/food/FoodCsvParserTest.kt`
- Modify: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/DietFixtures.kt` (`dummyFood`에 인자 3개)

**Interfaces:**
- Produces: `Food.sugarPer100g`·`sodiumMgPer100g`·`fiberPer100g`, `NutritionAmount.sugarG`·`sodiumMg`·`fiberG`, CSV 10컬럼

- [ ] **Step 1: 실패 테스트 작성**

`FoodCsvParserTest.kt`의 헤더 상수와 각 케이스를 새 컬럼 순서로 바꾼다. 헤더:

```kotlin
        val header =
            "code,servingSizeG,kcalPer100g,carbsPer100g,proteinPer100g,fatPer100g," +
                "sugarPer100g,sodiumMgPer100g,fiberPer100g,name"
```

정상 행 케이스를 아래로 바꾸고, 결측 케이스를 추가한다:

```kotlin
        Given("정상 행") {
            When("한 줄을 파싱하면") {
                val foods =
                    FoodCsvParser
                        .parse(
                            sequenceOf(header, "D000001,300,180.5,12.3,15.1,8.2,3.4,620,2.1,제육볶음"),
                            FoodDataset.DISH,
                        ).toList()

                Then("주의 영양소까지 함께 담긴다") {
                    foods[0].name shouldBe "제육볶음"
                    foods[0].kcalPer100g shouldBe 180.5
                    foods[0].sugarPer100g shouldBe 3.4
                    foods[0].sodiumMgPer100g shouldBe 620.0
                    foods[0].fiberPer100g shouldBe 2.1
                }
            }
        }

        Given("주의 영양소가 빈 행") {
            When("파싱하면") {
                val foods =
                    FoodCsvParser
                        .parse(sequenceOf(header, "D000007,200,150,20,10,3,,,,된장국"), FoodDataset.DISH)
                        .toList()

                Then("0으로 채우고 행은 살린다 — 탄단지가 멀쩡한데 버리면 그 음식을 못 쓴다") {
                    foods.size shouldBe 1
                    foods[0].sugarPer100g shouldBe 0.0
                    foods[0].sodiumMgPer100g shouldBe 0.0
                    foods[0].fiberPer100g shouldBe 0.0
                }
            }
        }
```

나머지 기존 케이스(이름에 쉼표, 1인분 결측, 망가진 행)의 데이터 줄도 컬럼 10개에 맞춰 고친다. **검증 내용은 그대로 유지한다.**

`DietFixtures.kt`의 `dummyFood`에 인자를 더한다(기존 인자 순서는 유지):

```kotlin
    sugarPer100g: Double = 3.0,
    sodiumMgPer100g: Double = 500.0,
    fiberPer100g: Double = 2.0,
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*FoodCsvParserTest*"`
Expected: FAIL — `No value passed for parameter 'sugarPer100g'` 또는 파싱 결과 불일치

- [ ] **Step 3: 엔티티에 컬럼 추가**

`Food.kt`의 생성자에 `fatPer100g` 다음으로 더한다:

```kotlin
    @Column(name = "sugar_per_100g", nullable = false)
    var sugarPer100g: Double,
    @Column(name = "sodium_mg_per_100g", nullable = false)
    var sodiumMgPer100g: Double,
    @Column(name = "fiber_per_100g", nullable = false)
    var fiberPer100g: Double,
```

`NutritionAmount`와 `nutritionFor`도 함께 넓힌다:

```kotlin
data class NutritionAmount(
    val quantityG: Double,
    val kcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val sugarG: Double,
    val sodiumMg: Double,
    val fiberG: Double,
)

fun Food.nutritionFor(portion: Double): NutritionAmount {
    val quantityG = servingSizeG * portion
    val ratio = quantityG / 100.0
    return NutritionAmount(
        quantityG = quantityG,
        kcal = kcalPer100g * ratio,
        carbsG = carbsPer100g * ratio,
        proteinG = proteinPer100g * ratio,
        fatG = fatPer100g * ratio,
        sugarG = sugarPer100g * ratio,
        sodiumMg = sodiumMgPer100g * ratio,
        fiberG = fiberPer100g * ratio,
    )
}
```

- [ ] **Step 4: 파서 컬럼 수 변경**

`FoodCsvParser.kt`의 `COLUMN_COUNT`를 10으로 바꾸고 `parseLine`을 고친다.
**이름이 마지막이라는 성질은 그대로다** — `split(',', limit = 10)`의 10번째가 나머지 전부다.

```kotlin
    private const val COLUMN_COUNT = 10
```

```kotlin
        val code = columns[0].trim().takeIf { it.isNotBlank() } ?: return null
        val name = columns[9].trim().takeIf { it.isNotBlank() } ?: return null
        val servingSizeG = columns[1].trim().toDoubleOrNull() ?: FoodPolicy.DEFAULT_SERVING_SIZE_G
        val kcal = columns[2].trim().toDoubleOrNull() ?: return null
        val carbs = columns[3].trim().toDoubleOrNull() ?: return null
        val protein = columns[4].trim().toDoubleOrNull() ?: return null
        val fat = columns[5].trim().toDoubleOrNull() ?: return null
        // 주의 영양소는 없으면 0으로 채운다 — 탄단지가 멀쩡한 행을 이것 때문에 버리면 손실이 크다.
        val sugar = columns[6].trim().toDoubleOrNull() ?: 0.0
        val sodium = columns[7].trim().toDoubleOrNull() ?: 0.0
        val fiber = columns[8].trim().toDoubleOrNull() ?: 0.0
```

`Food(...)` 생성에 세 값을 넘긴다.

- [ ] **Step 5: 시더의 원시 SQL 확장**

`FoodSeeder.kt`의 `INSERT_SQL`과 `insertAll`을 고친다. **컬럼 수와 물음표 수, `ps.setX` 인덱스가 전부 맞아야 한다** — 어긋나면 30만 행이 조용히 잘못된 컬럼에 들어간다.

```kotlin
        private val INSERT_SQL =
            """
            insert into food (code, name, normalized_name, dataset, serving_size_g,
                              kcal_per_100g, carbs_per_100g, protein_per_100g, fat_per_100g,
                              sugar_per_100g, sodium_mg_per_100g, fiber_per_100g,
                              created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (code) do nothing
            """.trimIndent()
```

`insertAll`의 바인딩에 세 줄을 더하고 `created_at`·`updated_at` 인덱스를 13·14로 민다:

```kotlin
            ps.setDouble(10, food.sugarPer100g)
            ps.setDouble(11, food.sodiumMgPer100g)
            ps.setDouble(12, food.fiberPer100g)
            ps.setObject(13, now)
            ps.setObject(14, now)
```

- [ ] **Step 6: 검색 응답에 노출**

`FoodDtos.kt`의 `FoodResponse`에 세 필드를 더하고 `toResponse()`에 매핑한다(앱이 수량을 곱해 항목을 만들려면 필요하다).

- [ ] **Step 7: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*Food*"`
Expected: PASS

- [ ] **Step 8: 정제 스크립트에 컬럼 추가**

`scripts/build-food-csv.py`를 고친다. `OUT_HEADER`는 **이름을 마지막에 유지**한다:

```python
OUT_HEADER = [
    "code", "servingSizeG", "kcalPer100g",
    "carbsPer100g", "proteinPer100g", "fatPer100g",
    "sugarPer100g", "sodiumMgPer100g", "fiberPer100g", "name",
]
```

`COLUMN_HINTS`에 셋을 더한다:

```python
    "sugar": ["당류"],
    "sodium": ["나트륨"],
    "fiber": ["식이섬유"],
```

**힌트 매칭이 다른 컬럼을 물지 않는지 확인해라** — `find_column`이 원본 컬럼 순서대로 훑어 첫 일치를 쓴다. 원본에 `당류(g)`·`나트륨(mg)`·`식이섬유(g)`가 각각 하나씩 있는 것은 확인됐다.

셋은 **없어도 되는 컬럼**으로 다룬다(`OPTIONAL`에 넣지 말고, 값이 없으면 빈 칸을 쓴다) — 판본이 바뀌어 컬럼이 빠져도 탄단지는 살려야 한다:

```python
        sugar = to_float(row.get(columns["sugar"])) if columns["sugar"] else None
        sodium = to_float(row.get(columns["sodium"])) if columns["sodium"] else None
        fiber = to_float(row.get(columns["fiber"])) if columns["fiber"] else None
```

출력 행에 세 값을 넣는다. **나트륨은 mg이라 100g 환산 계수(`factor`)를 똑같이 곱한다** — 단위만 다를 뿐 기준량 환산은 같다:

```python
                f"{sugar * factor:.2f}" if sugar is not None else "",
                f"{sodium * factor:.1f}" if sodium is not None else "",
                f"{fiber * factor:.2f}" if fiber is not None else "",
```

`missing` 검사에서 세 키를 제외해야 컬럼이 없어도 죽지 않는다.

- [ ] **Step 9: CSV 재생성 (사람이 확인하는 단계)**

원본 xlsx는 `~/Downloads`에 있다. `resources/food/README.md`의 절차 그대로 두 데이터셋을 다시 만든다:

```bash
python3 scripts/xlsx-to-csv.py "$HOME/Downloads/20251229_음식DB 19495건.xlsx" /tmp/food-raw.csv
python3 scripts/build-food-csv.py /tmp/food-raw.csv \
  apps/daily-record/src/main/resources/food/food-nutrition.csv
python3 scripts/xlsx-to-csv.py "$HOME/Downloads/20260626_가공식품DB_298288건.xlsx" /tmp/processed-raw.csv
python3 scripts/build-food-csv.py /tmp/processed-raw.csv \
  apps/daily-record/src/main/resources/food/processed-food-nutrition.csv
head -3 apps/daily-record/src/main/resources/food/food-nutrition.csv
wc -l apps/daily-record/src/main/resources/food/*.csv
```

**`xlsx-to-csv.py`의 `WANTED`에도 세 컬럼을 더해야 한다** — 안 그러면 중간 CSV에서 이미 빠져 정제 스크립트가 못 찾는다. 이걸 빠뜨리면 "컬럼을 찾지 못했습니다"가 아니라 **조용히 빈 값**이 되니 출력의 앞 몇 줄을 눈으로 확인해라.

행 수는 이전과 같아야 한다(음식 6,090 / 가공식품 298,271). 달라졌으면 힌트 매칭이 다른 컬럼을 물었을 가능성이 높다.

**CSV는 커밋하지 않는다**(`.gitignore` 대상).

- [ ] **Step 10: 로컬 DB 재적재**

컬럼이 늘었으므로 `food` 테이블을 비워야 시더가 다시 돈다. 테이블명이 단수로 바뀌면서 생긴 옛 테이블도 함께 정리한다:

```sql
drop table if exists foods;
truncate table food;
```

앱을 띄워 `식품DB 적재 완료: dataset=DISH, 6090건` / `dataset=PROCESSED, 298271건`이 찍히는지 확인하고, 임의의 행에서 `sugar_per_100g`·`sodium_mg_per_100g`가 채워졌는지 본다.

- [ ] **Step 11: 포맷·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src scripts
git commit -m "feat: 식품DB에 당류·나트륨·식이섬유를 추가한다"
```

---

### Task 4: 끼니와 프로필에 주의 영양소

식품DB가 값을 갖게 됐으니 이제 끼니에 저장하고 목표를 세운다. 기준값은 전부 KDRIs에서 오고, **소급 변경을 막기 위해 `Meal` 스냅샷에도 함께 넣는다.**

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutrientLimitPolicy.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionTargetCalculator.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionProfile.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutritionProfileDtos.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealItem.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/Meal.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealDtos.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealService.kt` (스냅샷 3개)
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DietFeedbackPrompts.kt` (`NutritionTotals` 확장)
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/profile/NutritionTargetCalculatorTest.kt`

**Interfaces:**
- Produces:
  - `object NutrientLimitPolicy` — `SODIUM_MG_LIMIT`·`FIBER_G_MALE`·`FIBER_G_FEMALE`·`SUGAR_ENERGY_RATIO`
  - `NutritionTargets(kcal, carbsG, proteinG, fatG, sugarG, sodiumMg, fiberG)` — **필드 3개 추가**
  - `NutritionTotals(kcal, carbsG, proteinG, fatG, sugarG, sodiumMg, fiberG)` — **필드 3개 추가**
  - `MealItem.sugarG`·`sodiumMg`·`fiberG`, `Meal.sugarG`·`sodiumMg`·`fiberG` + 스냅샷 `targetSugarG`·`targetSodiumMg`·`targetFiberG`

- [ ] **Step 1: 실패 테스트 작성**

`NutritionTargetCalculatorTest.kt`의 두 케이스에 단언을 더한다:

```kotlin
                Then("주의 영양소 기준도 함께 나온다 — 당류는 목표 칼로리의 20%, 나트륨은 상수, 식이섬유는 성별") {
                    targets.sugarG shouldBe 125 // 2509 × 0.20 / 4 = 125.45
                    targets.sodiumMg shouldBe 2300
                    targets.fiberG shouldBe 30 // 남성 충분섭취량
                }
```

여성 케이스에는:

```kotlin
                Then("여성은 식이섬유 기준이 다르다") {
                    targets.sugarG shouldBe 72 // 1439 × 0.20 / 4 = 71.95
                    targets.sodiumMg shouldBe 2300
                    targets.fiberG shouldBe 20
                }
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*NutritionTargetCalculatorTest*"`
Expected: FAIL — `Unresolved reference: sugarG`

- [ ] **Step 3: 기준 상수 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/profile/NutrientLimitPolicy.kt`:

```kotlin
package com.toy.backend.diet.profile

/**
 * 주의 영양소 기준. **전부 2025 한국인 영양소 섭취기준(KDRIs)에서 온 국가 기준이고, 우리가 정한
 * 값이 하나도 없다** — 점수 정책(`DietScorePolicy`)과 달리 자체 추정치가 섞이지 않았다.
 * 그래서 점수에 넣지 않고 「기준 대비 표시」로만 쓴다.
 *
 * 기준이 개정되면(KDRIs는 5년 주기다) 이 상수와 `NutrientLimit`의 문구를 함께 바꾼다.
 */
object NutrientLimitPolicy {
    /** 만성질환위험감소섭취량(성인). 충분섭취량 1,500mg보다 느슨한 쪽을 상한으로 쓴다. */
    const val SODIUM_MG_LIMIT = 2300

    /** 충분섭취량 19~64세 */
    const val FIBER_G_MALE = 30
    const val FIBER_G_FEMALE = 20

    /** 에너지적정비율 상한. **총당류** 기준이며 첨가당(10% 미만)과 다르다 — 식품DB의 당류 컬럼도 총당류다. */
    const val SUGAR_ENERGY_RATIO = 0.20
    const val KCAL_PER_G_SUGAR = 4.0
}
```

- [ ] **Step 4: 목표 계산 확장**

`NutritionTargetCalculator.kt`의 `NutritionTargets`에 세 필드를 더하고, `calculate`가 채우게 한다:

```kotlin
data class NutritionTargets(
    val kcal: Int,
    val carbsG: Int,
    val proteinG: Int,
    val fatG: Int,
    val sugarG: Int,
    val sodiumMg: Int,
    val fiberG: Int,
)
```

`calculate`의 반환에 더한다:

```kotlin
            sugarG = (kcal * NutrientLimitPolicy.SUGAR_ENERGY_RATIO / NutrientLimitPolicy.KCAL_PER_G_SUGAR).roundToInt(),
            sodiumMg = NutrientLimitPolicy.SODIUM_MG_LIMIT,
            fiberG = if (gender == Gender.MALE) NutrientLimitPolicy.FIBER_G_MALE else NutrientLimitPolicy.FIBER_G_FEMALE,
```

> 나트륨은 상수인데도 목표에 담아 저장한다. 그래야 기준이 개정돼도 **과거 기록의 판정이 소급
> 변경되지 않는다** — 스냅샷의 존재 이유가 그것이다.

- [ ] **Step 5: 프로필에 목표 컬럼 추가**

`NutritionProfile.kt`에 컬럼 셋을 더하고 `applyTargets`·`targets()`를 넓힌다:

```kotlin
    @Column(name = "target_sugar_g", nullable = false)
    var targetSugarG: Int = 0,
    @Column(name = "target_sodium_mg", nullable = false)
    var targetSodiumMg: Int = 0,
    @Column(name = "target_fiber_g", nullable = false)
    var targetFiberG: Int = 0,
```

```kotlin
    fun applyTargets(targets: NutritionTargets) {
        this.targetKcal = targets.kcal
        this.targetCarbsG = targets.carbsG
        this.targetProteinG = targets.proteinG
        this.targetFatG = targets.fatG
        this.targetSugarG = targets.sugarG
        this.targetSodiumMg = targets.sodiumMg
        this.targetFiberG = targets.fiberG
    }

    fun targets(): NutritionTargets =
        NutritionTargets(targetKcal, targetCarbsG, targetProteinG, targetFatG, targetSugarG, targetSodiumMg, targetFiberG)
```

`NutritionProfileDtos.kt`의 `NutritionProfileResponse`와 `toResponse()`에도 세 필드를 더한다.

- [ ] **Step 6: 끼니 항목·합계·스냅샷 확장**

`MealItem.kt` 생성자에 `fatG` 다음으로:

```kotlin
    @Column(name = "sugar_g", nullable = false)
    var sugarG: Double = 0.0,
    @Column(name = "sodium_mg", nullable = false)
    var sodiumMg: Double = 0.0,
    @Column(name = "fiber_g", nullable = false)
    var fiberG: Double = 0.0,
```

> 기본값 0을 주는 이유 — 앱이 아직 이 값을 안 보낼 수 있고, 안 보내도 탄단지 기반 점수는
> 그대로 나와야 한다.

`Meal.kt`에 합계 셋을 더하고 `replaceItems`에서 합산한다:

```kotlin
    @Column(name = "sugar_g", nullable = false)
    var sugarG: Double = 0.0,
    @Column(name = "sodium_mg", nullable = false)
    var sodiumMg: Double = 0.0,
    @Column(name = "fiber_g", nullable = false)
    var fiberG: Double = 0.0,
```

```kotlin
        sugarG = items.sumOf { it.sugarG }
        sodiumMg = items.sumOf { it.sodiumMg }
        fiberG = items.sumOf { it.fiberG }
```

스냅샷 셋도 `Meal` 생성자에 더하고 `targets()`를 넓힌다:

```kotlin
    @Column(name = "target_sugar_g", nullable = false)
    var targetSugarG: Int,
    @Column(name = "target_sodium_mg", nullable = false)
    var targetSodiumMg: Int,
    @Column(name = "target_fiber_g", nullable = false)
    var targetFiberG: Int,
```

```kotlin
    fun targets(): NutritionTargets =
        NutritionTargets(targetKcal, targetCarbsG, targetProteinG, targetFatG, targetSugarG, targetSodiumMg, targetFiberG)
```

`MealService.confirm`의 `Meal(...)` 생성에 스냅샷 세 줄을 더한다(`profile.targetSugarG` 등).

`MealDtos.kt` — `MealItemRequest`·`MealItemResponse`·`MealResponse`에 셋을 더하고, `toEntity`·`toResponse`에 매핑한다. **`MealItemRequest`의 셋은 기본값 0.0을 준다.**

- [ ] **Step 7: 합계 타입 확장**

`DietFeedbackPrompts.kt`의 `NutritionTotals`와 `List<Meal>.totals()`를 넓힌다:

```kotlin
data class NutritionTotals(
    val kcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val sugarG: Double,
    val sodiumMg: Double,
    val fiberG: Double,
)

fun List<Meal>.totals(): NutritionTotals =
    NutritionTotals(
        kcal = sumOf { it.totalKcal },
        carbsG = sumOf { it.carbsG },
        proteinG = sumOf { it.proteinG },
        fatG = sumOf { it.fatG },
        sugarG = sumOf { it.sugarG },
        sodiumMg = sumOf { it.sodiumMg },
        fiberG = sumOf { it.fiberG },
    )
```

- [ ] **Step 8: 컴파일 오류를 따라가며 픽스처·테스트 보정**

`NutritionTargets`·`Meal` 생성자가 넓어져 기존 테스트가 깨진다. **검증 내용을 바꾸지 말고 인자만 채워라.** `DietFixtures.kt`의 `dummyProfile`에도 목표 셋을 더한다(기본값 `targetSugarG = 125`, `targetSodiumMg = 2300`, `targetFiberG = 30`).

Run: `./gradlew :daily-record:test`
Expected: PASS — 기존 점수·확정·조회·하루 집계 테스트가 전부 그대로 통과해야 한다.

- [ ] **Step 9: 포맷·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src
git commit -m "feat: 끼니와 프로필에 당류·나트륨·식이섬유 목표를 추가한다"
```

---

### Task 5: 하루 응답의 주의 영양소 판정

값과 기준이 다 준비됐으니 이제 판정해서 내려준다. **판정은 서버가 한다** — `scoreBasis`와 같은 원칙이다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/NutrientLimit.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DayResponse.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DailyDietService.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DietFeedbackPrompts.kt` (`day`에만)
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/daily/NutrientLimitEvaluatorTest.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/daily/DailyDietServiceTest.kt`

**Interfaces:**
- Produces:
  - `enum class NutrientStatus { OK, WARN }`
  - `data class NutrientLimit(name, intake, unit, standardText, status)`
  - `NutrientLimitEvaluator.evaluate(totals: NutritionTotals, targets: NutritionTargets): List<NutrientLimit>`
  - `DayResponse.nutrientLimits: List<NutrientLimit>`

- [ ] **Step 1: 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/daily/NutrientLimitEvaluatorTest.kt`:

```kotlin
package com.toy.backend.diet.daily

import com.toy.backend.diet.feedback.NutritionTotals
import com.toy.backend.diet.profile.NutritionTargets
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class NutrientLimitEvaluatorTest :
    BehaviorSpec({
        val targets = NutritionTargets(2000, 275, 75, 67, sugarG = 100, sodiumMg = 2300, fiberG = 30)

        fun totals(
            sugarG: Double,
            sodiumMg: Double,
            fiberG: Double,
        ) = NutritionTotals(2000.0, 275.0, 75.0, 67.0, sugarG, sodiumMg, fiberG)

        Given("주의 영양소 판정") {
            When("나트륨이 기준을 넘으면") {
                val limits = NutrientLimitEvaluator.evaluate(totals(50.0, 2850.0, 35.0), targets)
                val sodium = limits.first { it.name == "나트륨" }

                Then("WARN — 상한을 넘는 쪽이 문제다") {
                    sodium.status shouldBe NutrientStatus.WARN
                    sodium.intake shouldBe 2850.0
                    sodium.unit shouldBe "mg"
                    sodium.standardText shouldBe "2,300mg 이하"
                }
            }

            When("식이섬유가 기준에 못 미치면") {
                val limits = NutrientLimitEvaluator.evaluate(totals(50.0, 2000.0, 12.0), targets)
                val fiber = limits.first { it.name == "식이섬유" }

                Then("WARN — 이쪽은 미달이 문제다") {
                    fiber.status shouldBe NutrientStatus.WARN
                    fiber.standardText shouldBe "30g 이상"
                }
            }

            When("전부 기준 안이면") {
                val limits = NutrientLimitEvaluator.evaluate(totals(50.0, 2000.0, 35.0), targets)

                Then("셋 다 OK") {
                    limits.size shouldBe 3
                    limits.all { it.status == NutrientStatus.OK } shouldBe true
                }
            }

            When("정확히 기준값이면") {
                val limits = NutrientLimitEvaluator.evaluate(totals(100.0, 2300.0, 30.0), targets)

                Then("경계는 OK다 — 「이하」·「이상」이므로 같은 값은 넘지 않은 것이다") {
                    limits.all { it.status == NutrientStatus.OK } shouldBe true
                }
            }
        }
    })
```

`DailyDietServiceTest`에는 응답에 실리는지와 **스냅샷 기준을 쓰는지**를 더한다:

```kotlin
                Then("주의 영양소 판정이 응답에 실린다") {
                    response.nutrientLimits.size shouldBe 3
                }
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*NutrientLimitEvaluatorTest*"`
Expected: FAIL — `Unresolved reference: NutrientLimitEvaluator`

- [ ] **Step 3: 판정기 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/NutrientLimit.kt`:

```kotlin
package com.toy.backend.diet.daily

import com.toy.backend.diet.feedback.NutritionTotals
import com.toy.backend.diet.profile.NutritionTargets

enum class NutrientStatus { OK, WARN }

/**
 * **점수에 들어가지 않으므로 `penalty`가 없다.** 점수는 여전히 탄단지 비율만 보고 매겨지며,
 * 이 판정은 「기준 대비 표시」일 뿐이다. 앱이 이것을 감점 요인처럼 보이게 하면 안 된다.
 *
 * `standardText`는 사람이 읽는 문구를 그대로 담는다 — 기준이 개정돼도 앱 배포 없이 따라간다.
 */
data class NutrientLimit(
    val name: String,
    val intake: Double,
    val unit: String,
    val standardText: String,
    val status: NutrientStatus,
)

/**
 * 항목마다 문제가 되는 방향이 다르다 — 나트륨·당류는 초과가, 식이섬유는 미달이 문제다.
 * 그 방향을 앱이 알 필요는 없어서 `OK`/`WARN` 둘로만 내려준다.
 */
object NutrientLimitEvaluator {
    fun evaluate(
        totals: NutritionTotals,
        targets: NutritionTargets,
    ): List<NutrientLimit> =
        listOf(
            upperLimit("나트륨", totals.sodiumMg, targets.sodiumMg, "mg"),
            lowerTarget("식이섬유", totals.fiberG, targets.fiberG, "g"),
            upperLimit("당류", totals.sugarG, targets.sugarG, "g"),
        )

    /** 경계는 넘지 않은 것으로 본다 — 「이하」이므로 같은 값은 OK다. */
    private fun upperLimit(
        name: String,
        intake: Double,
        standard: Int,
        unit: String,
    ) = NutrientLimit(
        name = name,
        intake = intake,
        unit = unit,
        standardText = "${format(standard)}$unit 이하",
        status = if (intake > standard) NutrientStatus.WARN else NutrientStatus.OK,
    )

    private fun lowerTarget(
        name: String,
        intake: Double,
        standard: Int,
        unit: String,
    ) = NutrientLimit(
        name = name,
        intake = intake,
        unit = unit,
        standardText = "${format(standard)}$unit 이상",
        status = if (intake < standard) NutrientStatus.WARN else NutrientStatus.OK,
    )

    private fun format(value: Int): String = "%,d".format(value)
}
```

- [ ] **Step 4: 하루 응답에 싣기**

`DayResponse.kt`에 필드를 더한다:

```kotlin
    val nutrientLimits: List<NutrientLimit>,
```

`DailyDietService.getDay`에서 채운다. **끼니가 0건인 날은 빈 목록**이다 — 목표가 없으니 판정할 것도 없다.

```kotlin
        val nutrientLimits = NutrientLimitEvaluator.evaluate(totals, targets)
```

끼니 0건 분기의 `DayResponse`에는 `nutrientLimits = emptyList()`를 넣는다.

- [ ] **Step 5: 하루 프롬프트에만 더하기**

`DietFeedbackPrompts.day`에 한 줄을 더한다. **`meal`에는 넣지 마라** — 나트륨 기준 자체가 하루 단위이고, 끼니 프롬프트는 하루 맥락을 걷어낸 참이다.

```kotlin
            appendLine(
                "[주의 영양소] 나트륨 ${totals.sodiumMg.roundToInt()}mg, " +
                    "식이섬유 ${totals.fiberG.roundToInt()}g, 당류 ${totals.sugarG.roundToInt()}g",
            )
```

- [ ] **Step 6: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test`
Expected: PASS

- [ ] **Step 7: 포맷·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src
git commit -m "feat: 하루 집계에 주의 영양소 판정을 추가한다"
```

---

### Task 6: 기간 통계

주·월을 한 엔드포인트로 처리한다. LLM이 없으니 캐시도 무효화도 없다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DietStatsService.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DietStatsController.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/daily/DietStatsServiceTest.kt`

**Interfaces:**
- Consumes: `MealRepository.findByUserAndDateBetweenOrderByDateAscIdAsc`, `DietScoreCalculator.scoreDay`, `FrequentItemService.aggregate`(Task 2), `List<Meal>.totals()`(Task 4)
- Produces: `DietStatsResponse`, `DailyScore`, `DietStatsService.stats(username, from, to)`

- [ ] **Step 1: 실패 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/diet/daily/DietStatsServiceTest.kt`:

```kotlin
package com.toy.backend.diet.daily

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealItem
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.meal.MealType
import com.toy.backend.diet.meal.FrequentItemService
import com.toy.backend.user.UserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate

class DietStatsServiceTest :
    BehaviorSpec({
        val mealRepository = mockk<MealRepository>()
        val frequentItemService = mockk<FrequentItemService>()
        val userRepository = mockk<UserRepository>()
        val service = DietStatsService(mealRepository, frequentItemService, userRepository)

        val user = dietUser()
        val from = LocalDate.of(2026, 7, 22)
        val to = LocalDate.of(2026, 7, 28)

        /** 목표에 정확히 맞는 끼니 — 그날 점수가 100이 된다. */
        fun perfectMeal(
            id: Long,
            date: LocalDate,
        ): Meal {
            val meal =
                Meal(
                    user = user,
                    date = date,
                    mealType = MealType.LUNCH,
                    weightKg = 70.0,
                    targetKcal = 2000,
                    targetCarbsG = 275,
                    targetProteinG = 75,
                    targetFatG = 67,
                    targetSugarG = 100,
                    targetSodiumMg = 2300,
                    targetFiberG = 30,
                ).withId(id)
            meal.replaceItems(
                listOf(
                    MealItem(
                        meal = meal,
                        foodName = "완벽한 한 끼",
                        foodCode = "D1",
                        quantityG = 500.0,
                        kcal = 2000.0,
                        carbsG = 275.0,
                        proteinG = 75.0,
                        fatG = 67.0,
                        source = NutritionSource.DB_MATCHED,
                    ).withId(id * 10),
                ),
            )
            return meal
        }

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            every { frequentItemService.aggregate(user, from, to) } returns emptyList()
        }

        Given("기간 통계") {
            When("7일 중 2일만 기록했으면") {
                every { mealRepository.findByUserAndDateBetweenOrderByDateAscIdAsc(user, from, to) } returns
                    listOf(perfectMeal(1L, LocalDate.of(2026, 7, 22)), perfectMeal(2L, LocalDate.of(2026, 7, 25)))

                val stats = service.stats("testuser", from, to)

                Then("기록한 날로만 평균을 낸다 — 안 적은 날을 0으로 세면 평균이 무의미해진다") {
                    stats.recordedDays shouldBe 2
                    stats.averageDayScore shouldBe 100
                    stats.averageIntake!!.kcal shouldBe 2000.0
                }

                Then("일별 점수가 날짜순으로 온다") {
                    stats.dailyScores.size shouldBe 2
                    stats.dailyScores[0].date shouldBe LocalDate.of(2026, 7, 22)
                    stats.dailyScores[0].dayScore shouldBe 100
                }
            }

            When("기록이 하나도 없으면") {
                every { mealRepository.findByUserAndDateBetweenOrderByDateAscIdAsc(user, from, to) } returns emptyList()

                val stats = service.stats("testuser", from, to)

                Then("0건 상태를 준다 — 오류가 아니다") {
                    stats.recordedDays shouldBe 0
                    stats.averageDayScore shouldBe null
                    stats.averageIntake shouldBe null
                    stats.dailyScores shouldBe emptyList()
                }
            }

            When("from이 to보다 뒤면") {
                Then("INVALID_REQUEST") {
                    val e = shouldThrow<CustomException> { service.stats("testuser", to, from) }
                    e.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :daily-record:test --tests "*DietStatsServiceTest*"`
Expected: FAIL — `Unresolved reference: DietStatsService`

- [ ] **Step 3: 서비스와 DTO 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DietStatsService.kt`:

```kotlin
package com.toy.backend.diet.daily

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.feedback.NutritionTotals
import com.toy.backend.diet.feedback.totals
import com.toy.backend.diet.meal.FrequentItemResponse
import com.toy.backend.diet.meal.FrequentItemService
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.profile.NutritionTargets
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import kotlin.math.roundToInt

data class DailyScore(
    val date: LocalDate,
    val dayScore: Int,
)

data class DietStatsResponse(
    val from: LocalDate,
    val to: LocalDate,
    /** 기록이 하나라도 있는 날의 수. 평균의 분모다 */
    val recordedDays: Int,
    val averageDayScore: Int?,
    val dailyScores: List<DailyScore>,
    val averageIntake: NutritionTotals?,
    val averageTargets: NutritionTargets?,
    val topFoods: List<FrequentItemResponse>,
)

/**
 * 기간 통계. **전부 `Meal` 합산이라 캐시도 무효화도 없다** — 언제 계산해도 같은 값이 나온다.
 * LLM 조언을 붙이지 않는 이유이기도 하다(붙이면 하루 피드백과 같은 무효화 문제가 생긴다).
 */
@Service
@Transactional(readOnly = true)
class DietStatsService(
    private val mealRepository: MealRepository,
    private val frequentItemService: FrequentItemService,
    private val userRepository: UserRepository,
) {
    fun stats(
        username: String,
        from: LocalDate,
        to: LocalDate,
    ): DietStatsResponse {
        if (from.isAfter(to)) throw CustomException(ErrorCode.INVALID_REQUEST, "from이 to보다 이후일 수 없습니다")
        if (from.plusDays(MAX_RANGE_DAYS) < to) throw CustomException(ErrorCode.INVALID_REQUEST, "기간은 최대 ${MAX_RANGE_DAYS}일입니다")

        val user = findUser(username)
        val byDate = mealRepository.findByUserAndDateBetweenOrderByDateAscIdAsc(user, from, to).groupBy { it.date }
        val topFoods = frequentItemService.aggregate(user, from, to)

        if (byDate.isEmpty()) {
            return DietStatsResponse(from, to, 0, null, emptyList(), null, null, topFoods)
        }

        val days = byDate.toSortedMap().map { (date, meals) -> date to meals }
        val dailyScores = days.map { (date, meals) -> DailyScore(date, dayScoreOf(meals)) }
        val dailyTotals = days.map { (_, meals) -> meals.totals() }
        // 하루 목표는 그날 첫 끼니의 스냅샷이다 — 하루 집계와 같은 규칙이라 값이 어긋나지 않는다.
        val dailyTargets = days.map { (_, meals) -> meals.first().targets() }

        return DietStatsResponse(
            from = from,
            to = to,
            recordedDays = days.size,
            averageDayScore = dailyScores.map { it.dayScore }.average().roundToInt(),
            dailyScores = dailyScores,
            averageIntake = dailyTotals.average(),
            averageTargets = dailyTargets.average(),
            topFoods = topFoods,
        )
    }

    private fun dayScoreOf(meals: List<Meal>): Int {
        val totals = meals.totals()
        return DietScoreCalculator
            .scoreDay(totals.kcal, totals.carbsG, totals.proteinG, totals.fatG, meals.first().targets())
            .score
    }

    private fun List<NutritionTotals>.average(): NutritionTotals =
        NutritionTotals(
            kcal = map { it.kcal }.average(),
            carbsG = map { it.carbsG }.average(),
            proteinG = map { it.proteinG }.average(),
            fatG = map { it.fatG }.average(),
            sugarG = map { it.sugarG }.average(),
            sodiumMg = map { it.sodiumMg }.average(),
            fiberG = map { it.fiberG }.average(),
        )

    /** 몸무게가 바뀌면 목표도 바뀌므로 기간 평균이 하나로 고정되지 않는다. */
    private fun List<NutritionTargets>.average(): NutritionTargets =
        NutritionTargets(
            kcal = map { it.kcal }.average().roundToInt(),
            carbsG = map { it.carbsG }.average().roundToInt(),
            proteinG = map { it.proteinG }.average().roundToInt(),
            fatG = map { it.fatG }.average().roundToInt(),
            sugarG = map { it.sugarG }.average().roundToInt(),
            sodiumMg = map { it.sodiumMg }.average().roundToInt(),
            fiberG = map { it.fiberG }.average().roundToInt(),
        )

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    companion object {
        private const val MAX_RANGE_DAYS = 366L
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :daily-record:test --tests "*DietStatsServiceTest*"`
Expected: PASS (3 When)

- [ ] **Step 5: 컨트롤러 작성**

`apps/daily-record/src/main/kotlin/com/toy/backend/diet/daily/DietStatsController.kt`:

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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "식단 기간 통계", description = "주·월 통계 — 기간만 바꿔 같은 엔드포인트를 쓴다")
@RestController
@RequestMapping("/diet/stats")
class DietStatsController(
    private val service: DietStatsService,
) {
    @GetMapping
    @Operation(summary = "기간 통계 — 평균은 기록한 날로만 낸다")
    fun stats(
        @Parameter(description = "시작일", example = "2026-07-22")
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @Parameter(description = "종료일", example = "2026-07-28")
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<DietStatsResponse>> =
        ResponseEntity.ok(DataResponseBody(service.stats(authentication.name, from, to)))
}
```

- [ ] **Step 6: 포맷·전체 테스트·커밋**

```bash
./gradlew spotlessApply :daily-record:test
git add apps/daily-record/src
git commit -m "feat: 식단 기간 통계 추가"
```

- [ ] **Step 7: 실기동 확인**

단위 테스트는 리포지토리를 목으로 대체하므로 **`join fetch`·컬럼 매핑·JDBC 배치 인덱스를 잡지 못한다.** 이번 계획에서 그게 위험한 지점이 셋이다. 실제로 앱을 띄워 확인한다.

```bash
OPENROUTER_API_KEY= ./gradlew :daily-record:bootRun
```

1. **식품DB 재적재** — 로그에 `dataset=DISH, 6090건` / `dataset=PROCESSED, 298271건`. 임의 행에서 `sugar_per_100g`·`sodium_mg_per_100g`가 0이 아닌 값인지 확인(Task 3의 JDBC 인덱스가 밀렸으면 여기서 드러난다).
2. **사진 없는 기록** — `POST /diet/meals {date, mealType, items}`(analysisId 없이) → 201. `GET /diet/meals/{id}`의 `photos`가 빈 배열이고 점수가 나오는지.
3. **자주 먹는 음식** — 같은 음식을 날짜를 바꿔 두어 번 기록한 뒤 `GET /diet/items/frequent`. `count`가 맞고 `quantityG`가 **가장 최근 값**인지. 쿼리 로그에 항목마다 `select meal ...`이 반복되지 않는지(`join fetch`가 듣는지).
4. **하루 집계** — `GET /diet/days/{date}`의 `nutrientLimits` 세 줄과 `status`.
5. **기간 통계** — `GET /diet/stats?from=&to=`. `recordedDays`가 기록한 날 수와 맞는지.

---

## 자체 점검 (계획 작성 후 확인한 것)

- **설계 대비 누락 없음** — 설계 문서의 4개 기능, API 3개(`POST /diet/meals`의 nullable 인자, `GET /diet/items/frequent`, `GET /diet/stats`), 스키마 변경(컬럼 12개), 기준값 3종이 모두 어느 Task엔가 대응된다.
- **타입 일관성** — `NutritionTargets`·`NutritionTotals`가 Task 4에서 7필드로 넓어지고 Task 5·6이 그 모양을 쓴다. `FrequentItemService.aggregate`는 Task 2에서 정의해 Task 6이 재사용한다. `Meal.targets()`는 스냅샷 7개를 돌려준다.
- **의도적인 순서** — Task 3(식품DB)이 Task 4(끼니)보다 앞이다. 값이 없는 상태로 컬럼만 만들면 실기동에서 전부 0이 나와 무엇이 틀렸는지 가려진다.
- **남은 판단 지점** — Task 3 Step 9에서 재생성한 CSV의 행 수가 이전과 다르면 힌트 매칭이 다른 컬럼을 물었을 가능성이 높다. 그 자리에서 멈추고 헤더를 확인해야 한다.
