# 하루 채팅 코치 타임라인 (서버) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 끼니를 확정하면 채팅 스트림에 끼니 카드가, 하루 총평이 완성되면 총평 카드가 쌓이게 한다.

**Architecture:** 카드는 **참조만 저장한다**(`mealId` 또는 `date`). 조회할 때 현재 `Meal`·
`DailyDietFeedback`에서 값을 읽어 채우므로 낡을 수가 없다. 프롬프트 히스토리는 `TEXT`만 본다.

**Tech Stack:** Kotlin, Spring Boot, JPA(Hibernate, `ddl-auto: update`), Kotest `BehaviorSpec`, MockK

설계: `docs/superpowers/specs/2026-08-07-diet-chat-timeline-design.md`

## Global Constraints

- **테스트는 Kotest `BehaviorSpec` + MockK다.** 이 저장소에는 MockMvc도 `@SpringBootTest`도 없다. 새로 들이지 않는다
- **주석·커밋 메시지는 한국어다.** 커밋 제목은 현재형("~한다"), 본문은 **왜**를 적는다
- **enum 컬럼은 `columnDefinition`을 명시한다** — ddl-auto가 CHECK 제약을 갱신하지 못해 나중에 값을 늘리면 기존 DB에서 INSERT가 깨진다
- **끼니 점수와 근거는 `DietScoreCalculator.scoreMeal` 한 번에서 함께 낸다.** 저장된 `Meal.score` 컬럼을 읽지 않는다
- **프롬프트 히스토리는 `ChatMessageType.TEXT`만 싣는다**
- **한 페이지에 리포지토리 조회는 종류당 한 번이다.** 카드마다 따로 조회하면 N+1이다
- **지워진 끼니의 카드는 삭제 시점에 지운다.** 조회에서 거르면 `nextCursor` 계산이 꼬인다
- 포맷: `./gradlew :daily-record:spotlessApply` — 커밋 전 필수
- 테스트: `./gradlew :daily-record:test`

## 파일 구조

| 파일 | 책임 | 태스크 |
| --- | --- | --- |
| `diet/chat/DietChatMessage.kt` | `ChatMessageType` enum, `type`·`mealId` 컬럼 | 1 |
| `diet/chat/DietChatMessageRepository.kt` | 타입 조건이 붙은 히스토리 조회, 존재 검사, 카드 삭제 | 1, 2 |
| `diet/chat/DietChatStore.kt` | 히스토리 타입 필터, 페이지에 카드 채우기 | 1, 5, 6 |
| `diet/chat/DietChatCardWriter.kt` (신규) | 카드를 놓고 치우는 자리 | 2 |
| `diet/chat/DietChatDtos.kt` | 타입별 응답, `ChatMealCard`, `ChatDayCard` | 4, 5, 6 |
| `diet/meal/MealService.kt` | 확정·삭제·병합에서 카드 관리 | 2 |
| `diet/feedback/DayFeedbackStore.kt` | 총평이 실리면 카드 | 3 |
| `diet/meal/MealRepository.kt` | 날짜 목록으로 끼니 읽기 | 6 |

---

### Task 1: 메시지에 타입을 넣고 프롬프트 히스토리가 TEXT만 보게 한다

카드 행이 프롬프트의 20턴 창을 먹으면 정작 사용자와 주고받은 대화가 밀려난다(설계 함정 4).
카드가 아직 없어도 **이 필터가 먼저 서 있어야** 다음 태스크에서 카드를 쌓는 순간 새지 않는다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatMessage.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatMessageRepository.kt:16-20`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatStore.kt:79-85`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/DietChatStoreTest.kt`

**Interfaces:**
- Produces:
  - `enum class ChatMessageType { TEXT, MEAL_CARD, DAY_SUMMARY }` (in `DietChatMessage.kt`)
  - `DietChatMessage(user, date, role, content, type = ChatMessageType.TEXT, mealId = null)`
  - `DietChatMessageRepository.findByUserAndTypeAndCreatedAtAfterOrderByIdDesc(user, type, createdAt, pageable): List<DietChatMessage>`

- [ ] **Step 1: 히스토리 조회에 TEXT가 전달되는지 보는 테스트를 쓴다**

`DietChatStoreTest.kt`의 `Given("히스토리 창은")` 블록을 통째로 아래로 바꾼다. 기존 블록은
같은 자리를 보고 있어서 둘을 남기면 같은 것을 두 번 검사한다.

```kotlin
        // 「어느 날 밥 얘기인가」가 아니라 「어느 대화를 기억하는가」라 축이 다르다.
        Given("히스토리 창은") {
            val type = slot<ChatMessageType>()
            val cutoff = slot<LocalDateTime>()
            val pageable = slot<org.springframework.data.domain.Pageable>()
            every {
                mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)
            } returns listOf(mealOn(date, MealType.LUNCH, "제육볶음", 6L))
            every {
                messageRepository.findByUserAndTypeAndCreatedAtAfterOrderByIdDesc(
                    eq(user),
                    capture(type),
                    capture(cutoff),
                    capture(pageable),
                )
            } returns emptyList()

            store.loadContext("testuser", date)

            // 카드가 섞이면 20턴 창을 카드가 먹어 정작 대화가 밀려난다. 카드 내용은 이미
            // `[끼니별 상세]`로 매 요청 실리므로 중복이기도 하다.
            Then("TEXT만 싣는다 — 카드는 프롬프트에 안 들어간다") {
                type.captured shouldBe ChatMessageType.TEXT
            }

            // 폭을 좁게 조인다 — HISTORY_DAYS를 6이나 8로 바꿔도 잡히도록, `now`를 두 번 평가해
            // 생기는 시차보다 훨씬 좁은 ±1분 창을 쓴다.
            Then("createdAt 기준 7일이다 — date 기준이 아니다") {
                val now = LocalDateTime.now()
                cutoff.captured shouldBeAfter now.minusDays(7).minusMinutes(1)
                cutoff.captured shouldBeBefore now.minusDays(7).plusMinutes(1)
            }

            Then("20턴, 즉 40행을 받는다") {
                pageable.captured.pageSize shouldBe 40
            }
        }
```

같은 파일의 `beforeContainer`(54-61행)와 `Given("히스토리가 7일 이내에 있으면")`의 스텁도
새 이름으로 바꾼다. **인자 개수가 늘었다.**

```kotlin
        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            every { activityRepository.findByUserAndDate(user, date) } returns null
            every { feedbackRepository.findByUserAndDate(user, date) } returns null
            every {
                messageRepository.findByUserAndTypeAndCreatedAtAfterOrderByIdDesc(any(), any(), any(), any())
            } returns emptyList()
        }
```

```kotlin
            every {
                messageRepository.findByUserAndTypeAndCreatedAtAfterOrderByIdDesc(eq(user), any(), any(), any())
            } returns
