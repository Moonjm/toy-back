# 하루 채팅을 이어지는 대화로 바꾸는 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 날짜 하나에 갇힌 대화를 채팅앱처럼 하나로 잇는다 — 히스토리는 「7일 이내 최근 20턴」 큐, 조회는 `id` 커서 페이징, 질문 횟수 상한은 없앤다.

**Architecture:** 이미 구현된 기능을 고치는 계획이다. 새로 만드는 파일이 없고 기존 여섯 파일을 바꾼다. **각 태스크가 끝날 때마다 트리가 컴파일되고 전체 스위트가 통과해야 한다** — 그래서 「상한 상수 제거」 같은 정리는 그 마지막 소비자가 사라지는 태스크에 붙였다.

**Tech Stack:** Kotlin / Spring Boot / JPA(Hibernate) / Kotest `BehaviorSpec` + MockK / Gradle(Spotless+ktlint)

**설계 문서:** `docs/superpowers/specs/2026-07-31-diet-day-chat-design.md` — **「세 창이 서로 다르다」와 함정 5-1을 먼저 읽는다.**

## Global Constraints

- **세 창을 섞지 않는다.** 데이터 블록의 직전 7일은 `Meal.date` 기준, 히스토리의 7일은 `createdAt` 기준, 20턴은 그 안에서 최근순이다. 같은 값(7)이라도 축이 달라 묶지 않는다.
- **히스토리는 큐다.** 20턴을 넘으면 오래된 것부터 밀려난다 — 막지 않는다. 그래서 요청 크기가 대화 길이와 무관하게 유계다.
- **질문 횟수를 막지 않는다.** 상한 검사·`remainingTurns`·`CHAT_TURN_LIMIT_EXCEEDED`가 전부 사라진다.
- **히스토리의 사용자 턴에 `date`를 접두로 붙인다**(함정 5-1). `createdAt`이 아니다. 답변 턴과 이번 질문에는 붙이지 않는다.
- **LLM 호출은 여전히 트랜잭션 밖이다**(함정 1). `DietChatService`에 `@Transactional`을 걸지 않는다.
- **`LLM_UNAVAILABLE` 가드는 `POST`에만**(함정 4). `GET`은 키 없이 돈다.
- **파생 쿼리 이름은 파서를 통과해야 한다.** `DietChatMessageRepositoryQueryTest`의 `PartTree` 검사가 새 메서드도 덮는다 — 이름이 틀리면 기동 때 앱이 통째로 안 뜬다.
- 커밋 전 `./gradlew spotlessApply`. 커밋 메시지는 이 저장소 관례(한국어 현재형 제목 + 왜를 적는 본문).

## 설계 문서에 없는 것을 하나 더한다

**`size`에 상한을 건다.** 설계는 `DEFAULT_PAGE_SIZE`만 정했는데, 클라이언트가 `size=100000`을 보내면 대화 전체를 한 번에 읽는다. `coerceIn(1, MAX_PAGE_SIZE)`로 서비스에서 조인다 — 한 줄이고, 없으면 잘못된 요청 하나가 그대로 전량 조회가 된다.

## 겸사겸사 닫는 이월 지적 하나

`ChatRole` → API 값 변환이 `it.role.name.lowercase()`로 **enum 이름에 암묵 결합**돼 있다(태스크 5 리뷰의 이월 Minor). 이 계획이 그 줄을 어차피 다시 쓰므로 `when`으로 바꾼다 — exhaustive 검사가 걸려 enum 이름이 바뀌면 컴파일에 잡힌다.

---

### Task 1: 히스토리를 「7일 × 20턴」 큐로 바꾸고 사용자 턴에 날짜를 붙인다

지금 히스토리는 `findByUserAndDateOrderByIdAsc(user, date)` — **그 날짜 전량**이다. 이것을 「`createdAt` 7일 이내 중 최근 20턴」으로 바꾸고, 날짜를 넘나들게 된 만큼 사용자 턴에 그 질문의 날짜를 붙인다(함정 5-1).

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatMessageRepository.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatPrompts.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatStore.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/DietChatStoreTest.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/DietChatServiceTest.kt` (`ChatContext` 픽스처)

**Interfaces:**
- Consumes: `ChatTurn(role: String, content: String)` (`diet.llm`), `DietChatMessage(user, date, role, content)`
- Produces:
  - `DietChatMessageRepository.findByUserAndCreatedAtAfterOrderByIdDesc(user, createdAt, pageable): List<DietChatMessage>`
  - `DietChatPrompts.HISTORY_TURNS = 20` · `DietChatPrompts.HISTORY_DAYS = 7L`
  - `DietChatPrompts.historyTurns(messages: List<DietChatMessage>): List<ChatTurn>`
  - `ChatContext`에서 `remainingTurns`가 빠진다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DietChatStoreTest.kt`의 `Given("히스토리가 있으면")` 블록을 아래로 **교체**한다. 나머지 `Given`은 그대로 둔다.

