# 하루 평가에 대해 되묻는 채팅 (백엔드) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /diet/days/{date}/chat`으로 그날 식단 평가에 대해 되물을 수 있게 하고, 답변 근거로 기준 날짜 상세와 **직전 7일 요약**을 함께 싣는다.

**Architecture:** LLM 호출을 트랜잭션 밖으로 빼는 기존 모양(`MealFeedbackStore`·`DayFeedbackStore`)을 그대로 따른다 — `DietChatStore`에 짧은 트랜잭션 두 개를 두고 그 사이에서 호출한다. 프롬프트 재료는 새로 만들지 않고 `DietFeedbackPrompts.day()`·`meal()`·`DietScoreCalculator`·`NutrientLimitEvaluator`를 재사용한다. 아래에서 위로 쌓는다: 기존 프롬프트 함수 정리 → LLM 클라이언트 → 엔티티 → 프롬프트 → 스토어 → 서비스·컨트롤러 → 정리 배치.

**Tech Stack:** Kotlin / Spring Boot / JPA(Hibernate) / Kotest `BehaviorSpec` + MockK / Gradle(Spotless+ktlint)

**설계 문서:** `docs/superpowers/specs/2026-07-31-diet-day-chat-design.md` — **함정 다섯을 먼저 읽는다.**

## Global Constraints

- **LLM 호출을 트랜잭션 안에서 하지 않는다**(함정 1). `DietChatStore`의 두 `@Transactional` 메서드 **사이**에서 부른다. 엔티티를 트랜잭션 경계 밖으로 넘기지 않는다 — 각 메서드가 `username`으로 사용자를 다시 조회한다.
- **데이터 블록을 저장하지 않는다**(함정 2). `DietChatMessage`에 들어가는 것은 사용자가 쓴 질문과 모델의 답뿐이다.
- **LLM이 null이면 아무것도 저장하지 않는다**(함정 3). 질문만 저장되면 히스토리가 `user, user, assistant`로 어긋나 다음 턴 프롬프트가 깨진다.
- **`LLM_UNAVAILABLE` 가드는 `POST`에만 넣는다**(함정 4). `GET`은 막지 않는다. 커밋 전 `grep -rln isAvailable --include='*.kt'`로 짝을 확인한다.
- **`max_tokens`를 빠뜨리지 않는다.** 없으면 잔액이 남았는데도 402가 나고 **실기동에서만 드러난다**(`OpenRouterClient.textBody` 주석).
- **API 응답은 바뀌지 않는다.** `MealResponse.mealType`은 `"LUNCH"` 그대로다 — iOS 계약이다. 한글은 **프롬프트 렌더링에만** 쓴다.
- **파생 쿼리 이름은 파서를 통과해야 한다.** 이름이 곧 쿼리라 오타가 컴파일에 안 걸리고 기동 때 앱이 통째로 안 뜬다(`FoodRepository`에서 실제로 겪었다). `FoodRepositoryQueryTest`의 `PartTree` 검사를 새 리포지토리에도 건다.
- `columnDefinition = "varchar(20)"`은 enum 컬럼에 필수다 — `ddl-auto`가 CHECK 제약을 갱신하지 못한다(`AGENTS.md`).
- 커밋 전 `./gradlew spotlessApply`. 커밋 메시지는 이 저장소 관례(한국어 현재형 제목 + 왜를 적는 본문).
- 이 저장소에는 MockMvc·`@SpringBootTest`가 없다. 서비스 단위 테스트만 쓴다.

## 설계 문서와 다르게 가는 것 하나

설계의 `RecentDaySummary.exceeded: List<String>`는 「나트륨 초과」·「식이섬유 부족」처럼 **방향 단어**를 담는 형태였다. 그런데 `NutrientLimit`에는 방향이 없다 — `upperLimit`/`lowerTarget`이 만들 때만 알고, 밖으로는 `status`(OK/WARN)와 `standardText`("2,300mg 이하"/"30g 이상")만 나온다.

**방향을 새로 계산하지 않고 `standardText`를 그대로 싣는다.** 필드 이름도 `warnings`로 바꾼다(식이섬유는 「초과」가 아니다).

```
- 08-01 (금) 58점 2,930kcal · 주의: 나트륨 4,200mg(기준 2,300mg 이하)
```

「이하」·「이상」이 문자열에 들어 있어 모델이 방향을 읽을 수 있고, 기준값까지 함께 가서 **모델이 많은지 적은지 스스로 판단하지 않는다**(`DietFeedbackPrompts.day`의 「기준을 함께 실어야」와 같은 이유).

---

### Task 1: 프롬프트에 날짜를 싣고 끼니 종류를 한글로 쓴다 (함정 5)

`DietFeedbackPrompts.day()`의 헤더가 `"[오늘 먹은 끼니]"` 고정 문자열이라 모델이 어느 날 얘기인지 모른다. 채팅은 어느 날짜로도 열리고, **하루 피드백도 지난 날짜를 조회하면 그때 생성되므로 지금도 「오늘」이 틀린 경우가 있다.**

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/Meal.kt` (`MealType`에 `label` 추가)
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DietFeedbackPrompts.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DayFeedbackStore.kt:57` (호출부)
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/feedback/DietFeedbackPromptsTest.kt` (신규)

**Interfaces:**
- Consumes: 없음(첫 태스크)
- Produces:
  - `MealType.label: String` — 아침·점심·저녁·간식
  - `DietFeedbackPrompts.day(date: LocalDate, meals: List<Meal>, totals: NutritionTotals, targets: NutritionTargets, dayScore: Int, activeEnergyKcal: Int?): String` — **첫 인자에 `date`가 붙었다**
  - `DietFeedbackPrompts.meal(meal: Meal, basis: MealScoreBasis?): String` — 시그니처 그대로, 렌더링만 한글

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DietFeedbackPromptsTest.kt`를 새로 만든다. **이 저장소의 프롬프트에 걸린 첫 테스트다** — 지금까지 프롬프트 문자열을 검사하는 테스트가 하나도 없었다.

```kotlin
package com.toy.backend.diet.feedback

import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyMeal
import com.toy.backend.diet.dummyMealItem
import com.toy.backend.diet.meal.MealType
import com.toy.backend.diet.meal.toResponse
import com.toy.backend.diet.profile.NutritionTargets
import com.toy.backend.diet.score.DietScoreCalculator
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.LocalDate

/**
 * 프롬프트 문자열에 걸린 첫 그물이다. 여기 담긴 것들은 전부 **모델이 지어내는 것을 막으려고**
 * 하나씩 붙은 값이라(`DietFeedbackPrompts` 주석), 조용히 빠져도 컴파일도 테스트도 안 깨졌다.
 */
class DietFeedbackPromptsTest :
    BehaviorSpec({
        val user = dietUser()
        val date = LocalDate.of(2026, 8, 1)

        fun lunch(): com.toy.backend.diet.meal.Meal {
            val meal = dummyMeal(user = user, date = date, mealType = MealType.LUNCH)
            meal.replaceItems(listOf(dummyMealItem(meal = meal, foodName = "제육볶음")))
            meal.applyScore(74)
            return meal
        }

        val targets = NutritionTargets(2509, 345, 94, 84, 125, 2300, 30)

        Given("하루 프롬프트는") {
            val meals = listOf(lunch())
            val prompt = DietFeedbackPrompts.day(date, meals, meals.totals(), targets, 61, 320)

            // 날짜가 없으면 모델은 어느 날 얘기인지 모른다. 지난 날짜 대화에서도 「오늘」이라 말하고,
            // 자기가 보고 있는 게 그 날짜인데도 「오늘 기록만 볼 수 있다」고 거절할 수 있다.
            Then("헤더에 기준 날짜와 요일이 박힌다 — 「오늘」이 아니다") {
                prompt shouldContain "[2026-08-01 (토) 먹은 끼니]"
                prompt shouldNotContain "오늘 먹은 끼니"
            }

            // 사용자가 한국어로 묻고 모델이 한국어로 답한다. 컨텍스트만 영어면 한 홉을 건너뛰어야 하고,
            // 모델이 근거를 인용할 때 「LUNCH에 드신」처럼 새어 나온다.
            Then("끼니 종류가 한글이다") {
                prompt shouldContain "- 점심: 제육볶음"
                prompt shouldNotContain "LUNCH"
            }

            Then("기준값을 함께 싣는다 — 없으면 모델이 많은지 적은지 스스로 판단한다") {
                prompt shouldContain "[목표]"
                prompt shouldContain "[주의 영양소]"
                prompt shouldContain "기준 2300mg 이하"
            }
        }

        Given("끼니 프롬프트는") {
            val meal = lunch()
            val prompt =
                DietFeedbackPrompts.meal(
                    meal,
                    DietScoreCalculator.scoreMeal(meal.carbsG, meal.proteinG, meal.fatG).basis,
                )

            Then("끼니 종류가 한글이다") {
                prompt shouldContain "[이번 끼니] 점심"
                prompt shouldNotContain "LUNCH"
            }
        }

        // 프롬프트를 한글로 바꾸면서 응답까지 바꾸면 iOS의 디코딩이 깨진다.
        Given("API 응답은") {
            Then("끼니 종류가 enum 이름 그대로다 — iOS 계약이다") {
                lunch().toResponse(emptyMap()).mealType.name shouldBe "LUNCH"
            }
        }
    })
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.feedback.DietFeedbackPromptsTest"`
Expected: **컴파일 실패** — `day()`가 `date`를 받지 않아 인자 개수가 맞지 않는다

