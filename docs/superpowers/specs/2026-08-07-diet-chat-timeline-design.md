# 하루 채팅을 코치 타임라인으로 (서버) 설계

작성일: 2026-08-07
짝 저장소: `woori-haru` (iOS) — **서버가 먼저 나가야 한다.** 앱 설계는 그쪽
`docs/superpowers/specs/2026-08-07-diet-chat-screen-design.md`에 따로 있다. 이 문서는 **서버만** 다룬다.

선행 설계: `2026-07-31-diet-day-chat-design.md`. 그 문서가 만든 것을 **바꾸는** 설계다.

## 배경

지금 채팅은 **사용자가 물어야만 무언가가 생긴다.** 처음 열면 빈 화면에 입력창 하나다.

화면에 하루 요약을 보여주는 방법은 두 가지였다 — 앱이 `GET /diet/days/{date}`로 매번 조립해
띄우거나(저장 안 됨), 서버가 스트림에 쌓거나. 전자는 **스크롤을 올려도 과거 요약이 없다.**
카드가 대화 기록의 일부가 아니라 화면 장식이기 때문이다.

**후자로 간다.** 끼니를 확정하면 서버가 그 자리에 카드를 쌓고, 하루 총평이 완성되면 그것도
쌓는다. 사용자 질문은 그 사이에 끼어 앉는다. 채팅이 **대화창이 아니라 코치 타임라인**이 된다.

**LLM 비용은 늘지 않는다.** 카드에 실을 문장을 이미 만들고 있다 — 끼니 피드백
(`DietFeedbackGenerator.generateForMeal`)과 하루 총평(`generateForDay`)이 그것이고, 지금은
끼니 상세와 하루 화면에만 붙는다. 카드는 그 문장을 **가리킬** 뿐이다.

---

## ⚠️ 먼저 읽을 함정 넷

### 함정 1 — **스냅샷으로 저장하면 카드가 낡는다**

카드에 확정 시점의 점수·열량을 JSON으로 박아 두는 것이 첫 충동이다. 그러면 **이 저장소가
방금 두 번 판 함정을 세 번째로 판다.**

이 앱은 저장된 끼니를 자주 고친다 — 항목 교체(`PUT /diet/meals/{id}/items`), 그램수 수정,
끼니 종류 변경(`PATCH /diet/meals/{id}`), 사진 삭제. 고칠 때마다 카드가 옛 값을 붙든다.
그러면 같은 화면에서 **카드는 696kcal라고 하고 끼니 상세는 540kcal라고 한다.**

**→ 저장하는 것은 참조(`mealId`)뿐이다.** 조회할 때 현재 `Meal`에서 점수·열량·매크로·피드백을
읽어 채운다. 낡을 수가 없다.

부수 효과가 하나 더 있다. **끼니 피드백은 확정 시점에 아직 없다** — `runAfterCommit`으로
뒤에서 만들어진다. 스냅샷이면 빈 코멘트가 영원히 박히거나 나중에 UPDATE해야 하는데,
참조라면 **저절로 채워진다.**

### 함정 2 — **합칠 때 카드를 또 만들면 같은 것이 두 번 뜬다**

`MealService.confirm`은 같은 날 같은 끼니를 다시 확정하면 **기존 끼니에 합친다**
(`mergeTargetOf`). 그때 새 카드를 만들면 같은 `mealId`를 가리키는 카드가 둘이 되고,
참조 방식이라 **둘 다 똑같은 최신 값을 보여준다.**

**→ 합쳐졌으면(`existing != null`) 카드를 만들지 않는다.** 기존 카드가 이미 합쳐진 값을
보여준다. 타입 변경 병합(`PATCH`)도 같다 — 원본 끼니가 지워지면서 그 카드도 지워지고
(함정 3), 대상 카드가 갱신된 값을 보여준다.

**대신 사용자 입장에서는 「간식을 추가했는데 타임라인에 새로 안 뜬다」가 된다.** 이것이 이
설계의 알려진 비용이다. 새 카드를 만드는 쪽을 택하면 중복이 생기고, 중복을 지우려면
「같은 `mealId` 카드는 하나」라는 제약을 어딘가에서 강제해야 한다 — 그건 결국 같은 결론이다.

### 함정 3 — **지워진 끼니를 조회에서 거르면 `nextCursor`가 꼬인다**

`DietChatStore.page`는 `size + 1`을 받아 「다음 장이 있는가」를 판별한다. 조회한 뒤 지워진
끼니의 카드를 응답에서 빼면 **그 셈이 틀린다** — 3건을 받아 1건을 걸렀는데 여전히
「다음 장 있음」으로 읽거나, 반대로 페이지가 `size`보다 작게 나간다.