```kotlin
        // 히스토리는 큐다 — 7일 이내에 물은 것 중 최근 20턴만 싣고 오래된 것은 밀려난다.
        Given("히스토리가 7일 이내에 있으면") {
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)
            } returns listOf(mealOn(date, MealType.LUNCH, "제육볶음", 5L))
            every { feedbackRepository.findByUserAndDate(user, date) } returns
                DailyDietFeedback(
                    user = user,
                    date = date,
                    dayScore = 61,
                    feedback = "총평",
                    generatedAt = LocalDateTime.now(),
                )
            // 8/6에 물었지만 8/1에 대한 질문 — 접두는 date(08-01)이지 createdAt(08-06)이 아니다.
            every {
                messageRepository.findByUserAndCreatedAtAfterOrderByIdDesc(eq(user), any(), any())
            } returns
                listOf(
                    DietChatMessage(user, date.minusDays(5), ChatRole.ASSISTANT, "나트륨 때문입니다").withId(2L),
                    DietChatMessage(user, date.minusDays(5), ChatRole.USER, "왜 낮아?").withId(1L),
                )

            val context = store.loadContext("testuser", date)

            Then("오래된 것부터 시간순으로 뒤집혀 실린다") {
                context.history.map { it.role } shouldBe listOf("user", "assistant")
            }

            // 히스토리가 날짜를 넘나들어서, 안 붙이면 모델이 예전 질문을 오늘 것으로 읽는다.
            Then("사용자 턴 앞에 그 질문의 날짜가 붙는다") {
                context.history[0].content shouldBe "[08-01] 왜 낮아?"
            }

            Then("답변 턴에는 안 붙는다 — 바로 뒤에 와서 짝이 명확하다") {
                context.history[1].content shouldBe "나트륨 때문입니다"
            }

            Then("하루 피드백을 함께 들고 나온다 — 대화의 출발점이다") {
                context.dayFeedback shouldBe "총평"
            }
        }

        // 「어느 날 밥 얘기인가」가 아니라 「어느 대화를 기억하는가」라 축이 다르다.
        Given("히스토리 창은") {
            val cutoff = slot<LocalDateTime>()
            val pageable = slot<org.springframework.data.domain.Pageable>()
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)
            } returns listOf(mealOn(date, MealType.LUNCH, "제육볶음", 6L))
            every {
                messageRepository.findByUserAndCreatedAtAfterOrderByIdDesc(eq(user), capture(cutoff), capture(pageable))
            } returns emptyList()

            store.loadContext("testuser", date)

            Then("createdAt 기준 7일이다 — date 기준이 아니다") {
                cutoff.captured shouldBeAfter LocalDateTime.now().minusDays(8)
                cutoff.captured shouldBeBefore LocalDateTime.now().minusDays(6)
            }

            Then("20턴, 즉 40행을 받는다") {
                pageable.captured.pageSize shouldBe 40
            }
        }
```

**같은 파일에서 세 곳을 더 고쳐야 컴파일된다.** 빠뜨리면 Step 2의 실패가 엉뚱한 이유로 난다.

1. 기존 `Given("턴 상한에 닿았으면")` 블록을 **통째로 지운다** — 상한이 없어졌다.
2. `beforeContainer`의 히스토리 스텁을 새 메서드로 바꾼다. 안 바꾸면 다른 `Given`들이
   스텁 없는 새 메서드를 불러 strict mockk가 터진다:

```kotlin
        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            every { activityRepository.findByUserAndDate(user, date) } returns null
            every { feedbackRepository.findByUserAndDate(user, date) } returns null
            every {
                messageRepository.findByUserAndCreatedAtAfterOrderByIdDesc(any(), any(), any())
            } returns emptyList()
        }
```

3. 기존 `Given("기준일과 직전 7일에 기록이 있으면")` 안의
   `Then("남은 턴은 상한 그대로다")` 블록을 **지운다** — `ChatContext.remainingTurns`가 사라진다.