```

- [ ] **Step 2: 컴파일이 깨지는 것을 확인한다**

Run: `./gradlew :daily-record:test --tests "*DietChatStoreTest*"`

Expected: 컴파일 실패 — `Unresolved reference: findByUserAndTypeAndCreatedAtAfterOrderByIdDesc`,
`Unresolved reference: ChatMessageType`.

**이 태스크의 RED는 컴파일 실패가 맞다.** 리포지토리 메서드 이름 자체가 검사 대상이라
시그니처가 없으면 테스트를 쓸 수 없다.

- [ ] **Step 3: 엔티티에 타입과 끼니 참조를 더한다**

`DietChatMessage.kt`의 `enum class ChatRole` 아래에 더한다.

```kotlin
/** 타임라인에 무엇이 놓인 자리인가. */
enum class ChatMessageType { TEXT, MEAL_CARD, DAY_SUMMARY }
```

클래스 본문의 `content` 파라미터 **뒤에** 두 칸을 더한다. 기본값이 있어 기존 호출부
(`DietChatMessage(user, date, ChatRole.USER, question)`)가 그대로 컴파일된다.

```kotlin
    /**
     * **기존 행은 전부 `TEXT`다.** 컬럼 정의에 `default`를 둬야 ddl-auto가 not null 컬럼을
     * 붙일 때 이미 있는 행이 살아남는다. `role`이 이미 같은 이유로 `columnDefinition`을 쓴다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20) default 'TEXT'")
    var type: ChatMessageType = ChatMessageType.TEXT,
    /**
     * `MEAL_CARD`가 가리키는 끼니. **FK를 걸지 않는다** — 걸면 카드 삭제가 한 번 새는 순간
     * **끼니를 못 지우는 상태**가 되는데, 그건 카드가 남는 것보다 나쁘다. 정합성은 삭제
     * 경로에서 지키고, 혹시 매달린 참조가 남아도 조회가 그 행을 조용히 건너뛴다.
     */
    @Column(name = "meal_id")
    var mealId: Long? = null,
```

- [ ] **Step 4: 리포지토리 메서드 이름에 타입을 넣는다**

`DietChatMessageRepository.kt`의 `findByUserAndCreatedAtAfterOrderByIdDesc`를 **바꾼다**(추가가
아니다 — 옛 이름이 남으면 필터 없는 경로가 살아 있게 된다).

```kotlin
    /**
     * 프롬프트에 실을 히스토리. **`createdAt` 기준**이고 `id DESC`라 최근 것부터 온다 —
     * 개수는 `Pageable`로 자른다. 시간순으로 뒤집는 것은 부르는 쪽의 몫이다.
     *
     * **`date`가 아니라 `createdAt`이다.** 「어느 날 밥 얘기인가」(`date`)와 「어느 대화를
     * 기억하는가」(`createdAt`)는 축이 다르다(설계 「세 창이 서로 다르다」).
     *
     * **[type]으로 카드를 걸러 낸다.** 카드가 20턴 창에 섞이면 정작 대화가 밀려나고,
     * 내용도 이미 `[끼니별 상세]`로 매 요청 실려 중복이다.
     */
    fun findByUserAndTypeAndCreatedAtAfterOrderByIdDesc(
        user: User,
        type: ChatMessageType,
        createdAt: LocalDateTime,
        pageable: Pageable,
    ): List<DietChatMessage>
```

- [ ] **Step 5: 스토어가 TEXT를 넘기게 한다**

`DietChatStore.kt`의 히스토리 조회(79-85행)를 바꾼다.

```kotlin
        val history =
            messageRepository
                .findByUserAndTypeAndCreatedAtAfterOrderByIdDesc(
                    user,
                    ChatMessageType.TEXT,
                    LocalDateTime.now().minusDays(DietChatPrompts.HISTORY_DAYS),
                    PageRequest.of(0, DietChatPrompts.HISTORY_TURNS * 2),
                ).reversed()
```

`DietChatPrompts.historyTurns`는 **손대지 않는다.** 들어오는 것이 전부 `TEXT`라 지금 로직이
그대로 맞다. 거기서 또 거르면 같은 규칙을 두 곳이 알게 되어 나중에 한쪽만 고쳐질 자리가 된다.

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `./gradlew :daily-record:test`

Expected: PASS. `DietChatMessageRepositoryQueryTest`도 함께 통과해야 한다 — 그 테스트가
리포지토리의 모든 파생 쿼리 이름을 `PartTree`로 파싱한다. 새 이름이 파싱되지 않으면
**기동 시점에 앱이 안 뜨는데**, 그것을 여기서 잡는다.

- [ ] **Step 7: 기존 행이 살아남는지 실기동으로 확인한다**

`ddl-auto: update`가 not null 컬럼을 기존 행이 있는 테이블에 붙일 수 있는지는 **부팅해야만**
드러난다. `diet_chat_message`에 행이 있는 상태로 띄운다.

Run: `./gradlew :daily-record:bootRun`

Expected: `Started DailyRecordApplicationKt`. 실패하면 `columnDefinition`의 `default 'TEXT'`가
빠졌거나 not null이 먼저 붙은 것이다.

- [ ] **Step 8: 커밋**

```bash
./gradlew :daily-record:spotlessApply
git add -A
git commit -m "$(cat <<'EOF'
feat: 채팅 메시지에 타입을 넣고 히스토리는 TEXT만 싣는다

타임라인에 카드 행이 쌓이기 시작하면 프롬프트의 20턴 창을 카드가 먹어
정작 사용자와 주고받은 대화가 밀려난다. 카드 내용은 이미 `[끼니별 상세]`로
매 요청 실리므로 중복이기도 하다.

카드를 쌓기 전에 이 필터를 먼저 세운다 — 반대 순서면 쌓는 순간 샌다.
EOF
)"
```

---

### Task 2: 끼니 확정·삭제·병합이 카드를 쌓고 지운다

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatCardWriter.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatMessageRepository.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealService.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/DietChatCardWriterTest.kt` (신규)
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/meal/MealConfirmTest.kt`

**Interfaces:**
- Consumes: `ChatMessageType`, `DietChatMessage(user, date, role, content, type, mealId)` (Task 1)
- Produces:
  - `DietChatCardWriter.writeMealCard(user: User, date: LocalDate, mealId: Long)`
  - `DietChatCardWriter.writeDaySummary(user: User, date: LocalDate)` — Task 3이 쓴다
  - `DietChatCardWriter.deleteMealCards(mealId: Long)`
  - `DietChatMessageRepository.existsByUserAndDateAndType(user, date, type): Boolean`
  - `DietChatMessageRepository.deleteByMealId(mealId: Long): Long`

- [ ] **Step 1: 카드를 쓰는 자리의 테스트를 쓴다**

Create `DietChatCardWriterTest.kt`:

```kotlin
package com.toy.backend.diet.chat

import com.toy.backend.common.entity.withId
import com.toy.backend.diet.dietUser
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate

