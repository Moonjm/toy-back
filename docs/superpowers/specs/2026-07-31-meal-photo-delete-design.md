# 끼니 사진 한 장 삭제 (백엔드) 설계

작성일: 2026-07-31
짝 저장소: `woori-haru` (iOS) — **이 API가 들어온 뒤에 앱 작업을 한다.** 아래 「iOS가 할 일」 참조.

## 배경

끼니 상세에서 사진을 잘못 찍었거나 엉뚱한 사진이 섞였을 때 **그 한 장만 지울 방법이 없다.**
지금 있는 것은 `DELETE /diet/meals/{id}`(끼니 통째 삭제)뿐이라, 사진 한 장 때문에 끼니를 지우고
항목을 처음부터 다시 넣어야 한다.

`PUT /diet/meals/{id}/items`는 **항목만** 교체한다. 사진(`MealPhoto`)은 별개 테이블이라 그 경로로
못 건드린다. 사진 단위 엔드포인트가 아예 없다.

## 계약

```
DELETE /diet/meals/{mealId}/photos/{fileId}  →  204 No Content
```

- 남의 끼니면 `RESOURCE_NOT_FOUND`(`requireOwned`가 이미 그렇게 한다 — 403이 아니라 404다.
  존재 여부를 알려 주지 않는 기존 방침을 따른다)
- 그 끼니에 없는 `fileId`면 `RESOURCE_NOT_FOUND`
- 성공하면 본문 없음. 앱은 끼니를 다시 조회해 화면을 갱신한다(`GET /diet/meals/{id}`)

`fileId`로 지운다. `MealPhoto`에 별도 id가 있지만 앱이 이미 `fileId`로 사진을 식별하고 있고
(`MealPhotoResponse.fileId`), 한 끼니 안에서 유일하다.

---

## ⚠️ 먼저 읽을 함정 셋

### 함정 1 — 파일을 **물리 삭제하면 안 된다**

`MealService.delete`가 이미 그 방식이고, 파일 주석에 이유가 적혀 있다:

```kotlin
fileService.detachFiles(meal.photos.map { it.fileId })
```

`detach`는 상태를 `TEMP`로 되돌리기만 하고 S3 객체는 매일 04:00 정리 배치가 수거한다.
**트랜잭션이 롤백되면 상태 변경도 함께 되돌아가므로** 「레코드는 살아났는데 객체는 사라진」
상태가 생기지 않는다. `s3Client.deleteObject`를 직접 부르면 그 보장이 깨진다.

한 장짜리도 같은 함수를 쓴다 — `detachFiles(listOf(fileId))`.

### 함정 2 — `photos`는 `orphanRemoval = true`다. **컬렉션에서 빼는 것으로 지운다**

```kotlin
@OneToMany(mappedBy = "meal", cascade = [CascadeType.ALL], orphanRemoval = true)
@OrderBy("sortOrder asc")
var photos: MutableList<MealPhoto> = mutableListOf()
```

`mealPhotoRepository.delete(photo)`를 따로 부르면 안 된다 — 영속성 컨텍스트의 `meal.photos`에는
그 인스턴스가 남아 있어, 같은 트랜잭션에서 `meal`을 flush 할 때 되살아나거나
`deleted instance passed to merge` 계열로 터진다.

**→ `Meal`에 제거 메서드를 만들어 컬렉션에서 뺀다:**

```kotlin
/**
 * 사진 한 장을 뺀다. **`orphanRemoval`이 실제 행을 지우므로 리포지토리를 따로 부르지 않는다.**
 * 없는 `fileId`면 아무것도 하지 않고 false를 돌려준다 — 호출부가 404로 옮긴다.
 */
fun removePhoto(fileId: Long): Boolean = photos.removeIf { it.fileId == fileId }
```

`addPhoto`가 이미 옆에 있으니 짝으로 둔다.

### 함정 3 — 점수·피드백을 **다시 만들지 않는다**

`updateItems`는 `markFeedbackPending()` + `generateForMeal`을 부르지만, **사진 삭제는 그러면 안 된다.**

- 끼니 점수는 `DietScoreCalculator.scoreMeal(carbsG, proteinG, fatG)` — 항목에서만 나온다
- 피드백 프롬프트(`DietFeedbackPrompts.meal`)에도 사진이 안 들어간다 — 항목·합계·균형 근거뿐이다

사진이 줄어도 **먹은 것은 그대로**다. 재생성을 걸면 같은 내용의 문장을 다시 만들면서 LLM
비용만 나간다. 하루 피드백 캐시도 건드리지 않는다(구성이 안 바뀌었다).

---

## 해야 할 일

### 1. `Meal` — `removePhoto(fileId:)` 추가

함정 2 참조. `addPhoto` 바로 아래에 둔다.

### 2. `MealService` — 삭제 메서드