import를 더한다:

```kotlin
import io.kotest.matchers.date.shouldBeAfter
import io.kotest.matchers.date.shouldBeBefore
import io.mockk.slot
import java.time.LocalDateTime
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.chat.DietChatStoreTest"`
Expected: **컴파일 실패** — `Unresolved reference: findByUserAndCreatedAtAfterOrderByIdDesc`

- [ ] **Step 3: 리포지토리에 히스토리 조회를 더한다**

`DietChatMessageRepository.kt`의 기존 메서드 **아래**에 더한다(기존 것은 아직 `append`·`history`가 쓴다):

```kotlin
    /**
     * 프롬프트에 실을 히스토리. **`createdAt` 기준**이고 `id DESC`라 최근 것부터 온다 —
     * 개수는 `Pageable`로 자른다. 시간순으로 뒤집는 것은 부르는 쪽의 몫이다.
     *
     * **`date`가 아니라 `createdAt`이다.** 「어느 날 밥 얘기인가」(`date`)와 「어느 대화를
     * 기억하는가」(`createdAt`)는 축이 다르다(설계 「세 창이 서로 다르다」).
     */
    fun findByUserAndCreatedAtAfterOrderByIdDesc(
        user: User,
        createdAt: LocalDateTime,
        pageable: Pageable,
    ): List<DietChatMessage>
```

import 두 개를 더한다:

```kotlin
import org.springframework.data.domain.Pageable
import java.time.LocalDateTime
```

- [ ] **Step 4: 프롬프트에 히스토리 조립을 더한다**

`DietChatPrompts.kt`의 `RECENT_DAYS` 아래에 상수 둘을 더한다:

```kotlin
    /** 프롬프트에 싣는 대화 창. 넘으면 오래된 턴부터 밀려난다 — 막지 않는다. */
    const val HISTORY_TURNS = 20

    /** 히스토리로 거슬러 올라가는 기간. **`createdAt` 기준**이라 `RECENT_DAYS`와 축이 다르다. */
    const val HISTORY_DAYS = 7L
```

`recentDaysBlock` 아래에 함수를 더한다:

```kotlin
    /**
     * 히스토리를 API 턴으로 바꾼다. **사용자 턴 앞에 그 질문의 날짜를 붙인다**(함정 5-1) —
     * 히스토리가 날짜를 넘나들어서, 안 붙이면 8월 3일에 물은 「점심 왜 낮아?」를 모델이
     * 오늘 점심 얘기로 읽는다.
     *
     * 붙이는 값은 `date`(어느 날에 대한 질문인가)이지 `createdAt`(언제 물었나)이 아니다 —
     * 8월 6일에 8월 1일을 물었다면 `[08-01]`이다.
     *
     * **답변에는 안 붙인다.** 바로 뒤에 와서 짝이 명확하고, 양쪽에 붙이면 노이즈만 는다.
     */
    fun historyTurns(messages: List<DietChatMessage>): List<ChatTurn> =
        messages.map {
            when (it.role) {
                ChatRole.USER -> ChatTurn("user", "[${it.date.format(HISTORY_DATE)}] ${it.content}")
                ChatRole.ASSISTANT -> ChatTurn("assistant", it.content)
            }
        }
```

`RECENT_DATE` 옆에 포매터를 더한다:

```kotlin
    /** 히스토리 접두. 연도를 빼 줄을 짧게 유지한다 — 7일 창 안이라 해가 갈릴 일이 드물다. */
    private val HISTORY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
```

import 하나를 더한다: `import com.toy.backend.diet.llm.ChatTurn`

**`when`으로 쓰는 이유** — 예전 `it.role.name.lowercase()`는 enum 이름에 암묵 결합돼 있었다. `when`이면 exhaustive 검사가 걸려 `ChatRole`을 바꿀 때 컴파일에 잡힌다.

- [ ] **Step 5: 스토어의 히스토리 로드를 바꾼다**

`DietChatStore.kt`에서 `ChatContext`의 `remainingTurns` 줄을 지운다:

```kotlin
data class ChatContext(
    /** `DietChatPrompts.context(...)` 결과. 매 요청 새로 만들고 저장하지 않는다(함정 2). */
    val dataBlock: String,
    /** 저장된 하루 피드백. 아직 생성 전이면 null이고, 그때는 오프닝 턴을 뺀다. */
    val dayFeedback: String?,
    val history: List<ChatTurn>,
)
```

