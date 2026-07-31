# 하루 평가에 대해 되묻는 채팅 (백엔드) 설계

작성일: 2026-07-31
짝 저장소: `woori-haru` (iOS) — **채팅 화면이 필요하다.** 이 문서 범위 밖이다.

## 배경

하루 마감 피드백은 2~3문장짜리 단방향 통보다. 「나트륨이 많았다」까지는 알려 주지만
「그럼 뭘 줄여야 하지?」·「어제보단 나은 거야?」는 물어볼 데가 없다.

평가에 대해 되물을 수 있게 한다. **하루 카드에서만** 연다.

**끼니에는 붙이지 않는다.** 하루 데이터에 이미 전체 끼니 목록·목표·주의 영양소가 다 들어 있어
「점심에 뭐 먹었지?」까지 커버되고, 만드는 자리도 하나로 끝난다. 끼니마다 붙이면 엔드포인트·
엔티티·iOS 화면이 두 벌이 되고, 이 저장소에서 반복된 「짝 중 한쪽만 고침」 위험이 늘어난다.

## 범위

| | 범위 |
| --- | --- |
| **한다** | `(user, date)` 하나당 대화 하나 · 저장 + TTL 정리 · 동기 요청/응답 · 턴 상한 |
| **안 한다** | 끼니 단위 채팅 · 스트리밍(SSE) · 대화 삭제 엔드포인트 · 대화 검색 · 여러 날 비교 |

---

## ⚠️ 먼저 읽을 함정 넷

### 함정 1 — LLM 호출을 트랜잭션 안에서 하면 안 된다

`MealFeedbackStore`가 존재하는 이유가 이것이다. 트랜잭션 안에서 부르면 엔티티가 호출 내내
영속성 컨텍스트에 남고, 그 사이 항목이 수정되면 커밋 때 dirty check가 합계 컬럼을 옛 값으로
되돌린다. 채팅은 호출이 더 길고(대화가 쌓일수록) 사용자가 그 시간 동안 화면을 보고 있으므로
같은 창이 더 넓다.

**→ 같은 모양으로 쪼갠다.** `DietChatStore`에 짧은 트랜잭션 두 개를 두고, LLM 호출은 그 사이
**트랜잭션 밖**에서 한다.

```
1. @Transactional(readOnly = true)  loadContext  — 끼니·피드백·히스토리 로드, 상한 검사
2. (트랜잭션 없음)                   client.chat  — LLM 호출
3. @Transactional                    append       — 질문·답 두 행 저장
```

엔티티를 경계 너머로 넘기지 않는다 — `@Async`가 id를 받아 다시 조회하는 것과 같은 이유로,
각 트랜잭션 메서드가 `username`으로 사용자를 다시 조회한다.

### 함정 2 — 데이터 블록을 히스토리에 저장하면 낡은 숫자가 굳는다

대화 도중 끼니를 고치면 앞서 나눈 대화가 낡은 수치를 가리킨다. 하루 피드백은
`generatedAt < 최종 updatedAt`으로 무효화하지만 **대화는 무효화할 수 없다** — 이미 한 말을
취소할 수 없다.

**→ 데이터 블록은 저장하지 않고 매 요청마다 현재 DB에서 새로 만든다.** 저장되는 것은
주고받은 말뿐이다. 그러면 지난 대화가 옛 숫자를 언급하더라도 **다음 답변은 항상 최신
수치를 근거로 한다.**

```
[system]     DietChatPrompts.SYSTEM_PROMPT
[user]       DietFeedbackPrompts.day(...)      ← 매번 새로 생성, 저장 안 함
[assistant]  저장된 하루 피드백                  ← 대화의 출발점 (없으면 이 줄을 뺀다)
[user]       저장된 질문 1
[assistant]  저장된 답 1
   ...
[user]       이번 질문
```

**`DietFeedbackPrompts.day()`를 그대로 재사용한다.** 그 안의 「목표」·「주의 영양소 기준값」·
「하루 점수」는 전부 *모델이 지어내는 것을 막으려고* 하나씩 추가된 것들이다(파일 주석 참고 —
「기준을 함께 실어야 LLM이 나트륨 2,610mg만 보고 많은지 적은지 스스로 판단하지 않는다」).
새로 쓰면 그 방어를 처음부터 다시 겪는다.

### 함정 3 — LLM 호출이 실패하면 **아무것도 저장하지 않는다**

질문만 저장하고 답을 못 받으면 히스토리가 `user, user, assistant...`로 어긋나 다음 턴의
프롬프트가 깨진다. `generateText`는 실패를 전부 null로 돌려주므로(자동 재시도 없음) 이 경로는
흔하다.