- [ ] **Step 3: `MealType.label`을 추가한다**

`Meal.kt`의 `mergesWithinDay` 아래:

```kotlin
    /**
     * **프롬프트 표기 전용이다.** API 응답(`MealResponse.mealType`)은 enum 이름(`"LUNCH"`)이고
     * iOS가 그 값으로 디코딩한다 — 여기 값을 응답에 쓰면 앱이 깨진다.
     *
     * 프롬프트를 한글로 쓰는 이유 — 사용자가 한국어로 묻고 모델이 한국어로 답하는데 컨텍스트만
     * 영어면 모델이 점심↔LUNCH를 한 홉 건너뛴 뒤 근거를 찾고, 인용할 때 「LUNCH에 드신 짜장면이」
     * 처럼 새어 나온다.
     */
    val label: String
        get() =
            when (this) {
                BREAKFAST -> "아침"
                LUNCH -> "점심"
                DINNER -> "저녁"
                SNACK -> "간식"
            }
```

- [ ] **Step 4: `day()`·`meal()`을 고친다**

`DietFeedbackPrompts.kt`의 `object DietFeedbackPrompts` 안, `SYSTEM_PROMPT` 위에 포매터를 둔다:

```kotlin
    /**
     * `2026-08-01 (토)`. **요일까지 넣는다** — 주말 과식 같은 요일 효과는 날짜만으로는 안 보인다.
     */
    private val PROMPT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd (E)", Locale.KOREAN)
```

`meal()`의 첫 줄:

```kotlin
            appendLine("[이번 끼니] ${meal.mealType.label}")
```

`day()`의 시그니처와 앞 두 블록:

```kotlin
    fun day(
        date: LocalDate,
        meals: List<Meal>,
        totals: NutritionTotals,
        targets: NutritionTargets,
        dayScore: Int,
        activeEnergyKcal: Int?,
    ): String =
        buildString {
            appendLine("[${date.format(PROMPT_DATE)} 먹은 끼니]")
            meals.forEach { meal ->
                appendLine(
                    "- ${meal.mealType.label}: ${meal.items.joinToString(", ") { it.foodName }} " +
                        "(${meal.totalKcal.roundToInt()}kcal)",
                )
            }
```

나머지(`[총 섭취]`·`[목표]`·`[주의 영양소]`·`[하루 점수]`·`[활동 에너지]`)는 건드리지 않는다.

import 세 개를 더한다:

```kotlin
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
```

- [ ] **Step 5: 호출부를 고친다**

`DayFeedbackStore.kt:57`:

```kotlin
            prompt = DietFeedbackPrompts.day(date, meals, totals, targets, dayScore, activeEnergyKcal),
```

`loadPrompt`가 이미 `date` 파라미터를 갖고 있어 그대로 넘기면 된다.

- [ ] **Step 6: 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.feedback.DietFeedbackPromptsTest"`
Expected: PASS

Run: `./gradlew :daily-record:test`
Expected: PASS (전체)

- [ ] **Step 7: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/Meal.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DietFeedbackPrompts.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DayFeedbackStore.kt \
        apps/daily-record/src/test/kotlin/com/toy/backend/diet/feedback/DietFeedbackPromptsTest.kt
git commit -m "feat: 프롬프트에 날짜를 싣고 끼니 종류를 한글로 쓴다

day() 헤더가 「[오늘 먹은 끼니]」 고정 문자열이라 모델이 어느 날 얘기인지
모른다. 지난 날짜를 조회하면 하루 피드백도 그때 생성되므로 지금도 「오늘」이
틀린 경우가 있고, 곧 들어올 채팅은 어느 날짜로도 열린다.

끼니 종류도 한글로 렌더링한다. 사용자가 한국어로 묻고 모델이 한국어로 답하는데
컨텍스트만 LUNCH면 한 홉을 건너뛰어야 하고, 인용할 때 「LUNCH에 드신」처럼
새어 나온다. 지금 프롬프트에서 영어는 이 한 자리뿐이라 섞인 언어가 그 자체로
불리하다.

API는 안 건드린다. MealResponse.mealType은 \"LUNCH\" 그대로다 — iOS 계약이라
바꾸면 앱이 깨진다. 그 경계를 테스트로 못 박았다.

프롬프트에 걸린 첫 테스트다. 여기 담긴 값들은 전부 모델이 지어내는 것을
막으려고 하나씩 붙은 것인데, 지금까지 조용히 빠져도 아무 데서도 안 걸렸다."
```

---

### Task 2: `OpenRouterClient.chat()` — 여러 턴을 보낼 수 있게

지금 `generateText(system, user)`는 단발이다. 채팅은 히스토리를 실어야 한다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/llm/OpenRouterClient.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/llm/OpenRouterClientTest.kt`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `data class ChatTurn(val role: String, val content: String)` — `role`은 OpenRouter API 값(`"user"`/`"assistant"`)
  - `OpenRouterClient.chat(systemPrompt: String, turns: List<ChatTurn>): String?`
  - `OpenRouterClient.chatBody(systemPrompt: String, turns: List<ChatTurn>): Map<String, Any>` (internal) — **`textBody`를 대체한다**
  - `generateText(systemPrompt, userPrompt)`는 시그니처 그대로 유지되고 내부만 `chat` 위의 얇은 래퍼가 된다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`OpenRouterClientTest.kt`의 `Given("문장 생성 요청 본문")` 블록을 통째로 아래로 바꾼다. 기존 세 검사(한도·설정값·모델 구분)는 `chatBody`로 옮기고 다중 턴 검사를 더한다.

```kotlin
        Given("문장 생성 요청 본문") {
            val client = client()
            val body = client.chatBody("시스템", listOf(ChatTurn("user", "사용자")))

            Then("max_tokens를 싣는다") {
                body["max_tokens"] shouldBe 2000
            }

            Then("설정값을 그대로 쓴다") {
                client(textMaxTokens = 123)
                    .chatBody("s", listOf(ChatTurn("user", "u")))["max_tokens"] shouldBe 123
            }

            Then("사진 인식과 다른 모델·한도를 쓴다 — 문장 생성이 더 싸고 짧다") {
                body["model"] shouldNotBe client.visionBody("QUJD", "image/jpeg")["model"]
                body["max_tokens"] shouldNotBe client.visionBody("QUJD", "image/jpeg")["max_tokens"]
            }

            // 히스토리가 뒤섞이면 모델이 누가 무슨 말을 했는지 잃는다.
            Then("system이 맨 앞이고 턴이 준 순서 그대로 뒤에 붙는다") {
                val many =
                    client.chatBody(
                        "시스템",
                        listOf(
                            ChatTurn("user", "데이터"),
                            ChatTurn("assistant", "총평"),
                            ChatTurn("user", "질문"),
                        ),
                    )
                @Suppress("UNCHECKED_CAST")
                val messages = many["messages"] as List<Map<String, String>>
                messages.map { it["role"] } shouldBe listOf("system", "user", "assistant", "user")
                messages.map { it["content"] } shouldBe listOf("시스템", "데이터", "총평", "질문")
            }
        }
```

**`generateText`가 래퍼라는 것은 따로 검사하지 않는다.** `generateText`는 `post`를 타므로 본문만 꺼내 볼 수 없고, 억지로 확인하려면 `post`를 목으로 바꿔야 해서 테스트가 구현에 붙는다. 위 네 검사가 `chatBody`를 덮고, 래퍼라는 사실은 코드 두 줄로 자명하다.

`ChatTurn`은 테스트 파일과 같은 패키지(`com.toy.backend.diet.llm`)라 import가 필요 없다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.llm.OpenRouterClientTest"`
Expected: **컴파일 실패** — `Unresolved reference: chatBody`, `Unresolved reference: ChatTurn`

- [ ] **Step 3: 최소 구현**

`OpenRouterClient.kt`의 `RecognizedFood` 아래에 데이터 클래스를 더한다:

```kotlin
/**
 * OpenRouter `messages` 배열의 한 칸. **`role`은 API 값**(`"user"`/`"assistant"`)이다 —
 * 도메인 enum(`ChatRole`)을 여기 두면 `diet.llm`이 `diet.chat`을 알게 되어 의존이 뒤집힌다.
 * 변환은 부르는 쪽에서 한다.
 */
data class ChatTurn(
    val role: String,
    val content: String,
)
```