`loadContext`의 히스토리 부분(`val history = ...`부터 `return ChatContext(...)`까지)을 바꾼다:

```kotlin
        // **히스토리는 큐다.** 7일 이내에 물은 것 중 최근 20턴만 싣고 오래된 것은 밀려난다 —
        // 막지 않는다. 그래서 대화가 100번 쌓여도 요청 크기가 유계다.
        //
        // `id DESC`로 받아 뒤집는다. `asc`로 받으면 **가장 오래된** 20턴이 되어 정반대가 된다.
        // `append`가 질문·답을 한 트랜잭션에 함께 쓰므로 행은 늘 교대하고, 짝수 개를 가져오니
        // 뒤집은 목록의 맨 앞은 항상 질문이다.
        val history =
            messageRepository
                .findByUserAndCreatedAtAfterOrderByIdDesc(
                    user,
                    LocalDateTime.now().minusDays(DietChatPrompts.HISTORY_DAYS),
                    PageRequest.of(0, DietChatPrompts.HISTORY_TURNS * 2),
                ).reversed()

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
            history = DietChatPrompts.historyTurns(history),
        )
    }
```

`DietErrorCode` import와 `ChatRole` import가 이 파일에서 아직 쓰이는지 확인하고, 안 쓰이면 지운다
(`append`가 여전히 `ChatRole`을 쓰므로 그쪽은 남는다).

import 둘을 더한다:

```kotlin
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime
```

- [ ] **Step 5-1: `DietChatServiceTest`의 픽스처를 고친다**

`ChatContext`에서 `remainingTurns`가 빠지므로 서비스 테스트의 헬퍼도 고쳐야 컴파일된다:

```kotlin
        fun context(
            feedback: String? = "총평",
            history: List<ChatTurn> = emptyList(),
        ) = ChatContext(
            dataBlock = "[2026-08-01 (토) 먹은 끼니]\n- 점심: 제육볶음 (555kcal)\n[직전 7일]\n- 07-30 (목) 58점 2930kcal",
            dayFeedback = feedback,
            history = history,
        )
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
        apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/
git commit -m "feat: 채팅 히스토리를 7일 × 20턴 큐로 바꾼다

그 날짜 전량만 싣던 것을 「createdAt 7일 이내 중 최근 20턴」으로 바꾼다. 어제
물은 것이 오늘 대화의 컨텍스트에 실려야 「어제보다 나았어?」에 답할 수 있다.

큐라서 20턴을 넘으면 오래된 것부터 밀려난다. 대화가 100번 쌓여도 요청 크기가
유계이고, 그래서 질문 횟수 상한이 비용 방어로 필요하지 않게 된다.

창은 createdAt 기준이다. 데이터 블록의 직전 7일은 Meal.date 기준이라 축이
다르다 — 앞은 「어느 날 밥 얘기인가」이고 뒤는 「어느 대화를 기억하는가」다.

히스토리가 날짜를 넘나들게 됐으므로 사용자 턴 앞에 그 질문의 날짜를 붙인다.
안 붙이면 8월 3일에 물은 「점심 왜 낮아?」를 모델이 오늘 점심 얘기로 읽는다 —
함정 5가 히스토리에서 되풀이되는 자리다. 붙이는 값은 date이지 createdAt이
아니고, 답변에는 안 붙인다.

역할 변환을 when으로 바꿨다. name.lowercase()는 enum 이름에 암묵 결합돼 있어
이름을 바꾸면 런타임에야 드러났다."
```

---

### Task 2: 저장 응답에서 남은 턴을 걷어내고 메시지에 날짜를 싣는다

`append`가 `DietChatAnswerResponse(message, remainingTurns)`를 돌려주는데 남은 턴이 사라졌다. 저장된 답 한 건만 돌려준다. 그리고 스트림이 물은 시각 순이라 **메시지마다 `date`가 필요하다** — 8/1에 대한 질문이 8/6 대화 사이에 앉기 때문이다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatDtos.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatStore.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatService.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatController.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/DietChatStoreTest.kt`, `DietChatServiceTest.kt`

**Interfaces:**
- Consumes: Task 1의 `ChatContext`(remainingTurns 없음)
- Produces:
  - `DietChatMessageResponse(id, date, role, content, createdAt)` — **`date`가 새로 생겼다**
  - `DietChatStore.append(...): DietChatMessageResponse`
  - `DietChatService.ask(...): DietChatMessageResponse`
  - `DietChatAnswerResponse`가 사라진다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DietChatStoreTest.kt`에 `append` 테스트를 더한다(Task 1에서 지운 상한 블록 자리에 넣으면 된다):