```kotlin
/**
 * 사진 한 장 삭제. **항목·점수·피드백은 건드리지 않는다** — 사진이 줄어도 먹은 것은 그대로다.
 * 파일은 물리 삭제하지 않고 detach 한다(`delete`와 같은 이유).
 */
@Transactional
fun deletePhoto(
    username: String,
    mealId: Long,
    fileId: Long,
) {
    val meal = requireOwned(findUser(username), mealId)
    if (!meal.removePhoto(fileId)) throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, fileId)
    fileService.detachFiles(listOf(fileId))
}
```

**`sortOrder`는 다시 매기지 않는다.** 0,1,2에서 1을 빼면 0,2가 남는데 `@OrderBy("sortOrder asc")`라
순서는 그대로 맞다. 오히려 구멍을 남기는 편이 낫다 — 끼니 병합이 사진을 이어 붙일 때
`maxOfOrNull { it.sortOrder } + 1`을 쓰므로(`2026-07-31-meal-merge-by-type-design.md` 함정 2),
다시 매기면 그쪽과 값이 겹칠 여지가 생긴다.

### 3. `MealController` — 엔드포인트

```kotlin
@DeleteMapping("/{mealId}/photos/{fileId}")
@Operation(summary = "사진 한 장 삭제 — detach 후 정리 배치가 수거한다. 점수·피드백은 그대로다")
fun deletePhoto(
    @PathVariable mealId: Long,
    @PathVariable fileId: Long,
    authentication: Authentication,
): ResponseEntity<Void> {
    service.deletePhoto(authentication.name, mealId, fileId)
    return ResponseEntity.noContent().build()
}
```

기존 `@DeleteMapping("/{id}")`와 경로가 겹치지 않는다(세그먼트 수가 다르다).

### 4. 마지막 한 장을 지우는 경우 — **막지 않는다**

사진 0장인 끼니는 **정상 상태다.** 사진 없는 기록(`analysisId == null`)이 이미 그렇게 저장되고,
`MealResponse.photos`가 빈 배열이어도 앱이 깨지지 않는다(`PhotoStrip`이 아무것도 안 그린다).

항목까지 비는 일은 이 경로로 안 생긴다 — 항목은 `PUT items`가 빈 배열을 이미 거절한다.

---

## 테스트

- **사진 한 장을 지우면 그 행만 사라진다** — 3장 중 가운데를 지우고 남은 둘의 `fileId`를 확인
- **`detachFiles`가 그 `fileId` 하나로만 불린다** — 나머지 사진까지 detach 하면 멀쩡한 사진이
  정리 배치에 수거된다. 이 확인이 없으면 「전부 detach」로 잘못 짜도 통과한다
- **`sortOrder`가 다시 매겨지지 않는다** — 0,1,2에서 1을 지우면 0,2가 남는지
- **점수·`status`·`feedback`이 그대로다** — 삭제 전에 `COMPLETED`+피드백이 있던 끼니로 고정한다.
  이 테스트가 없으면 `updateItems`를 흉내 내 `markFeedbackPending()`을 넣어도 통과한다
- **`generateForMeal`이 불리지 않는다** — 목으로 확인. 유료 호출이 새는 자리다
- **남의 끼니면 404**
- **그 끼니에 없는 `fileId`면 404** — 다른 끼니의 `fileId`를 넣어도 지워지지 않는지
- **마지막 한 장을 지워도 성공하고 끼니는 남는다**

**고의 파손 확인**을 권한다: `removePhoto`가 항상 true를 돌려주게 바꾸면 「없는 fileId면 404」가,
`detachFiles(listOf(fileId))`를 `detachFiles(meal.photos.map { it.fileId })`로 바꾸면 detach 범위
테스트가 실제로 빨개져야 한다. 이 저장소들이 「구현이 망가져도 통과하는 테스트」로 여러 번 데였다.

---

## iOS가 할 일 (이 API가 들어온 뒤)

앱은 아직 아무것도 안 만들어 뒀다. 들어올 것:

- `DietServing`에 `deleteMealPhoto(mealId:fileId:)` 추가 → `DELETE /diet/meals/{mealId}/photos/{fileId}`
- `MealDetailViewModel`에 삭제 메서드. **`replaceItems`와 같은 가드가 필요하다** — `isSaving`으로
  중복 요청을 막고, `isStale`이면 받지 않는다(낡은 화면에서 지우면 엉뚱한 사진을 가리킨다)
- 전체화면 뷰어(`PhotoViewerSheet`)에 삭제 버튼. **되돌릴 수 없으므로 확인 알럿을 단다** —
  끼니 삭제가 이미 그렇게 한다
- 삭제 성공 시 뷰어를 닫고 끼니를 다시 조회한다

이 목록은 참고용이고, 실제 작업은 API가 배포된 뒤 `woori-haru`에서 따로 한다.

## 범위 밖

사진 추가(기존 끼니에 새 사진 붙이기) · 사진 순서 바꾸기 · 사진 교체 · 되돌리기(휴지통).