class DietChatCardWriterTest :
    BehaviorSpec({
        val repository = mockk<DietChatMessageRepository>()
        val writer = DietChatCardWriter(repository)

        val user = dietUser()
        val date = LocalDate.of(2026, 8, 1)

        Given("끼니 카드를 쓰면") {
            val saved = slot<DietChatMessage>()
            every { repository.save(capture(saved)) } answers { firstArg<DietChatMessage>().withId(1L) }

            writer.writeMealCard(user, date, mealId = 42L)

            Then("MEAL_CARD 한 행이 그 끼니를 가리킨다") {
                saved.captured.type shouldBe ChatMessageType.MEAL_CARD
                saved.captured.mealId shouldBe 42L
                saved.captured.date shouldBe date
            }

            // 코치가 놓은 것이라 화면에서 왼쪽에 선다.
            Then("역할은 ASSISTANT다") {
                saved.captured.role shouldBe ChatRole.ASSISTANT
            }

            // 내용은 참조로만 채워진다 — 스냅샷을 담으면 끼니를 고칠 때 카드가 낡는다.
            Then("본문은 비어 있다") {
                saved.captured.content shouldBe ""
            }
        }

        // 총평은 끼니를 고칠 때마다 무효화되고 다시 생성되어 이 자리가 여러 번 불린다.
        // 참조 방식이라 기존 행이 새 총평을 가리키므로 또 만들 이유가 없다.
        Given("그날 총평 카드가 이미 있으면") {
            every {
                repository.existsByUserAndDateAndType(user, date, ChatMessageType.DAY_SUMMARY)
            } returns true

            writer.writeDaySummary(user, date)

            Then("또 쌓지 않는다") {
                verify(exactly = 0) { repository.save(any()) }
            }
        }

        Given("그날 총평 카드가 없으면") {
            val saved = slot<DietChatMessage>()
            every {
                repository.existsByUserAndDateAndType(user, date, ChatMessageType.DAY_SUMMARY)
            } returns false
            every { repository.save(capture(saved)) } answers { firstArg<DietChatMessage>().withId(2L) }

            writer.writeDaySummary(user, date)

            Then("DAY_SUMMARY 한 행이 그 날짜에 놓인다") {
                saved.captured.type shouldBe ChatMessageType.DAY_SUMMARY
                saved.captured.date shouldBe date
                saved.captured.mealId shouldBe null
            }
        }

        // 조회에서 거르면 `size + 1`로 다음 장을 판별하는 셈이 틀어진다(설계 함정 3).
        Given("끼니가 사라지면") {
            every { repository.deleteByMealId(42L) } returns 1L

            writer.deleteMealCards(42L)

            Then("그 끼니의 카드도 지운다") {
                verify { repository.deleteByMealId(42L) }
            }
        }
    })
```

- [ ] **Step 2: 컴파일이 깨지는 것을 확인한다**

Run: `./gradlew :daily-record:test --tests "*DietChatCardWriterTest*"`

Expected: 컴파일 실패 — `Unresolved reference: DietChatCardWriter`.

- [ ] **Step 3: 리포지토리에 존재 검사와 카드 삭제를 더한다**

`DietChatMessageRepository.kt`에 더한다. `import java.time.LocalDate`도 함께.

```kotlin
    /**
     * 그날 총평 카드가 이미 있는가. **총평은 끼니를 고칠 때마다 재생성되어** 쌓는 자리가
     * 여러 번 불린다 — 참조 방식이라 기존 행이 새 총평을 가리키므로 또 만들지 않는다.
     */
    fun existsByUserAndDateAndType(
        user: User,
        date: LocalDate,
        type: ChatMessageType,
    ): Boolean

    /**
     * 끼니가 사라질 때 그 카드도 지운다. **조회에서 거르지 않는 이유가 여기 있다** —
     * `page`가 `size + 1`을 받아 다음 장 유무를 판별하는데, 조회한 뒤 몇 건을 빼면 그 셈이
     * 틀린다. 삭제 시점에 지우면 페이징 쿼리는 손댈 곳이 없다.
     */
    fun deleteByMealId(mealId: Long): Long
```

- [ ] **Step 4: 카드를 쓰는 컴포넌트를 만든다**

Create `DietChatCardWriter.kt`:

```kotlin
package com.toy.backend.diet.chat

import com.toy.backend.user.User
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 타임라인에 카드를 놓고 치운다.
 *
 * **`diet.meal`·`diet.feedback`이 이쪽을 부른다.** `diet.chat`은 이미 `diet.meal`을 읽고
 * 있어서(`DietChatStore`) 패키지 참조가 양방향이 된다. 그래도 여기 두는 이유는 **채팅 테이블을
 * 아는 코드를 한 패키지에 모으기 위해서다** — 반대로 두면 끼니·피드백 서비스가 `type`의
 * 기본값이며 `mealId`에 FK가 없다는 것까지 각자 알아야 한다. 빈 사이에는 순환이 없다.
 *
 * **트랜잭션을 스스로 열지 않는다.** 부르는 쪽(끼니 확정·총평 저장)의 트랜잭션에 얹혀야
 * 끼니만 커밋되고 카드는 없는 상태가 생기지 않는다.
 */