**→ `chat`이 null이면 두 행 다 저장하지 않고 오류를 던진다.** 사용자는 다시 물으면 된다.

### 함정 4 — `LLM_UNAVAILABLE` 가드는 `POST`에만 넣는다

키가 없으면 `POST`는 `LLM_UNAVAILABLE`로 거절한다. **`GET`은 막지 않는다** — 저장된 대화를
보여주는 데는 LLM이 필요 없다.

이 세션에서 이 가드를 네 번 넣었다(`MealAnalysisService.create`/`retry`,
`MealService.retryFeedback`, `DailyDietService.resolveFeedback`). 매번 짝 중 한쪽을 빠뜨렸다가
리뷰에서 잡혔다. **커밋 전에 `grep -rln isAvailable --include='*.kt'` 목록에 새 파일이 있는지
확인한다**(`AGENTS.md`).

---

## 해야 할 일

### 1. `DietChatMessage` 엔티티 · 리포지토리

```kotlin
enum class ChatRole { USER, ASSISTANT }

@Entity
@Table(
    name = "diet_chat_message",
    indexes = [Index(name = "idx_diet_chat_user_date", columnList = "user_id, date, id")],
)
class DietChatMessage(
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(nullable = false) var date: LocalDate,
    @Enumerated(EnumType.STRING) @Column(nullable = false, columnDefinition = "varchar(20)")
    var role: ChatRole,
    @Column(nullable = false, columnDefinition = "text") var content: String,
) : BaseEntity()
```

`columnDefinition = "varchar(20)"`은 필수다 — `ddl-auto`가 CHECK 제약을 갱신하지 못해
나중에 enum 값을 늘리면 기존 DB에서 INSERT가 깨진다(`AGENTS.md`).

**스레드 테이블은 두지 않는다.** 하루당 대화가 하나뿐이라 `(user, date)`가 곧 스레드다.
턴 수도 별도 컬럼 없이 `USER` 메시지 개수로 센다 — 카운터를 두면 메시지와 어긋날 자리가 생긴다.

```kotlin
interface DietChatMessageRepository : JpaRepository<DietChatMessage, Long> {
    fun findByUserAndDateOrderByIdAsc(user: User, date: LocalDate): List<DietChatMessage>
    fun deleteByCreatedAtBefore(cutoff: LocalDateTime): Long   // 정리 배치용
}
```

턴 수는 `findByUserAndDateOrderByIdAsc`로 가져온 목록에서 센다 — 어차피 히스토리를 전부
읽어야 하므로 `count` 쿼리를 따로 두지 않는다.

### 2. `OpenRouterClient` — 여러 턴을 보낼 수 있게

지금 `generateText(system, user)`는 단발이다. `chat`을 추가하고 **`generateText`를 그 위의
얇은 래퍼로 바꾼다** — 요청을 만드는 자리를 하나로 유지한다.

```kotlin
/** OpenRouter `messages` 배열의 한 칸. `role`은 API 값("user"/"assistant")이다. */
data class ChatTurn(val role: String, val content: String)

fun chat(systemPrompt: String, turns: List<ChatTurn>): String? {
    val body = mapOf(
        "model" to properties.textModel,
        "messages" to
            listOf(mapOf("role" to "system", "content" to systemPrompt)) +
                turns.map { mapOf("role" to it.role, "content" to it.content) },
    )
    return post(body)?.trim()?.takeIf { it.isNotBlank() }
}

fun generateText(systemPrompt: String, userPrompt: String): String? =
    chat(systemPrompt, listOf(ChatTurn("user", userPrompt)))
```

`ChatRole` → API 값 변환(`name.lowercase()`)은 **채팅 쪽에서 한다.** `diet.llm`이 `diet.chat`을
알면 의존이 뒤집힌다.

### 3. `DietChatPrompts`

지금 `DietFeedbackPrompts.SYSTEM_PROMPT`는 「① 잘한 점 ② 부족한 점 ③ 개선 행동, 2~3문장」을
강제해서 채팅에 맞지 않는다. 별도로 둔다.

