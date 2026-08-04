# 저장된 끼니의 타입 수정 (백엔드) 설계

작성일: 2026-08-04
짝 저장소: `woori-haru` (iOS) — 앱 설계는 그쪽
`docs/superpowers/specs/2026-08-04-meal-type-edit-design.md`. **서버가 먼저 나가야 한다.**

## 배경

**저녁을 먹고 간식으로 저장하면 되돌릴 길이 없다.** `/diet/meals`에 있는 것은 확정(`POST`)·
조회(`GET`)·항목 교체(`PUT /{id}/items`)·삭제(`DELETE`)뿐이다.

앱에서 할 수 있는 것은 끼니를 통째로 지우고 다시 만드는 것뿐인데, **앱에 사진 바이트가 없어
사진을 다시 올릴 수 없다.** 찍어 둔 사진이 사라진다.

`Meal.mealType`은 이미 `var`이고 유니크 제약도 없다(인덱스는 `idx_meal_user_date`). **스키마
변경은 없다.**

## 범위

| | 범위 |
| --- | --- |
| **한다** | `PATCH /diet/meals/{id}`로 `mealType` 변경 · 대상 타입에 기존 끼니가 있으면 **항목·사진을 옮기고 원본을 지운다** · 점수 재계산 · 끼니 피드백 재생성 · 하루 캐시 무효화 |
| **안 한다** | 날짜 변경 · 합친 것 되돌리기 · 여러 끼니 한 번에 옮기기 · 「합칠까요?」 되묻기(앱이 미리 묻는다) |

---

## ⚠️ 먼저 읽을 함정 넷

### 함정 1 — 항목·사진을 **옮기면 안 된다. 베껴 붙이고 원본을 통째로 지운다**

`Meal.items`와 `Meal.photos`가 둘 다 `orphanRemoval = true`다. 원본 끼니의 컬렉션에서 빼는
순간 **그 행이 삭제 대상으로 표시된다.** 같은 인스턴스를 대상 끼니에 붙이면 Hibernate가
「삭제된 엔티티를 다시 저장」으로 보고 던진다.

이것은 `2026-07-31-meal-merge-by-type-design.md`의 함정 1과 같은 부류다. 그때는 `replaceItems`
대신 추가 전용 `addItems`를 새로 만들어 풀었다.

**→ 새 `MealItem`·`MealPhoto`를 만들어 대상에 붙이고, 원본 `Meal`을 `repository.delete`로
지운다.** cascade가 원본의 자식 행을 함께 지운다. 어차피 지울 것이라 「옮기기」로 볼 이유가 없다.

`meal_photo`에는 `file_id` 유니크 제약이 없다(인덱스는 `meal_id`뿐). 같은 `fileId`를 가리키는
행이 한 트랜잭션 안에 잠깐 둘이어도 문제없다.

### 함정 2 — **`delete`를 재사용하면 옮겨 붙인 사진이 며칠 뒤에 죽는다**

`MealService.delete`는 행을 지우기 전에 이렇게 한다:

```kotlin
fileService.detachFiles(meal.photos.map { it.fileId })
```

`detach()`는 파일 상태를 `TEMP`로 되돌리고, **매일 04:00 정리 배치가 그 객체를 수거한다.**
병합에서 이 경로를 재사용하면 방금 대상 끼니에 붙인 사진의 `fileId`가 함께 `TEMP`로 돌아가고,
**TTL이 지나면 S3 객체가 사라진다.** 화면에는 그날 멀쩡히 보이다가 며칠 뒤 깨진다.

되돌릴 수도 없다 — `FileService.attachFile`이 detach된 파일의 재연결을 **거부한다**
(「연결이 해제된 파일은 재사용할 수 없습니다」). 경로가 이미 영구 경로라 다시 붙일 수 없다.

**→ 병합 삭제에서는 `detachFiles`를 부르지 않는다.** 파일의 소유가 원본에서 대상으로 넘어간
것이지 쓰이지 않게 된 것이 아니다.

### 함정 3 — **항목이 안 바뀌어도 `contentUpdatedAt`을 올려야 한다**

`Meal.contentUpdatedAt`은 하루 피드백 캐시의 무효화 기준이고, 주석이 「항목·합계가 바뀔 때만
오른다」고 못 박아 뒀다. 사진 추가·삭제조차 반영하지 않는다 — **하루 프롬프트에 사진이 들어가지
않기 때문이다.**

그런데 **끼니 타입은 하루 프롬프트에 들어간다:**

```kotlin
"- ${meal.mealType}: ${meal.items.joinToString(", ") { it.foodName }} "
```

타입만 바꾸는 갈래(아래 ②)는 항목도 합계도 안 바뀌므로 그냥 두면 `contentUpdatedAt`이 그대로다.
**하루 피드백이 「간식: 치킨」이라고 쓴 채로 남는다.**

**→ 타입이 실제로 바뀌면 `contentUpdatedAt`을 올린다.** 「사진은 반영하지 않는다」와 대비되는
지점이라 헷갈리기 쉽다 — **판단 기준은 「항목이 바뀌었나」가 아니라 「하루 프롬프트가 읽는
값이 바뀌었나」다.**

### 함정 4 — **방향에 따라 동작이 다르다**

`MealType.mergesWithinDay`가 간식만 `false`다.

| 바꾸는 방향 | 그날 대상 타입이 이미 있으면 |
| --- | --- |
| 간식 → 저녁 | **합쳐진다** |
| 저녁 → 간식 | **안 합쳐진다** (간식 카드가 둘이 된다) |

