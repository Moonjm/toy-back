# 하루 평가에 대해 되묻는 채팅 (백엔드) 설계

작성일: 2026-07-31 (2026-08-02 화면 형태 반영, 2026-08-04 직전 7일 컨텍스트 반영,
2026-08-06 정리 배치 제거 — 대화를 영구 보관한다,
2026-08-06 이어지는 대화로 전환 — 커서 페이징 · 히스토리 20턴 큐 · 질문 횟수 상한 제거)
짝 저장소: `woori-haru` (iOS) — **채팅 화면이 필요하다.** 아래 「iOS가 할 일」 참조.

## 배경

하루 마감 피드백은 2~3문장짜리 단방향 통보다. 「나트륨이 많았다」까지는 알려 주지만
「그럼 뭘 줄여야 하지?」·「어제보단 나은 거야?」는 물어볼 데가 없다.

평가에 대해 되물을 수 있게 한다.

**「어제보단 나은 거야?」에 답하려면 하루치로는 부족하다.** 그래서 대화 기준 날짜의 상세와 함께
**직전 7일 요약을 컨텍스트에 싣는다**(아래 「3. 직전 7일 블록」). 하루만 싣고 다른 날짜 질문을 전부
거절하면, 이 기능을 만든 동기 중 하나를 스스로 막는 셈이 된다.

**끼니에는 붙이지 않는다.** 하루 데이터에 이미 전체 끼니 목록·목표·주의 영양소가 다 들어 있어
「점심에 뭐 먹었지?」까지 커버되고, 만드는 자리도 하나로 끝난다. 끼니마다 붙이면 엔드포인트·
엔티티·iOS 화면이 두 벌이 되고, 이 저장소에서 반복된 「짝 중 한쪽만 고침」 위험이 늘어난다.

## 화면 형태 — 플로팅 버튼 → 코치 대화

오른쪽 아래 동그란 플로팅 버튼을 누르면 채팅 화면이 열리고, **코치가 먼저 말을 건다.**
먼저 하루·끼니 요약을 말풍선으로 보여준 뒤 질문을 받는다.

```
[코치] 오늘 1,240kcal 드셨어요 (목표 2,509)          ← 앱이 조립
[코치] 아침 84점 · 점심 47점 · 저녁 —                ← 앱이 조립
[코치] 나트륨이 기준을 넘었어요. 저녁에는...          ← DayResponse.feedback (이미 캐시된 LLM 문장)
[입력창]
```

**요약 말풍선은 LLM으로 만들지 않는다.** `GET /diet/days/{date}`가 주는 `DayResponse`에
필요한 것이 다 있다 — `dayScore`·`totalKcal`·`nutrientLimits`·`meals[]`(각 `MealResponse`에
점수·근거·합계·항목까지). 앱이 조립하면 즉시 뜨고 비용이 0이며, 「LLM에게 점수를 묻지 않는다」는
이 도메인의 원칙과도 맞는다. **이 화면을 위해 새로 만들 엔드포인트는 없다.**

LLM에서 오는 것은 총평 한 덩어리(`feedback`)와 사용자가 물었을 때의 답변뿐이다.

**진입 지점이 전역이라 앵커를 앱이 정한다.** 버튼을 어디서 누르든 그때 보고 있던 날짜를
경로에 넣는다. 백엔드는 여전히 `(user, date)` 하나만 안다.

## 범위

| | 범위 |
| --- | --- |
| **한다** | 사용자당 **이어지는 대화 하나** · **영구 저장**(정리 배치 없음) · 동기 요청/응답 · **커서 페이징 조회** · **직전 7일 요약을 컨텍스트에 포함** |
| **안 한다** | 끼니 단위 채팅 · 요약 말풍선용 API(앱이 `DayResponse`로 조립) · 스트리밍(SSE) · 대화 삭제 엔드포인트 · 대화 검색 · **질문 횟수 상한** · **직전 7일보다 먼 날짜** · 기간 평균·자주 먹은 음식 같은 통계(`DietStatsService`의 몫) |

**대화는 어느 날짜로도 열린다.** `POST` 경로에 날짜가 들어가고, 그 날짜가 **코치가 보는 식단이
어느 날 것인가**를 정한다. 오늘 전용이 아니다.

**화면은 하나로 이어진다.** 채팅앱처럼 최신이 아래에 오고 위로 스크롤하면 과거를 불러온다.
저장은 여전히 메시지마다 `date`(어느 날에 대한 질문인가)를 갖지만, **조회는 날짜로 자르지 않고
`id` 커서로 페이징한다.** 그래서 8월 6일에 8월 1일을 물은 질문도 **물은 시각 자리**에 놓인다 —
말풍선에 「8/1에 대해」를 붙이는 것은 앱의 몫이고, 그래서 응답이 메시지마다 `date`를 준다.

## 세 창이 서로 다르다 — 헷갈리기 쉬운 자리

| 창 | 기준 | 무엇을 자르나 |
| --- | --- | --- |
| 데이터 블록의 **직전 7일** | 대화 기준 **날짜**(`Meal.date`) | 프롬프트에 싣는 **식단** |
| 히스토리 **7일** | **물은 시각**(`createdAt`) | 프롬프트에 싣는 **대화** |
| 히스토리 **20턴** | 최근순 | 프롬프트에 싣는 **대화**(위 7일 안에서 다시 자른다) |

