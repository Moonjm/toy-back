# 같은 날 같은 끼니는 하나로 묶기 (백엔드) 설계

작성일: 2026-07-31
짝 저장소: `woori-haru` (iOS) — **iOS 변경은 없다.** 아래 「iOS는 손대지 않는다」 참조.

## 배경

지금 아침을 두 번 저장하면 **아침 카드가 두 개 생긴다.** `Meal`에 인덱스
`idx_meal_user_date`(user_id, date)만 있고 `meal_type`을 포함한 유니크 제약이 없어
`MealService.confirm`이 매번 새 행을 만든다.

사용자가 실제로 겪은 문제다 — 「아침을 먹다가 하나 더 먹어서 추가했는데 아침이 두 줄이 됐다」.
하루 화면이 아침·점심·저녁·간식 네 줄로 보이는 것이 자연스러운데 지금은 그렇지 않다.

**아침·점심·저녁만 묶는다. 간식은 묶지 않는다.** 앞의 셋은 하루에 하나가 자연스럽지만
간식은 본래 여러 번이다. 오전 과자와 밤 아이스크림을 한 카드에 합치면 끼니 점수가 뒤섞이고,
사진 없는 기록을 열어 둔 이유(「과자 하나를 적으려고 사진을 찍게 만들지 말자」)와도 어긋난다.

## 범위

| | 범위 |
| --- | --- |
| **한다** | `MealService.confirm`이 같은 (user, date, mealType)의 기존 끼니를 찾으면 **거기에 합친다** — 항목 추가·사진 추가·점수 재계산·피드백 재생성 |
| **안 한다** | 이미 만들어진 중복 행의 소급 병합 · 사용자에게 「합칠까요?」 묻기 · 응답으로 「합쳤음」 알리기 · SNACK 병합 |

---

## ⚠️ 먼저 읽을 함정 셋

### 함정 1 — `Meal.replaceItems`는 **전체 교체**다. 그대로 쓰면 병합이 아니라 덮어쓰기다

```kotlin
fun replaceItems(newItems: List<MealItem>) {
    items.clear()
    items.addAll(newItems)
    totalKcal = items.sumOf { it.kcal }
    ...
}
```

`MealService.applyItems`가 이걸 부르고, 확정과 수정이 이 한 곳을 공유한다. 병합에서 새 항목만
넘기면 **기존 항목이 전부 사라진다.**

그렇다고 「기존 + 새 항목」을 만들어 `replaceItems`에 넘기는 것도 안 된다. `items`는
`orphanRemoval = true`라서 `clear()` 순간 기존 엔티티가 삭제 대상으로 표시되고, 같은 인스턴스를
다시 `addAll` 하면 Hibernate가 「삭제된 엔티티를 다시 저장」으로 보고 던진다
(`deleted instance passed to merge` 계열).

**→ `Meal`에 추가 전용 메서드를 새로 만든다.** 기존 행을 건드리지 않는다:

```kotlin
/**
 * 같은 날 같은 끼니에 다시 기록했을 때 **기존 항목 위에 얹는다.**
 * `replaceItems`와 달리 기존 항목을 지우지 않는다 — 지우면 orphanRemoval이 실제 행을
 * 삭제하고, 같은 인스턴스를 다시 넣으면 Hibernate가 던진다.
 */
fun addItems(newItems: List<MealItem>) {
    items.addAll(newItems)
    recalculateTotals()
}
```

`replaceItems`의 합산 부분을 `private fun recalculateTotals()`로 뽑아 둘이 공유한다.

### 함정 2 — `attachPhotos`의 `sortOrder`가 0부터 다시 시작한다

```kotlin
objectMapper.readValue<AnalysisResult>(analysis.resultJson).photos.forEachIndexed { index, photo ->
    fileService.attachFile(photo.fileId, MEAL_FILE_PREFIX)
    meal.addPhoto(MealPhoto(meal = meal, fileId = photo.fileId, sortOrder = index))
}
```

`Meal.photos`는 `@OrderBy("sortOrder asc")`다. 사진 두 장이 있는 아침에 두 장을 더 합치면
sortOrder가 `0,1,0,1`이 되어 **정렬이 뒤섞인다.** iOS `PhotoStrip`이 그 순서로 그린다.