```kotlin
object DietChatPrompts {
    const val SYSTEM_PROMPT =
        "당신은 식단 코치입니다. 사용자의 하루 식단 기록에 대해 묻는 말에 한국어 존댓말로 답하세요.\n" +
            "앞서 드린 [오늘 기록]에 있는 사실만 근거로 삼으세요. 기록에 없는 것은 추측하지 말고 " +
            "모른다고 말하세요 — 숫자를 지어내면 안 됩니다.\n" +
            "3문장 이내로 짧게. 목록 기호는 쓰지 마세요.\n" +
            "금지: 의학적 진단·처방, 특정 질환 언급, 영양제 권유.\n" +
            "식단·영양과 무관한 질문에는 답하지 말고, 식단에 대한 질문을 받겠다고 안내하세요."
}
```

**범위 제한이 피드백보다 중요하다** — 피드백은 우리가 주제를 정하지만 채팅은 사용자가 정한다.
「이 약 먹어도 돼?」·「살 빼는 법」이 실제로 온다.

### 4. `DietChatStore` — 짧은 트랜잭션 두 개

```kotlin
data class ChatContext(
    /** 매 요청 새로 만든 데이터 블록. 저장하지 않는다. */
    val dataBlock: String,
    /** 저장된 하루 피드백. 아직 생성 전이면 null이고, 그때는 오프닝 턴을 빼고 보낸다. */
    val dayFeedback: String?,
    val history: List<ChatTurn>,
    val remainingTurns: Int,
)
```

`loadContext(username, date)`:
1. 사용자 조회
2. `mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc` — **비어 있으면
   `INVALID_REQUEST`**("그날 기록된 끼니가 없습니다")
3. `totals()`·`targets()`·`scoreDay`·`activeEnergyKcal`로 `DietFeedbackPrompts.day(...)` 생성
   — `DailyDietService.getDay`와 같은 재료다
4. `feedbackRepository.findByUserAndDate`에서 피드백 문장
5. 히스토리 로드, `USER` 개수가 상한 이상이면 **`CHAT_TURN_LIMIT_EXCEEDED`**

`append(username, date, question, answer)`: 두 행을 순서대로 저장하고 `DietChatAnswerResponse`를
돌려준다 — 저장 직후의 id·`createdAt`·남은 턴 수가 다 이 트랜잭션 안에 있다.

### 5. `DietChatService`

```kotlin
fun ask(username: String, date: LocalDate, message: String): DietChatAnswerResponse {
    val openRouter = client ?: throw CustomException(DietErrorCode.LLM_UNAVAILABLE)
    val context = store.loadContext(username, date)          // 트랜잭션 1
    val turns = buildTurns(context, message)
    val answer = openRouter.chat(DietChatPrompts.SYSTEM_PROMPT, turns)
        ?: throw CustomException(DietErrorCode.CHAT_FAILED)  // 함정 3 — 아무것도 저장하지 않는다
    return store.append(username, date, message, answer)     // 트랜잭션 2
}
```

`val isAvailable: Boolean get() = client != null`을 두고 **`ask` 맨 앞에서 확인한다**(함정 4).

### 6. 컨트롤러 — **응답 규칙에서 의도적으로 벗어난다**

```kotlin
@RestController
@RequestMapping("/diet/days/{date}/chat")
class DietChatController(private val service: DietChatService) {
    @PostMapping fun ask(...): ResponseEntity<DataResponseBody<DietChatAnswerResponse>>
    @GetMapping  fun list(...): ResponseEntity<DataResponseBody<DietChatResponse>>
}
```

저장소 관례는 「생성은 `@ResponseCreated`로 201 + Location」이다. **여기서는 200 + 바디를
쓴다** — 201을 주고 클라이언트가 답을 다시 조회하게 만들면 왕복이 두 번이 되고, 사용자가
화면에서 기다리는 대화에서 그 지연은 그대로 체감된다. 답변 자체가 데이터라 `DataResponseBody`가
맞다. **관례 위반이 아니라 기록된 예외로 둔다.**

```kotlin
data class DietChatRequest(
    @field:NotBlank @field:Size(max = 500) val message: String,
)

data class DietChatMessageResponse(val id: Long, val role: ChatRole, val content: String, val createdAt: LocalDateTime)
data class DietChatAnswerResponse(val message: DietChatMessageResponse, val remainingTurns: Int)
data class DietChatResponse(val messages: List<DietChatMessageResponse>, val remainingTurns: Int)
```

`@Size(max = 500)`은 프롬프트가 통째로 커지는 것을 막는 상한이다.
`remainingTurns`를 함께 내려야 앱이 상한에 닿았을 때 입력창을 잠글 수 있다 — 없으면 앱이
메시지를 세어 서버 상한을 추측하게 된다.

날짜는 `DailyDietController`와 같이 `@DateTimeFormat(iso = ISO.DATE)`를 붙인다.