`generateText`를 바꾸고 `textBody`를 `chatBody`로 대체한다:

```kotlin
    /**
     * 여러 턴을 보낸다. `generateText`가 이 위의 얇은 래퍼라 **요청을 만드는 자리가 하나로
     * 유지된다** — 두 벌이면 `max_tokens`처럼 한쪽에만 빠지는 값이 생긴다.
     */
    fun chat(
        systemPrompt: String,
        turns: List<ChatTurn>,
    ): String? = post(chatBody(systemPrompt, turns))?.trim()?.takeIf { it.isNotBlank() }

    fun generateText(
        systemPrompt: String,
        userPrompt: String,
    ): String? = chat(systemPrompt, listOf(ChatTurn("user", userPrompt)))
```

```kotlin
    internal fun chatBody(
        systemPrompt: String,
        turns: List<ChatTurn>,
    ): Map<String, Any> =
        mapOf(
            "model" to properties.textModel,
            "messages" to
                listOf(mapOf("role" to "system", "content" to systemPrompt)) +
                    turns.map { mapOf("role" to it.role, "content" to it.content) },
            // 안 보내면 잔액이 남았는데도 402가 난다(`visionBody`와 같은 이유).
            "max_tokens" to properties.textMaxTokens,
        )
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.llm.OpenRouterClientTest"`
Expected: PASS

Run: `./gradlew :daily-record:test`
Expected: PASS — `DietFeedbackGeneratorTest`가 `generateText`를 목으로 쓰므로 시그니처가 그대로여야 한다

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/llm/OpenRouterClient.kt \
        apps/daily-record/src/test/kotlin/com/toy/backend/diet/llm/OpenRouterClientTest.kt
git commit -m "feat: OpenRouter 호출이 여러 턴을 실을 수 있게 한다

채팅은 히스토리를 통째로 보내야 하는데 generateText가 단발이었다.

chat(system, turns)를 두고 generateText를 그 위의 얇은 래퍼로 바꾼다 — 요청을
만드는 자리를 하나로 유지한다. 두 벌이면 max_tokens처럼 한쪽에만 빠지는 값이
생기고, 그건 잔액이 남았는데도 402로 나며 실기동에서만 드러난다.

ChatTurn.role은 도메인 enum이 아니라 API 값이다. 여기 ChatRole을 두면
diet.llm이 diet.chat을 알게 되어 의존이 뒤집힌다."
```

---

### Task 3: `DietChatMessage` 엔티티 · 리포지토리

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatMessage.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatMessageRepository.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/DietChatMessageRepositoryQueryTest.kt` (신규)

**Interfaces:**
- Consumes: 없음
- Produces:
  - `enum class ChatRole { USER, ASSISTANT }`
  - `class DietChatMessage(user: User, date: LocalDate, role: ChatRole, content: String) : BaseEntity()`
  - `DietChatMessageRepository.findByUserAndDateOrderByIdAsc(user: User, date: LocalDate): List<DietChatMessage>`
  - `DietChatMessageRepository.deleteByCreatedAtBefore(cutoff: LocalDateTime): Long`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DietChatMessageRepositoryQueryTest.kt`:

```kotlin
package com.toy.backend.diet.chat

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldNotBe
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.parser.PartTree

/**
 * **파생 쿼리는 이름이 곧 쿼리라 오타가 컴파일에 안 걸린다.** 파싱은 기동할 때 일어나고,
 * 실패하면 리포지토리 빈이 안 만들어져 앱이 통째로 안 뜬다. 목으로 대체하는 단위 테스트는
 * 이름을 읽지도 않으므로 아무것도 못 잡는다.
 *
 * `FoodRepository`에서 실제로 그렇게 터졌다(`existsByDatasetAndCodeNotStartingWith` →
 * 「No property 'not' found for type 'String'」). 그래서 파서를 여기서 직접 돌린다.
 */
class DietChatMessageRepositoryQueryTest :
    BehaviorSpec({
        Given("파생 쿼리 이름들은") {
            val derived =
                DietChatMessageRepository::class.java.declaredMethods
                    .filterNot { it.isAnnotationPresent(Query::class.java) }

            Then("하나도 빠짐없이 파서를 통과한다 — 못 통과하면 기동 시점에 앱이 안 뜬다") {
                derived shouldNotBe emptyList<java.lang.reflect.Method>()
                derived.forEach {
                    withClue("${it.name} 가 파싱되지 않는다") {
                        shouldNotThrowAny { PartTree(it.name, DietChatMessage::class.java) }
                    }
                }
            }
        }
    })
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.chat.DietChatMessageRepositoryQueryTest"`
Expected: **컴파일 실패** — `Unresolved reference: DietChatMessageRepository`

- [ ] **Step 3: 엔티티를 만든다**

`DietChatMessage.kt`:

```kotlin
package com.toy.backend.diet.chat

import com.toy.backend.common.entity.BaseEntity
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
import java.time.LocalDate

enum class ChatRole { USER, ASSISTANT }

/**
 * 하루 평가에 대해 주고받은 말. **저장되는 것은 사용자가 쓴 질문과 모델의 답뿐이다** —
 * 프롬프트의 데이터 블록은 저장하지 않는다. 대화 도중 끼니를 고치면 그 블록이 낡은 수치를
 * 가리키는데, 하루 피드백과 달리 **대화는 무효화할 수 없다**(이미 한 말을 취소할 수 없다).
 * 매 요청 현재 DB에서 새로 만들면 지난 대화가 옛 숫자를 언급하더라도 다음 답변은 최신을 본다.
 *
 * **스레드 테이블을 두지 않는다.** 하루당 대화가 하나뿐이라 `(user, date)`가 곧 스레드다.
 * 턴 수도 별도 컬럼 없이 `USER` 메시지 개수로 센다 — 카운터를 두면 메시지와 어긋날 자리가 생긴다.
 */
@Entity
@Table(
    name = "diet_chat_message",
    indexes = [Index(name = "idx_diet_chat_user_date", columnList = "user_id, date, id")],
)
class DietChatMessage(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(nullable = false)
    var date: LocalDate,
    // `columnDefinition`이 필수다 — ddl-auto가 CHECK 제약을 갱신하지 못해, 나중에 값을 늘리면
    // 기존 DB에서 INSERT가 깨진다(`AGENTS.md`).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    var role: ChatRole,
    @Column(nullable = false, columnDefinition = "text")
    var content: String,
) : BaseEntity()
```

- [ ] **Step 4: 리포지토리를 만든다**

`DietChatMessageRepository.kt`:

```kotlin
package com.toy.backend.diet.chat

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.LocalDateTime

interface DietChatMessageRepository : JpaRepository<DietChatMessage, Long> {
    /**
     * 히스토리 전량. **턴 수도 이 목록에서 센다** — 어차피 전부 읽어야 하므로 `count` 쿼리를
     * 따로 두지 않는다.
     */
    fun findByUserAndDateOrderByIdAsc(
        user: User,
        date: LocalDate,
    ): List<DietChatMessage>

    /** 정리 배치용. `MealAnalysisRepository`와 같은 모양이다. */
    fun deleteByCreatedAtBefore(cutoff: LocalDateTime): Long
}
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.chat.DietChatMessageRepositoryQueryTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/ \
        apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/
git commit -m "feat: 하루 채팅 메시지 엔티티를 만든다

(user, date)가 곧 스레드다. 하루당 대화가 하나뿐이라 스레드 테이블을 두지 않고,
턴 수도 별도 컬럼 없이 USER 메시지 개수로 센다 — 카운터를 두면 메시지와
어긋날 자리가 생긴다.

저장하는 것은 주고받은 말뿐이다. 프롬프트의 데이터 블록은 저장하지 않는다.
대화 도중 끼니를 고치면 그 블록이 낡은 수치를 가리키는데, 하루 피드백과 달리
대화는 무효화할 수 없다.

파생 쿼리 이름에 파서 검사를 건다. FoodRepository에서 이름이 안 파싱돼 앱이
통째로 안 뜬 적이 있고, 목으로 대체하는 단위 테스트는 이름을 읽지도 않는다."
```

---

### Task 4: `DietChatPrompts` — 시스템 프롬프트 · 컨텍스트 · 직전 7일

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatPrompts.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/RecentDaySummary.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/DietChatPromptsTest.kt` (신규)

**Interfaces:**
- Consumes: `MealType.label`, `DietFeedbackPrompts.day(date, …)`, `DietFeedbackPrompts.meal(meal, basis)` (Task 1)
- Produces:
  - `data class RecentDaySummary(date: LocalDate, dayScore: Int?, totalKcal: Double, warnings: List<String>, meals: List<Pair<MealType, List<String>>>)`
  - `DietChatPrompts.SYSTEM_PROMPT: String`
  - `DietChatPrompts.recentDaysBlock(days: List<RecentDaySummary>): String`
  - `DietChatPrompts.context(date: LocalDate, meals: List<Meal>, totals: NutritionTotals, targets: NutritionTargets, dayScore: Int, activeEnergyKcal: Int?, recentDays: List<RecentDaySummary>): String`
  - `DietChatPrompts.RECENT_DAYS: Int = 7`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DietChatPromptsTest.kt`:

```kotlin
package com.toy.backend.diet.chat

import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyMeal
import com.toy.backend.diet.dummyMealItem
import com.toy.backend.diet.feedback.totals
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealType
import com.toy.backend.diet.profile.NutritionTargets
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.LocalDate

class DietChatPromptsTest :
    BehaviorSpec({
        val user = dietUser()
        val date = LocalDate.of(2026, 8, 1)
        val targets = NutritionTargets(2509, 345, 94, 84, 125, 2300, 30)

        fun lunch(): Meal {
            val meal = dummyMeal(user = user, date = date, mealType = MealType.LUNCH)
            meal.replaceItems(listOf(dummyMealItem(meal = meal, foodName = "제육볶음")))
            meal.applyScore(74)
            return meal
        }

        val recent =
            listOf(
                RecentDaySummary(
                    date = LocalDate.of(2026, 7, 30),
                    dayScore = 58,
                    totalKcal = 2930.0,
                    warnings = listOf("나트륨 4200mg(기준 2300mg 이하)"),
                    meals = listOf(MealType.DINNER to listOf("치킨", "맥주")),
                ),
                RecentDaySummary(
                    date = LocalDate.of(2026, 7, 31),
                    dayScore = null,
                    totalKcal = 0.0,
                    warnings = emptyList(),
                    meals = emptyList(),
                ),
            )

        Given("직전 7일 블록은") {
            val block = DietChatPrompts.recentDaysBlock(recent)

            Then("하루 한 줄에 점수·열량이 들어간다") {
                block shouldContain "- 07-30 (목) 58점 2930kcal"
            }

            // 점수가 보이면 이유를 묻는다(함정 2-1). 이름이 없으면 모델이 지어낸다.
            Then("끼니별 음식 이름이 한글 끼니명과 함께 붙는다") {
                block shouldContain "저녁: 치킨, 맥주"
                block shouldNotContain "DINNER"
            }

            // 기준을 함께 실어야 모델이 많은지 적은지 스스로 판단하지 않는다.
            Then("주의 영양소는 기준값까지 함께 싣는다") {
                block shouldContain "나트륨 4200mg(기준 2300mg 이하)"
            }

            // 빼 버리면 모델이 날짜가 연속인 줄 알고 「이틀 연속 좋았다」처럼 없는 추세를 만든다.
            Then("기록 없는 날도 자리를 지킨다") {
                block shouldContain "- 07-31 (금) 기록 없음"
            }

            // 7일치에 매크로까지 실으면 기준일 상세와 크기가 비슷해진다.
            Then("수량·매크로는 없다") {
                block shouldNotContain "탄 "
                block shouldNotContain "균형 근거"
            }
        }

        Given("컨텍스트는") {
            val meals = listOf(lunch())
            val ctx = DietChatPrompts.context(date, meals, meals.totals(), targets, 61, 320, recent)

            Then("기준일이 먼저, 직전 7일이 뒤다") {
                ctx.indexOf("[2026-08-01 (토) 먹은 끼니]") shouldBeLessThan ctx.indexOf("[직전 2일]")
            }

            // 화면이 「점심 74점」을 보여주므로 「왜 그래?」가 반드시 온다. day()만으로는 못 답한다.
            Then("끼니별 상세가 들어간다 — 점수와 균형 근거까지") {
                ctx shouldContain "[끼니별 상세]"
                ctx shouldContain "[이번 끼니] 점심"
                ctx shouldContain "[균형 근거]"
            }
        }

        Given("시스템 프롬프트는") {
            Then("범위 밖을 거절이 아니라 길 안내로 돌린다") {
                DietChatPrompts.SYSTEM_PROMPT shouldContain "그 날짜를 열어서"
            }
        }
    })
```

import를 하나 더한다: `import io.kotest.matchers.ints.shouldBeLessThan`

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.chat.DietChatPromptsTest"`
Expected: **컴파일 실패** — `Unresolved reference: DietChatPrompts`, `RecentDaySummary`

- [ ] **Step 3: `RecentDaySummary`를 만든다**

```kotlin
package com.toy.backend.diet.chat

import com.toy.backend.diet.meal.MealType
import java.time.LocalDate

/**
 * 직전 7일 한 줄치. **렌더링에 필요한 것만 담는다** — 수량·매크로는 일부러 없다. 7일치에
 * 항목별 g·kcal·균형 근거까지 실으면 기준일 상세와 크기가 비슷해진다.
 *
 * [warnings]는 방향 단어(「초과」·「부족」)를 새로 만들지 않고 `NutrientLimit`의 값을 그대로
 * 조립한 문자열이다 — 「나트륨 4200mg(기준 2300mg 이하)」. 「이하」·「이상」이 문자열에 들어
 * 있어 모델이 방향을 읽고, 기준값이 함께 가서 많은지 적은지 스스로 판단하지 않는다.
 */
data class RecentDaySummary(
    val date: LocalDate,
    /** 그날 기록이 없으면 null. 「기록 없음」으로 렌더링한다. */
    val dayScore: Int?,
    val totalKcal: Double,
    val warnings: List<String>,
    /** 끼니 종류 → 음식 이름들. 확정 순서 그대로다. */
    val meals: List<Pair<MealType, List<String>>>,
)
```

- [ ] **Step 4: `DietChatPrompts`를 만든다**

```kotlin
package com.toy.backend.diet.chat

import com.toy.backend.diet.feedback.DietFeedbackPrompts
import com.toy.backend.diet.feedback.NutritionTotals
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.profile.NutritionTargets
import com.toy.backend.diet.score.DietScoreCalculator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object DietChatPrompts {
    /** 컨텍스트에 싣는 과거 창. 「이번 주」 감각과 맞춘 값이다. */
    const val RECENT_DAYS = 7

    /**
     * **범위 제한이 피드백보다 중요하다** — 피드백은 우리가 주제를 정하지만 채팅은 사용자가
     * 정한다. 「이 약 먹어도 돼?」·「살 빼는 법」이 실제로 온다.
     *
     * 막는 것은 둘뿐이다 — 직전 7일보다 먼 날짜와, 7일 안이지만 요약보다 자세한 것.
     * **둘 다 거절이 아니라 길 안내로 돌린다.** 대화가 날짜별로 열려 있어서 그렇게 하면
     * 실제로 답을 얻는다.
     */
    const val SYSTEM_PROMPT =
        "당신은 식단 코치입니다. 사용자의 식단 기록에 대해 묻는 말에 한국어 존댓말로 답하세요.\n" +
            "앞서 드린 끼니 기록·[끼니별 상세]·[직전 7일]에 있는 사실만 근거로 삼으세요. 기록에 " +
            "없는 것은 추측하지 말고 모른다고 말하세요 — 숫자를 지어내면 안 됩니다.\n" +
            "3문장 이내로 짧게. 목록 기호는 쓰지 마세요.\n" +
            "금지: 의학적 진단·처방, 특정 질환 언급, 영양제 권유.\n" +
            "식단·영양과 무관한 질문에는 답하지 말고, 식단에 대한 질문을 받겠다고 안내하세요.\n" +
            "[직전 7일]에는 그날의 점수·열량과 먹은 음식 이름만 있습니다. 그보다 자세한 것을 " +
            "물으면 그 날짜를 열어서 물어봐 달라고 안내하세요.\n" +
            "[직전 7일]보다 먼 날짜는 볼 수 없습니다. 그때도 그 날짜를 열어서 물어봐 달라고 하세요."

    /**
     * 기준일 상세 + 직전 7일. **기준일이 먼저다** — 대화의 주제는 기준일이고 7일은 배경이며,
     * 시스템 프롬프트가 「앞서 드린」으로 가리키는 순서와도 맞는다.
     *
     * **매 요청 새로 만들고 저장하지 않는다**(함정 2).
     */
    fun context(
        date: LocalDate,
        meals: List<Meal>,
        totals: NutritionTotals,
        targets: NutritionTargets,
        dayScore: Int,
        activeEnergyKcal: Int?,
        recentDays: List<RecentDaySummary>,
    ): String =
        buildString {
            append(DietFeedbackPrompts.day(date, meals, totals, targets, dayScore, activeEnergyKcal))
            appendLine()
            appendLine("[끼니별 상세]")
            // 화면이 「점심 47점」을 보여주므로 「왜 그래?」가 반드시 온다. day()는 이름과 열량만
            // 담아 그 질문에 못 답한다. basis는 저장하지 않고 그때 다시 계산한다 — 감점 기울기를
            // 바꿨을 때 응답과 프롬프트가 어긋나지 않는다.
            meals.forEach {
                append(DietFeedbackPrompts.meal(it, DietScoreCalculator.scoreMeal(it.carbsG, it.proteinG, it.fatG).basis))
            }
            appendLine()
            append(recentDaysBlock(recentDays))
        }

    /**
     * 직전 7일. 하루 한 줄 + 끼니별 **음식 이름까지만**.
     *
     * **기록 없는 날도 줄을 남긴다.** 빼 버리면 모델이 날짜가 연속인 줄 알고 「사흘 연속
     * 좋았다」처럼 없는 추세를 만든다.
     */
    fun recentDaysBlock(days: List<RecentDaySummary>): String =
        buildString {
            appendLine("[직전 ${days.size}일]")
            days.forEach { day ->
                if (day.dayScore == null) {
                    appendLine("- ${day.date.format(RECENT_DATE)} 기록 없음")
                    return@forEach
                }
                append("- ${day.date.format(RECENT_DATE)} ${day.dayScore}점 ${day.totalKcal.roundToInt()}kcal")
                if (day.warnings.isNotEmpty()) append(" · 주의: ${day.warnings.joinToString(", ")}")
                appendLine()
                day.meals.forEach { (type, foods) -> appendLine("    ${type.label}: ${foods.joinToString(", ")}") }
            }
        }

    /** `07-30 (목)`. 기준일 헤더와 달리 연도를 빼 줄을 짧게 유지한다 — 같은 해 안의 최근 7일이다. */
    private val RECENT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd (E)", Locale.KOREAN)
}
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.chat.DietChatPromptsTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/ \
        apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/
git commit -m "feat: 채팅 프롬프트와 직전 7일 블록을 만든다

기준일 상세 뒤에 직전 7일을 붙인다. 「어제보단 나은 거야?」가 이 기능을 만든
동기 중 하나였는데 하루치만 실으면 답할 수 없다.

끼니별 음식 이름까지 넣는다. 점수가 보이면 이유를 묻는다 — 「금요일엔 뭘
먹었길래 58점이야?」는 반드시 오고, 이름이 없으면 모델이 지어낸다. 대신 수량과
매크로는 넣지 않는다. 7일치에 그것까지 실으면 기준일 상세와 크기가 비슷해진다.

기록 없는 날도 줄을 남긴다. 빼면 모델이 날짜가 연속인 줄 알고 없는 추세를
만든다.

주의 영양소는 방향 단어를 새로 만들지 않고 NutrientLimit의 값을 그대로
조립한다. 「이하」·「이상」이 문자열에 들어 있어 모델이 방향을 읽고, 기준값이
함께 가서 많은지 적은지 스스로 판단하지 않는다.

범위 밖은 거절이 아니라 길 안내로 돌린다. 대화가 날짜별로 열려 있어서 그
날짜를 열면 실제로 답을 얻는다."
```