앞의 것은 「어느 날 밥 얘기인가」이고 뒤의 둘은 「어느 대화를 기억하는가」다. 축이 다르므로
같은 값(7)이라도 묶지 않는다.

**히스토리는 큐다.** 20턴을 넘으면 오래된 것부터 밀려난다 — 막지 않는다. 그래서 대화가 100번
쌓여도 한 요청의 프롬프트는 최근 20턴까지이고, **요청 크기가 대화 길이와 무관하게 유계다.**

---

## ⚠️ 먼저 읽을 함정 다섯 (5-1 포함 여섯 자리)

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
[user]       DietChatPrompts.context(...)      ← 매번 새로 생성, 저장 안 함
[assistant]  저장된 하루 피드백                  ← 대화의 출발점 (없으면 이 줄을 뺀다)
[user]       저장된 질문 1
[assistant]  저장된 답 1
   ...
[user]       이번 질문
```

**기존 프롬프트 빌더를 그대로 재사용한다.** `DietFeedbackPrompts.day()`의 「목표」·「주의 영양소
기준값」·「하루 점수」는 전부 *모델이 지어내는 것을 막으려고* 하나씩 추가된 것들이다(파일 주석
참고 — 「기준을 함께 실어야 LLM이 나트륨 2,610mg만 보고 많은지 적은지 스스로 판단하지 않는다」).
새로 쓰면 그 방어를 처음부터 다시 겪는다.

### 함정 2-1 — 화면에 끼니 요약이 보이면 **끼니를 물어본다.** `day()`만으로는 못 답한다

`DietFeedbackPrompts.day()`는 끼니를 **이름과 열량만** 담는다:

```
- 점심: 제육볶음, 두부, 잡곡밥 (555kcal)
```

끼니별 점수도, 매크로도, 균형 근거도 없다. 그런데 채팅 화면이 「점심 47점」을 보여주므로
**「점심 47점은 왜 그래?」가 반드시 온다.** 이대로면 모델이 지어낸다.

**→ `context()`는 `day()` 뒤에 끼니마다 `DietFeedbackPrompts.meal(meal, basis)`를 덧붙인다.**
그 함수가 이미 항목별 g·kcal·매크로와 「균형 근거」(권장 범위 대비 %와 초과/부족)를 담고 있어
그 질문에 정확히 답할 재료가 된다. 새로 쓰지 않고 붙이기만 한다.

```kotlin
fun context(date: LocalDate, meals: List<Meal>, totals: NutritionTotals, targets: NutritionTargets,
            dayScore: Int, activeEnergyKcal: Int?, recentDays: List<RecentDaySummary>): String =
    buildString {
        append(DietFeedbackPrompts.day(date, meals, totals, targets, dayScore, activeEnergyKcal))
        appendLine()
        appendLine("[끼니별 상세]")
        meals.forEach { append(DietFeedbackPrompts.meal(it, DietScoreCalculator.scoreMeal(it.carbsG, it.proteinG, it.fatG).basis)) }
        appendLine()
        append(recentDaysBlock(recentDays))   // 아래 「3. 직전 7일 블록」
    }