「오전 과자와 밤 아이스크림을 한 카드에 합치면 끼니 점수가 뒤섞인다」는 기존 판단
(`2026-07-31-meal-merge-by-type-design.md`) 그대로다. **합치기를 대칭으로 생각하면 틀린 코드를
쓴다.**

---

## API

### `PATCH /diet/meals/{id}`

```json
{ "mealType": "DINNER" }
```

응답:

```
200 OK
Location: /diet/meals/42
```

`Location`이 가리키는 것은 **살아남은 끼니**다. 합쳐졌으면 대상 끼니, 아니면 요청한 id 그대로다.
**본문은 비운다.**

**본문이 아니라 헤더인 이유:** 확정(`confirm`)·분석 생성·사진 업로드가 이미
`@ResponseCreated("/diet/meals/{id}")`로 `Location`에 id를 싣고, iOS의 `APIClient.postCreated`가
그것을 읽는다. 본문 봉투(`DataResponseBody`)는 데이터를 돌려줄 때만 쓴다 — id 하나를 위해 새 응답
DTO를 만들 이유가 없다.

의미도 이쪽이 맞는다. 「이 요청의 결과로 볼 리소스는 여기」라, **원본이 사라진 상황**에 특히
어울린다.

> **정할 것:** `@ResponseCreated`는 이름이 「만들었다」인데 `PATCH`는 만들지 않는다. 다만
> `confirm`도 이미 `ResponseEntity.ok`(200)로 쓰고 있어 생성 전용은 아니다. 이름을 그대로 쓸지
> `@ResponseLocation` 같은 것을 새로 둘지는 이 저장소가 정한다 — **동작은 같다.**

소유권은 기존 `requireOwned`로 검사한다. 없는 id는 `RESOURCE_NOT_FOUND`(404).

---

## 세 갈래

### ① 요청 타입이 지금과 같다

아무것도 하지 않고 요청한 id를 돌려준다. **피드백을 다시 만들지 않는다** — 앱이 실수로 같은
값을 보내도 유료 호출이 나가면 안 된다. `contentUpdatedAt`도 건드리지 않는다.

### ② 합칠 대상이 없다

`mealType`만 바꾸고 **`contentUpdatedAt`을 올린다**(함정 3). 대상 타입이 간식이면 항상 여기다.

점수는 그대로 둔다 — `DietScoreCalculator`는 `mealType`을 쓰지 않는다. **끼니 피드백은 다시
만든다** — `DietFeedbackPrompts.meal`이 `[이번 끼니] ${meal.mealType}`을 읽는다.

### ③ 합칠 대상이 있다

대상 타입이 `mergesWithinDay`이고 같은 날 그 타입 끼니가 이미 있는 경우다. 대상은 확정 저장과
**같은 기준**으로 고른다:

```kotlin
repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(user, date, mealType)
```

1. 원본의 항목을 새 `MealItem`으로 만들어 대상에 `addItems`(함정 1)
2. 원본의 사진을 새 `MealPhoto`로 만들어 대상에 `addPhoto` — **`sortOrder`는 대상의 최대값
   다음부터**(아래)
3. 대상의 점수를 다시 계산하고 `contentUpdatedAt`을 올린다
4. **`detachFiles` 없이** 원본을 `repository.delete`(함정 2)
5. 대상의 끼니 피드백을 다시 만든다

### 사진 `sortOrder`

**대상의 최대값 다음부터 매긴다.** 확정 저장의 병합이 이미 그렇게 하고 있고
(`MealService.attachPhotos`), 0부터 다시 매기면 `Meal.photos`의 `@OrderBy("sortOrder asc")` 때문에
`0,1,0,1`이 되어 **앱의 사진 순서가 뒤섞인다.**

### 하루 피드백 캐시

**따로 지우지 않는다.** ②는 `contentUpdatedAt`을 올리고 ③은 대상의 항목이 늘며 함께 오르므로,
무효화 조건(`generatedAt < 최종 contentUpdatedAt`)에 그대로 걸린다.

`delete`가 `dailyFeedbackRepository.deleteByUserAndDate`를 명시적으로 부르는 것은 **남는 끼니가
아무것도 안 바뀌는** 경우라서다. 여기는 항상 무언가 바뀐다.

### 끼니 피드백 재생성

확정 저장·항목 교체와 같다:

```kotlin
meal.markFeedbackPending()
runAfterCommit { feedbackGenerator.generateForMeal(살아남은 id) }
```

앱은 기존 폴링으로 받는다.

---

## 테스트

- **① 같은 타입이면 아무 일도 없다** — 피드백 생성이 안 걸리고 `contentUpdatedAt`이 그대로다
- **② 합칠 대상이 없으면 타입만 바뀐다** — 항목·점수는 그대로, `contentUpdatedAt`은 오른다
- **③ 합치면 항목이 대상으로 간다** — 대상의 항목 수와 합계가 늘고 원본이 사라진다
- **③ 합치면 사진도 간다** — 대상의 사진 수가 늘고 **`sortOrder`가 이어진다**(0,1,0,1이 아니다)
- **③ 합쳐도 파일이 detach 되지 않는다**(함정 2) — 옮겨 붙인 `fileId`의 `FileStatus`가 그대로다
- **저녁 → 간식은 합치지 않는다**(함정 4) — 같은 날 간식이 있어도 둘 다 남는다
- **남의 끼니는 못 바꾼다** — `requireOwned`
- **없는 id는 404**

---

## 범위 밖

- **날짜 변경** — 하루 집계·점수·피드백이 이틀에 걸쳐 다시 계산돼야 해서 훨씬 크다
- **합친 것 되돌리기** — 어느 항목이 어느 끼니에서 왔는지 기록하지 않는다
- **이미 만들어진 중복 행의 소급 정리**