```kotlin
        Given("질문과 답을 저장하면") {
            val saved = mutableListOf<DietChatMessage>()
            every { messageRepository.save(capture(saved)) } answers {
                firstArg<DietChatMessage>().withId(saved.size.toLong())
            }

            val response = store.append("testuser", date, "왜 낮아?", "나트륨 때문입니다")

            // 데이터 블록이 세 번째 행으로 저장되면 여기서 잡힌다 — 함정 2가 재현되는 자리다.
            Then("USER, ASSISTANT 두 행만 저장된다") {
                saved.map { it.role } shouldBe listOf(ChatRole.USER, ChatRole.ASSISTANT)
                saved.map { it.content } shouldBe listOf("왜 낮아?", "나트륨 때문입니다")
            }

            Then("저장된 답 한 건을 돌려준다") {
                response.role shouldBe ChatRole.ASSISTANT
                response.content shouldBe "나트륨 때문입니다"
            }

            // 스트림이 물은 시각 순이라 8/1에 대한 질문이 8/6 대화 사이에 앉는다.
            // 이 값이 없으면 앱이 말풍선에 「8/1에 대해」를 못 붙인다.
            Then("어느 날에 대한 질문인지가 실린다") {
                response.date shouldBe date
            }
        }
```

`DietChatServiceTest.kt`에서 `answer` 픽스처와 그것을 쓰는 단언을 바꾼다:

```kotlin
        val answer =
            DietChatMessageResponse(2L, date, ChatRole.ASSISTANT, "나트륨 때문입니다", LocalDateTime.now())
```

`Then("저장된 답을 돌려준다")`의 `result shouldBe answer`는 그대로 통과한다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.chat.*"`
Expected: **컴파일 실패** — `DietChatMessageResponse`에 `date` 인자가 없고 `append`의 반환 타입이 다르다

- [ ] **Step 3: DTO를 고친다**

`DietChatDtos.kt`에서 `DietChatMessageResponse`에 `date`를 더하고 `DietChatAnswerResponse`를 지운다:

```kotlin
data class DietChatMessageResponse(
    val id: Long,
    /**
     * **어느 날에 대한 질문인가.** `createdAt`(언제 물었나)과 다르다 — 8월 6일에 8월 1일을
     * 물을 수 있다. 스트림이 물은 시각 순이라 그 질문이 8월 6일 대화 사이에 앉으므로,
     * 앱이 말풍선에 「8/1에 대해」를 붙이려면 이 값이 필요하다.
     */
    val date: LocalDate,
    val role: ChatRole,
    val content: String,
    val createdAt: LocalDateTime,
)
```

import 하나를 더한다: `import java.time.LocalDate`

- [ ] **Step 4: 스토어·서비스·컨트롤러의 반환 타입을 바꾼다**

`DietChatStore.kt`의 `append`:

```kotlin
    /** 질문·답 두 행을 순서대로 저장하고 **저장된 답 한 건**을 돌려준다. id·`createdAt`이 이 트랜잭션 안에서 채워진다. */
    @Transactional
    fun append(
        username: String,
        date: LocalDate,
        question: String,
        answer: String,
    ): DietChatMessageResponse {
        val user = findUser(username)
        messageRepository.save(DietChatMessage(user, date, ChatRole.USER, question))
        return messageRepository.save(DietChatMessage(user, date, ChatRole.ASSISTANT, answer)).toResponse()
    }
```

같은 파일의 `toResponse`:

```kotlin
    private fun DietChatMessage.toResponse() = DietChatMessageResponse(requiredId, date, role, content, createdAt)
```

`DietChatService.ask`의 반환 타입을 `DietChatMessageResponse`로, `DietChatController.ask`의 반환 타입을 `ResponseEntity<DataResponseBody<DietChatMessageResponse>>`로 바꾼다. 본문은 그대로다.

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.chat.*"`
Expected: PASS

Run: `./gradlew :daily-record:test`
Expected: PASS (전체)

- [ ] **Step 6: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/ \
        apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/
git commit -m "feat: 저장된 답에 날짜를 싣고 남은 턴을 걷어낸다

질문 횟수 상한이 사라져 remainingTurns가 뜻을 잃었다. append는 저장된 답 한
건만 돌려준다.