---

### Task 5: `DietChatStore` — 짧은 트랜잭션 두 개

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatStore.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/DietErrorCode.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatDtos.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/DietChatStoreTest.kt` (신규)

**Interfaces:**
- Consumes: `DietChatPrompts.context(...)`, `RecentDaySummary`, `DietChatMessageRepository`, `ChatTurn` (Task 2·3·4)
- Produces:
  - `data class ChatContext(dataBlock: String, dayFeedback: String?, history: List<ChatTurn>, remainingTurns: Int)`
  - `DietChatStore.loadContext(username: String, date: LocalDate): ChatContext`
  - `DietChatStore.append(username: String, date: LocalDate, question: String, answer: String): DietChatAnswerResponse`
  - `DietChatStore.history(username: String, date: LocalDate): DietChatResponse` — `GET`용
  - `DietErrorCode.CHAT_TURN_LIMIT_EXCEEDED`, `DietErrorCode.CHAT_FAILED`
  - DTO 넷: `DietChatRequest` · `DietChatMessageResponse` · `DietChatAnswerResponse` · `DietChatResponse`
  - `MAX_TURNS_PER_DAY = 20`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DietChatStoreTest.kt`:

```kotlin
package com.toy.backend.diet.chat

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.daily.DailyActivityRepository
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyMeal
import com.toy.backend.diet.dummyMealItem
import com.toy.backend.diet.feedback.DailyDietFeedback
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.meal.MealType
import com.toy.backend.user.UserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate

class DietChatStoreTest :
    BehaviorSpec({
        val userRepository = mockk<UserRepository>()
        val mealRepository = mockk<MealRepository>()
        val activityRepository = mockk<DailyActivityRepository>()
        val feedbackRepository = mockk<DailyDietFeedbackRepository>()
        val messageRepository = mockk<DietChatMessageRepository>()
        val store =
            DietChatStore(userRepository, mealRepository, activityRepository, feedbackRepository, messageRepository)

        val user = dietUser()
        val date = LocalDate.of(2026, 8, 1)

        fun mealOn(
            day: LocalDate,
            type: MealType,
            food: String,
            id: Long,
        ): Meal {
            val meal = dummyMeal(user = user, date = day, mealType = type, id = id)
            meal.replaceItems(listOf(dummyMealItem(meal = meal, foodName = food, id = id * 10)))
            meal.applyScore(70)
            return meal
        }

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            every { activityRepository.findByUserAndDate(user, date) } returns null
            every { feedbackRepository.findByUserAndDate(user, date) } returns null
            every { messageRepository.findByUserAndDateOrderByIdAsc(user, date) } returns emptyList()
        }

        Given("기준일과 직전 7일에 기록이 있으면") {
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(
                    user,
                    date.minusDays(7),
                    date,
                )
            } returns
                listOf(
                    mealOn(date.minusDays(2), MealType.DINNER, "치킨", 1L),
                    mealOn(date, MealType.LUNCH, "제육볶음", 2L),
                )

            val context = store.loadContext("testuser", date)

            Then("기준일 상세와 직전 7일이 한 블록에 담긴다") {
                context.dataBlock shouldContain "[2026-08-01 (토) 먹은 끼니]"
                context.dataBlock shouldContain "[직전 7일]"
                context.dataBlock shouldContain "저녁: 치킨"
            }

            // 창이 오늘 기준이면 지난 날짜 대화에서 엉뚱한 7일이 실린다.
            Then("창은 기준 날짜 기준이다") {
                context.dataBlock shouldContain "- 07-30 (목)"
                context.dataBlock shouldNotContain "- 08-02"
            }

            Then("기록 없는 날도 자리를 지킨다 — 7일 전부가 목록에 있다") {
                (1L..7L).forEach { back ->
                    context.dataBlock shouldContain date.minusDays(back).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))
                }
            }

            Then("남은 턴은 상한 그대로다") {
                context.remainingTurns shouldBe MAX_TURNS_PER_DAY
            }
        }

        Given("기준일에 기록이 없으면") {
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)
            } returns listOf(mealOn(date.minusDays(1), MealType.DINNER, "치킨", 3L))

            // 화면이 기준일 요약 말풍선으로 시작하는 구조라 보여줄 것이 없다.
            Then("직전 7일에 기록이 있어도 거절한다") {
                val e = shouldThrow<CustomException> { store.loadContext("testuser", date) }
                e.errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }

        Given("턴 상한에 닿았으면") {
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)
            } returns listOf(mealOn(date, MealType.LUNCH, "제육볶음", 4L))
            every { messageRepository.findByUserAndDateOrderByIdAsc(user, date) } returns
                (1..MAX_TURNS_PER_DAY).flatMap {
                    listOf(
                        DietChatMessage(user, date, ChatRole.USER, "질문$it").withId(it.toLong() * 2),
                        DietChatMessage(user, date, ChatRole.ASSISTANT, "답$it").withId(it.toLong() * 2 + 1),
                    )
                }

            Then("CHAT_TURN_LIMIT_EXCEEDED") {
                val e = shouldThrow<CustomException> { store.loadContext("testuser", date) }
                e.errorCode shouldBe DietErrorCode.CHAT_TURN_LIMIT_EXCEEDED
            }
        }

        Given("히스토리가 있으면") {
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)
            } returns listOf(mealOn(date, MealType.LUNCH, "제육볶음", 5L))
            every { messageRepository.findByUserAndDateOrderByIdAsc(user, date) } returns
                listOf(
                    DietChatMessage(user, date, ChatRole.USER, "왜 낮아?").withId(1L),
                    DietChatMessage(user, date, ChatRole.ASSISTANT, "나트륨 때문입니다").withId(2L),
                )
            every { feedbackRepository.findByUserAndDate(user, date) } returns
                DailyDietFeedback(user = user, date = date, dayScore = 61, feedback = "총평", generatedAt = java.time.LocalDateTime.now())

            val context = store.loadContext("testuser", date)

            Then("API 역할 값으로 교대해 나온다") {
                context.history.map { it.role } shouldBe listOf("user", "assistant")
                context.history.map { it.content } shouldBe listOf("왜 낮아?", "나트륨 때문입니다")
            }

            Then("하루 피드백을 함께 들고 나온다 — 대화의 출발점이다") {
                context.dayFeedback shouldBe "총평"
            }

            Then("남은 턴이 준다") {
                context.remainingTurns shouldBe MAX_TURNS_PER_DAY - 1
            }
        }
    })
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.chat.DietChatStoreTest"`
Expected: **컴파일 실패** — `Unresolved reference: DietChatStore`, `MAX_TURNS_PER_DAY`