@Component
class DietChatCardWriter(
    private val repository: DietChatMessageRepository,
) {
    /**
     * 끼니 확정 시 한 행. **합쳐졌으면 부르지 않는다** — 참조 방식이라 기존 카드가 이미
     * 합쳐진 값을 보여주고, 또 만들면 같은 끼니를 가리키는 카드가 둘이 되어 같은 내용이
     * 두 번 뜬다. 그 판단은 부르는 쪽이 한다(`MealService.confirm`의 `existing`).
     */
    fun writeMealCard(
        user: User,
        date: LocalDate,
        mealId: Long,
    ) {
        repository.save(
            DietChatMessage(
                user = user,
                date = date,
                role = ChatRole.ASSISTANT,
                // 본문은 참조로만 채워진다 — 스냅샷을 담으면 끼니를 고칠 때 카드가 낡는다.
                content = "",
                type = ChatMessageType.MEAL_CARD,
                mealId = mealId,
            ),
        )
    }

    /** 총평이 처음 완성될 때 한 행. 이미 있으면 아무것도 하지 않는다. */
    fun writeDaySummary(
        user: User,
        date: LocalDate,
    ) {
        if (repository.existsByUserAndDateAndType(user, date, ChatMessageType.DAY_SUMMARY)) return
        repository.save(
            DietChatMessage(
                user = user,
                date = date,
                role = ChatRole.ASSISTANT,
                content = "",
                type = ChatMessageType.DAY_SUMMARY,
            ),
        )
    }

    /** 끼니가 사라지면 그 카드도 사라진다. */
    fun deleteMealCards(mealId: Long) {
        repository.deleteByMealId(mealId)
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `./gradlew :daily-record:test --tests "*DietChatCardWriterTest*" --tests "*DietChatMessageRepositoryQueryTest*"`

Expected: PASS. 파생 쿼리 테스트가 `existsByUserAndDateAndType`·`deleteByMealId`도 파싱한다.

- [ ] **Step 6: 끼니 확정이 카드를 쌓는 테스트를 쓴다**

**먼저 목을 하나 더한다.** `MealConfirmTest.kt`(30-52행)와 `MealTypeChangeTest.kt`(49행)의
`MealService(...)` 호출 **맨 끝**(`dailyFeedbackRepository` 뒤)에 인자를 더한다. 두 파일 모두
`val chatCards = mockk<DietChatCardWriter>()` 선언이 필요하다.

```kotlin
        val chatCards = mockk<DietChatCardWriter>()
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
                chatCards,
            )
```

`MealConfirmTest.kt`의 `beforeContainer`(104-110행)에 기본 스텁을 더한다. 이걸 안 넣으면
strict mockk가 기존 확정 테스트를 전부 깨뜨린다.

```kotlin
            justRun { chatCards.writeMealCard(any(), any(), any()) }
            justRun { chatCards.deleteMealCards(any()) }
```

그리고 `Given("끼니 확정")` **아래**에 새 블록 셋을 더한다. 날짜·항목은 그 파일이 이미 쓰는
`LocalDate.of(2026, 7, 29)`와 `userItems`를 그대로 쓴다.

```kotlin
        Given("타임라인 카드") {
            When("새 끼니를 확정하면") {
                val cardMealId = slot<Long>()
                every { repository.save(any()) } answers { (firstArg() as Meal).withId(70L) }
                every { chatCards.writeMealCard(any(), any(), capture(cardMealId)) } just Runs

                val id =
                    service.confirm(
                        "testuser",
                        MealConfirmRequest(
                            date = LocalDate.of(2026, 7, 29),
                            mealType = MealType.LUNCH,
                            analysisId = null,
                            items = userItems,
                        ),
                    )

                Then("타임라인에 그 끼니 카드가 쌓인다") {
                    cardMealId.captured shouldBe id
                }
            }

            // 참조 방식이라 기존 카드가 이미 합쳐진 값을 보여준다. 또 만들면 같은 끼니를
            // 가리키는 카드가 둘이 되어 같은 내용이 두 번 뜬다.
            When("같은 날 같은 끼니가 이미 있어 합쳐지면") {
                val existing =
                    dummyMeal(
                        user = user,
                        date = LocalDate.of(2026, 7, 29),
                        mealType = MealType.LUNCH,
                        id = 71L,
                    )
                every {
                    repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(
                        user,
                        LocalDate.of(2026, 7, 29),
                        MealType.LUNCH,
                    )
                } returns existing

                service.confirm(
                    "testuser",
                    MealConfirmRequest(
                        date = LocalDate.of(2026, 7, 29),
                        mealType = MealType.LUNCH,
                        analysisId = null,
                        items = userItems,
                    ),
                )

                Then("카드를 새로 만들지 않는다") {
                    verify(exactly = 0) { chatCards.writeMealCard(any(), any(), any()) }
                }
            }

            When("끼니를 지우면") {
                val meal = dummyMeal(user = user, date = LocalDate.of(2026, 7, 29), id = 72L)
                every { repository.findByIdOrNull(72L) } returns meal
                justRun { fileService.detachFiles(any()) }
                justRun { repository.delete(meal) }
                justRun { dailyFeedbackRepository.deleteByUserAndDate(user, meal.date) }

                service.delete("testuser", 72L)

                Then("그 끼니의 카드도 지운다") {
                    verify { chatCards.deleteMealCards(72L) }
                }
            }
        }
```

`MealTypeChangeTest.kt`에는 **기존 `Given("그날 대상 종류의 끼니가 이미 있으면")` 블록**
(207행)에 `Then` 하나만 더한다. 그 블록이 이미 원본(81L)을 대상(80L)에 합치는 픽스처를
세워 두었으므로 새 픽스처를 만들지 않는다. 같은 파일의 `beforeContainer`에
`justRun { chatCards.deleteMealCards(any()) }`가 필요하다.

```kotlin
            // 병합은 원본을 지운다 — 그 카드가 남으면 없는 끼니를 가리킨다.
            Then("사라지는 원본의 카드를 지운다") {
                verify { chatCards.deleteMealCards(81L) }
            }
```

- [ ] **Step 7: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "*MealConfirmTest*" --tests "*MealTypeChangeTest*"`

Expected: 컴파일 실패 — `MealService` 생성자에 그런 파라미터가 없다.

- [ ] **Step 8: `MealService`가 카드를 관리하게 한다**

생성자에 더한다(`dailyFeedbackRepository` 뒤).

```kotlin
    private val chatCards: DietChatCardWriter,
```

`confirm`의 `val saved = existing ?: repository.save(meal)` **바로 아래**에 더한다.

```kotlin
        // **합쳤으면 카드를 만들지 않는다.** 참조 방식이라 기존 카드가 이미 합쳐진 값을
        // 보여주고, 또 만들면 같은 끼니를 가리키는 카드가 둘이 되어 같은 내용이 두 번 뜬다.
        // 대신 「간식을 추가했는데 타임라인에 새로 안 뜬다」가 되는데, 그게 이 설계의 값이다.
        if (existing == null) chatCards.writeMealCard(user, meal.date, saved.requiredId)
```

`delete`의 `repository.delete(meal)` **앞**에 더한다.

```kotlin
        chatCards.deleteMealCards(id)
```

`mergeInto`의 `repository.delete(source)` **앞**에 더한다.

```kotlin
        // 원본이 사라지므로 그 카드도 지운다 — 남기면 없는 끼니를 가리킨다. 대상의 카드는
        // 그대로 두면 된다(참조라 합쳐진 값을 보여준다).
        chatCards.deleteMealCards(source.requiredId)
```

- [ ] **Step 9: 전체 테스트를 돌린다**

Run: `./gradlew :daily-record:test`

Expected: PASS.

- [ ] **Step 10: 커밋**

```bash
./gradlew :daily-record:spotlessApply
git add -A
git commit -m "$(cat <<'EOF'
feat: 끼니 확정이 타임라인에 카드를 쌓는다

카드는 참조(`mealId`)만 저장한다. 스냅샷으로 담으면 항목 교체·그램수
수정·타입 변경 때마다 카드가 낡아, 같은 화면에서 카드는 696kcal라 하고
상세는 540kcal라 하는 상태가 된다.

합쳐질 때는 만들지 않는다 — 참조라 기존 카드가 이미 합쳐진 값을 보여주고,
또 만들면 같은 끼니를 가리키는 카드가 둘이 된다.

끼니가 사라지는 두 자리(삭제·타입 변경 병합)에서 카드를 함께 지운다.
조회에서 거르면 `size + 1`로 다음 장을 판별하는 셈이 틀어진다.
EOF
)"
```

---

### Task 3: 총평이 완성되면 카드를 쌓는다

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/feedback/DayFeedbackStore.kt:73-88`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/feedback/DietFeedbackGeneratorTest.kt`

**Interfaces:**
- Consumes: `DietChatCardWriter.writeDaySummary(user, date)` (Task 2)

- [ ] **Step 1: 테스트를 쓴다**

`DietFeedbackGeneratorTest.kt` 43행의 `DayFeedbackStore(...)` 생성에 목을 하나 더한다.

```kotlin
        val chatCards = mockk<DietChatCardWriter>()
        val dayStore = DayFeedbackStore(userRepository, mealRepository, activityRepository, feedbackRepository, chatCards)
```

**`publish`를 직접 부른다.** 이 자리의 계약은 「문장이 실렸는가」와 「카드를 만드는가」의
관계뿐이라, 생성기 전체를 태우면 무엇이 무엇을 걸렀는지 흐려진다. 파일 끝에 더한다.

```kotlin
        Given("총평을 실을 때") {
            val summaryDate = LocalDate.of(2026, 8, 1)
            val markerAt = LocalDateTime.of(2026, 8, 1, 22, 0)

            When("마커가 내 것과 같으면") {
                val cached =
                    DailyDietFeedback(
                        user = user,
                        date = summaryDate,
                        dayScore = 0,
                        feedback = null,
                        generatedAt = markerAt,
                    ).withId(20L)
                every { userRepository.findByIdOrNull(user.requiredId) } returns user
                every { feedbackRepository.findByUserAndDate(user, summaryDate) } returns cached
                justRun { chatCards.writeDaySummary(any(), any()) }

                dayStore.publish(user.requiredId, summaryDate, markerAt, dayScore = 61, feedback = "총평")

                Then("문장이 실리고 그 날짜에 총평 카드가 놓인다") {
                    cached.feedback shouldBe "총평"
                    verify { chatCards.writeDaySummary(user, summaryDate) }
                }
            }

            // 마커가 낡았다는 것은 그 사이 끼니가 바뀌어 이 문장이 이미 낡았다는 뜻이다.
            // 문장을 버리면서 카드만 만들면 있지도 않은 총평을 가리킨다.
            When("그 사이 새 마커가 찍혔으면") {
                val newer =
                    DailyDietFeedback(
                        user = user,
                        date = summaryDate,
                        dayScore = 0,
                        feedback = null,
                        generatedAt = markerAt.plusMinutes(3),
                    ).withId(21L)
                every { userRepository.findByIdOrNull(user.requiredId) } returns user
                every { feedbackRepository.findByUserAndDate(user, summaryDate) } returns newer

                dayStore.publish(user.requiredId, summaryDate, markerAt, dayScore = 61, feedback = "총평")

                Then("문장을 버리고 카드도 만들지 않는다") {
                    newer.feedback shouldBe null
                    verify(exactly = 0) { chatCards.writeDaySummary(any(), any()) }
                }
            }
        }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "*DietFeedbackGeneratorTest*"`

Expected: 컴파일 실패 — `DayFeedbackStore` 생성자에 그런 파라미터가 없다.

- [ ] **Step 3: `DayFeedbackStore`가 카드를 쌓게 한다**

생성자에 `private val chatCards: DietChatCardWriter,`를 더하고, `publish`의
`cached.publish(dayScore, feedback)` **바로 아래**에 더한다.

```kotlin
        // **문장이 실제로 실린 뒤에만 쌓는다.** 위의 두 `return`(마커 없음·마커 낡음)은 이
        // 문장을 버리는 자리라, 거기서 카드를 만들면 있지도 않은 총평을 가리킨다.
        //
        // 총평은 끼니를 고칠 때마다 재생성되어 여기가 여러 번 불리는데, `writeDaySummary`가
        // 이미 있는 날짜를 걸러 낸다. 그래서 **카드는 그 날짜의 총평이 처음 완성된 시각에
        // 앉고** 재생성으로 자리가 움직이지 않는다 — 타임라인에서 아래로 튀어 오르지 않는
        // 편이 읽기 쉽다.
        chatCards.writeDaySummary(user, date)
```

- [ ] **Step 4: 전체 테스트를 돌린다**

Run: `./gradlew :daily-record:test`

Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
./gradlew :daily-record:spotlessApply
git add -A
git commit -m "$(cat <<'EOF'
feat: 총평이 완성되면 타임라인에 카드를 쌓는다

문장이 실제로 실린 뒤에만 쌓는다 — 마커가 없거나 낡아 문장을 버리는
자리에서 카드를 만들면 있지도 않은 총평을 가리킨다.

총평은 끼니를 고칠 때마다 재생성되어 이 자리가 여러 번 불리므로, 이미
있는 날짜는 거른다. 카드는 그 날짜의 총평이 처음 완성된 시각에 앉고
재생성으로 자리가 움직이지 않는다.
EOF
)"
```

---

### Task 4: 응답을 타입별로 나눈다

카드 자리는 아직 비운다(`null`). **파괴적 변경만 먼저 떼어 내** 앱과의 계약을 확정한다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatDtos.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatStore.kt:172`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/DietChatStoreTest.kt`

**Interfaces:**
- Produces:
  - `DietChatMessageResponse(id, type, date, role, createdAt, content, meal = null, day = null)`
  - `ChatMealCard(mealId, mealType, score, scoreBasis, totalKcal, carbsG, proteinG, fatG, photoUrl, feedback)`
  - `ChatDayCard(dayScore, totalKcal, targetKcal, feedback)`

- [ ] **Step 1: 테스트를 쓴다**

`DietChatStoreTest.kt`의 `Given("대화를 페이징으로 읽으면")`에 더한다.

```kotlin
            Then("TEXT 메시지는 타입이 실리고 카드 자리는 비어 있다") {
                page.messages.first().type shouldBe ChatMessageType.TEXT
                page.messages.first().content shouldBe "답2"
                page.messages.first().meal shouldBe null
                page.messages.first().day shouldBe null
            }
```

`Given("질문과 답을 저장하면")`에도 더한다.

```kotlin
            Then("저장된 답은 TEXT다") {
                response.type shouldBe ChatMessageType.TEXT
            }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "*DietChatStoreTest*"`

Expected: 컴파일 실패 — `DietChatMessageResponse`에 `type`이 없다.

- [ ] **Step 3: DTO를 타입별로 나눈다**

`DietChatDtos.kt`의 `DietChatMessageResponse`를 바꾸고 카드 둘을 더한다.
`import com.toy.backend.diet.meal.MealType`·`import com.toy.backend.diet.score.MealScoreBasis`가
필요하다.

```kotlin
data class DietChatMessageResponse(
    val id: Long,
    /** 무엇이 놓인 자리인가. 앱이 이 값으로 렌더링을 가른다. */
    val type: ChatMessageType,
    /**
     * **어느 날에 대한 것인가.** `createdAt`(언제 있었나)과 다르다 — 8월 6일에 8월 1일을
     * 물을 수 있고, 8월 1일 끼니를 8월 3일에 뒤늦게 확정할 수도 있다. 스트림이 시각 순이라
     * 그것들이 다른 날 사이에 앉으므로, 앱이 「8/1에 대해」를 붙이려면 이 값이 필요하다.
     */
    val date: LocalDate,
    val role: ChatRole,
    val createdAt: LocalDateTime,
    /**
     * `TEXT`일 때만 있다. **카드 행은 null이다** — DB에는 빈 문자열로 저장되지만 그대로
     * 내보내면 앱이 빈 말풍선을 그릴 여지가 생긴다. 「없다」와 「비어 있다」를 같은 값으로
     * 만들지 않는다.
     */
    val content: String?,
    /** `MEAL_CARD`일 때만. */
    val meal: ChatMealCard? = null,
    /** `DAY_SUMMARY`일 때만. */
    val day: ChatDayCard? = null,
)

/**
 * 끼니 카드. **저장된 값이 아니라 조회 시점의 끼니에서 읽은 값이다** — 스냅샷으로 담으면
 * 끼니를 고칠 때마다 낡는다.
 */
data class ChatMealCard(
    val mealId: Long,
    val mealType: MealType,
    /** 저장된 `Meal.score` 컬럼이 아니라 재계산 값이다 — 화면·프롬프트와 같아야 한다. */
    val score: Int?,
    /**
     * [score]와 **같은 계산에서 나온다.** 앱이 탄단지 구성비 막대를 그리는데, 비율의 분모가
     * `totalKcal`이 아니라 매크로에서 역산한 값이라(`DietScoreCalculator` 주석) 앱이 g에서
     * 직접 나누면 다른 숫자가 나온다. `MealResponse.scoreBasis`와 같은 타입이다.
     */
    val scoreBasis: MealScoreBasis?,
    val totalKcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    /** 사진 한 장. 없으면 null. presigned URL이라 매 조회 새로 발급된다. */
    val photoUrl: String?,
    /** 생성 중이거나 실패했으면 null — 앱이 그 자리를 로딩으로 채운다. */
    val feedback: String?,
)

/** 총평 카드. 끼니 카드와 같은 이유로 조회 시점 값이다. */
data class ChatDayCard(
    val dayScore: Int,
    val totalKcal: Double,
    /**
     * 그날 **첫 끼니의 스냅샷**(`Meal.targetKcal`)이다. 프로필의 현재 목표를 읽으면 몸무게를
     * 바꿨을 때 과거 카드의 분모가 함께 흔들린다.
     */
    val targetKcal: Int,
    /** 재생성 중이면 null — 앱이 「마감 피드백을 만들고 있어요」를 띄운다. */
    val feedback: String?,
)
```

- [ ] **Step 4: 스토어의 변환을 고친다**

`DietChatStore.kt`의 마지막 줄(172행)을 바꾼다.

```kotlin
    /** 카드 자리는 `page`가 채운다 — `append`가 돌려주는 것은 늘 `TEXT`다. */
    private fun DietChatMessage.toResponse() =
        DietChatMessageResponse(
            id = requiredId,
            type = type,
            date = date,
            role = role,
            createdAt = createdAt,
            content = if (type == ChatMessageType.TEXT) content else null,
        )
```

- [ ] **Step 5: 테스트를 돌린다**

Run: `./gradlew :daily-record:test`

Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
./gradlew :daily-record:spotlessApply
git add -A
git commit -m "$(cat <<'EOF'
feat: 채팅 응답을 메시지 타입별로 나눈다

앱이 말풍선과 카드를 가려 그려야 하므로 `type`을 싣고, 카드 자리를
`meal`·`day`로 연다. 카드 행의 `content`는 null로 내린다 — DB에는 빈
문자열이지만 그대로 내보내면 앱이 빈 말풍선을 그릴 여지가 생긴다.

`content`가 nullable이 되는 파괴적 변경이라 채우는 일과 떼어 먼저 낸다.
EOF
)"
```

---

### Task 5: 조회가 끼니 카드를 채운다

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatStore.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/DietChatStoreTest.kt`