**→ 거르지 않는다. 끼니를 지울 때 카드 행도 함께 지운다.** 끼니가 사라지는 자리는 둘뿐이다:

- `MealService.delete`
- `MealService.changeType`의 병합 갈래 (원본 끼니 삭제)

거기서 지우면 **매달린 참조가 애초에 생기지 않고**, 페이징 쿼리는 손댈 곳이 없다.

### 함정 4 — **카드가 프롬프트 히스토리를 먹는다**

프롬프트에 싣는 히스토리는 최근 20턴이다(`HISTORY_TURNS`). 카드 행이 그 창에 섞이면
**정작 사용자와 주고받은 대화가 밀려난다.** 끼니를 다섯 번 확정한 날이면 그날 카드만으로
열 행이다.

내용도 이미 중복이다 — 카드가 가리키는 끼니의 점수·매크로·균형 근거는 `DietChatPrompts.context`가
`[끼니별 상세]` 블록으로 **매 요청 새로 싣는다.**

**→ 히스토리 조회는 `TEXT`만 본다.** 리포지토리 쿼리에 타입 조건을 건다.

---

## 스키마

`DietChatMessage`에 둘을 더한다.

```kotlin
enum class ChatMessageType { TEXT, MEAL_CARD, DAY_SUMMARY }
```

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| `type` | `varchar(20)` not null | 기본값 `TEXT` — 기존 행이 그대로 산다 |
| `meal_id` | `bigint` nullable | `MEAL_CARD`일 때만. **FK를 걸지 않는다**(아래) |

**`columnDefinition`을 명시한다.** ddl-auto가 CHECK 제약을 갱신하지 못해, 나중에 타입을 늘리면
기존 DB에서 INSERT가 깨진다(`AGENTS.md`). `role` 컬럼이 이미 같은 이유로 그렇게 돼 있다.

**`meal_id`에 FK를 걸지 않는다.** 걸면 끼니 삭제가 채팅 행에 막힌다 — 함정 3의 처리(삭제 시
카드도 지우기)가 실패했을 때 **끼니를 못 지우는 상태**가 되는데, 그건 카드가 남는 것보다 나쁘다.
정합성은 삭제 경로에서 지키고, 혹시 매달린 참조가 남아도 조회가 그 행을 조용히 건너뛴다.

**`content`는 그대로 not null이다.** 카드 행은 빈 문자열을 넣는다 — 컬럼을 nullable로 바꾸면
`TEXT` 행의 「내용이 반드시 있다」는 보장까지 함께 잃는다.

**응답에서는 카드 행의 `content`를 null로 내린다.** 빈 문자열을 그대로 내보내면 앱이 빈
말풍선을 그릴 여지가 생긴다 — 「없다」와 「비어 있다」를 같은 값으로 만들지 않는다.

**`date`는 카드에도 쓴다.** `MEAL_CARD`는 그 끼니의 날짜, `DAY_SUMMARY`는 그 총평의 날짜다.
「어느 날 밥 얘기인가」라는 뜻이 그대로 유지된다.

**`role`은 카드도 `ASSISTANT`다.** 코치가 놓은 것이라 화면에서 왼쪽에 선다.

---

## 쓰기 — 언제 쌓나

### `MEAL_CARD` — 끼니 확정 시

`MealService.confirm`이 새 끼니를 만들었을 때만(`existing == null`) 카드 한 행을 쌓는다.
피드백 생성과 같은 `runAfterCommit` 블록이 아니라 **같은 트랜잭션 안**에서 쓴다 — 카드는
LLM을 기다리지 않고, 확정과 카드가 따로 커밋되면 확정만 되고 카드가 없는 상태가 생긴다.

`date`는 `meal.date`, `mealId`는 `saved.requiredId`.

### `DAY_SUMMARY` — 총평 생성이 성공했을 때

`DayFeedbackStore.publish`가 문장을 실제로 실은 뒤(`cached.publish(...)` 성공) 쌓는다.

**실패는 안 쌓는다.** `publish`는 마커가 없거나 낡았으면 문장을 버리는데, 그때는 카드도
만들지 않는다 — 있지도 않은 총평을 가리키게 된다.