- [ ] **Step 3: 에러 코드를 더한다**

`DietErrorCode.kt`의 `FEEDBACK_NOT_RETRYABLE` 아래:

```kotlin
    CHAT_TURN_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "하루에 물어볼 수 있는 횟수(%s번)를 넘었습니다."),
    CHAT_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "답변 생성에 실패했습니다. 잠시 후 다시 물어봐 주세요."),
```

- [ ] **Step 4: DTO를 만든다**

`DietChatDtos.kt`:

```kotlin
package com.toy.backend.diet.chat

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/** `@Size(max = 500)`은 프롬프트가 통째로 커지는 것을 막는 상한이다. */
data class DietChatRequest(
    @field:NotBlank @field:Size(max = 500)
    val message: String,
)

data class DietChatMessageResponse(
    val id: Long,
    val role: ChatRole,
    val content: String,
    val createdAt: LocalDateTime,
)

/**
 * `remainingTurns`를 함께 내려야 앱이 상한에 닿았을 때 입력창을 잠글 수 있다 — 없으면 앱이
 * 메시지를 세어 서버 상한을 추측하게 된다.
 */
data class DietChatAnswerResponse(
    val message: DietChatMessageResponse,
    val remainingTurns: Int,
)

data class DietChatResponse(
    val messages: List<DietChatMessageResponse>,
    val remainingTurns: Int,
)
```

- [ ] **Step 5: `DietChatStore`를 만든다**

```kotlin
package com.toy.backend.diet.chat

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.daily.DailyActivityRepository
import com.toy.backend.diet.daily.NutrientLimitEvaluator
import com.toy.backend.diet.daily.NutrientStatus
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.totals
import com.toy.backend.diet.llm.ChatTurn
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import kotlin.math.roundToInt

/** 하루당 물어볼 수 있는 횟수. 공개 근거 없는 자체 설정값이다. */
const val MAX_TURNS_PER_DAY = 20

/**
 * 프롬프트 재료와 히스토리. **엔티티를 담지 않는다** — 트랜잭션 밖으로 나가는 값이다.
 */
data class ChatContext(
    /** `DietChatPrompts.context(...)` 결과. 매 요청 새로 만들고 저장하지 않는다(함정 2). */
    val dataBlock: String,
    /** 저장된 하루 피드백. 아직 생성 전이면 null이고, 그때는 오프닝 턴을 뺀다. */
    val dayFeedback: String?,
    val history: List<ChatTurn>,
    val remainingTurns: Int,
)

/**
 * 채팅의 DB 왕복을 **각각 짧은 트랜잭션**으로 끊는다. `MealFeedbackStore`·`DayFeedbackStore`와
 * 같은 이유이고 같은 모양이다 — LLM 호출을 트랜잭션 안에서 하면 엔티티가 호출 내내 영속성
 * 컨텍스트에 남고, 그 사이 항목이 수정되면 커밋의 dirty check가 합계 컬럼을 옛 값으로 되돌린다.
 * **채팅은 그 창이 더 넓다** — 대화가 쌓일수록 호출이 길어지고 사용자가 그동안 화면을 보고 있다.
 */
@Component
class DietChatStore(
    private val userRepository: UserRepository,
    private val mealRepository: MealRepository,
    private val activityRepository: DailyActivityRepository,
    private val feedbackRepository: DailyDietFeedbackRepository,
    private val messageRepository: DietChatMessageRepository,
) {
    /**
     * **조회를 한 번으로 묶는다.** 기준일과 직전 7일을 따로 읽지 않고 8일치를 받아 날짜로 쪼갠다.
     * 이 정렬(`createdAt asc`)은 그날 첫 끼니의 스냅샷을 목표로 쓰기 위한 것이라 이 용도에 맞는다.
     */
    @Transactional(readOnly = true)
    fun loadContext(
        username: String,
        date: LocalDate,
    ): ChatContext {
        val user = findUser(username)
        val window = DietChatPrompts.RECENT_DAYS.toLong()
        val byDate =
            mealRepository
                .findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(window), date)
                .groupBy { it.date }

        // 기준일이 비면 직전 7일이 있어도 거절한다 — 화면이 기준일 요약으로 시작하는 구조라
        // 보여줄 것이 없고, 기록이 있는 날을 열면 되는 일이다. 앱이 먼저 막고 여기는 안전망이다.
        val meals = byDate[date] ?: throw CustomException(ErrorCode.INVALID_REQUEST, "그날 기록된 끼니가 없습니다")

        val totals = meals.totals()
        val targets = meals.first().targets()
        val dayScore = DietScoreCalculator.scoreDay(totals.kcal, totals.carbsG, totals.proteinG, totals.fatG, targets).score
        val recent = (window downTo 1).map { back -> summarize(date.minusDays(back), byDate) }

        val history = messageRepository.findByUserAndDateOrderByIdAsc(user, date)
        val used = history.count { it.role == ChatRole.USER }
        if (used >= MAX_TURNS_PER_DAY) throw CustomException(DietErrorCode.CHAT_TURN_LIMIT_EXCEEDED, MAX_TURNS_PER_DAY)

        return ChatContext(
            dataBlock =
                DietChatPrompts.context(
                    date,
                    meals,
                    totals,
                    targets,
                    dayScore,
                    activityRepository.findByUserAndDate(user, date)?.activeEnergyKcal,
                    recent,
                ),
            dayFeedback = feedbackRepository.findByUserAndDate(user, date)?.feedback,
            history = history.map { ChatTurn(it.role.name.lowercase(), it.content) },
            remainingTurns = MAX_TURNS_PER_DAY - used,
        )
    }

    /** 질문·답 두 행을 순서대로 저장한다. 저장 직후의 id·`createdAt`·남은 턴이 이 트랜잭션 안에 있다. */
    @Transactional
    fun append(
        username: String,
        date: LocalDate,
        question: String,
        answer: String,
    ): DietChatAnswerResponse {
        val user = findUser(username)
        messageRepository.save(DietChatMessage(user, date, ChatRole.USER, question))
        val saved = messageRepository.save(DietChatMessage(user, date, ChatRole.ASSISTANT, answer))
        val used = messageRepository.findByUserAndDateOrderByIdAsc(user, date).count { it.role == ChatRole.USER }
        return DietChatAnswerResponse(saved.toResponse(), MAX_TURNS_PER_DAY - used)
    }

    /** `GET`용. **키가 없어도 동작한다** — 저장된 대화를 보여주는 데는 LLM이 필요 없다(함정 4). */
    @Transactional(readOnly = true)
    fun history(
        username: String,
        date: LocalDate,
    ): DietChatResponse {
        val messages = messageRepository.findByUserAndDateOrderByIdAsc(findUser(username), date)
        return DietChatResponse(
            messages = messages.map { it.toResponse() },
            remainingTurns = MAX_TURNS_PER_DAY - messages.count { it.role == ChatRole.USER },
        )
    }

    /** 기록이 없는 날도 자리를 만든다 — 빼면 모델이 날짜가 연속인 줄 알고 없는 추세를 만든다. */
    private fun summarize(
        day: LocalDate,
        byDate: Map<LocalDate, List<Meal>>,
    ): RecentDaySummary {
        val meals = byDate[day]
        if (meals.isNullOrEmpty()) {
            return RecentDaySummary(day, null, 0.0, emptyList(), emptyList())
        }
        val totals = meals.totals()
        val targets = meals.first().targets()
        return RecentDaySummary(
            date = day,
            dayScore = DietScoreCalculator.scoreDay(totals.kcal, totals.carbsG, totals.proteinG, totals.fatG, targets).score,
            totalKcal = totals.kcal,
            // 방향 단어를 새로 만들지 않고 기준 문구를 그대로 싣는다 — 「이하」·「이상」이 들어 있어
            // 모델이 방향을 읽고, 기준값이 함께 가서 많은지 적은지 스스로 판단하지 않는다.
            warnings =
                NutrientLimitEvaluator
                    .evaluate(totals, targets)
                    .filter { it.status == NutrientStatus.WARN }
                    .map { "${it.name} ${it.intake.roundToInt()}${it.unit}(기준 ${it.standardText})" },
            meals = meals.map { it.mealType to it.items.map { item -> item.foodName } },
        )
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    private fun DietChatMessage.toResponse() = DietChatMessageResponse(requiredId, role, content, createdAt)
}
```

- [ ] **Step 6: 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.chat.DietChatStoreTest"`
Expected: PASS

Run: `./gradlew :daily-record:test`
Expected: PASS (전체)