**→ 기존 최대값 다음부터 매긴다:**

```kotlin
val startOrder = (meal.photos.maxOfOrNull { it.sortOrder } ?: -1) + 1
... MealPhoto(meal = meal, fileId = photo.fileId, sortOrder = startOrder + index)
```

새 끼니일 때는 `photos`가 비어 있어 `startOrder = 0`이라 기존 동작과 같다 — 분기하지 않는다.

### 함정 3 — 병합은 **피드백을 다시 만들어야** 한다

`confirm`은 새 끼니라서 `status`가 기본 `PENDING`이고 `runAfterCommit`으로 생성이 걸린다.
병합 대상은 이미 `COMPLETED`일 수 있고, 그러면 **항목이 늘었는데 옛 피드백이 그대로 남는다.**

`updateItems`가 이미 같은 문제를 `meal.markFeedbackPending()`으로 풀고 있다. 병합도 똑같이 한다.

하루 피드백 캐시는 **따로 지우지 않아도 된다.** 무효화 조건이
`generatedAt < 최종 Meal.updatedAt`이고(`DietFeedbackGenerator` 주석 참조), 병합은 기존 `Meal`의
`updatedAt`을 갱신하므로 자동으로 걸린다. `delete()`가 캐시를 직접 지우는 이유는 그 경로에서만
「남은 끼니의 `updatedAt`이 안 바뀌는」 상황이 생기기 때문이고, 병합은 거기 해당하지 않는다.

---

## 해야 할 일

### 1. `MealRepository` — 조회 하나 추가

```kotlin
fun findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(
    user: User, date: LocalDate, mealType: MealType,
): Meal?
```

**처음 적었던 `findByUserAndDateAndMealType(...): Meal?`은 쓸 수 없다.** 유니크 제약이 없어
행이 둘 이상이면 Spring Data가 `IncorrectResultSizeDataAccessException`을 던진다. 그리고 이
문서 6번이 **기존 중복 행을 그대로 두기로** 했으므로 그런 날이 실제로 있다 — 중복이 있는 날
아침을 다시 확정하면 병합은커녕 500이 난다.

`First`를 붙여 하나만 받고, 정렬은 `createdAt asc, id asc`로 둔다. 하루 목표를 읽는
「첫 끼니」(`findByUserAndDateOrderByCreatedAtAscIdAsc`)와 같은 행에 합쳐야 목표 스냅샷과
합쳐진 항목이 같은 끼니에 있게 된다.

### 2. `Meal` — `addItems` 추가, 합산 로직 공유

함정 1 참조. `replaceItems`의 시그니처와 동작은 **바꾸지 않는다** — `updateItems`가 그대로 쓴다.

### 3. `MealService.confirm` — 병합 분기

```kotlin
@Transactional
fun confirm(username: String, request: MealConfirmRequest): Long {
    if (request.items.isEmpty()) throw CustomException(ErrorCode.INVALID_REQUEST, "항목이 비어 있습니다")
    val user = findUser(username)
    val profile = profileService.requireProfile(user)
    val analysis = request.analysisId?.let { confirmableAnalysis(user, it) }

    // 아침·점심·저녁은 하루에 하나다. 간식은 본래 여러 번이라 묶지 않는다.
    val existing =
        if (request.mealType.mergesWithinDay) {
            repository.findByUserAndDateAndMealType(user, request.date, request.mealType)
        } else {
            null
        }

    val meal = existing ?: Meal(user = user, date = request.date, mealType = request.mealType, /* 스냅샷 8개 */ ...)

    if (existing != null) {
        meal.addItems(request.items.map { it.toEntity(meal) })
        meal.applyScore(DietScoreCalculator.scoreMeal(meal.carbsG, meal.proteinG, meal.fatG).score)
        // 항목이 늘었으므로 옛 피드백은 버린다(`updateItems`와 같은 처리).
        meal.markFeedbackPending()
    } else {
        applyItems(meal, request.items)
    }

    analysis?.let { attachPhotos(meal, it) }

    val saved = if (existing != null) meal else repository.save(meal)
    analysis?.let { analysisRepository.delete(it) }
    runAfterCommit { feedbackGenerator.generateForMeal(saved.requiredId) }
    return saved.requiredId
}
```