**같은 날짜에 두 번 쌓지 않는다.** 총평은 끼니를 고칠 때마다 무효화되고 다시 생성되므로
`publish`가 여러 번 불린다. `(user, date, type = DAY_SUMMARY)` 행이 이미 있으면 **아무것도
하지 않는다** — 참조 방식이라 기존 행이 새 총평을 가리킨다. 없을 때만 만든다.

그래서 **총평 카드는 그 날짜의 총평이 처음 완성된 시각에 앉는다.** 나중에 재생성돼도 자리가
움직이지 않는다. 타임라인에서 카드가 아래로 튀어 오르지 않는 편이 읽기 쉽다.

---

## 조회 — `GET /diet/chat`

경로·커서·`size`는 그대로다. **바뀌는 것은 응답의 한 칸씩이다.**

```kotlin
data class DietChatMessageResponse(
    val id: Long,
    val type: ChatMessageType,
    val date: LocalDate,
    val role: ChatRole,
    val createdAt: LocalDateTime,
    /** `TEXT`일 때만. 카드 행은 null이다. */
    val content: String?,
    /** `MEAL_CARD`일 때만. */
    val meal: ChatMealCard?,
    /** `DAY_SUMMARY`일 때만. */
    val day: ChatDayCard?,
)

data class ChatMealCard(
    val mealId: Long,
    val mealType: MealType,
    /** 저장된 컬럼이 아니라 **재계산 값**이다 — 화면·프롬프트와 같은 값이어야 한다. */
    val score: Int?,
    /**
     * [score]와 **같은 계산에서 나온다.** 카드가 탄단지 구성비 막대(`46% / 17% / 37%`)를
     * 그리는데, 그 비율을 앱이 g에서 직접 내면 **감점 규칙의 분모가 두 곳에 생긴다** —
     * 비율의 분모는 `Meal.totalKcal`이 아니라 매크로에서 역산한 값이라(`DietScoreCalculator`
     * 주석) 앱이 순진하게 계산하면 다른 숫자가 나온다. `MealResponse.scoreBasis`와 같은 타입이라
     * 앱이 이미 디코딩할 줄 안다.
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

data class ChatDayCard(
    val dayScore: Int,
    val totalKcal: Double,
    /**
     * 그날 **첫 끼니의 스냅샷**이다(`Meal.targetKcal`). 카드가 `1,240 / 2,509 kcal`을 보여주려면
     * 분모가 필요한데, 프로필의 현재 목표를 읽으면 과거 카드의 분모가 오늘 몸무게로 바뀐다.
     */
    val targetKcal: Int,
    /** 재생성 중이면 null — 앱이 「마감 피드백을 만들고 있어요」를 띄운다. */
    val feedback: String?,
)
```

**`content`가 nullable이 된다 — 파괴적 변경이다.** 앱이 함께 나가야 한다.

### 채우는 순서

1. 메시지 한 장을 읽는다 (지금 쿼리 그대로)
2. `MEAL_CARD` 행들의 `mealId`를 모아 **`IN` 한 번**으로 끼니를 읽는다
3. `DAY_SUMMARY` 행들의 날짜를 모아 **`IN` 한 번**으로 총평을 읽는다
4. 끼니 사진 파일 id를 모아 presigned URL을 **한 번**에 받는다(`FileService.getPresignedUrls`)

**끼니마다 따로 조회하지 않는다.** 한 장이 100건까지 올 수 있어 그대로 N+1이 된다.

**점수와 근거는 `Meal.score` 컬럼이 아니라 `DietScoreCalculator.scoreMeal` 한 번에서 함께 낸다.**
저장된 컬럼을 읽으면 감점 기울기를 튜닝했을 때 화면·프롬프트와 어긋나고, 점수와 근거를 따로
구하면 둘이 서로 어긋난다 — `MealDtos.toResponse`와 `DietFeedbackPrompts.meal`이 이미 같은
이유로 그렇게 한다.

### 매달린 참조

함정 3의 처리로 생기지 않아야 하지만, 혹시 `mealId`가 가리키는 끼니가 없으면 **그 행을 응답에서
조용히 뺀다.** 그때는 `nextCursor`가 어긋날 수 있지만, 있지도 않은 끼니의 빈 카드를 내리는
것보다 낫다. **로그를 남긴다** — 삭제 경로가 새는 신호다.

---

## 프롬프트

`DietChatStore.loadContext`의 히스토리 조회가 `TEXT`만 본다(함정 4).

```kotlin
fun findByUserAndTypeAndCreatedAtAfterOrderByIdDesc(
    user: User,
    type: ChatMessageType,
    createdAt: LocalDateTime,
    pageable: Pageable,
): List<DietChatMessage>
```