- [ ] **Step 7: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/ \
        apps/daily-record/src/main/kotlin/com/toy/backend/diet/DietErrorCode.kt \
        apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/
git commit -m "feat: 채팅 컨텍스트 로드와 메시지 저장을 짧은 트랜잭션 둘로 나눈다

LLM 호출을 트랜잭션 안에서 하면 엔티티가 호출 내내 영속성 컨텍스트에 남고,
그 사이 항목이 수정되면 커밋의 dirty check가 합계 컬럼을 옛 값으로 되돌린다.
채팅은 그 창이 더 넓다 — 대화가 쌓일수록 호출이 길어지고 사용자가 그동안
화면을 보고 있다. MealFeedbackStore와 같은 모양으로 쪼갠다.

조회는 한 번이다. 기준일과 직전 7일을 따로 읽지 않고 between으로 8일치를 받아
날짜로 쪼갠다.

기준일이 비면 직전 7일이 있어도 거절한다. 화면이 기준일 요약 말풍선으로
시작하는 구조라 보여줄 것이 없고, 기록이 있는 날을 열면 되는 일이다.

턴 수는 USER 메시지 개수로 센다. 카운터 컬럼을 두면 메시지와 어긋날 자리가
생긴다."
```

---

### Task 6: `DietChatService` · 컨트롤러

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatService.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatController.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/DietChatServiceTest.kt` (신규)

**Interfaces:**
- Consumes: `DietChatStore`(`loadContext`/`append`/`history`), `OpenRouterClient.chat`, `DietChatPrompts.SYSTEM_PROMPT`, `ChatTurn` (Task 2·4·5)
- Produces:
  - `DietChatService.isAvailable: Boolean`
  - `DietChatService.ask(username: String, date: LocalDate, message: String): DietChatAnswerResponse`
  - `DietChatService.history(username: String, date: LocalDate): DietChatResponse`
  - `POST /diet/days/{date}/chat` → `200` + `DataResponseBody<DietChatAnswerResponse>`
  - `GET /diet/days/{date}/chat` → `200` + `DataResponseBody<DietChatResponse>`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DietChatServiceTest.kt`:

```kotlin
package com.toy.backend.diet.chat

import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.llm.ChatTurn
import com.toy.backend.diet.llm.OpenRouterClient
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalDateTime

class DietChatServiceTest :
    BehaviorSpec({
        val store = mockk<DietChatStore>()
        val client = mockk<OpenRouterClient>()
        val date = LocalDate.of(2026, 8, 1)

        fun context(
            feedback: String? = "총평",
            history: List<ChatTurn> = emptyList(),
        ) = ChatContext(
            dataBlock = "[2026-08-01 (토) 먹은 끼니]\n- 점심: 제육볶음 (555kcal)\n[직전 7일]\n- 07-30 (목) 58점 2930kcal",
            dayFeedback = feedback,
            history = history,
            remainingTurns = 19,
        )

        val answer =
            DietChatAnswerResponse(
                DietChatMessageResponse(2L, ChatRole.ASSISTANT, "나트륨 때문입니다", LocalDateTime.now()),
                19,
            )

        Given("정상 흐름이면") {
            val service = DietChatService(store, client)
            val turns = slot<List<ChatTurn>>()
            every { store.loadContext("testuser", date) } returns
                context(history = listOf(ChatTurn("user", "이전 질문"), ChatTurn("assistant", "이전 답")))
            every { client.chat(DietChatPrompts.SYSTEM_PROMPT, capture(turns)) } returns "나트륨 때문입니다"
            every { store.append("testuser", date, "왜 낮아?", "나트륨 때문입니다") } returns answer

            val result = service.ask("testuser", date, "왜 낮아?")

            Then("저장된 답을 돌려준다") {
                result shouldBe answer
            }

            // 데이터 → 총평 → 히스토리 → 이번 질문. 순서가 어긋나면 모델이 맥락을 잃는다.
            Then("턴 순서가 데이터·총평·히스토리·이번 질문이다") {
                turns.captured.map { it.role } shouldBe listOf("user", "assistant", "user", "assistant", "user")
                turns.captured.first().content shouldContain "[직전 7일]"
                turns.captured[1].content shouldBe "총평"
                turns.captured.last().content shouldBe "왜 낮아?"
            }
        }

        Given("하루 피드백이 아직 없으면") {
            val service = DietChatService(store, client)
            val turns = slot<List<ChatTurn>>()
            every { store.loadContext("testuser", date) } returns context(feedback = null)
            every { client.chat(any(), capture(turns)) } returns "답"
            every { store.append(any(), any(), any(), any()) } returns answer

            service.ask("testuser", date, "질문")

            Then("오프닝 턴을 넣지 않는다 — assistant가 안 들어간다") {
                turns.captured.map { it.role } shouldBe listOf("user", "user")
            }
        }

        // 질문만 저장되면 히스토리가 user, user, assistant로 어긋나 다음 턴 프롬프트가 깨진다.
        Given("LLM이 null을 돌려주면") {
            val service = DietChatService(store, client)
            every { store.loadContext("testuser", date) } returns context()
            every { client.chat(any(), any()) } returns null

            Then("CHAT_FAILED이고 아무것도 저장하지 않는다") {
                val e = shouldThrow<CustomException> { service.ask("testuser", date, "질문") }
                e.errorCode shouldBe DietErrorCode.CHAT_FAILED
                verify(exactly = 0) { store.append(any(), any(), any(), any()) }
            }
        }

        Given("키가 없으면") {
            val service = DietChatService(store, null)

            Then("LLM_UNAVAILABLE이고 컨텍스트를 읽지도 않는다") {
                val e = shouldThrow<CustomException> { service.ask("testuser", date, "질문") }
                e.errorCode shouldBe DietErrorCode.LLM_UNAVAILABLE
                verify(exactly = 0) { store.loadContext(any(), any()) }
            }

            // 저장된 대화를 보여주는 데는 LLM이 필요 없다(함정 4).
            Then("조회는 그대로 동작한다") {
                val response = DietChatResponse(emptyList(), MAX_TURNS_PER_DAY)
                every { store.history("testuser", date) } returns response
                service.history("testuser", date) shouldBe response
            }
        }

        // 데이터 블록이 저장되면 대화 도중 끼니를 고쳤을 때 낡은 숫자가 굳는다.
        Given("저장 호출은") {
            val service = DietChatService(store, client)
            val question = slot<String>()
            every { store.loadContext("testuser", date) } returns context()
            every { client.chat(any(), any()) } returns "답"
            every { store.append("testuser", date, capture(question), "답") } returns answer

            service.ask("testuser", date, "왜 낮아?")

            Then("사용자가 쓴 질문만 넘긴다 — 데이터 블록이 섞이지 않는다") {
                question.captured shouldBe "왜 낮아?"
                question.captured shouldNotContain "[직전 7일]"
            }
        }
    })
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.chat.DietChatServiceTest"`
Expected: **컴파일 실패** — `Unresolved reference: DietChatService`

- [ ] **Step 3: 서비스를 만든다**

```kotlin
package com.toy.backend.diet.chat

import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.llm.ChatTurn
import com.toy.backend.diet.llm.OpenRouterClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * **트랜잭션을 걸지 않는다.** LLM 호출이 `DietChatStore`의 두 트랜잭션 사이, 트랜잭션 **밖**에서
 * 일어나야 한다(함정 1).
 */
@Service
class DietChatService(
    private val store: DietChatStore,
    // 키가 없으면 빈이 등록되지 않는다 — `DietFeedbackGenerator`와 같은 모양이다.
    @Autowired(required = false) private val client: OpenRouterClient?,
) {
    val isAvailable: Boolean get() = client != null

    /**
     * **가드가 맨 앞이다**(함정 4). 키가 없으면 컨텍스트를 읽지도 않고 거절한다 — 읽어 봐야
     * 호출할 수 없고, 상한 검사에 걸려 엉뚱한 오류가 나갈 수도 있다.
     */
    fun ask(
        username: String,
        date: LocalDate,
        message: String,
    ): DietChatAnswerResponse {
        val openRouter = client ?: throw CustomException(DietErrorCode.LLM_UNAVAILABLE)
        val context = store.loadContext(username, date)
        // 데이터 → 총평 → 히스토리 → 이번 질문. 총평은 대화의 출발점이라 assistant 자리에 넣고,
        // 아직 생성 전이면 그 줄을 뺀다(넣으면 빈 assistant 턴이 되어 히스토리가 어긋난다).
        val turns =
            buildList {
                add(ChatTurn("user", context.dataBlock))
                context.dayFeedback?.let { add(ChatTurn("assistant", it)) }
                addAll(context.history)
                add(ChatTurn("user", message))
            }
        // 답을 못 받으면 두 행 다 저장하지 않는다(함정 3) — 질문만 남으면 히스토리가
        // user, user, assistant로 어긋나 다음 턴 프롬프트가 깨진다. 사용자는 다시 물으면 된다.
        val answer = openRouter.chat(DietChatPrompts.SYSTEM_PROMPT, turns) ?: throw CustomException(DietErrorCode.CHAT_FAILED)
        return store.append(username, date, message, answer)
    }

    /** **키가 없어도 동작한다**(함정 4) — 저장된 대화를 보여주는 데는 LLM이 필요 없다. */
    fun history(
        username: String,
        date: LocalDate,
    ): DietChatResponse = store.history(username, date)
}
```