메시지에 date를 싣는다. 스트림이 물은 시각 순이라 8월 1일에 대한 질문이 8월 6일
대화 사이에 앉는데, 그 값이 없으면 앱이 말풍선에 「8/1에 대해」를 못 붙인다.
createdAt(언제 물었나)과는 다른 값이다.

기능의 유일한 쓰기 경로인 append에 테스트를 걸었다. 데이터 블록이 세 번째 행으로
저장되면 여기서 잡힌다 — 함정 2가 재현되는 자리인데 그물이 반쪽이었다."
```

---

### Task 3: 조회를 커서 페이징으로 바꾸고 죽은 상한을 걷어낸다

`GET /diet/days/{date}/chat`이 그날 대화만 준다. 화면이 채팅앱 형태가 되므로 **날짜에서 풀고 `id` 커서로 페이징**한다. 이 태스크가 `MAX_TURNS_PER_DAY`의 마지막 소비자를 없애므로 상수와 에러코드도 함께 지운다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatMessageRepository.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatDtos.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatStore.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatService.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatController.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/DietErrorCode.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/DietChatStoreTest.kt`, `DietChatServiceTest.kt`

**Interfaces:**
- Consumes: Task 2의 `DietChatMessageResponse(id, date, role, content, createdAt)`
- Produces:
  - `DietChatMessageRepository.findByUserAndIdLessThanOrderByIdDesc(user, id, pageable): List<DietChatMessage>`
  - `DietChatPageResponse(messages: List<DietChatMessageResponse>, nextCursor: Long?)`
  - `DietChatStore.page(username, before: Long?, size: Int): DietChatPageResponse`
  - `DietChatService.page(username, before, size): DietChatPageResponse`
  - `GET /diet/chat?before=&size=`
  - `MAX_TURNS_PER_DAY`·`DietChatResponse`·`DietErrorCode.CHAT_TURN_LIMIT_EXCEEDED`가 사라진다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DietChatStoreTest.kt`의 `Given("히스토리가 있으면")` 뒤에 페이징 테스트를 더한다:

```kotlin
        Given("대화를 페이징으로 읽으면") {
            val before = slot<Long>()
            val pageable = slot<org.springframework.data.domain.Pageable>()
            // size(2)보다 한 건 더 준다 — 다음 장이 있다는 뜻이다.
            every {
                messageRepository.findByUserAndIdLessThanOrderByIdDesc(eq(user), capture(before), capture(pageable))
            } returns
                listOf(
                    DietChatMessage(user, date, ChatRole.ASSISTANT, "답2").withId(4L),
                    DietChatMessage(user, date.minusDays(3), ChatRole.USER, "질문2").withId(3L),
                    DietChatMessage(user, date.minusDays(3), ChatRole.ASSISTANT, "답1").withId(2L),
                )

            val page = store.page("testuser", null, 2)

            Then("첫 장은 커서 없이 최신부터다") {
                before.captured shouldBe Long.MAX_VALUE
                page.messages.map { it.id } shouldBe listOf(4L, 3L)
            }

            Then("다음 장이 있으면 nextCursor가 마지막 id다") {
                page.nextCursor shouldBe 3L
            }

            // 날짜로 자르지 않으므로 한 페이지에 여러 날짜가 섞인다.
            Then("메시지마다 어느 날에 대한 질문인지가 실린다") {
                page.messages.map { it.date } shouldBe listOf(date, date.minusDays(3))
            }

            Then("다음 장이 있는지 보려고 한 건 더 받는다") {
                pageable.captured.pageSize shouldBe 3
            }
        }

        Given("마지막 장이면") {
            every {
                messageRepository.findByUserAndIdLessThanOrderByIdDesc(eq(user), any(), any())
            } returns listOf(DietChatMessage(user, date, ChatRole.USER, "질문").withId(1L))

            val page = store.page("testuser", 5L, 2)

            // 앱이 무한 스크롤을 멈추는 신호다.
            Then("nextCursor가 null이다") {
                page.nextCursor shouldBe null
            }
        }
```

`DietChatServiceTest.kt`의 `Then("조회는 그대로 동작한다")`를 바꾼다:

```kotlin
            Then("조회는 그대로 동작한다") {
                val response = DietChatPageResponse(emptyList(), null)
                every { store.page("testuser", null, 30) } returns response
                service.page("testuser", null, 30) shouldBe response
            }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.chat.*"`
Expected: **컴파일 실패** — `Unresolved reference: findByUserAndIdLessThanOrderByIdDesc`, `page`, `DietChatPageResponse`