```

**기준일이 먼저, 직전 7일이 뒤다.** 대화의 주제는 기준일이고 7일은 배경이며, 시스템 프롬프트가
「앞서 드린」으로 가리키는 순서와도 맞는다.

`basis`는 저장하지 않고 `MealDtos.toResponse`와 같은 식으로 그때 다시 계산한다 — 감점 기울기를
바꿨을 때 응답과 프롬프트가 어긋나지 않는다.

**토큰이 늘어난다.** 하루 3~4끼면 감당되지만, 이것이 이 기능의 주된 비용 증가 요인이다.
끼니가 비정상적으로 많은 날은 상한을 걸어야 할 수 있다 — 지금은 걸지 않고 지켜본다.

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

### 함정 5 — **프롬프트에 날짜가 없으면 모델은 늘 「오늘」이라고 말한다**

`DietFeedbackPrompts.day()`의 헤더가 `"[오늘 먹은 끼니]"` **고정 문자열**이다. 대화는 어느
날짜로도 열리는데 컨텍스트에는 그 날짜가 어디에도 없다. 8월 1일 대화에서 「오늘 저녁은…」이
나오고, 더 나쁘게는 **자기가 보고 있는 게 바로 8월 1일인데도** 「오늘 기록만 볼 수 있습니다」로
거절할 수 있다. 판단할 근거가 없기 때문이다.

이건 채팅만의 문제가 아니다. **하루 피드백도 지난 날짜를 조회하면 그때 생성되므로 지금도
「오늘」이 틀린 경우가 있다.**

**→ `day()`가 `date`를 받아 헤더에 박는다.** `[2026-08-01 (토) 먹은 끼니]`. 하루 피드백과 채팅이
한 번에 정확해진다. 호출부는 `DayFeedbackStore.loadPrompt` 하나뿐이고 이미 `date`를 갖고 있다.

**끼니 종류도 함께 한글로 렌더링한다.** 사용자가 한국어로 묻고 모델이 한국어로 답하는데
컨텍스트만 `LUNCH`면 모델이 점심↔LUNCH를 한 홉 건너뛴 뒤 근거를 찾는다. 모델은 근거를 인용할
때 컨텍스트 표기를 그대로 쓰는 경향이 있어 「LUNCH에 드신 짜장면이」 같은 문장도 나온다. 지금
프롬프트에서 영어는 이 한 자리뿐이라 섞인 언어가 그 자체로 불리하다. `day()`·`meal()` 둘 다
바꾼다.

**API는 건드리지 않는다.** `MealResponse.mealType`은 `"LUNCH"` 그대로다 — iOS 계약이라 바꾸면
앱이 깨진다. 바뀌는 것은 **프롬프트 렌더링뿐**이다.

### 함정 5-1 — **히스토리도 날짜를 넘나든다.** 예전 질문이 오늘 것으로 읽힌다

히스토리가 「그날 것만」에서 「7일 이내 최근 20턴」으로 넓어지면서 **한 프롬프트에 여러 날의
질문이 섞인다.** 8월 3일에 물은 「점심 왜 낮아?」가 8월 6일 대화의 컨텍스트에 그냥 실리면
모델은 그것을 **8월 6일 점심 얘기로 읽는다.** 함정 5와 같은 문제가 히스토리에서 되풀이된다.

**→ 히스토리의 사용자 턴 앞에 그 질문의 날짜를 붙인다.**

```
[user]      [08-03] 점심 왜 낮아?
[assistant] 나트륨이 기준을 넘었습니다...
[user]      [08-05] 어제보다 나아졌어?
[assistant] 네, 12점 올랐습니다...
[user]      오늘은 어때?                    ← 이번 질문은 접두 없음
```

**붙이는 것은 `date`이지 `createdAt`이 아니다.** 「어느 날에 대한 질문인가」가 모델에게 필요한
값이다 — 8월 6일에 8월 1일을 물었다면 `[08-01]`이다.

**사용자 턴에만 붙인다.** 답변은 바로 뒤에 오므로 짝이 명확하고, 양쪽에 붙이면 노이즈만 는다.

**이번 질문에는 붙이지 않는다.** 마지막 턴이라 헷갈릴 다른 것이 없고, 바로 위 데이터 블록
헤더가 이미 그 날짜를 말한다(`[2026-08-01 (토) 먹은 끼니]`). 사용자가 실제로 쓴 문장을
**모델이 답할 지점에서는 손대지 않은 채로** 두는 편이 낫다.

---

## 해야 할 일

### 1. `DietChatMessage` 엔티티 · 리포지토리

```kotlin
enum class ChatRole { USER, ASSISTANT }