- [ ] **Step 4: 컨트롤러를 만든다**

```kotlin
package com.toy.backend.diet.chat

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
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * **응답 규칙에서 의도적으로 벗어난다.** 저장소 관례는 「생성은 `@ResponseCreated`로 201 +
 * Location」인데 여기서는 200 + 바디를 쓴다 — 201을 주고 클라이언트가 답을 다시 조회하게 만들면
 * 왕복이 두 번이 되고, 사용자가 화면에서 기다리는 대화에서 그 지연은 그대로 체감된다.
 * 답변 자체가 데이터라 `DataResponseBody`가 맞다. **관례 위반이 아니라 기록된 예외다.**
 */
@Tag(name = "하루 채팅", description = "하루 평가에 대해 되묻는 대화")
@RestController
@RequestMapping("/diet/days/{date}/chat")
class DietChatController(
    private val service: DietChatService,
) {
    @PostMapping
    @Operation(summary = "질문 — 답변을 만들어 저장하고 그대로 돌려준다")
    fun ask(
        @Parameter(description = "기준 날짜", example = "2026-08-01")
        @PathVariable
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @Valid @RequestBody request: DietChatRequest,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<DietChatAnswerResponse>> =
        ResponseEntity.ok(DataResponseBody(service.ask(authentication.name, date, request.message)))

    @GetMapping
    @Operation(summary = "대화 조회 — LLM 키가 없어도 동작한다")
    fun list(
        @Parameter(description = "기준 날짜", example = "2026-08-01")
        @PathVariable
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<DietChatResponse>> =
        ResponseEntity.ok(DataResponseBody(service.history(authentication.name, date)))
}
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.chat.DietChatServiceTest"`
Expected: PASS

Run: `grep -rln isAvailable --include='*.kt' apps/daily-record/src/main`
Expected: `DietChatService.kt`가 목록에 있다 — 함정 4의 짝 확인이다

Run: `./gradlew :daily-record:test`
Expected: PASS (전체)

- [ ] **Step 6: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/ \
        apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/
git commit -m "feat: 하루 채팅 엔드포인트를 연다

POST/GET /diet/days/{date}/chat. 200 + 바디를 쓴다 — 201을 주고 클라이언트가
답을 다시 조회하게 만들면 왕복이 두 번이 되고, 사용자가 화면에서 기다리는
대화에서 그 지연은 그대로 체감된다. 관례 위반이 아니라 기록된 예외로 둔다.

LLM이 null을 돌려주면 두 행 다 저장하지 않는다. 질문만 남으면 히스토리가
user, user, assistant로 어긋나 다음 턴 프롬프트가 깨진다.

LLM_UNAVAILABLE 가드는 POST에만 넣는다. GET은 막지 않는다 — 저장된 대화를
보여주는 데는 LLM이 필요 없다. 이 저장소에서 이 짝을 네 번 넣으며 매번 한쪽을
빠뜨렸다.

턴 순서는 데이터·총평·히스토리·이번 질문이다. 총평이 아직 없으면 그 줄을
뺀다 — 넣으면 빈 assistant 턴이 되어 히스토리가 어긋난다."
```

---

### Task 7: 정리 배치

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatCleanupService.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatCleanupScheduler.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/DietChatCleanupServiceTest.kt` (신규)

**Interfaces:**
- Consumes: `DietChatMessageRepository.deleteByCreatedAtBefore` (Task 3)
- Produces: `DietChatCleanupService.purgeExpired(cutoff: LocalDateTime): Long`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```kotlin
package com.toy.backend.diet.chat

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDateTime

class DietChatCleanupServiceTest :
    BehaviorSpec({
        val repository = mockk<DietChatMessageRepository>()
        val service = DietChatCleanupService(repository)
        val cutoff = LocalDateTime.of(2026, 7, 28, 4, 20)

        Given("만료된 대화가 있으면") {
            every { repository.deleteByCreatedAtBefore(cutoff) } returns 12L

            Then("지운 건수를 돌려준다") {
                service.purgeExpired(cutoff) shouldBe 12L
            }
        }
    })
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.chat.DietChatCleanupServiceTest"`
Expected: **컴파일 실패** — `Unresolved reference: DietChatCleanupService`

- [ ] **Step 3: 서비스와 스케줄러를 만든다**

```kotlin
package com.toy.backend.diet.chat

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class DietChatCleanupService(
    private val repository: DietChatMessageRepository,
) {
    @Transactional
    fun purgeExpired(cutoff: LocalDateTime): Long = repository.deleteByCreatedAtBefore(cutoff)
}
```

```kotlin
package com.toy.backend.diet.chat

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * 오래된 대화를 지운다. 04:00(임시 파일)·04:10(분석) 뒤에 붙인다.
 *
 * **분석 TTL이 24시간인데 대화를 7일로 두는 이유** — 분석은 확정하면 지워지는 중간 산물이지만
 * 대화는 사용자가 직접 쓴 것이고, 다음 날 다시 열어 볼 만하다.
 */
@Component
class DietChatCleanupScheduler(
    private val service: DietChatCleanupService,
) {
    // cutoff 는 createdAt(감사 필드)과 같은 시계를 써야 하므로 zone 인자 없는 now() 를 쓴다.
    @Scheduled(cron = "0 20 4 * * *")
    fun purgeExpiredChats() {
        val cutoff = LocalDateTime.now().minus(CHAT_TTL)
        try {
            val purged = service.purgeExpired(cutoff)
            if (purged > 0) logger.info { "만료 하루 채팅 정리 완료: ${purged}건 (cutoff=$cutoff)" }
        } catch (e: Exception) {
            logger.error(e) { "만료 하루 채팅 정리 실패 (cutoff=$cutoff)" }
        }
    }

    companion object {
        private val CHAT_TTL: Duration = Duration.ofDays(7)
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :daily-record:test`
Expected: PASS (전체)

- [ ] **Step 5: 기동으로 확인한다**

**파생 쿼리 파싱과 스케줄러 등록은 기동해야 드러난다.** `FoodRepository`에서 이름이 안 파싱돼 앱이 통째로 안 뜬 적이 있다 — Task 3의 `PartTree` 검사가 그물이지만, 실제 기동이 최종 확인이다.

```bash
./gradlew :daily-record:bootRun
```

`Started DailyRecordApplicationKt`가 뜨고 빈 생성 예외가 없으면 된다. 로컬 Postgres가 필요하다.

- [ ] **Step 6: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/ \
        apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/
git commit -m "feat: 오래된 하루 채팅을 정리한다

매일 04:20, TTL 7일. 04:00(임시 파일)·04:10(분석) 뒤에 붙인다.

분석 TTL이 24시간인데 대화를 7일로 두는 이유 — 분석은 확정하면 지워지는 중간
산물이지만 대화는 사용자가 직접 쓴 것이고 다음 날 다시 열어 볼 만하다."
```

---

## 수동 확인 (전체 태스크 완료 뒤)

이 저장소의 단위 테스트는 리포지토리를 목으로 대체하므로 **트랜잭션 경계·LAZY 로딩·DB 제약을 잡지 못한다**(`AGENTS.md`). 아래는 기동해서 봐야 한다.

- `OPENROUTER_API_KEY` 없이 기동 → `POST`가 503(`LLM_UNAVAILABLE`), `GET`은 200
- 키를 넣고 기동 → 오늘 끼니를 하나 확정한 뒤 `POST /diet/days/{today}/chat`으로 「점심 왜 이 점수야?」
- **지난 날짜로 열어 「이 날 어땠어?」** — 답변이 「오늘」이라고 하지 않는지, 그 날짜를 정확히 가리키는지
- **「어제보다 나았어?」** — 직전 7일을 근거로 답하는지
- **「지난달 1일은?」** — 그 날짜를 열어 물어보라고 안내하는지(거절 문구가 아니라)
- 기록 없는 날짜로 `POST` → 400
- 같은 날 21번째 질문 → 400(`CHAT_TURN_LIMIT_EXCEEDED`)
- **하루 피드백 문장이 여전히 정상인지** — Task 1이 그 프롬프트를 바꿨다

## 범위 밖 (설계 문서와 같다)

끼니 단위 채팅 · 직전 7일보다 먼 날짜 · 기간 집계(`DietStatsService`의 몫) · 직전 7일의 수량·매크로 · 스트리밍(SSE) · 대화 삭제 엔드포인트 · 대화 검색 · 자동 재시도 · 동시성 방어(사용자가 직접 누르는 동작이고 사용자가 둘이다)