**스냅샷 8개(`weightKg`·`targetKcal`·`target*`)는 병합할 때 갱신하지 않는다.** 그 끼니를 처음
확정한 시점의 값이 그 끼니를 설명하는 값이다. 아침에 재고 저녁에 다시 잰 몸무게로 아침 점수의
근거가 바뀌면, 「과거 점수는 바뀌지 않는다」는 이 도메인의 약속이 깨진다.

`existing != null`이면 `repository.save`를 부르지 않아도 된다(영속 상태라 더티 체킹으로 반영된다).
불러도 무해하다.

### 4. `MealType`에 판단을 붙인다

```kotlin
enum class MealType {
    BREAKFAST, LUNCH, DINNER, SNACK,
    ;

    /** 하루에 하나인 끼니인가. **간식만 아니다** — 본래 여러 번이라 묶으면 점수가 뒤섞인다. */
    val mergesWithinDay: Boolean get() = this != SNACK
}
```

서비스에 `if (mealType != SNACK)`을 흩어 두지 않는다 — 나중에 규칙이 바뀔 때 찾을 자리가 하나다.

### 5. (선택) 동시성 방어 — 부분 유니크 인덱스

두 요청이 동시에 들어오면 둘 다 `existing == null`을 보고 각각 만들 수 있다. 확정은 사용자가
직접 누르는 동작이라 확률이 낮지만, 막고 싶으면 부분 유니크 인덱스가 답이다:

```sql
CREATE UNIQUE INDEX CONCURRENTLY uk_meal_user_date_type_not_snack
    ON meal (user_id, date, meal_type)
    WHERE meal_type <> 'SNACK';
```

**JPA `@Table(indexes = ...)`로는 표현할 수 없다** — `WHERE` 절이 없다. `ddl-auto: update`가
만들어 주지 않으므로 직접 실행해야 하고, **기존 중복 행이 있으면 생성이 실패한다.** 먼저
중복을 확인한다:

```sql
SELECT user_id, date, meal_type, count(*)
FROM meal WHERE meal_type <> 'SNACK'
GROUP BY 1,2,3 HAVING count(*) > 1;
```

이 방어를 넣지 않기로 해도 된다. **넣지 않는 것이 기본값이다** — 실패 확률과 운영 부담을 견줘
보면 지금은 서비스 레벨 병합만으로 충분하다.

### 6. 이미 만들어진 중복은 그대로 둔다

소급 병합하면 **과거 끼니 점수와 하루 점수가 바뀐다.** 사용자가 이미 본 숫자가 소리 없이
달라지는 것은 이 도메인이 계속 피해 온 실패다. 오늘부터 안 생기게 하는 것으로 충분하고,
남은 중복은 사용자가 직접 지우면 된다.

---

## 테스트

- **아침 두 번 확정 → `Meal`이 하나다.** 항목이 두 벌 다 들어 있고 `totalKcal`·탄단지·
  **당류·나트륨·식이섬유**가 합으로 맞는지. (주의 영양소 3필드는 이 도메인에서 조용히 0이 됐던
  전력이 있다 — 병합 경로에서도 반드시 센다.)
- **간식 두 번 확정 → `Meal`이 둘이다.** 이 테스트가 없으면 「전부 묶기」로 잘못 구현해도 통과한다.
- **날짜가 다르면 안 묶인다** — 같은 아침이어도 어제와 오늘은 별개다.
- **사용자가 다르면 안 묶인다.**
- **`sortOrder`가 이어진다** — 사진 있는 아침에 사진 있는 아침을 합치면 `0,1,2,3`인지.
  함정 2가 실제로 재현되는 자리다.
- **병합하면 `status`가 `PENDING`으로 돌아가고 `feedback`이 `null`이 된다** — 병합 전에
  `COMPLETED`+피드백이 있던 끼니로 고정한다.
- **스냅샷이 안 바뀐다** — 두 번째 확정 전에 프로필 몸무게·목표를 바꿔 두고, 병합 뒤에도
  `weightKg`·`targetKcal`이 **첫 확정 시점 값**인지.
- **점수가 다시 계산된다** — 병합 전후 `score`가 달라지는 항목 조합으로 고정한다.
- 기존 `confirm` 테스트(새 끼니 생성·사진 attach·분석 삭제)가 그대로 통과하는지.