**Interfaces:**
- Consumes: `ChatMealCard` (Task 4), `FileService.getPresignedUrls(ids): Map<Long, String>`
- Produces: `DietChatStore(userRepository, mealRepository, activityRepository, feedbackRepository, messageRepository, fileService)` — 생성자에 `FileService`가 는다

- [ ] **Step 1: 테스트를 쓴다**

`DietChatStoreTest.kt`에 더한다. 파일 위쪽의 `store` 생성에 `fileService` 목을 더해야 한다.

```kotlin
        // 스냅샷으로 담았다면 확정 시점 값이 나온다. 참조라야 지금 값이 나온다.
        Given("끼니 카드를 읽으면") {
            val meal = mealOn(date, MealType.LUNCH, "제육볶음", 42L)
            meal.applyScore(11) // 저장된 컬럼은 낡았다 — 재계산 값이 나와야 한다
            meal.markFeedback("파닭에 코우슬로를 곁들여서…")
            every {
                messageRepository.findByUserAndIdLessThanOrderByIdDesc(eq(user), any(), any())
            } returns
                listOf(
                    DietChatMessage(user, date, ChatRole.ASSISTANT, "", ChatMessageType.MEAL_CARD, 42L)
                        .withId(9L),
                )
            every { mealRepository.findAllById(listOf(42L)) } returns listOf(meal)
            every { fileService.getPresignedUrls(any()) } returns emptyMap()

            val page = store.page("testuser", null, 10)
            val card = page.messages.single().meal!!

            Then("지금 끼니의 값이 실린다") {
                card.mealId shouldBe 42L
                card.mealType shouldBe MealType.LUNCH
                card.totalKcal shouldBe meal.totalKcal
            }

            // 저장된 컬럼을 읽으면 감점 기울기를 튜닝했을 때 화면·프롬프트와 어긋난다.
            Then("점수와 근거가 같은 재계산에서 나온다") {
                val scored = DietScoreCalculator.scoreMeal(meal.carbsG, meal.proteinG, meal.fatG)
                card.score shouldBe scored.score
                card.score shouldNotBe 11
                card.scoreBasis shouldBe scored.basis
            }

            Then("끼니 피드백이 그대로 실린다") {
                card.feedback shouldBe "파닭에 코우슬로를 곁들여서…"
            }

            Then("사진이 없으면 photoUrl이 null이다") {
                card.photoUrl shouldBe null
            }

            Then("본문은 null이다 — 빈 말풍선이 그려지면 안 된다") {
                page.messages.single().content shouldBe null
            }
        }

        // 한 장이 100건까지 오므로 카드마다 조회하면 그대로 N+1이다.
        Given("한 장에 끼니 카드가 셋이면") {
            every {
                messageRepository.findByUserAndIdLessThanOrderByIdDesc(eq(user), any(), any())
            } returns
                (1L..3L).map {
                    DietChatMessage(user, date, ChatRole.ASSISTANT, "", ChatMessageType.MEAL_CARD, it)
                        .withId(it + 10)
                }
            every { mealRepository.findAllById(any<Iterable<Long>>()) } returns
                (1L..3L).map { mealOn(date, MealType.LUNCH, "밥", it) }
            every { fileService.getPresignedUrls(any()) } returns emptyMap()

            store.page("testuser", null, 10)

            Then("끼니 조회는 한 번이다") {
                verify(exactly = 1) { mealRepository.findAllById(any<Iterable<Long>>()) }
            }
        }

        // 삭제 경로가 새면 생긴다. 빈 카드를 내리는 것보다 빼는 편이 낫다.
        Given("카드가 가리키는 끼니가 없으면") {
            every {
                messageRepository.findByUserAndIdLessThanOrderByIdDesc(eq(user), any(), any())
            } returns
                listOf(
                    DietChatMessage(user, date, ChatRole.ASSISTANT, "", ChatMessageType.MEAL_CARD, 99L)
                        .withId(9L),
                    DietChatMessage(user, date, ChatRole.USER, "질문").withId(8L),
                )
            every { mealRepository.findAllById(listOf(99L)) } returns emptyList()
            every { fileService.getPresignedUrls(any()) } returns emptyMap()

            val page = store.page("testuser", null, 10)

            Then("그 행만 빠지고 나머지는 남는다") {
                page.messages.map { it.id } shouldBe listOf(8L)
            }
        }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "*DietChatStoreTest*"`