- [ ] **Step 3: 리포지토리를 고친다**

`findByUserAndDateOrderByIdAsc`를 **지우고**(이 태스크가 마지막 소비자를 없앤다) 페이징 조회를 더한다:

```kotlin
    /**
     * 화면용 페이징. **날짜로 자르지 않는다** — 사용자 전체가 한 스트림이다.
     *
     * `id`가 단조 증가라 `offset` 없이 안정적으로 뒤로 간다. 중간에 새 메시지가 들어와도
     * 이미 읽은 페이지가 밀리지 않는다.
     */
    fun findByUserAndIdLessThanOrderByIdDesc(
        user: User,
        id: Long,
        pageable: Pageable,
    ): List<DietChatMessage>
```

- [ ] **Step 4: DTO를 고친다**

`DietChatResponse`를 지우고:

```kotlin
/**
 * 한 장. **`nextCursor`가 null이면 더 없다** — 앱이 그때 무한 스크롤을 멈춘다.
 *
 * 최신이 먼저 온다(`id DESC`). 앱이 뒤집어 아래에 붙인다.
 */
data class DietChatPageResponse(
    val messages: List<DietChatMessageResponse>,
    val nextCursor: Long?,
)
```

- [ ] **Step 5: 스토어의 `history`를 `page`로 바꾼다**

`DietChatStore.kt`에서 `history`를 지우고 넣는다. 파일 맨 위의 `const val MAX_TURNS_PER_DAY = 20`도 지운다.

```kotlin
    /**
     * 화면용 페이징. **키가 없어도 동작한다** — 저장된 대화를 보여주는 데는 LLM이 필요 없다(함정 4).
     *
     * **한 건 더 받아 「다음 장이 있는가」를 판별한다.** `size`만 받으면 마지막 장이 정확히
     * 꽉 찼을 때 커서가 남아 앱이 빈 요청을 한 번 더 한다.
     */
    @Transactional(readOnly = true)
    fun page(
        username: String,
        before: Long?,
        size: Int,
    ): DietChatPageResponse {
        val rows =
            messageRepository.findByUserAndIdLessThanOrderByIdDesc(
                findUser(username),
                // 첫 장은 커서가 없다 — 가장 큰 id보다 큰 값으로 열어 준다.
                before ?: Long.MAX_VALUE,
                PageRequest.of(0, size + 1),
            )
        val page = rows.take(size)
        return DietChatPageResponse(
            messages = page.map { it.toResponse() },
            nextCursor = if (rows.size > size) page.last().requiredId else null,
        )
    }
```

- [ ] **Step 6: 서비스와 컨트롤러를 고친다**

`DietChatService.kt`의 `history`를 바꾼다:

```kotlin
    /**
     * **키가 없어도 동작한다**(함정 4) — 저장된 대화를 보여주는 데는 LLM이 필요 없다.
     *
     * `size`를 조인다. 없으면 `size=100000` 한 번이 대화 전량 조회가 된다.
     */
    fun page(
        username: String,
        before: Long?,
        size: Int,
    ): DietChatPageResponse = store.page(username, before, size.coerceIn(1, MAX_PAGE_SIZE))

    private companion object {
        /** 한 장의 상한. 앱은 기본값(30)을 쓰고, 이 값은 잘못된 요청을 막는 자리다. */
        const val MAX_PAGE_SIZE = 100
    }
```

`DietChatController.kt`를 바꾼다 — **클래스 수준 `@RequestMapping`을 지우고 메서드마다 전체 경로를 준다.**

```kotlin
@Tag(name = "하루 채팅", description = "하루 평가에 대해 되묻는 대화")
@RestController
class DietChatController(
    private val service: DietChatService,
) {
    /** 그날 식단으로 답해야 하므로 날짜가 필요하다. */
    @PostMapping("/diet/days/{date}/chat")
    @Operation(summary = "질문 — 답변을 만들어 저장하고 그대로 돌려준다")
    fun ask(
        @Parameter(description = "기준 날짜", example = "2026-08-01")
        @PathVariable
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @Valid @RequestBody request: DietChatRequest,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<DietChatMessageResponse>> =
        ResponseEntity.ok(DataResponseBody(service.ask(authentication.name, date, request.message)))

    /**
     * **날짜가 없다** — 이어지는 스트림이라 날짜로 자르지 않는다. 최신이 먼저 오고 앱이
     * 뒤집어 아래에 붙인다. 위로 스크롤하면 `nextCursor`로 다음 장을 부른다.
     */
    @GetMapping("/diet/chat")
    @Operation(summary = "대화 페이징 조회 — LLM 키가 없어도 동작한다")
    fun page(
        @Parameter(description = "이 id보다 이전 것. 첫 장은 비운다", example = "120")
        @RequestParam(required = false) before: Long?,
        @Parameter(description = "한 장 크기", example = DEFAULT_PAGE_SIZE)
        @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) size: Int,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<DietChatPageResponse>> =
        ResponseEntity.ok(DataResponseBody(service.page(authentication.name, before, size)))
}

/** 애너테이션 인자라 컴파일 상수여야 해서 문자열이다. */
private const val DEFAULT_PAGE_SIZE = "30"
```