**고의 파손 확인**을 붙이길 권한다: `mergesWithinDay`를 `true` 고정으로 바꾸면 간식 테스트가,
`addItems`를 `replaceItems`로 바꾸면 합산 테스트가 실제로 빨개져야 한다. 이 저장소들이
「구현이 망가져도 통과하는 테스트」로 여러 번 데였다.

---

## 구현 결과 (2026-07-31)

**분기를 하나로 합쳤다.** 설계는 `if (existing != null)`로 갈랐지만, 새 끼니는 `items`가 비어
있어 「얹기」와 「교체」가 같은 결과다. 그래서 세 줄을 무조건 실행한다:

```kotlin
meal.addItems(request.items.map { it.toEntity(meal) })
meal.applyScore(DietScoreCalculator.scoreMeal(meal.carbsG, meal.proteinG, meal.fatG).score)
meal.markFeedbackPending()
```

`markFeedbackPending()`도 새 끼니에는 이미 그 상태라 무해하다. 갈래가 없으면 「한쪽만 고친」
사고가 안 난다. 저장만 갈린다 — `val saved = existing ?: repository.save(meal)`.

`MealService.applyItems`는 이제 `updateItems`만 쓰므로 `private`으로 좁혔다.

### 고의 파손 확인 — 넷 다 빨개진다

| 파손 | 결과 |
| --- | --- |
| `mergesWithinDay`를 `true` 고정 | 1건 실패 (간식 병합 안 함) |
| `addItems` → `replaceItems` | **4건 실패** (항목·합계·주의 영양소·점수) |
| `sortOrder = startOrder + index` → `index` | 1건 실패 |
| `markFeedbackPending()` 제거 | 1건 실패 |

312건 통과(기존 306 + 신규 6).

### 단위 테스트의 한계

「날짜가 다르면 안 묶인다」·「사용자가 다르면 안 묶인다」는 리포지토리를 목으로 대체한
단위 테스트로는 실제 필터링을 확인할 수 없다. **조회 인자가 (요청 사용자, 요청 날짜,
요청 끼니 종류)인지**만 고정했고, 실제 `WHERE`는 파생 쿼리 이름이 만든다 —
이름이 잘못되면 컨텍스트 기동에서 깨진다. 배포 후 실기동으로 확인할 자리다.

### 5번(부분 유니크 인덱스)은 넣지 않았다

문서의 기본값을 따랐고 `AGENTS.md`의 「동시성 방어 없음」과도 맞는다. 확정은 사용자가 직접
누르는 동작이고 단일 인스턴스·사용자 2명이다.

## iOS는 손대지 않는다

계약이 그대로다 — `POST /diet/meals`는 병합했을 때도 **201 + `Location: /diet/meals/{id}`**를
돌려주고, `{id}`는 합쳐진 기존 끼니의 것이다. iOS `DietService.confirmMeal`은 `Location`에서
id만 읽고, 저장 뒤 하루 화면을 다시 조회하므로 합쳐진 결과가 그대로 보인다.

엄밀히 말하면 아무것도 생성하지 않았는데 201을 주는 것이라 HTTP 의미와는 어긋난다. 200으로
바꾸면 iOS `postCreated`가 201만 받도록 돼 있어 앱이 깨진다. **의도적으로 201을 유지한다** —
이 API의 유일한 소비자가 우리 앱이고, 시맨틱을 맞추자고 클라이언트를 깨뜨릴 이유가 없다.

**iOS 후속 후보(이 작업 밖):** 확인 화면에서 끼니 종류를 고를 때 이미 그날 기록이 있으면
「이미 기록한 아침에 합쳐집니다」를 한 줄 띄우는 것. 없어도 동작에 문제는 없지만, 저장 버튼을
누르기 전에 무슨 일이 일어날지 알려 주는 편이 낫다. 이건 `GET /diet/days/{date}`가 이미 주는
정보로 만들 수 있어 백엔드 작업이 아니다.

## 범위 밖

기존 중복 행 소급 병합 · 병합 여부를 응답에 싣기 · 사용자 확인 프롬프트 · 간식 병합 ·
「합친 기록을 다시 떼어내기」.