**`DietChatPrompts.historyTurns`는 손대지 않는다.** 들어오는 것이 전부 `TEXT`라 지금 로직이
그대로 맞는다. 방어적으로 카드를 거르는 코드를 넣으면 **두 곳이 같은 규칙을 알게 되어**,
나중에 규칙이 바뀔 때 한쪽만 고쳐질 자리가 된다.

`POST /diet/days/{date}/chat`의 응답과 저장 동작은 **바뀌지 않는다.** 질문·답 두 `TEXT` 행이다.

---

## 인덱스

히스토리 쿼리에 `type` 조건이 붙지만 **인덱스를 바꾸지 않는다.** `idx_diet_chat_user_id(user_id, id)`가
여전히 사용자별 범위를 좁히고, 한 사람의 메시지 수가 인덱스를 더 쪼갤 만큼 많아지지 않는다.

**옛 `idx_diet_chat_user_date`가 아직 환경에 남아 있다.** `ddl-auto: update`가 인덱스를 지우지
않아서다. 어떤 쿼리도 받치지 않으므로 배포 때 `DROP INDEX idx_diet_chat_user_date;`를 손으로
한다 — 이 설계와 무관한 선행 부채이지만 같은 테이블이라 여기 적어 둔다.

---

## 테스트

이 저장소는 MockMvc도 `@SpringBootTest`도 쓰지 않는다. Kotest `BehaviorSpec` + MockK다.

**쓰기**
- 새 끼니를 확정하면 `MEAL_CARD` 한 행이 생긴다
- **합쳐지면 카드를 안 만든다**(함정 2) — `mergeTargetOf`가 기존 끼니를 주는 픽스처
- 총평이 실리면 `DAY_SUMMARY` 한 행이 생긴다
- **같은 날짜에 두 번 안 쌓는다** — `publish`를 두 번 부르고 행이 하나인지
- **마커가 낡아 문장을 버리면 카드도 안 만든다**
- 끼니를 지우면 그 카드 행도 지워진다(함정 3)
- 타입 변경 병합으로 원본이 지워질 때도 그 카드가 지워진다

**조회**
- `MEAL_CARD`가 **현재** 끼니 값을 싣는다 — 확정 뒤 항목을 바꾼 픽스처로, 옛 값이 아니라 새
  값이 나오는지(함정 1이 재현되는 자리)
- 끼니 피드백이 아직 null이면 카드의 `feedback`이 null이다
- `DAY_SUMMARY`가 **현재** 총평을 싣고, 재생성 중(null)이면 null이 나간다
- 사진이 없는 끼니는 `photoUrl`이 null이다
- **끼니 조회가 `IN` 한 번이다** — 카드 세 건짜리 페이지에서 리포지토리 호출 횟수를 센다
  (N+1이 들어오면 빨개진다)
- **점수가 저장된 컬럼이 아니라 재계산 값이다** — 컬럼에 낡은 값을 심은 픽스처로 가른다

**프롬프트**
- 히스토리에 **카드가 안 실린다**(함정 4) — 카드와 `TEXT`가 섞인 픽스처
- 타입 조건이 실제로 리포지토리로 전달된다

---

## 마이그레이션

`ddl-auto: update`라 컬럼이 자동으로 붙는다. **기존 행은 `type`이 기본값 `TEXT`**여야 하므로
컬럼에 `default 'TEXT'`를 준다 — 안 그러면 not null 컬럼 추가가 기존 행에서 실패한다.

**이미 있는 끼니·총평에 대한 카드는 소급해 만들지 않는다.** 만들려면 「그 끼니를 확정한
시각」에 행을 끼워 넣어야 하는데, `id`가 단조 증가라 그 자리에 넣을 수 없다. 타임라인은
**배포 시점부터 쌓인다.**

---

## 범위 밖

- **점수 증감(`▲67`)** — 그 끼니가 하루 점수를 얼마나 올렸는지. 끼니별 기여분을 서버가 갖고
  있지 않아 전후를 따로 계산해야 한다
- **채팅에서 기록하기** — 사진·텍스트로 끼니를 남기는 것. 인식·확인·확정 흐름이 이미 있고,
  채팅에 그 길을 또 내면 두 벌이 된다
- **카드에 대한 답글** — 특정 카드를 인용해 묻기. 앵커 날짜로 충분하다
- **카드 숨기기·삭제** — 사용자가 타임라인에서 카드만 지우는 것
- **활동 에너지·몸무게 카드** — 끼니와 총평 둘로 시작한다