### 7. `DietErrorCode` 두 개 추가

```kotlin
CHAT_TURN_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "하루에 물어볼 수 있는 횟수(%s번)를 넘었습니다."),
CHAT_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "답변 생성에 실패했습니다. 잠시 후 다시 물어봐 주세요."),
```

### 8. 정리 배치

`DietChatCleanupScheduler` — **매일 04:20**, `createdAt` 기준 **TTL 7일**.
기존 04:00(임시 파일)·04:10(분석) 뒤에 붙인다. `MealAnalysisCleanupScheduler`와 같은 모양
(스케줄러는 로깅·예외 처리, 실제 삭제는 `@Transactional` 서비스).

분석 TTL이 24시간인데 채팅을 7일로 두는 이유 — 분석은 확정하면 지워지는 **중간 산물**이지만
대화는 사용자가 직접 쓴 것이고, 다음 날 다시 열어 볼 만하다.

### 9. 상수

```kotlin
/** 하루당 물어볼 수 있는 횟수. 공개 근거 없는 자체 설정값이다. */
const val MAX_TURNS_PER_DAY = 20
```

히스토리는 **전량 싣는다.** 상한이 20턴이라 자연히 유계이고, 손잡이를 하나만 둔다.

---

## 테스트

- **정상 흐름** — 질문·답 두 행이 `USER`, `ASSISTANT` 순서로 저장되고 `remainingTurns`가 준다
- **데이터 블록은 저장되지 않는다** — 저장된 메시지 어디에도 `[오늘 먹은 끼니]`가 없다.
  함정 2가 재현되는 자리다
- **매 턴 데이터 블록이 새로 만들어진다** — 두 번째 질문 때 넘어간 `turns[0]`이 그 시점의
  총섭취량을 담는지(첫 턴 이후 끼니를 고친 픽스처로 고정)
- **하루 피드백이 null이면 오프닝 턴을 넣지 않는다** — `turns`에 `assistant`가 안 들어간다
- **히스토리가 교대로 실린다** — `user, assistant, user, ...` 순서
- **LLM이 null을 돌려주면 아무것도 저장하지 않는다** — `CHAT_FAILED`, 저장 호출 0회. 함정 3
- **턴 상한 초과** — `CHAT_TURN_LIMIT_EXCEEDED`, **LLM을 부르지 않는다**
- **키가 없으면** — `LLM_UNAVAILABLE`, 컨텍스트 로드조차 하지 않는다
- **그날 끼니가 없으면** — `INVALID_REQUEST`
- **`GET`은 키가 없어도 동작한다** — 저장된 대화를 그대로 준다

**고의 파손 확인**을 붙인다(이 저장소가 「구현이 망가져도 통과하는 테스트」로 여러 번 데였다):

| 파손 | 빨개져야 하는 테스트 |
| --- | --- |
| 상한 검사 제거 | 턴 상한 |
| 데이터 블록을 히스토리에 저장 | 데이터 블록 미저장 |
| LLM 실패 시 질문만 저장 | LLM null |
| `isAvailable` 가드 제거 | 키 없음 |

---

## 의도적으로 하지 않는 것

- **끼니를 지워도 대화는 지우지 않는다.** `MealService.delete`가 하루 피드백 캐시는 지우지만
  (낡은 기계 생성물이라), 대화에는 사용자가 직접 쓴 질문이 들어 있다. 데이터 블록은 어차피
  매 턴 새로 만들어지고 TTL이 지운다. 그날 끼니가 전부 사라지면 다음 질문이
  `INVALID_REQUEST`로 막히는데, 그 상태에서 지난 대화를 읽는 것은 여전히 가능하다
- **동시성 방어 없음** — 두 요청이 겹치면 상한을 한 번 넘길 수 있다. 사용자가 직접 누르는
  동작이고 사용자가 2명이다(`AGENTS.md`)
- **스트리밍(SSE) 없음** — `flash-lite` 텍스트 호출은 수 초다. WebFlux 스트리밍은 이 규모에 과하다
- **대화 삭제 엔드포인트 없음** — 지우면 턴 상한이 초기화돼 상한이 무의미해진다
- **자동 재시도 없음** — 실패는 사용자가 다시 묻는다. 저장소 전체가 같은 방침이다

## 범위 밖

끼니 단위 채팅 · 여러 날을 걸친 질문(「이번 주 어땠어?」) · 대화 검색 · 대화 내보내기 ·
음식 추천을 식품DB에서 실제로 조회해 주는 것(지금은 모델의 지식으로만 답한다).