Expected: 컴파일 실패 후 고치면 `card.meal`이 null이라 실패.

- [ ] **Step 3: 스토어가 끼니 카드를 채우게 한다**

생성자에 `private val fileService: FileService,`를 더하고(`import com.toy.backend.file.FileService`),
`page`를 바꾼다.

```kotlin
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
        val mealCards = mealCardsOf(page)
        return DietChatPageResponse(
            // **매달린 참조는 행째로 뺀다.** 삭제 경로가 제대로 돌면 생기지 않지만, 생겼을 때
            // 빈 카드를 내리는 것보다 낫다. `nextCursor`는 원래 행에서 내므로 흔들리지 않는다.
            messages = page.mapNotNull { it.toResponse(mealCards) },
            nextCursor = if (rows.size > size) page.last().requiredId else null,
        )
    }

    /**
     * 카드가 가리키는 끼니를 **`IN` 한 번**으로 읽어 `mealId`별 카드로 만든다. 한 장이 100건까지
     * 오므로 카드마다 조회하면 그대로 N+1이다. presigned URL도 한 번에 받는다.
     */
    private fun mealCardsOf(page: List<DietChatMessage>): Map<Long, ChatMealCard> {
        val ids = page.filter { it.type == ChatMessageType.MEAL_CARD }.mapNotNull { it.mealId }
        if (ids.isEmpty()) return emptyMap()
        val meals = mealRepository.findAllById(ids)
        val urls = fileService.getPresignedUrls(meals.mapNotNull { it.photos.firstOrNull()?.fileId })
        return meals.associate { it.requiredId to it.toChatCard(urls) }
    }

    /** `@OrderBy("sortOrder asc")`라 `photos.first()`가 첫 장이다. */
    private fun Meal.toChatCard(urls: Map<Long, String>): ChatMealCard {
        // 점수와 근거를 한 번에 받는다 — 따로 구하면 둘이 어긋난다(`MealDtos.toResponse`와 같다).
        val scored = DietScoreCalculator.scoreMeal(carbsG, proteinG, fatG)
        return ChatMealCard(
            mealId = requiredId,
            mealType = mealType,
            score = scored.score,
            scoreBasis = scored.basis,
            totalKcal = totalKcal,
            carbsG = carbsG,
            proteinG = proteinG,
            fatG = fatG,
            photoUrl = photos.firstOrNull()?.let { urls[it.fileId] },
            feedback = feedback,
        )
    }
```