import 하나를 더한다: `import org.springframework.web.bind.annotation.RequestParam`
`import org.springframework.web.bind.annotation.RequestMapping`은 지운다.

- [ ] **Step 7: 죽은 에러코드를 지운다**

`DietErrorCode.kt`에서 지운다:

```kotlin
    CHAT_TURN_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "하루에 물어볼 수 있는 횟수(%s번)를 넘었습니다."),
```

- [ ] **Step 8: 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.diet.chat.*"`
Expected: PASS

Run: `grep -rn "MAX_TURNS_PER_DAY\|remainingTurns\|CHAT_TURN_LIMIT" apps/daily-record/src`
Expected: 아무것도 안 나온다

Run: `./gradlew :daily-record:test`
Expected: PASS (전체)

- [ ] **Step 9: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/diet/ \
        apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/
git commit -m "feat: 대화 조회를 커서 페이징으로 바꾼다

GET /diet/days/{date}/chat → GET /diet/chat?before=&size=. 화면이 채팅앱
형태가 되므로 날짜에서 푼다. POST는 그날 식단으로 답해야 하니 날짜를 유지한다.

id 키셋 페이징이라 offset 없이 안정적으로 뒤로 간다 — 중간에 새 메시지가
들어와도 이미 읽은 페이지가 밀리지 않는다.

한 건 더 받아 다음 장이 있는지 본다. size만 받으면 마지막 장이 정확히 꽉 찼을
때 커서가 남아 앱이 빈 요청을 한 번 더 한다.

size를 100으로 조인다. 설계에 없던 것인데, 없으면 size=100000 한 번이 대화
전량 조회가 된다.

이 변경으로 MAX_TURNS_PER_DAY의 마지막 소비자가 사라져 상수와
CHAT_TURN_LIMIT_EXCEEDED도 함께 지운다."
```

---

## 수동 확인 (전체 태스크 완료 뒤)

이 저장소의 단위 테스트는 리포지토리를 목으로 대체하므로 **파생 쿼리의 실제 실행·트랜잭션 경계·`Pageable` 동작을 잡지 못한다**(`AGENTS.md`). 아래는 기동해서 봐야 한다.

- **기동 자체** — 파생 쿼리 이름 둘이 새로 생겼다. `PartTree` 테스트가 그물이지만 최종 확인은 기동이다
- `OPENROUTER_API_KEY` 없이 → `POST`가 503, `GET /diet/chat`은 200
- 키를 넣고 여러 날에 걸쳐 물어본 뒤:
  - **`GET /diet/chat`이 날짜를 넘어 한 스트림으로 오는지**, `nextCursor`로 뒤로 가는지
  - 마지막 장에서 `nextCursor`가 null인지
  - 메시지마다 `date`가 실리는지, 그 값이 `createdAt`과 다를 수 있는지(8/6에 8/1을 물어 확인)
- **21번째 질문이 통과하는지** — 상한이 정말 없어졌는지
- **21턴을 쌓은 뒤 22번째 질문의 답이 첫 질문을 모르는지** — 큐가 실제로 미는지
- **8일 전에 물은 대화가 안 실리는지** — `createdAt` 창이 도는지
- **지난 날짜로 열어 「이 날 어땠어?」** — 답변이 그 날짜를 정확히 가리키는지(함정 5)
- **여러 날 히스토리에서 예전 질문을 오늘 것으로 오해하지 않는지**(함정 5-1)

## 범위 밖

앱 작업(짝 저장소에서 API가 끝난 뒤에 한다) · 대화 삭제 엔드포인트 · 대화 검색 · 스트리밍(SSE) · 질문 횟수 상한 · 동시성 방어