@Entity
@Table(
    name = "diet_chat_message",
    // 남은 쿼리 둘을 받친다 — 히스토리(user_id, created_at>? ORDER BY id DESC)와
    // 커서 페이징(user_id, id<? ORDER BY id DESC). 둘 다 date를 안 쓴다.
    indexes = [Index(name = "idx_diet_chat_user_id", columnList = "user_id, id")],
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

**스레드 테이블은 두지 않는다.** 대화는 사용자당 하나의 이어지는 스트림이라 별도 스레드
식별자가 필요 없다 — `user`만으로 그 사람의 전체 대화가 정해진다. `date`는 스레드가 아니라
**그 질문이 어느 날 식단에 대한 것인가**로만 남는다(대화 자체는 날짜로 자르지 않는다). 질문
횟수 상한이 없어 턴 수를 세는 코드도 없다.

```kotlin
interface DietChatMessageRepository : JpaRepository<DietChatMessage, Long> {
    /** 프롬프트에 실을 히스토리. `createdAt` 기준으로 최근 것부터, 개수는 `Pageable`로 자른다. */
    fun findByUserAndCreatedAtAfterOrderByIdDesc(
        user: User,
        createdAt: LocalDateTime,
        pageable: Pageable,
    ): List<DietChatMessage>

    /** 화면용 커서 페이징. 날짜로 자르지 않는다 — 사용자 전체가 한 스트림이다. */
    fun findByUserAndIdLessThanOrderByIdDesc(
        user: User,
        id: Long,
        pageable: Pageable,
    ): List<DietChatMessage>
}
```

리포지토리 메서드는 둘이다 — 프롬프트용 히스토리 조회(`createdAt` 창 + 개수 제한)와 화면용
커서 페이징(`id` 기준)이 서로 다른 쿼리라 하나로 합치지 않는다. 인덱스는 `(user_id, id)`
하나로 둘 다 받친다.

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

### 3. 직전 7일 블록

기준 날짜 **직전 7일**을 하루 한 줄로 싣고, 그 아래 끼니별 **음식 이름까지만** 붙인다.

```
[직전 7일]
- 07-25 (금) 72점 1,980kcal 나트륨 초과
    아침: 토스트, 계란
    점심: 김치찌개, 밥
- 07-26 (토) 기록 없음
- 07-27 (일) 69점 2,250kcal
    점심: 비빔밥
    저녁: 된장찌개, 밥
```

**왜 7일인가** — 「이번 주」 감각과 맞고 주말 과식 같은 요일 효과가 보인다. 하루 한 줄이라
토큰 부담이 작다. 3일은 「어제보단」에만 답하고 주 단위 질문에 못 답하며, 14일은 줄이 두 배로
늘면서 먼 날짜일수록 모델이 언급할 이유가 줄어든다.

**왜 음식 이름까지 넣는가** — 함정 2-1과 같은 이유다. 점수가 보이면 이유를 묻는다. 「금요일엔
뭘 먹었길래 58점이야?」는 반드시 오고, 이름이 없으면 모델이 지어낸다.

**왜 수량·매크로는 넣지 않는가** — 7일치에 항목별 g·kcal·균형 근거까지 실으면 기준일 상세와
크기가 비슷해진다. 「금요일 점심 탄수화물 몇 g이야」에는 **그 날짜를 열어 물어봐 달라고
안내한다** — 그러라고 날짜별로 대화가 열려 있다.

**기록 없는 날도 줄을 남긴다.** 빼 버리면 모델이 날짜가 연속인 줄 알고 「사흘 연속 좋았다」처럼
없는 추세를 만든다.

**쿼리는 늘리지 않는다.** 기준일과 직전 7일을 따로 읽지 않고
`findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)`
**한 번**으로 8일치를 받아 날짜로 쪼갠다. 그 메서드의 KDoc이 「통계가 날짜별로 묶은 뒤 그날 첫
끼니의 스냅샷을 목표로 쓴다」고 적어 둔, 정확히 이 용도의 정렬이다.

날짜별 점수·주의 영양소는 새로 계산하지 않고 `totals()` · `DietScoreCalculator.scoreDay` ·
`NutrientLimitEvaluator.evaluate`를 그대로 쓴다. **계산은 `DietChatStore`, 렌더링은
`DietChatPrompts`** — `day()`가 이미 계산된 값을 받는 것과 같은 분업이다.

```kotlin
/** 직전 7일 한 줄치. 렌더링에 필요한 것만 담는다 — 매크로는 일부러 없다. */
data class RecentDaySummary(
    val date: LocalDate,
    /** 그날 기록이 없으면 null. 「기록 없음」으로 렌더링한다. */
    val dayScore: Int?,
    val totalKcal: Double,
    /** 기준을 벗어난 주의 영양소 이름들 — 「나트륨 초과」·「식이섬유 부족」 */
    val exceeded: List<String>,
    /** 끼니 종류 → 음식 이름들. 확정 순서 그대로다. */
    val meals: List<Pair<MealType, List<String>>>,
)
```

**토큰과 조회량이 늘어난다.** 하루 3~4끼면 7일 합쳐 35줄 안팎으로 기준일 상세보다 작지만,
`items`가 LAZY라 **읽는 끼니가 하루치의 8배**가 된다. 사용자 둘 규모에서 걸릴 값은 아니라
지금은 상한을 걸지 않고 지켜본다 — 함정 2-1의 토큰 상한과 같은 태도다.

### 4. `DietChatPrompts`

지금 `DietFeedbackPrompts.SYSTEM_PROMPT`는 「① 잘한 점 ② 부족한 점 ③ 개선 행동, 2~3문장」을
강제해서 채팅에 맞지 않는다. 별도로 둔다.

화면에서 이 답변을 말하는 주체는 **「코치」**다. 별도의 이름·캐릭터는 두지 않는다 — 이름을
붙이면 앱의 아바타·말풍선 라벨과 계속 맞춰야 하고, 얻는 것은 말투의 일관성뿐이다.

```kotlin
object DietChatPrompts {
    const val SYSTEM_PROMPT =
        "당신은 식단 코치입니다. 사용자의 하루 식단 기록에 대해 묻는 말에 한국어 존댓말로 답하세요.\n" +
            "앞서 드린 [오늘 먹은 끼니]·[끼니별 상세]에 있는 사실만 근거로 삼으세요. 기록에 없는 " +
            "것은 추측하지 말고 모른다고 말하세요 — 숫자를 지어내면 안 됩니다.\n" +
            "3문장 이내로 짧게. 목록 기호는 쓰지 마세요.\n" +
            "금지: 의학적 진단·처방, 특정 질환 언급, 영양제 권유.\n" +
            "식단·영양과 무관한 질문에는 답하지 말고, 식단에 대한 질문을 받겠다고 안내하세요.\n" +
            "[직전 7일]에는 그날의 점수·열량과 먹은 음식 이름만 있습니다. 그보다 자세한 것을 " +
            "물으면 그 날짜를 열어서 물어봐 달라고 안내하세요.\n" +
            "[직전 7일]보다 먼 날짜는 볼 수 없습니다. 그때도 그 날짜를 열어서 물어봐 달라고 하세요."
    // 여기에 `context(...)` (함정 2-1)
}
```

**범위 제한이 피드백보다 중요하다** — 피드백은 우리가 주제를 정하지만 채팅은 사용자가 정한다.
「이 약 먹어도 돼?」·「살 빼는 법」이 실제로 온다.

**「어제는 어땠어?」는 이제 답한다**(직전 7일). 막는 것은 두 가지뿐이다 — 7일보다 먼 날짜와,
7일 안이지만 요약보다 자세한 것. 둘 다 「그 날짜를 열어서 물어보라」로 보낸다. **거절이 아니라
길 안내다** — 대화가 날짜별로 열려 있어서 실제로 그렇게 하면 답을 얻는다.

「이번 주 평균은?」류의 기간 통계는 `DietStatsService`의 몫이고 채팅에 싣지 않는다. 직전 7일은
**날짜별 원자료**이지 집계가 아니다 — 모델에게 평균을 계산시키면 숫자를 지어낼 자리가 생긴다.

### 5. `DietChatStore` — 짧은 트랜잭션 두 개

```kotlin
data class ChatContext(
    /** `DietChatPrompts.context(...)` 결과 — 하루 블록 + 끼니별 상세. 매 요청 새로 만들고 저장하지 않는다. */
    val dataBlock: String,
    /** 저장된 하루 피드백. 아직 생성 전이면 null이고, 그때는 오프닝 턴을 빼고 보낸다. */
    val dayFeedback: String?,
    val history: List<ChatTurn>,
)
```

`loadContext(username, date)`:
1. 사용자 조회
2. `mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(7), date)`
   **한 번**으로 8일치를 받아 날짜로 쪼갠다 — 기준일과 직전 7일을 따로 조회하지 않는다
3. **기준일 몫이 비어 있으면 `INVALID_REQUEST`**("그날 기록된 끼니가 없습니다"). 아래 「빈 날」 참조
4. 기준일: `totals()`·`targets()`·`scoreDay`·`activeEnergyKcal` — `DailyDietService.getDay`와 같은 재료
5. 직전 7일: 날짜마다 `totals()`·`scoreDay`·`NutrientLimitEvaluator.evaluate`로 `RecentDaySummary`.
   기록 없는 날도 자리를 만든다
6. 4·5로 `DietChatPrompts.context(...)` 생성
7. `feedbackRepository.findByUserAndDate`에서 피드백 문장
8. 히스토리 로드 — **`createdAt`이 7일 이내인 것 중 최근 20턴**(40행)을 `id DESC`로 받아
   뒤집는다. 상한 검사는 없다(질문 횟수를 막지 않는다). 사용자 턴에는 **그 질문의 `date`를
   접두로 붙인다**(함정 5-1)

**빈 날 — 기준일이 비면 직전 7일이 있어도 거절한다.** 「기준일은 비었지만 요즘 얘기는 할 수
있지 않나」가 되지만, 화면이 기준일 요약 말풍선으로 시작하는 구조라 보여줄 것이 없고, 기록이
있는 날을 열면 되는 일이다. **판정은 기준일 하나로 단순하게 둔다.**

**서버는 거절하지만 앱이 먼저 막는다.** 플로팅 버튼이 늘 떠 있어서 기록 전에 누르는 일이
흔하다. 그때마다 400을 받아 오류 화면을 띄우면 곤란하다. **앱이 `DayResponse.meals`가 비었으면
입력창을 잠그고 정적 문구를 띄운다**(「아직 오늘 기록이 없어요. 먼저 한 끼 기록해 볼까요?」) —
LLM 호출 없이 즉시 뜨고 비용이 0이다. 서버의 거절은 안전망으로 남긴다.

`append(username, date, question, answer)`: 두 행을 순서대로 저장하고 **저장된 답 한 건**을
돌려준다 — id·`createdAt`이 이 트랜잭션 안에서 채워진다.

`page(username, before: Long?, size: Int)`: `id < before`인 것을 `id DESC`로 `size`개.
`before`가 null이면 첫 장이다. **날짜로 자르지 않는다** — 사용자 전체가 한 스트림이다.
`id`가 단조 증가라 `offset` 없이 안정적으로 뒤로 간다(중간에 새 메시지가 들어와도 페이지가
밀리지 않는다).

### 6. `DietChatService`

```kotlin
fun ask(username: String, date: LocalDate, message: String): DietChatMessageResponse {
    val openRouter = client ?: throw CustomException(DietErrorCode.LLM_UNAVAILABLE)
    val context = store.loadContext(username, date)          // 트랜잭션 1
    val turns = buildTurns(context, message)
    val answer = openRouter.chat(DietChatPrompts.SYSTEM_PROMPT, turns)
        ?: throw CustomException(DietErrorCode.CHAT_FAILED)  // 함정 3 — 아무것도 저장하지 않는다
    return store.append(username, date, message, answer)     // 트랜잭션 2
}
```

**가드는 `client ?: throw`가 전부다.** 별도 `isAvailable` 프로퍼티를 두지 않는다 — 같은 클래스가
`client`를 직접 들고 있어 읽을 사람이 없고, 남기면 「가드가 여기 있다」는 오독을 부른다
(`DietFeedbackGenerator.isAvailable`은 **다른 클래스**가 읽어서 존재 이유가 있는 경우다).

### 7. 컨트롤러 — **응답 규칙에서 의도적으로 벗어난다**

**두 경로가 다르다.** 묻는 것은 날짜에 매이고 읽는 것은 매이지 않는다.

```kotlin
@RestController
class DietChatController(private val service: DietChatService) {
    /** 그날 식단으로 답해야 하므로 날짜가 필요하다. */
    @PostMapping("/diet/days/{date}/chat")
    fun ask(...): ResponseEntity<DataResponseBody<DietChatMessageResponse>>

    /** 이어지는 스트림이라 날짜가 없다. `before`가 null이면 첫 장. */
    @GetMapping("/diet/chat")
    fun page(before: Long?, size: Int): ResponseEntity<DataResponseBody<DietChatPageResponse>>
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

/** `date`는 **어느 날에 대한 질문인가**다 — `createdAt`(언제 물었나)과 다르다. 8/6에 8/1을 물을 수 있다. */
data class DietChatMessageResponse(
    val id: Long, val date: LocalDate, val role: ChatRole,
    val content: String, val createdAt: LocalDateTime,
)

/** `nextCursor`가 null이면 더 없다 — 앱이 그때 무한 스크롤을 멈춘다. */
data class DietChatPageResponse(
    val messages: List<DietChatMessageResponse>,
    val nextCursor: Long?,
)
```

`@Size(max = 500)`은 프롬프트가 통째로 커지는 것을 막는 상한이다.

**메시지에 `date`를 실어야 한다.** 스트림이 물은 시각 순이라 8/1에 대한 질문이 8/6 대화 사이에
끼어 앉는다. 이 값이 없으면 앱이 그 말풍선에 「8/1에 대해」를 붙일 수 없다.

`POST`의 날짜는 `DailyDietController`와 같이 `@DateTimeFormat(iso = ISO.DATE)`를 붙인다.

### 8. `DietErrorCode` 한 개 추가

```kotlin
CHAT_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "답변 생성에 실패했습니다. 잠시 후 다시 물어봐 주세요."),
```

### 9. 상수

```kotlin
/** 프롬프트에 싣는 대화 창. 넘으면 오래된 턴부터 밀려난다 — 막지 않는다. */
const val HISTORY_TURNS = 20

/** 히스토리로 거슬러 올라가는 기간. `createdAt` 기준이다. */
const val HISTORY_DAYS = 7L

/** 컨텍스트에 싣는 과거 **식단** 창. `Meal.date` 기준이라 위 둘과 축이 다르다. */
const val RECENT_DAYS = 7

/** 페이징 기본 크기. 앱이 넘기지 않으면 이 값을 쓴다. */
const val DEFAULT_PAGE_SIZE = 30
```

**질문 횟수를 막지 않는다.** 예전에는 하루 20문 상한이 비용 방어도 겸했는데, 그 상한이 곧
「일주일치를 하루에 다 쓰면 남은 엿새를 못 묻는다」가 되어 대화를 끊었다. 히스토리를 큐로 두면
요청 크기는 그대로 유계이므로(최대 20턴) 상한 없이도 비용이 발산하지 않는다.

**남는 위험 하나** — 질문 횟수를 막는 것이 이제 아무것도 없다. 클라이언트가 루프에 빠지면 그대로
LLM 호출이 된다. `POST`가 사용자가 타이핑한 메시지를 요구하고 앱이 중복 전송을 막으며 사용자가
둘이라 감수한다(`AGENTS.md`의 「동시성 방어를 하지 않는다」와 같은 결의 판단이다).

### 10. 기존 프롬프트 함수 변경 (함정 5)

- `MealType`에 프롬프트 표시명 `label`을 단다(아침·점심·저녁·간식). **enum 이름과 API 응답은
  그대로다** — `MealResponse.mealType`은 `"LUNCH"`다.
- `DietFeedbackPrompts.day(date, ...)` — 헤더를 `[2026-08-01 (토) 먹은 끼니]`로, 끼니 종류를
  `label`로. 호출부는 `DayFeedbackStore.loadPrompt` 하나뿐이고 이미 `date`를 갖고 있다.
- `DietFeedbackPrompts.meal(...)` — `[이번 끼니]` 뒤를 `label`로.

**파급은 문장 쪽이다.** 하루·끼니 피드백 프롬프트가 바뀌므로 앞으로 생성되는 문장이 미세하게
달라질 수 있다. 이미 저장된 문장은 그대로 남고, `contentUpdatedAt` 기반 무효화는 프롬프트 변경을
모르므로 재생성이 몰리지도 않는다.

**기존 테스트는 깨지지 않는다.** 프롬프트 문자열을 검사하는 테스트가 지금 하나도 없다
(`DietFeedbackPrompts`를 언급하는 네 자리가 전부 주석이다). 뒤집어 말하면 **이 프롬프트들이
지금 무검증**이라는 뜻이고, 아래 테스트가 그 첫 그물이 된다.

---

## 테스트

- **정상 흐름** — 질문·답 두 행이 `USER`, `ASSISTANT` 순서로 저장되고 저장된 답이 돌아온다
- **데이터 블록은 저장되지 않는다** — 저장된 메시지 어디에도 `[오늘 먹은 끼니]`가 없다.
  함정 2가 재현되는 자리다
- **매 턴 데이터 블록이 새로 만들어진다** — 두 번째 질문 때 넘어간 `turns[0]`이 그 시점의
  총섭취량을 담는지(첫 턴 이후 끼니를 고친 픽스처로 고정)
- **끼니별 상세가 컨텍스트에 들어간다** — `turns[0]`에 **끼니별 점수와 균형 근거(권장 범위
  대비 %)**가 있는지. 함정 2-1이 재현되는 자리다. 이 확인이 없으면 `day()`만 넘겨도 통과하고,
  화면에 보이는 「점심 47점」을 물었을 때 모델이 지어낸다
- **하루 피드백이 null이면 오프닝 턴을 넣지 않는다** — `turns`에 `assistant`가 안 들어간다
- **히스토리가 교대로 실린다** — `user, assistant, user, ...` 순서
- **LLM이 null을 돌려주면 아무것도 저장하지 않는다** — `CHAT_FAILED`, 저장 호출 0회. 함정 3
- **키가 없으면** — `LLM_UNAVAILABLE`, 컨텍스트 로드조차 하지 않는다
- **그날 끼니가 없으면** — `INVALID_REQUEST`. **직전 7일에 기록이 있어도 마찬가지다**
- **`GET`은 키가 없어도 동작한다** — 저장된 대화를 그대로 준다

히스토리 창과 페이징:

- **20턴을 넘으면 오래된 것부터 밀려난다** — 25턴을 쌓아 두고 `turns`에 최근 20턴만 들어가는지,
  **가장 오래된 질문이 빠졌는지**. 큐가 재현되는 자리다
- **7일보다 오래 전에 물은 대화는 안 실린다** — `createdAt` 기준이다. **`date`가 아니다**
- **날짜를 넘나드는 히스토리가 들어간다** — 어제 물은 것이 오늘 대화의 컨텍스트에 실린다.
  「그날 것만」으로 되돌리면 빨개진다
- **히스토리의 사용자 턴에 날짜가 붙는다**(함정 5-1) — `[08-03] 점심 왜 낮아?`. 붙는 값이
  `date`이지 `createdAt`이 아닌지도 함께 건다(8/6에 8/1을 물은 픽스처로 고정)
- **답변 턴에는 안 붙는다** — `assistant` 내용이 저장된 그대로다
- **이번 질문에는 안 붙는다** — 마지막 턴이 사용자가 쓴 문장 그대로다
- **페이징이 `id` 커서로 뒤로 간다** — `before`를 넘기면 그보다 작은 id만, `id DESC`로
- **첫 장은 `before` 없이** — null이면 최신부터
- **더 없으면 `nextCursor`가 null이다** — 앱이 무한 스크롤을 멈추는 신호다
- **페이징은 날짜로 자르지 않는다** — 한 페이지에 여러 날짜가 섞여 나온다
- **응답 메시지에 `date`가 실린다** — `createdAt`과 다른 값일 수 있다(8/6에 8/1을 물은 경우)

직전 7일과 날짜 라벨(함정 5):

- **직전 7일이 컨텍스트에 들어간다** — `turns[0]`에 `[직전 7일]`과 그 안의 음식 이름
- **기준 날짜가 헤더에 박힌다** — 과거 날짜로 요청하면 그 날짜가 헤더에 나온다(「오늘」이 아니다)
- **기록 없는 날이 자리를 지킨다** — 7일 중 빈 날이 「기록 없음」으로 남고 생략되지 않는다.
  생략하면 모델이 없는 연속성을 만든다
- **직전 7일에는 매크로가 없다** — 음식 이름까지만 들어간다
- **창은 기준 날짜 기준이다** — 과거 날짜로 요청하면 그 날짜 직전 7일이지 오늘 직전 7일이 아니다
- **프롬프트에 `LUNCH`가 없다** — 끼니 종류가 한글로 렌더링된다
- **API는 그대로다** — `MealResponse.mealType`이 `"LUNCH"`다. 이 검사가 없으면 프롬프트를
  한글로 바꾸면서 응답까지 바꿔 앱을 깨뜨릴 수 있다
- **조회는 한 번이다** — 기준일과 직전 7일을 따로 읽지 않는다

**고의 파손 확인**을 붙인다(이 저장소가 「구현이 망가져도 통과하는 테스트」로 여러 번 데였다):

| 파손 | 빨개져야 하는 테스트 |
| --- | --- |
| 히스토리 창을 무제한으로 | 20턴 큐 |
| 히스토리 창을 `date` 기준으로 | 7일은 `createdAt` 기준 |
| 히스토리를 기준 날짜로만 조회 | 날짜를 넘나드는 히스토리 |
| 히스토리 사용자 턴의 날짜 접두 제거 | 히스토리 날짜 접두 |
| 접두를 `createdAt`으로 붙임 | 히스토리 날짜 접두 |
| 페이징에서 `before`를 무시 | `id` 커서 |
| 마지막 장에서도 `nextCursor`를 채움 | `nextCursor`가 null |
| 데이터 블록을 히스토리에 저장 | 데이터 블록 미저장 |
| `context()`를 `day()`만 쓰도록 되돌림 | 끼니별 상세 |
| LLM 실패 시 질문만 저장 | LLM null |
| `ask`의 `client ?: throw` 가드 제거 | 키 없음 |
| 직전 7일 블록 제거 | 직전 7일 |
| `day()` 헤더를 「오늘 먹은 끼니」로 되돌림 | 기준 날짜 |
| 기록 없는 날을 목록에서 생략 | 기록 없는 날 |
| 창을 `LocalDate.now()` 기준으로 계산 | 창은 기준 날짜 기준 |

---

## 의도적으로 하지 않는 것

- **대화를 지우지 않는다 — TTL도, 삭제 엔드포인트도 없다.** 지난 대화를 나중에 다시 열어 볼
  수 있어야 한다는 것이 이 결정의 이유이고, 화면이 무한 스크롤이라 오래된 것도 실제로 보인다.

  **대신 대화를 지울 방법이 아예 없다.** 잘못 쓴 질문을 지우거나 운영에서 특정 사용자의
  대화를 정리하려면 DB를 직접 만져야 한다. 필요해지면 그때 삭제 엔드포인트를 따로 설계한다.
- **질문 횟수를 막지 않는다.** 히스토리가 큐라 요청 크기가 유계이므로 상한이 비용 방어로
  필요하지 않고, 상한을 두면 「일주일치를 하루에 다 쓰면 남은 엿새를 못 묻는다」가 되어
  대화를 끊는다. 남는 위험(클라이언트 루프)은 「9. 상수」에 적었다.
- **끼니를 지워도 대화는 지우지 않는다.** `MealService.delete`가 하루 피드백 캐시는 지우지만
  (낡은 기계 생성물이라), 대화에는 사용자가 직접 쓴 질문이 들어 있다. 데이터 블록은 어차피
  매 턴 새로 만들어진다. 그날 끼니가 전부 사라지면 다음 질문이 `INVALID_REQUEST`로 막히는데,
  그 상태에서 지난 대화를 읽는 것은 여전히 가능하다
- **동시성 방어 없음** — 사용자가 직접 누르는 동작이고 사용자가 2명이다(`AGENTS.md`)
- **스트리밍(SSE) 없음** — `flash-lite` 텍스트 호출은 수 초다. WebFlux 스트리밍은 이 규모에 과하다
- **대화 검색 없음** — 무한 스크롤로 거슬러 올라가는 것으로 충분하다고 본다
- **자동 재시도 없음** — 실패는 사용자가 다시 묻는다. 저장소 전체가 같은 방침이다
- **요약 말풍선을 서버가 만들지 않는다** — 「오늘 1,240kcal 드셨어요」류는 앱이 `DayResponse`로
  조립한다. 서버가 문장으로 만들어 주면 매번 LLM 비용이 나가거나(결정성도 잃는다) 문구 포맷이
  백엔드에 박혀 화면을 바꿀 때마다 서버를 고쳐야 한다

---

## iOS가 할 일 (이 API가 들어온 뒤)

**화면이 채팅앱 형태로 바뀐다** — 「그날 대화만」에서 「이어지는 스트림 + 무한 스크롤」로.
`DayResponse`와 `MealResponse.mealType`(`"LUNCH"`)은 그대로지만 **채팅 API는 바뀐다.**

- **오른쪽 아래 플로팅 버튼** → 채팅 화면. 보고 있던 날짜를 `POST`에 그대로 넘긴다
- **코치 오프닝 말풍선 3종을 앱이 조립한다** — 하루 요약·끼니별 점수 요약은 `DayResponse`에서,
  총평은 `DayResponse.feedback`에서. **LLM을 부르지 않는다**
- `DietServing`에 `fetchChatPage(before:size:)` / `askDayChat(date:message:)` 추가
- **최신이 아래, 위로 스크롤하면 다음 장.** `nextCursor`가 null이면 멈춘다
- **말풍선에 `date` 배지를 붙인다** — 스트림이 물은 시각 순이라 8/1에 대한 질문이 8/6 대화
  사이에 앉는다. `createdAt`이 아니라 `date`로 붙여야 한다
- **입력창 잠금은 빈 날 하나뿐이다** — 질문 횟수 상한이 없어졌다.
  `DayResponse.meals`가 비었으면 잠그고 정적 문구를 띄운다
- `feedback`이 아직 null이면(생성 중) 총평 말풍선 자리에 로딩을 두고, 기존 폴링을 그대로 쓴다
- 전송 중에는 중복 요청을 막는다 — `MealConfirmViewModel.isSaving`과 같은 가드

실제 작업은 API가 배포된 뒤 `woori-haru`에서 따로 한다.

## 범위 밖

끼니 단위 채팅 · **직전 7일보다 먼 날짜** · **기간 집계**(「이번 주 평균 몇 점이야?」 —
`DietStatsService`의 몫이고, 모델에게 평균을 계산시키면 숫자를 지어낼 자리가 생긴다) ·
직전 7일의 수량·매크로 · 대화 검색 · 대화 내보내기 · 음식 추천을 식품DB에서 실제로 조회해
주는 것(지금은 모델의 지식으로만 답한다).