`toResponse`를 카드 지도를 받도록 바꾼다. **매달린 참조면 null을 돌려주고 로그를 남긴다** —
삭제 경로(`MealService.delete`·`mergeInto`)가 새고 있다는 신호라, 조용히 넘기면 아무도 모른다.
파일 맨 위에 로거를 둔다.

```kotlin
private val log = KotlinLogging.logger {}
```

```kotlin
    private fun DietChatMessage.toResponse(mealCards: Map<Long, ChatMealCard> = emptyMap()): DietChatMessageResponse? {
        val meal = if (type == ChatMessageType.MEAL_CARD) mealCards[mealId] else null
        if (type == ChatMessageType.MEAL_CARD && meal == null) {
            log.warn { "카드가 가리키는 끼니가 없어 건너뛴다 — 삭제 경로가 새고 있다: messageId=$requiredId, mealId=$mealId" }
            return null
        }
        return DietChatMessageResponse(
            id = requiredId,
            type = type,
            date = date,
            role = role,
            createdAt = createdAt,
            content = if (type == ChatMessageType.TEXT) content else null,
            meal = meal,
        )
    }
```

`append`의 마지막 줄이 `toResponse()`를 부르는데 이제 nullable이다. `TEXT`라 절대 null이
아니므로 그 사실을 코드로 적는다.

```kotlin
        // 방금 저장한 TEXT 행이라 매달린 참조가 있을 수 없다.
        return messageRepository.save(DietChatMessage(user, date, ChatRole.ASSISTANT, answer)).toResponse()!!
```

- [ ] **Step 4: 테스트를 돌린다**

Run: `./gradlew :daily-record:test`

Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
./gradlew :daily-record:spotlessApply
git add -A
git commit -m "$(cat <<'EOF'
feat: 조회가 끼니 카드를 현재 값으로 채운다

저장된 것은 `mealId`뿐이라 조회 시점의 끼니에서 점수·근거·열량·매크로·
사진·피드백을 읽는다. 끼니를 고쳐도 카드가 낡지 않고, 확정 시점에 아직
없던 피드백도 저절로 채워진다.

끼니를 `IN` 한 번으로 읽는다 — 한 장이 100건까지 오므로 카드마다
조회하면 그대로 N+1이다.

매달린 참조는 행째로 뺀다. `nextCursor`는 원래 행에서 내므로 흔들리지 않는다.
EOF
)"
```

---

### Task 6: 조회가 총평 카드를 채운다

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/chat/DietChatStore.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/diet/meal/MealRepository.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/diet/chat/DietChatStoreTest.kt`

**Interfaces:**
- Consumes: `ChatDayCard` (Task 4)
- Produces: `MealRepository.findByUserAndDateIn(user, dates): List<Meal>`

- [ ] **Step 1: 테스트를 쓴다**

```kotlin
        Given("총평 카드를 읽으면") {
            val meal = mealOn(date, MealType.LUNCH, "제육볶음", 7L)
            every {
                messageRepository.findByUserAndIdLessThanOrderByIdDesc(eq(user), any(), any())
            } returns
                listOf(
                    DietChatMessage(user, date, ChatRole.ASSISTANT, "", ChatMessageType.DAY_SUMMARY)
                        .withId(9L),
                )
            every { feedbackRepository.findByUserAndDateIn(user, listOf(date)) } returns
                listOf(
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 61,
                        feedback = "나트륨이 기준을 넘었어요",
                        generatedAt = LocalDateTime.now(),
                    ),
                )
            every { mealRepository.findByUserAndDateIn(user, listOf(date)) } returns listOf(meal)

            val card = store.page("testuser", null, 10).messages.single().day!!

            Then("지금 총평과 하루 점수가 실린다") {
                card.dayScore shouldBe 61
                card.feedback shouldBe "나트륨이 기준을 넘었어요"
            }

            // 프로필의 현재 목표를 읽으면 몸무게를 바꿨을 때 과거 카드의 분모가 흔들린다.
            Then("목표는 그날 첫 끼니의 스냅샷이다") {
                card.targetKcal shouldBe meal.targetKcal
                card.totalKcal shouldBe meal.totalKcal
            }
        }

        // 끼니를 전부 지우면 총평 행도 함께 지워진다(`MealService.delete`).
        Given("총평 카드가 가리키는 총평이 없으면") {
            every {
                messageRepository.findByUserAndIdLessThanOrderByIdDesc(eq(user), any(), any())
            } returns
                listOf(
                    DietChatMessage(user, date, ChatRole.ASSISTANT, "", ChatMessageType.DAY_SUMMARY)
                        .withId(9L),
                    DietChatMessage(user, date, ChatRole.USER, "질문").withId(8L),
                )
            every { feedbackRepository.findByUserAndDateIn(user, listOf(date)) } returns emptyList()
            every { mealRepository.findByUserAndDateIn(user, listOf(date)) } returns emptyList()

            val page = store.page("testuser", null, 10)

            Then("그 행만 빠진다") {
                page.messages.map { it.id } shouldBe listOf(8L)
            }
        }

        // 재생성 중이면 문장이 잠깐 null이다. 카드를 없애면 타임라인에서 뭔가가 사라졌다
        // 다시 나타난다 — 자리를 지키고 앱이 「만들고 있어요」를 띄운다.
        Given("총평이 재생성 중이면") {
            every {
                messageRepository.findByUserAndIdLessThanOrderByIdDesc(eq(user), any(), any())
            } returns
                listOf(
                    DietChatMessage(user, date, ChatRole.ASSISTANT, "", ChatMessageType.DAY_SUMMARY)
                        .withId(9L),
                )
            every { feedbackRepository.findByUserAndDateIn(user, listOf(date)) } returns
                listOf(
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 61,
                        feedback = null,
                        generatedAt = LocalDateTime.now(),
                    ),
                )
            every { mealRepository.findByUserAndDateIn(user, listOf(date)) } returns
                listOf(mealOn(date, MealType.LUNCH, "제육볶음", 7L))

            val card = store.page("testuser", null, 10).messages.single().day!!

            Then("카드는 남고 문장만 null이다") {
                card.feedback shouldBe null
                card.dayScore shouldBe 61
            }
        }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "*DietChatStoreTest*"`

Expected: 컴파일 실패 — `findByUserAndDateIn`이 두 리포지토리에 없다.

- [ ] **Step 3: 리포지토리에 날짜 목록 조회를 더한다**

`MealRepository.kt`:

```kotlin
    /**
     * 여러 날짜의 끼니를 한 번에. 채팅 한 장에 총평 카드가 여러 날짜만큼 들어 있어,
     * 날짜마다 조회하면 N+1이 된다.
     */
    fun findByUserAndDateIn(
        user: User,
        dates: Collection<LocalDate>,
    ): List<Meal>
```

`DailyDietFeedbackRepository.kt`:

```kotlin
    /** 채팅 한 장에 실린 총평 카드들을 한 번에 읽는다. */
    fun findByUserAndDateIn(
        user: User,
        dates: Collection<LocalDate>,
    ): List<DailyDietFeedback>
```

- [ ] **Step 4: 스토어가 총평 카드를 채우게 한다**

`page`가 `dayCardsOf`도 부르게 하고 `toResponse`에 넘긴다.

```kotlin
        val page = rows.take(size)
        val user = findUser(username)
        val mealCards = mealCardsOf(page)
        val dayCards = dayCardsOf(user, page)
        return DietChatPageResponse(
            messages = page.mapNotNull { it.toResponse(mealCards, dayCards) },
            nextCursor = if (rows.size > size) page.last().requiredId else null,
        )
```

`findUser`가 두 번 불리지 않도록 `rows` 조회 위로 올린다.

```kotlin
    private fun dayCardsOf(
        user: User,
        page: List<DietChatMessage>,
    ): Map<LocalDate, ChatDayCard> {
        val dates = page.filter { it.type == ChatMessageType.DAY_SUMMARY }.map { it.date }.distinct()
        if (dates.isEmpty()) return emptyMap()
        // 그날 끼니는 총평의 열량·목표를 내는 데 필요하다. 목표는 **첫 끼니의 스냅샷**이라
        // 프로필을 읽지 않는다 — 읽으면 몸무게를 바꿨을 때 과거 카드의 분모가 흔들린다.
        val mealsByDate = mealRepository.findByUserAndDateIn(user, dates).groupBy { it.date }
        return feedbackRepository
            .findByUserAndDateIn(user, dates)
            .mapNotNull { cached ->
                val meals = mealsByDate[cached.date]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val totals = meals.totals()
                cached.date to
                    ChatDayCard(
                        dayScore = cached.dayScore,
                        totalKcal = totals.kcal,
                        targetKcal = meals.first().targetKcal,
                        // 재생성 중이면 null이다 — 카드는 남고 앱이 「만들고 있어요」를 띄운다.
                        feedback = cached.feedback,
                    )
            }.toMap()
    }
```

`toResponse`를 확장한다.

```kotlin
    private fun DietChatMessage.toResponse(
        mealCards: Map<Long, ChatMealCard> = emptyMap(),
        dayCards: Map<LocalDate, ChatDayCard> = emptyMap(),
    ): DietChatMessageResponse? {
        val meal = if (type == ChatMessageType.MEAL_CARD) mealCards[mealId] else null
        if (type == ChatMessageType.MEAL_CARD && meal == null) {
            log.warn { "카드가 가리키는 끼니가 없어 건너뛴다 — 삭제 경로가 새고 있다: messageId=$requiredId, mealId=$mealId" }
            return null
        }
        val day = if (type == ChatMessageType.DAY_SUMMARY) dayCards[date] else null
        // 그날 끼니를 전부 지우면 총평 행도 함께 지워진다(`MealService.delete`) — 정상 경로다.
        // 끼니 카드와 달리 로그를 남기지 않는다.
        if (type == ChatMessageType.DAY_SUMMARY && day == null) return null
        return DietChatMessageResponse(
            id = requiredId,
            type = type,
            date = date,
            role = role,
            createdAt = createdAt,
            content = if (type == ChatMessageType.TEXT) content else null,
            meal = meal,
            day = day,
        )
    }
```

- [ ] **Step 5: 전체 테스트를 돌린다**

Run: `./gradlew :daily-record:test`

Expected: PASS.

- [ ] **Step 6: 실기동으로 파생 쿼리를 확인한다**

새 파생 쿼리 셋(`existsByUserAndDateAndType`·`deleteByMealId`·`findByUserAndDateIn` ×2)이
실제로 파싱되는지는 부팅해야 확실하다.

Run: `./gradlew :daily-record:bootRun`

Expected: `Started DailyRecordApplicationKt`, 빈 생성 예외 0건.

- [ ] **Step 7: 커밋**

```bash
./gradlew :daily-record:spotlessApply
git add -A
git commit -m "$(cat <<'EOF'
feat: 조회가 총평 카드를 현재 값으로 채운다

저장된 것은 날짜뿐이라 조회 시점의 총평·하루 점수를 읽는다. 재생성 중이면
문장만 null로 내리고 카드는 자리를 지킨다 — 없애면 타임라인에서 뭔가가
사라졌다 다시 나타난다.

목표 열량은 그날 첫 끼니의 스냅샷을 쓴다. 프로필의 현재 목표를 읽으면
몸무게를 바꿨을 때 과거 카드의 분모가 함께 흔들린다.

총평과 끼니를 날짜 목록으로 한 번씩만 읽는다.
EOF
)"
```

---

## 수동 확인 (구현 뒤)

자동 테스트가 못 닿는 자리다. `OPENROUTER_API_KEY`와 실제 DB·S3가 필요하다.

- [ ] 끼니를 확정하고 `GET /diet/chat`에 **카드가 뜨는지**. 확정 직후에는 `feedback`이 null이고,
      잠시 뒤 다시 부르면 채워져 있어야 한다(참조 방식이 실제로 도는지 보는 자리)
- [ ] 그 끼니의 항목을 고치고 다시 부르면 **카드의 열량·점수가 바뀌어 있는지**
- [ ] 같은 날 같은 끼니를 다시 확정해 합쳐지면 **카드가 늘지 않는지**
- [ ] 끼니를 지우면 **그 카드가 사라지는지**
- [ ] 하루 화면을 열어 총평이 생성된 뒤 `GET /diet/chat`에 **총평 카드가 뜨는지**
- [ ] 끼니를 고쳐 총평이 무효화된 동안 **카드는 남고 `feedback`만 null인지**
- [ ] 질문을 하나 던지고, 그 답이 **카드를 프롬프트에 싣지 않는지**(서버 로그의 프롬프트 확인)
- [ ] 배포 환경에서 `DROP INDEX idx_diet_chat_user_date;` — 이 계획과 무관한 선행 부채이지만
      같은 테이블이라 함께 처리한다

## 앱 작업

이 계획이 끝나고 배포된 뒤 `woori-haru`에서 따로 한다 —
`docs/superpowers/specs/2026-08-07-diet-chat-screen-design.md`.
