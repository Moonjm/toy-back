# 식단 사진 분석·점수·피드백 (백엔드) 설계

작성일: 2026-07-27
모듈: `apps/daily-record`
짝 문서: `woori-haru/docs/superpowers/specs/2026-07-27-diet-tracking-ios-design.md`

> **2026-07-28 개정** — 끼니당 사진 여러 장을 허용하고, 인식 결과를 사용자가 확인·수정한 뒤
> 저장하는 흐름으로 바꿨다. **iOS 짝 문서는 아직 개정 전이다** — 단일 사진과 확인 단계 없는
> 흐름(`POST /diet/meals {fileId}` → 폴링 → 완료)을 전제하고 있어, 다중 선택·확인 화면·
> `analyses` 폴링을 반영한 개정이 필요하다.
>
> **2026-07-29 개정** — ① 매일 재는 몸무게를 반영했다. 몸무게 전용 갱신 엔드포인트를 두고,
> 확정 시점의 몸무게·목표를 `Meal`에 스냅샷으로 남겨 **하루 점수도 소급 변경되지 않게** 했다.
> ② OpenRouter 연동을 API 키가 있을 때만 빈으로 등록하도록 바꿔, 키 없이 로컬 실행이 된다.
> ③ `가공식품` 데이터셋을 함께 적재하되 **완전일치에만 참여**시켰다(과자·음료는 포장 사진에서
> 브랜드명이 읽힌다). ④ 하루 마감 피드백을 **`@Async`로 뺐다** — 조회를 60초 붙잡지 않는다.
>
> **iOS 짝 문서에 반영해야 할 것** — 다중 선택·확인 화면·`analyses` 폴링(2026-07-28 개정),
> 몸무게 전용 엔드포인트 `PUT /diet/profile/weight`, 키 없는 환경의 503(`LLM_UNAVAILABLE`),
> 그리고 **`GET /diet/days/{date}`의 `feedback`도 폴링 대상**이라는 것.

## 배경

식사 사진을 올리면 음식을 인식해 탄단지를 산출하고, 개인 목표 대비 점수와 개선 피드백을
돌려주는 기능을 만든다. iOS 앱은 사진 업로드와 조회만 담당하고, 인식·계산·피드백 생성은
전부 서버가 한다. OpenRouter를 LLM 게이트웨이로 쓴다.

**LLM에게 영양소 수치나 점수를 직접 물어보지 않는다.** Vision 모델은 음식 식별에는 쓸 만하지만
중량·영양소 수치는 같은 사진에서도 호출마다 달라진다. LLM은 *음식 식별*과 *문장 생성*만 맡고,
영양소는 식품DB 조회로, 점수는 룰 기반 계산으로 구한다. 그래야 값이 결정적이고 단위 테스트가
가능하며 사용자가 점수를 신뢰할 수 있다.

## 범위

본인이 올린 식단만 본인이 조회한다. 배우자 공유는 이번 범위에서 제외한다.
기존 `dailyrecords`·`daily-overeats` 도메인과는 연동하지 않고 독립적으로 동작한다.

## 결정 사항

| 항목 | 결정 | 이유 |
| --- | --- | --- |
| 영양소 출처 | 식품DB 조회 (실패 시 LLM 추정 fallback) | LLM 수치는 재현성이 없다 |
| 점수 | 룰 기반 계산, 기준은 KDRIs 에너지적정비율 | 결정적·테스트 가능하고 **근거를 사용자에게 보여줄 수 있다** |
| LLM 호출 | 사진당 1회(이미지) + 확정 시 1회(텍스트 피드백) | 사진마다 따로 인식하고, 피드백은 사용자가 확정한 수치가 필요하다 |
| 끼니 피드백의 범위 | 그 끼니만 본다(하루 맥락 없음) | 끼니끼리 독립이라야 앞 끼니를 고쳐도 뒤 끼니 조언이 낡지 않는다 |
| 끼니당 사진 | 여러 장 (최대 5장) | 상을 나눠 찍거나 음식별로 따로 찍는다 |
| 저장 시점 | 인식 결과를 사용자가 확인·수정한 뒤 확정 | 중복·오인식을 사람이 거른다. 서버가 음식명만으로 판단할 수 없다 |
| 사진 저장 | `common-file` 재사용 (MinIO) | `family-tree`에서 검증된 경로. 새로 만들 게 없다 |
| 사진 접근 제어 | presigned URL (10분) | `FileService`가 이미 제공한다 |
| 분석 실행 | `@Async` (큐 없음) | 사용자 2명·하루 수십 건. 큐는 과하다 |
| 하루 피드백 | lazy 생성 + 캐시 무효화, **생성은 `@Async`** | 크론 불필요, 안 보는 날 LLM 비용이 안 든다. 조회를 60초 붙잡지 않는다 |
| 활동 에너지 | 표시·피드백 맥락으로만 사용, 목표에는 반영하지 않음 | 목표가 매일 흔들리면 점수를 설명할 수 없다 |
| 몸무게 갱신 | 전용 엔드포인트로 매일 갱신, 확정 시점 값을 `Meal`에 스냅샷 | 매일 재는 값이라 프로필 전체를 다시 보내게 하면 안 된다 |
| 하루 점수 기준 | 그날 첫 `Meal`의 스냅샷 목표 | 현재 프로필로 계산하면 어제 점수가 오늘 몸무게에 흔들린다 |
| LLM 빈 등록 | `openrouter.api-key`가 있을 때만 등록 | 키 없이 로컬을 띄워도 나머지 기능이 다 돌아야 한다 |

## 도메인 모델

패키지는 `com.toy.backend.diet.*` (`ledger` 패턴). 앱 전용 에러 코드는 `DietErrorCode` enum.

### `NutritionProfile` — 사용자당 1개

`userId`, `heightCm`, `weightKg`, `activityLevel`, `goal`,
그리고 서버가 계산해 저장하는 `targetKcal`·`targetCarbsG`·`targetProteinG`·`targetFatG`.

나이·성별은 `common-auth`의 `User.birthDate`·`gender`를 재사용한다. 둘 중 하나라도 없으면
BMR을 계산할 수 없으므로 프로필 저장을 `INVALID_REQUEST`로 거절한다.

**목표치를 계산해서 저장하는 이유** — 몸무게를 갱신했을 때 과거 점수의 기준이 소급 변경되면
안 된다. 점수는 `Meal`에 확정값으로 남고, 프로필은 현재 목표만 들고 있는다.

**몸무게는 매일 갱신된다.** 그래서 `PUT /diet/profile/weight {weightKg}`를 따로 둔다. 키·활동량·
목표는 몇 달에 한 번 바뀌는 값인데 이걸 매일 함께 보내게 하면 클라이언트가 낡은 값을 되돌려
쓰는 사고가 난다. 이 엔드포인트는 `weightKg`만 갱신하고 목표 4개를 즉시 재계산한다.

**몸무게 이력 테이블은 만들지 않는다.** 확정 시점의 몸무게·목표를 `Meal`에 스냅샷으로 남기므로
"그날 몇 kg 기준으로 채점됐는지"는 이미 남는다. 식사를 기록하지 않은 날의 몸무게는 이 도메인이
쓸 데가 없다 — 체중 추이 그래프가 필요해지면 그때 별도로 만든다.

### `Meal` — 확정된 끼니 1건

`userId`, `date`, `mealType`, `status`, `score`, `totalKcal`,
`carbsG`·`proteinG`·`fatG`, `feedback`, `photos`(OneToMany), `items`(OneToMany),
그리고 확정 시점 스냅샷 `weightKg`·`targetKcal`·`targetCarbsG`·`targetProteinG`·`targetFatG`.

**스냅샷을 끼니마다 복사해 두는 이유** — 하루 점수는 목표 대비 총량이라 목표가 필요한데,
조회 시점의 프로필을 쓰면 오늘 몸무게를 갱신했을 때 지난주 하루 점수가 함께 바뀐다. 그날
첫 `Meal`의 스냅샷을 기준으로 삼으면 계산이 언제 돌아도 같은 값이 나온다. 끼니 점수는
KDRIs 비율만 보므로 스냅샷과 무관하지만, 같은 행에 있어야 "이 끼니는 몇 kg 기준이었나"를
설명할 수 있다.

**`Meal`은 사용자가 확인·확정한 것만 존재한다.** 인식만 되고 확정되지 않은 결과는
`MealAnalysis`에 있고 `Meal`이 되지 않는다. 덕분에 하루 집계·점수·음식 빈도 쿼리에
"확정된 것만" 조건을 붙일 필요가 없다.

`status`는 **피드백 생성 상태**다(`PENDING → COMPLETED`/`FAILED`). 확정 시점에 점수는
동기로 계산되고 피드백만 뒤에서 생성되므로, iOS는 이 값으로 피드백 도착을 폴링한다.

### `MealPhoto` — 끼니에 딸린 사진

`mealId`, `fileId`, `sortOrder`.

**끼니당 최대 5장으로 제한한다.** 사진마다 이미지 LLM을 호출하므로 장수가 곧 비용·지연이고,
라즈베리파이에서 여러 장을 base64로 동시에 들고 있는 것도 부담이다. 초과 시 `INVALID_REQUEST`.

### `MealItem` — 끼니 안의 개별 음식

`foodName`(정규화된 이름), `foodCode`(식품DB 코드, nullable), `quantityG`,
`kcal`, `carbsG`·`proteinG`·`fatG`, `source`.

**`MealItem`을 별도 테이블로 쪼개는 게 이 설계의 핵심이다.** 영양소를 `Meal`에 뭉쳐 저장하면
① 음식별 빈도 집계("이번 주 제육볶음 3회")가 불가능하고 ② 인식이 틀렸을 때 항목 단위로
수정·재계산할 수 없다. 두 기능 모두 이 도메인의 존재 이유에 해당한다.

### `MealAnalysis` — 확정 전 인식 결과 (임시)

`userId`, `status`, `resultJson`, `createdAt`.

사진 목록·인식 결과·사진별 실패 여부를 `resultJson` 한 컬럼에 담는다.

```
{photos: [{fileId, failed, items: [{name, foodCode, quantityG, kcal, carbsG, proteinG, fatG, source}]}]}
```

**자식 테이블로 쪼개지 않는다.** 확인 전 임시 데이터라 이걸로 질의할 일이 없고, 확정되면
`MealItem`으로 옮겨가며 통째로 버려진다. 반면 `MealItem`을 테이블로 쪼갠 이유(음식별 빈도
집계·항목 단위 수정)는 확정된 데이터에만 해당한다.

확정되면 삭제하고, 확인하지 않고 버려진 것은 TTL 24시간 배치가 지운다.

### `DailyDietFeedback` — 하루 마감 피드백 캐시

`userId`, `date`, `dayScore`, `feedback`, `generatedAt`.

하루 집계값은 테이블로 만들지 않고 `Meal` 합산으로 구한다. 이 엔티티는 LLM 호출 결과를
재사용하기 위한 캐시일 뿐이다.

### `DailyActivity` — 하루 활동 에너지

`userId`, `date`, `activeEnergyKcal`. iOS가 HealthKit에서 읽어 올린 값을 upsert 한다.

### `Food` — 식품 영양 성분

`code`, `name`, `normalizedName`, `dataset`, `servingSizeG`,
`kcalPer100g`·`carbsPer100g`·`proteinPer100g`·`fatPer100g`.

`dataset`(`DISH`/`PROCESSED`)은 매칭 규칙을 가르는 축이다 — 「식품DB」 절 참조.

### 수치 타입

영양소·몸무게·키는 `Double`, kcal 합계도 `Double`, 목표치와 점수만 `Int`다.
AGENTS.md의 `BigDecimal` 관례는 **금액**에 대한 것이고, 영양소는 원본(식품DB 100g당 값)부터
소수이며 점수 계산이 전부 실수 연산이라 `BigDecimal`로 들고 다니면 변환만 늘어난다.
목표치(`targetKcal` 등)와 점수는 사용자에게 보이는 확정값이라 계산 끝에 반올림해 `Int`로 저장한다.

### enum 컬럼

`MealType`(BREAKFAST/LUNCH/DINNER/SNACK), `AnalysisStatus`(PENDING/COMPLETED/FAILED),
`ActivityLevel`(SEDENTARY/LIGHT/MODERATE/ACTIVE/VERY_ACTIVE), `DietGoal`(LOSE/MAINTAIN/GAIN),
`NutritionSource`(DB_MATCHED/LLM_ESTIMATED), `FoodDataset`(DISH/PROCESSED).

`AnalysisStatus`는 `MealAnalysis`(인식 진행 상태)와 `Meal`(피드백 생성 상태) 양쪽에서 쓴다.
값 집합과 전이 모양이 같아 따로 만들 이유가 없다.

**AGENTS.md의 `columnDefinition` 관례는 실제로는 CHECK 제약을 막지 못한다.** 2026-07-28에
실측했다 — `columnDefinition = "varchar(20)"`을 명시하고 테이블을 드롭 후 새로 생성시켜도
`files_status_check`가 그대로 붙었고, 이미 관례를 따르고 있는 `ledger_entries`의 `type`·`source`에도
제약이 살아 있다. 즉 **이 도메인의 enum 5개에 값을 나중에 추가하면 기존 DB에서 INSERT가 깨진다.**

**이 설계는 ②를 전제로 간다** — 제약이 생기는 것을 막지 못한다고 보고, enum 값을 추가할 때
`ALTER TABLE ... DROP CONSTRAINT <표>_<컬럼>_check`를 배포 절차에 넣는다. 값 추가 가능성이 있는
enum(`MealType`에 야식 추가 등)이 있어 그냥 넘어갈 수 없고, ②는 어떤 경우에도 동작한다.

별개로 ① 현재 Hibernate 버전에서 제약 생성을 실제로 막는 방법을 찾아 AGENTS.md를 고치는 일은
이 도메인과 무관하게 저장소 전체에 필요하다. 찾으면 ②는 불필요해진다.

## API

응답 규칙은 저장소 관례를 따른다 — 생성은 `@ResponseCreated` 201 + Location, 수정·삭제는
204, 조회는 `DataResponseBody`. **타인 소유 리소스는 404(`RESOURCE_NOT_FOUND`)로 존재를 숨긴다.**

```
GET    /diet/profile              내 프로필 + 계산된 목표
PUT    /diet/profile              키·몸무게·활동량·목표 저장 → 서버가 목표 재계산, 204
PUT    /diet/profile/weight       {weightKg}만 갱신 → 목표 재계산, 204 (매일 호출)

POST   /diet/analyses             {fileIds} → 201 + Location, 사진별 인식은 @Async
GET    /diet/analyses/{id}        상태 + 사진별 인식 결과 (확인 화면·폴링용)
POST   /diet/analyses/{id}/retry  실패한 사진만 재인식, 204
DELETE /diet/analyses/{id}        확인 취소, 204

POST   /diet/meals                {date, mealType, analysisId, items} → 201 + Location
GET    /diet/meals/{id}           단건 (피드백 완료 폴링용)
GET    /diet/meals?from=&to=      기간 목록
PUT    /diet/meals/{id}/items     항목 전체 교체 → 영양소·점수·피드백 재계산, 204
DELETE /diet/meals/{id}           204
POST   /diet/meals/{id}/retry     피드백 재생성 (FAILED 상태에서만), 204

PUT    /diet/activity             {date, activeEnergyKcal} upsert, 204
GET    /diet/days/{date}          하루 집계 + dayScore + 마감 피드백(lazy 생성, @Async — 폴링)
GET    /diet/foods?q=&size=       식품DB 검색 (iOS 항목 수정 화면용)
```

### 인식 → 확인 → 확정 흐름

```
POST /files (multipart) × N                → fileId들, temp/ 프리픽스에 저장
POST /diet/analyses {fileIds}              → 201 analysisId, 사진별 인식은 @Async
GET  /diet/analyses/{id}                   → status + photos[{fileId, url, items, failed}]
        ↓ 사용자가 확인·수정 (중복 제거, 수량 조정, 항목 추가·삭제)
POST /diet/meals {date, mealType, analysisId, items}
                                           → attachFile(fileId, "meals/") × N, MealPhoto 생성
조회 시 FileService.getPresignedUrls(fileIds) → 10분 만료 URL을 응답에 담는다
DELETE /diet/meals/{id}                    → FileService.detachFiles(fileIds)
```

**확정 요청에 `fileIds`를 다시 보내지 않는다.** `analysisId`로 서버가 사진 목록을 안다.
클라이언트가 파일 목록을 재구성하다 인식에 쓴 사진과 어긋나는 경로를 없앤다.

**`items`는 사용자가 고친 최종본을 통째로 받는다.** 서버는 인식 결과와 대조하지 않고 그대로
신뢰한다 — 확인 단계의 존재 이유가 사용자 판단을 최종으로 삼는 것이다. 모양은 기존
`PUT /diet/meals/{id}/items`(전체 교체)와 같아 항목 편집 로직을 한 벌만 만든다.

조회·목록 응답의 사진 URL은 `getPresignedUrls`로 한 번에 받는다(끼니 목록에서 N+1 방지).

**`Meal` 삭제 시 파일을 물리 삭제하지 않고 detach한다.** `2026-07-27-file-detach-and-temp-cleanup`
변경으로 `FileService`는 `delete`/`deleteAll` 대신 `detachFile`/`detachFiles`를 제공한다 —
상태를 `TEMP`로 되돌리기만 하고 S3 객체는 매일 04:00 정리 배치가 수거한다. 도메인 트랜잭션이
롤백되면 상태 변경도 함께 되돌아가므로 "레코드는 살아났는데 객체는 사라진" 상태가 생기지 않는다.

부수 효과로 **확인 단계에서 이탈한 사진도 자동 정리된다.** 확정되지 않으면 `attachFile`이
호출되지 않으므로 파일은 `TEMP`로 남고 TTL 24시간 뒤 배치가 수거한다. 파일 쪽은 이 도메인에서
새로 만들 게 없다. 다만 **`MealAnalysis` 레코드 자체는 지워야 하므로** 같은 주기로 도는
`MealAnalysisCleanupScheduler`를 이 도메인에 하나 둔다(TTL 24시간, `createdAt` 기준).

`daily-record`의 `common-file` 배선은 완료됐다(PR #24) — 의존성과 `s3.*` 설정(버킷 `daily`)이
들어가 있고, `@SpringBootApplication`이 `com.toy.backend`에 있어 `com.toy.backend.file`의
빈·엔티티는 자동 스캔된다. `@EnableScheduling`도 이미 있어 파일 정리 배치가 동작 중이다.

**`POST /diet/analyses`가 즉시 201을 반환하고 인식은 뒤에서 돌린다.** 사진마다 OpenRouter
이미지 호출이 수 초 걸려 5장이면 동기 처리 시 수십 초를 응답 대기로 잡아먹는다. iOS는 받은
id로 `GET /diet/analyses/{id}`를 폴링한다. `@EnableAsync`를 `DailyRecordApplication`에
추가한다(`@EnableScheduling`은 이미 있다).

## 인식 파이프라인 (`MealAnalyzer`, `@Async`)

**사진마다 독립적으로 돈다.** 한 호출에 여러 장을 넣지 않는 이유는 확인 화면에서 "이 항목은
몇 번째 사진에서 나왔다"를 보여주기 위해서다. 사용자가 중복을 판단하려면 출처가 보여야 한다.

사진 1장에 대해:

1. MinIO에서 이미지를 읽어 base64로 인코딩한다.
   **리사이즈는 하지 않는다** — iOS가 업로드 전에 장변 1024px로 줄여서 보낸다.
   라즈베리파이에서 이미지를 재인코딩하는 건 낭비다.
2. OpenRouter `chat/completions` 호출 (vision 모델, `response_format: json_schema` strict).
   응답 스키마: `{items:[{name, portion, estimatedKcal, estimatedCarbsG, estimatedProteinG, estimatedFatG}]}`
   `name`은 한국어 음식명, `portion`은 1인분 대비 배수(0.5 = 반 인분).
3. 각 `name`을 `FoodMatcher`로 식품DB에 매칭한다.
   - 성공: `quantityG = servingSizeG × portion` → 100g당 값으로 영양소 산출, `source = DB_MATCHED`
   - 실패: 2번에서 함께 받아둔 LLM 추정값을 그대로 쓰고 `source = LLM_ESTIMATED`

사진을 전부 처리하면 결과를 `resultJson`에 모아 쓰고 `MealAnalysis.status`를 갱신한다.
**여기까지가 인식이다. 점수도 피드백도 만들지 않는다** — 사용자가 항목을 고칠 수 있으므로
확정 전 수치로 계산하면 버려진다.

**사진별 부분 실패를 허용한다.** 5장 중 1장이 실패해도 나머지 결과로 확인 화면을 띄우고,
실패한 사진만 `failed: true`로 표시한다. 전부 실패했을 때만 `status = FAILED`다. 사진 한 장
때문에 나머지 인식 결과를 버리면 사용자는 전부 다시 올려야 한다.

`POST /diet/analyses/{id}/retry`는 **실패한 사진만** 다시 호출한다. 성공한 사진을 재호출하면
비용이 이중으로 나가고 결과가 흔들린다.

`@Async` 메서드는 별도 트랜잭션이므로 **`MealAnalysis`를 id로 다시 조회해서 다룬다.** 호출
측에서 넘긴 엔티티를 그대로 쓰면 준영속 상태 문제가 생긴다.

## 확정 (`MealService.confirm`)

`POST /diet/meals {date, mealType, analysisId, items}`:

1. `MealAnalysis`를 조회해 소유자를 확인한다(타인 것이면 404).
2. `attachFile(fileId, "meals/")`을 사진마다 호출하고 `MealPhoto`를 만든다.
3. 받은 `items`로 `MealItem`을 만들고 합산 → `DietScoreCalculator`로 끼니 점수 산출.
4. `Meal`을 `status = PENDING`으로 저장하고 **201을 즉시 반환한다.**
5. `MealAnalysis`를 삭제한다.
6. `@Async`로 `DietFeedbackGenerator` 텍스트 호출 → `feedback` 채우고 `status = COMPLETED`.

**점수는 동기, 피드백은 비동기다.** 점수는 룰 기반이라 즉시 나오고 사용자가 바로 봐야 하는
값이지만, 피드백은 LLM 텍스트 호출이라 수 초 걸린다. 확정 응답을 붙잡을 이유가 없다.

파일 attach가 실패하면 트랜잭션 전체가 롤백된다. detach 전환 덕에 이미 attach된 사진의 S3
객체는 사라지지 않고, 커밋되지 않았으므로 `TEMP`로 남아 정리 배치가 수거한다.

## 점수 계산 (`DietScoreCalculator`)

**점수는 사용자에게 근거를 보여줄 수 있어야 한다.** 숫자만 던지면 신뢰가 생기지 않고 행동도
바뀌지 않는다. 그래서 ① 기준을 가능한 한 공개된 출처에서 가져오고 ② 계산 결과에 항목별 근거를
함께 실어 앱이 그대로 표시한다.

### 기준의 출처

| 요소 | 출처 | 성격 |
| --- | --- | --- |
| BMR 공식 | Mifflin-St Jeor (1990) | 공개된 표준. 실무에서 가장 널리 쓰인다 |
| 활동 계수 1.2~1.9 | PAL(신체활동수준) 관례값 | 통용되는 값 |
| 매크로 권장 범위 | **2025 한국인 영양소 섭취기준(KDRIs) 에너지적정비율** | 국가 기준 |
| 감점 기울기·가중치 | 자체 설정 | **공개 근거 없음. 초기 추정치다** |

감점 계수는 전부 `DietScorePolicy` object의 상수로 모아 튜닝할 수 있게 한다. **어느 값이 국가
기준이고 어느 값이 우리가 정한 것인지 상수 주석에 명시한다** — 나중에 이 구분이 흐려지면
사용자에게 근거를 설명할 수 없게 된다.

### 목표 산출 (`NutritionProfileService`)

BMR은 Mifflin-St Jeor:

```
남: 10×kg + 6.25×cm − 5×age + 5
여: 10×kg + 6.25×cm − 5×age − 161
```

활동 계수 — SEDENTARY 1.2 / LIGHT 1.375 / MODERATE 1.55 / ACTIVE 1.725 / VERY_ACTIVE 1.9.
`TDEE = BMR × 계수`. 목표 조정 — LOSE `×0.85` / MAINTAIN `×1.0` / GAIN `×1.1`.

매크로 분배는 **KDRIs 에너지적정비율(탄 50~65% · 단 10~20% · 지 15~30%) 안에서** 목표별로
고른다. 세 비율의 합이 100이 되어야 하므로 범위 중앙값을 그대로 쓸 수는 없다:

```
LOSE      탄 50 / 단 20 / 지 30   (탄수 하한, 단백질 상한)
MAINTAIN  탄 55 / 단 15 / 지 30
GAIN      탄 60 / 단 15 / 지 25
```

`g = kcal × 비율 / (탄 4, 단 4, 지 9)`. 세 조합 모두 KDRIs 범위 안에 있어서, 어떤 목표를
골라도 국가 기준을 벗어난 목표가 제시되지 않는다.

### 끼니 점수 — KDRIs 범위를 벗어난 만큼만 감점

```
macroKcal = carbsG×4 + proteinG×4 + fatG×9
실제 비율 c% = carbsG×4 / macroKcal × 100   (단백질·지방도 동일)

각 매크로: excess = max(0, 하한 − 실제%, 실제% − 상한)     ← 범위 안이면 0
deviation = 탄 excess + 단 excess + 지 excess
mealScore = round(max(0, 100 − 2.0 × deviation))
```

**점 목표가 아니라 범위로 채점한다.** 개인 목표(50/15/30 등)를 점으로 놓고 편차를 감점하면
탄수화물 55%처럼 **권장 범위 한가운데인 식사도 감점된다.** 그건 사용자에게 설명할 수 없는
감점이고, 국가 기준과도 어긋난다. 범위 기준이면 "권장 범위를 벗어난 만큼만 깎였다"고 그대로
말할 수 있다.

그래서 **끼니 점수는 목표(LOSE/MAINTAIN/GAIN)와 무관하게 KDRIs 범위만 본다.** 개인 목표는
하루 점수의 g 목표에 반영된다 — 끼니는 「균형」, 하루는 「목표 대비 총량」으로 역할을 나눈다.

예) 탄 75% / 단 8% / 지 17% → excess 10 + 2 + 0 = 12 → `100 − 24` = **76점**.
극단적으로 밥만 먹으면(탄 100/단 0/지 0) excess 35 + 10 + 15 = 60 → 0점.

**비율 계산에는 `Meal.totalKcal`이 아니라 매크로에서 역산한 `macroKcal`을 쓴다.** 식품DB의
100g당 kcal은 탄단지 합산과 정확히 일치하지 않아(알코올·식이섬유·측정 오차) `totalKcal`을
분모로 쓰면 세 비율의 합이 100%가 되지 않고 편차가 왜곡된다.

**끼니 점수에 칼로리를 넣지 않는다.** 아침을 가볍게 먹은 것을 감점하면 안 된다. 총량은
하루 단위에서만 평가한다.

`macroKcal == 0`이면(물·커피 사진 등) 비율을 정의할 수 없으므로 `score = null`로 둔다.

### 하루 점수 — 칼로리 40% + 매크로 60%

**목표(`targetKcal`·매크로 g)는 그날 첫 `Meal`의 스냅샷에서 읽는다.** 현재 프로필을 쓰면 오늘
몸무게를 갱신했을 때 지난주 하루 점수가 같이 바뀐다. `Meal`이 0건인 날은 `dayScore`가 어차피
`null`이라 목표가 필요 없어, 이 규칙에 예외가 생기지 않는다.

"첫 `Meal`"은 `createdAt` 오름차순(동률이면 `id` 오름차순) 첫 건이다. 그 끼니를 지우면 다음
끼니의 스냅샷이 기준이 되는데, **같은 날 안에서 몸무게를 두 번 재는 경우가 아니면 값이 같다.**
이 좁은 창은 감수한다(AGENTS.md — 크래시 중간 상태 복구 없음과 같은 판단). **"과거 점수는
흔들리지 않는다"는 이 설계의 주장에 난 유일한 틈이 여기다** — 몸무게를 하루 중간에 갱신하고,
그날 첫 끼니를 나중에 지웠을 때에 한한다.

**응답에 담기는 끼니 순서는 `mealType` 순(아침→점심→저녁→간식)이다.** 목표를 뽑는 정렬
(`createdAt`)과 표시 정렬을 분리한다 — 저녁을 먼저 확정하고 아침을 나중에 확정한 날 화면이
저녁부터 나오면 안 된다.

칼로리 점수 — `totalKcal`은 그날 모든 `MealItem.kcal`의 합이다(끼니 점수와 달리 역산하지 않고
식품DB의 kcal 값을 그대로 쓴다. 총량 평가에서는 실제 칼로리가 맞는 값이다):

```
r = totalKcal / targetKcal
0.9 ≤ r ≤ 1.1        → 100
r < 0.9              → max(0, 100 − 200 × (0.9 − r))
r > 1.1              → max(0, 100 − 200 × (r − 1.1))
```

매크로별 점수 (`r = 실제g / 목표g`):

```
탄수·지방:  r < 1 → 100×r   |  1 ≤ r ≤ 1.1 → 100  |  r > 1.1 → max(0, 100 − 200×(r−1.1))
단백질:     r < 1 → 100×r   |  r ≥ 1 → 100
```

**단백질만 초과를 감점하지 않는다.** 단백질 과다는 실질적 문제가 아니어서, 감점하면
"고기를 충분히 먹었더니 점수가 깎이는" 잘못된 신호를 준다.

```
macroScore = (탄수 + 단백질 + 지방) / 3
dayScore   = round(0.4 × calorieScore + 0.6 × macroScore)
```

`0.4 / 0.6` 가중치와 `200 ×` 기울기는 **우리가 정한 값이고 공개 근거가 없다.** 총량보다 구성이
조금 더 중요하다는 판단일 뿐이다. 사용자에게 보여줄 때 국가 기준과 같은 무게로 제시하지 않는다.

그날 `Meal`이 0건이면 `dayScore = null`이고 피드백도 만들지 않는다.

### 점수 근거를 응답에 함께 싣는다

`GET /diet/meals/{id}`와 `GET /diet/days/{date}`는 점수와 함께 `scoreBasis`를 반환한다.
앱이 계산을 다시 하지 않고 그대로 표시할 수 있는 형태여야 한다.

```json
{
  "score": 76,
  "scoreBasis": {
    "standard": "2025 한국인 영양소 섭취기준(KDRIs) 에너지적정비율",
    "macros": [
      {"name": "탄수화물", "percent": 75.0, "rangeMin": 50, "rangeMax": 65, "status": "OVER",     "penalty": 20.0},
      {"name": "단백질",   "percent":  8.0, "rangeMin": 10, "rangeMax": 20, "status": "UNDER",    "penalty":  4.0},
      {"name": "지방",     "percent": 17.0, "rangeMin": 15, "rangeMax": 30, "status": "IN_RANGE", "penalty":  0.0}
    ]
  }
}
```

하루 점수는 여기에 칼로리 항목(`intakeKcal`·`targetKcal`·`ratio`·`calorieScore`)과 매크로 g
목표 대비 실제값을 더해 같은 모양으로 담는다.

**`status`와 `penalty`를 서버가 계산해 내려준다.** 앱이 `percent`와 범위만 받아 판정하면 감점
규칙이 두 곳에 생기고, 서버가 기울기를 튜닝했을 때 앱 표시와 실제 점수가 어긋난다.

`standard` 문구는 상수로 두고 응답에 그대로 싣는다. 기준이 개정되면(KDRIs는 5년 주기다) 이
문자열과 범위 상수를 함께 바꾼다.

## 피드백 생성 (`DietFeedbackGenerator`)

텍스트 모델을 쓴다. 이미지 호출이 비싼 부분이고 텍스트 호출은 훨씬 저렴하므로,
정확한 수치를 얻은 뒤 2차로 나눠 부르는 비용이 크지 않다.

**끼니 피드백은 확정 시점에 만든다.** 인식 직후가 아니라 사용자가 항목을 고친 뒤라야 실제로
먹은 것에 대한 조언이 된다. 항목을 다시 고치면(`PUT /diet/meals/{id}/items`) 재생성한다.

**끼니 피드백 프롬프트 입력** — 그 끼니의 음식 목록과 영양소, 계산된 끼니 점수,
그리고 **점수 근거**(매크로별 실제 비율·KDRIs 권장 범위·초과/부족/범위내). 근거를 함께 넘기는
이유는 "부족·과다 1개"를 LLM이 지어내지 않고 계산된 사실에서 뽑게 하기 위해서다.

> **2026-07-29 개정 — 끼니 피드백은 하루 맥락을 보지 않는다.** 원래는 그날 누적 섭취량과 하루
> 목표·활동 에너지를 함께 넘겨 "점심까지 단백질 28g, 저녁에 몰아 드세요" 같은 조언을 노렸다.
> 그런데 그러면 **앞선 끼니를 고치거나 지웠을 때 나중 끼니들의 피드백이 낡은 누적치 기준으로
> 남는다.** 무효화로 막으려면 앞선 끼니가 바뀔 때마다 그날의 나중 끼니를 전부 재생성해야 하는데,
> 한 번 수정에 LLM 호출이 여러 번 나가는 구조라 이 규모에 맞지 않는다.
>
> 더 중요한 건 **이게 원래 이 설계가 점수에서 정해둔 역할 분담과 어긋나 있었다는 것**이다.
> 「끼니 점수」 절이 *"끼니 점수에 칼로리를 넣지 않는다, 총량은 하루 단위에서만 평가한다"*,
> *"끼니는 「균형」, 하루는 「목표 대비 총량」으로 역할을 나눈다"*고 못박아 뒀는데 점수만 그
> 경계를 지키고 프롬프트가 넘고 있었다. 하루 맥락은 마감 피드백의 일로 되돌린다.
>
> 얻는 것: 끼니 피드백이 **서로 독립**이라 한 끼를 고쳐도 다른 끼니가 낡지 않고, 재생성이
> 항상 한 번이다. 잃는 것: 끼니 조언에 하루 맥락이 안 담긴다 — 그건 하루 화면이 맡는다.

**하루 마감 피드백 프롬프트 입력** — 그날 끼니 전체, 총 섭취량과 목표, `dayScore`, 활동 에너지.

**출력 규격 (프롬프트에 강제)** — 3요소 고정: ① 잘한 점 1개 ② 부족·과다 1개
③ **구체적 음식이 담긴 개선 행동 1개**. 2~3문장 한국어 존댓말. ③을 강제하지 않으면
"골고루 드세요"류로 흐른다. 의학적 진단·처방·특정 질환 언급은 금지 항목으로 명시한다.

**하루 피드백도 비동기다.** 원래는 `GET /diet/days/{date}`에서 동기로 만들 계획이었으나
2026-07-29 최종 리뷰에서 뒤집었다 — ① 실패를 캐시하지 않는 규칙과 겹치면 OpenRouter가 죽어
있는 동안 조회할 때마다 호출이 나가 「자동 재시도를 넣지 않는다」는 결정과 정면으로 충돌하고
② 쓰기 트랜잭션을 연 채 요청 스레드를 타임아웃(60초)까지 붙잡는다. 끼니 피드백과 같은 모양으로
맞춘다 — 조회는 즉시 반환하고 iOS가 폴링한다.

**하루 피드백 캐시 무효화** — 아래 중 하나라도 해당하면 버리고 다시 만든다.

1. `DailyDietFeedback`이 없다
2. `generatedAt`이 그날 `Meal`의 최종 `updatedAt`보다 이르다 — 당일에는 식사가 계속 추가되므로
   이 조건 없이 캐시하면 미완성 데이터로 만든 피드백이 고정된다
3. **끼니가 삭제됐다** — `MealService.delete`가 그날 캐시 행을 지운다

③이 없으면 남은 끼니의 `updatedAt`은 그대로라 ②가 걸리지 않는다. 그러면 **같은 응답 안에서
`dayScore`는 재계산돼 맞는데 `feedback` 문장은 지운 끼니를 그대로 언급한다.**

**활동 에너지 갱신은 무효화하지 않는다.** 2026-07-29 최종 리뷰 직후 한 번 넣었다가 되돌렸다.
넣은 근거는 "활동 에너지도 프롬프트에 들어가니 같은 문제"였는데, **활동 에너지는 점수에 아예
들어가지 않는다**(표시·피드백 맥락 전용). 그래서 ③이 막으려던 "점수와 문장이 모순된다"가
성립하지 않는다.

반대로 무효화하면 비용이 샌다. **활동 에너지는 하루 종일 증가하는 값**이라 앱이 하루 화면에
들어갈 때마다 값이 달라지고, 그때마다 캐시가 날아가 LLM을 다시 부른다. 화면을 다섯 번 열면
호출이 다섯 번이다 — 「자동 재시도를 넣지 않는다」로 막아 둔 비용 경로를 다른 문으로 여는 셈이다.

문장이 조금 낡은 활동량을 말할 수는 있다. 그건 감수한다 — 끼니가 추가·수정되면 어차피 ②로
재생성되므로 하루가 끝날 무렵의 피드백은 최근 값을 담게 된다.

**생성 시작 전에 마커를 남긴다.** 캐시가 무효하면 `feedback = null`·`generatedAt = now`인 행을
먼저 저장하고 그 다음에 `@Async`를 띄운다. 이 행이 "이미 생성이 걸렸다"는 표시가 되어, 폴링으로
여러 번 조회해도 **호출이 한 번만 나간다.** 마커가 없으면 폴링이 곧 무제한 호출이 되어 비동기로
바꾼 의미가 사라진다.

**생성이 실패하면 마커를 그대로 둔다.** `feedback`은 null로 남고 `generatedAt`은 방금 시각이라
끼니가 바뀌기 전까지 재호출되지 않는다. 사용자가 다음 끼니를 확정·수정하면 무효화 규칙이 걸려
자연히 다시 시도된다 — 별도의 재시도 엔드포인트를 두지 않는 이유다.

**비동기가 끝났을 때 캐시 행이 사라졌으면 결과를 버린다.** 마커는 항상 트리거 직전에 저장되므로,
완료 시점에 행이 없다는 것은 그 사이에 ③이나 ④가 캐시를 지웠다는 뜻이다. 즉 **지금 들고 있는
문장이 이미 낡았다는 신호다.** 여기서 새 행을 만들면 방금 무효화한 피드백이 되살아나 ③이 막으려던
모순이 그대로 재현된다.

## 식품DB (`Food`, `FoodMatcher`, `FoodSeeder`)

**외부 공공 API를 요청마다 호출하지 않는다.** 식약처 식품영양성분DB를 초기 1회 내려받아
우리 DB에 적재한다. 매 분석마다 외부 API를 타면 지연·장애·트래픽 제한을 그대로 떠안는다.

### 데이터셋 선택 — `음식` 표준데이터만 쓴다

`전국통합식품영양성분정보`는 `음식`·`가공식품`·`원재료성식품`으로 나뉜다.
**`음식`**(<https://www.data.go.kr/data/15100070/standard.do>)만 적재한다. 국민건강영양조사의
음식별 식품재료량 자료집 기반이라 "제육볶음"·"김치찌개" 같은 조리된 한식이 그대로 들어 있어
식사 사진 인식 결과와 어휘가 맞는다.

> **2026-07-29 개정 — `가공식품`도 적재한다.** 아래 원래 판단은 "LLM은 브랜드명을 주지 않는다"를
> 전제했는데, **봉지째 찍은 사진은 Vision 모델이 포장의 글자를 읽어 브랜드명을 거의 그대로 준다.**
> 과자·음료처럼 포장된 것은 오히려 브랜드명이 나오는 쪽이고, 그러면 `가공식품`이 더 잘 맞는다.
> 데이터 품질도 반대였다 — 실측 결과 `가공식품` 298,288행은 **전부 탄단지가 완비**돼 있고,
> `음식`은 19,495행 중 13,405행이 탄수·지방 결측이라 버려야 했다.
>
> 매칭률이 떨어진다는 우려는 유효하므로 **규칙을 분리해서 해결한다**(아래 「매칭 순서」 참조).

~~**`가공식품`은 넣지 않는다.**~~ (원래 판단, 위 개정으로 뒤집힘) 건수가 훨씬 많은데 이름이
"농심 신라면 봉지면" 같은 브랜드명 위주여서, LLM이 내놓는 "라면"과는 오히려 매칭되지 않는다.

### 두 데이터셋을 섞지 않고 나눠서 쓴다

`Food.dataset`(`DISH`/`PROCESSED`)으로 출처를 구분한다. **한 테이블에 뭉쳐 같은 규칙으로 찾으면
지금 잘 되는 매칭까지 망가진다** — 부분일치가 "이름이 가장 짧은 후보"를 고르는데, 브랜드 행
30만 개가 들어오면 "라면"·"우유" 같은 일반어가 수천 건을 긁어오고 그중 짧은 것이 엉뚱한 제품일
수 있다. 정규화 후 `음식` 이름과 그대로 겹치는 `가공식품` 행도 2,818개 있다(배추김치·스파게티 등).

**`가공식품`은 완전일치에만 참여한다.** 포장 사진은 완전일치가 성립하므로 그걸로 충분하고,
느린 `LIKE` 풀스캔은 6천 건짜리 `음식`에만 남는다. 성능상으로도 이 구조가 맞다 — 완전일치는
`normalized_name` 인덱스 조회라 30만 건이어도 빠르고, 풀스캔 대상만 작게 유지된다.

### 적재 절차

1. 공공데이터포털에서 CSV를 내려받는다. 그리드 다운로드는 5만 건 제한이 있어 전량이 안 나오면
   같은 페이지의 오픈API로 페이징해 덤프한다 — **준비 단계 1회이고 런타임 호출이 아니다**
2. `scripts/build-food-csv.py`로 정제한다. 33개 컬럼 중 8개만 남긴다:
   `식품코드`·`식품명`·`영양성분함량 기준량`·`에너지(kcal)`·`탄수화물(g)`·`단백질(g)`·`지방(g)`·`1인(회)분량 참고량`
3. 결과를 `apps/daily-record/src/main/resources/food/`에 **데이터셋마다 한 파일로** 둔다
   (`food-nutrition.csv` = 음식 6,090행, `processed-food-nutrition.csv` = 가공식품 298,271행).
   파일을 나누면 시더가 파일 단위로 `dataset`을 붙일 수 있고, 한쪽만 갱신하기도 쉽다.

   **이 CSV는 커밋하지 않는다**(`.gitignore`). 가공식품 정제본이 23MB라 저장소와 도커 이미지가
   그만큼 무거워지는데, 원본만 있으면 스크립트로 언제든 다시 만들 수 있는 파일이다. 대신
   **재생성 경로를 저장소에 남긴다** — `scripts/xlsx-to-csv.py`(엑셀→CSV)와
   `scripts/build-food-csv.py`(정제), 그리고 `resources/food/README.md`(출처 링크·명령·판단 근거).
   빌드하는 기계에 파일이 있어야 도커 이미지에 실려 파이에서도 채워진다
4. `FoodSeeder`가 기동 시 **아직 적재되지 않은 데이터셋만** 배치 삽입한다(라즈베리파이 메모리).
   가공식품 30만 행 때문에 최초 기동이 수 분 걸리지만 1회뿐이다

### 정제 시 주의 — 기준량 정규화

**`영양성분함량 기준량` 컬럼이 따로 있다는 것은 영양소 값이 항상 100g 기준이 아니라는 뜻이다.**
기준량이 `200g`인 행의 값을 100g당으로 착각하면 그 음식만 칼로리가 2배로 잡힌다.
정제 스크립트에서 기준량을 파싱해 **모든 값을 100g 기준으로 환산**한 뒤 저장한다.
파싱할 수 없는 기준량은 행을 버린다 — 틀린 값을 넣는 것보다 매칭 실패로 LLM 추정에
맡기는 편이 낫다.

`normalizedName`은 공백·괄호·특수문자 제거 + 소문자화로 만든다.

**`servingSizeG`의 출처는 `식품중량`이다.** 원래 `1인(회)분량 참고량`에서 뽑으려 했으나
2026-07-29 실측에서 **그 컬럼이 두 데이터셋 전 행에서 비어 있었다.** `식품중량`에 1회 제공량이
들어 있다(국밥 900g, 제육볶음 250g). 두 컬럼을 순서대로 보고 둘 다 없을 때만 결측 처리한다.

매칭 순서 (`FoodMatcher.match` — 인식 파이프라인이 자동으로 고르는 경로):

1. `음식` 완전일치
2. `가공식품` 완전일치 — 포장 사진에서 읽힌 브랜드명이 여기서 걸린다.
   **`음식`을 먼저 보는 이유**는 이름이 겹칠 때(배추김치 등) 조리된 음식 쪽이 사진에 찍힌 것에
   가깝기 때문이다. 순서가 곧 우선순위다
3. `음식` 부분일치 — `normalizedName LIKE '%정규화된 입력%'` 후보 중 **이름이 가장 짧은 것**.
   "제육볶음"으로 검색하면 "제육볶음(급식용)"·"제육볶음덮밥"이 같이 걸리는데, 짧은 쪽이
   더 일반적인 항목이라 사용자가 실제로 먹은 것에 가깝다. **`가공식품`은 여기에 넣지 않는다**
4. 실패 시 LLM 추정값 fallback (`source = LLM_ESTIMATED`)

**`pg_trgm` 같은 확장은 쓰지 않는다.** 부분일치 대상이 6천 건이고 조회는 하루 수십 건이라
`LIKE` 스캔으로 충분하다. 매칭률을 관찰한 뒤 부족하면 그때 도입한다.

`GET /diet/foods?q=`(iOS 항목 수정 화면)는 **두 데이터셋 모두 부분일치로 검색한다.** 사람이
목록에서 직접 고르는 화면이라 후보가 많은 것이 문제가 되지 않고, 오히려 "새우깡"을 찾을 수
있어야 한다. 자동 선택 없이 후보 목록을 그대로 반환하고, 응답에 `dataset`을 실어 앱이
「가공식품」임을 표시할 수 있게 한다.

## 설정 (`application.yml`)

```yaml
s3:   # family-tree에서 복사, bucket만 daily
  endpoint: ${S3_ENDPOINT:http://localhost:9000}
  public-endpoint: ${S3_PUBLIC_ENDPOINT:http://localhost:9000}
  region: ${S3_REGION:ap-northeast-2}
  access-key: ${S3_ACCESS_KEY:}
  secret-key: ${S3_SECRET_KEY:}
  bucket: ${S3_BUCKET:daily}

openrouter:
  api-key: ${OPENROUTER_API_KEY:}
  base-url: https://openrouter.ai/api/v1
  vision-model: ${OPENROUTER_VISION_MODEL:google/gemini-2.5-flash}
  text-model: ${OPENROUTER_TEXT_MODEL:google/gemini-2.5-flash-lite}
  timeout-seconds: 60
```

**모델을 이미지용·텍스트용으로 나눠 쓴다.** 음식 식별은 정확도가 결과 전체를 좌우하므로
Flash를 쓰고, 피드백 문장 생성은 수치와 목표를 이미 다 넘겨받아 문장만 만드는 쉬운 작업이라
Flash Lite로 충분하다.

`OpenRouterClient`·`OpenRouterProperties`·`OpenRouterConfig`는 `HolidayApiClient` 패턴을
복제한다. `webflux`와 `kotlinx-coroutines-reactor`가 이미 의존성에 있어 추가 라이브러리가 없다.

### API 키가 없으면 빈을 등록하지 않는다

로컬에서는 키 없이 앱을 띄운다. `OpenRouterClient`는 **키가 있을 때만** 빈으로 등록하고,
소비자(`MealAnalyzer`·`DietFeedbackGenerator`)는 `OpenRouterClient?`로 받는다.

```kotlin
@ConditionalOnExpression("'\${openrouter.api-key:}'.trim().length() > 0")
```

**`@ConditionalOnProperty`는 여기서 동작하지 않는다.** `api-key: ${OPENROUTER_API_KEY:}`는 환경
변수가 없어도 프로퍼티 자체는 빈 문자열로 *존재*하고, `havingValue`를 비워 둔 `ConditionalOnProperty`는
"존재하고 `false`가 아니면 참"이라 항상 매칭된다. 값이 비었는지를 봐야 하므로 SpEL 조건을 쓴다.

키가 없을 때의 동작:

| 대상 | 동작 |
| --- | --- |
| `POST /diet/analyses`, `POST /diet/analyses/{id}/retry` | `DietErrorCode.LLM_UNAVAILABLE`(503)로 즉시 거절 |
| 끼니 피드백 | 생성을 건너뛰고 `feedback = null`, `Meal.status = FAILED`. **점수는 살아 있다** |
| 하루 피드백 | 생성을 건너뛰고 `dayScore`만 반환 |
| 그 외 전부 | 정상 동작 — 프로필·확정·항목 수정·하루 집계·식품DB 검색은 LLM을 쓰지 않는다 |

인식만 즉시 거절하는 이유는, 인식은 LLM 없이 대체 경로가 없어서 진행시켜 봐야 `FAILED`
레코드만 쌓이기 때문이다. 반면 피드백은 없어도 점수라는 본체가 남는다 — 실제 호출 실패
처리와 같은 경로다.

**모델명은 환경변수로만 정한다.** 코드에 박지 않으면 한식 인식 정확도를 모델별로 비교하며
교체할 수 있다. 실제 모델은 착수 시점에 후보를 비교해 결정하고, **`json_schema` strict를
지원하는 모델인지 반드시 확인한다**(지원하지 않는 모델은 응답 파싱이 불안정해진다).

**프라이버시** — OpenRouter 계정에서 학습 미사용(data policy) 설정을 켠다. 식사 사진이 외부
AI 서비스로 전송되므로 앱 개인정보 처리방침에도 명시가 필요하다(iOS 스펙 참조).

## 실패 처리

| 상황 | 처리 |
| --- | --- |
| `openrouter.api-key` 미설정 | 인식 요청은 `LLM_UNAVAILABLE`(503), 피드백은 건너뛴다 (위 「설정」 참조) |
| 이미지 호출이 일부 사진에서 실패 | 그 사진만 `failed: true`, 나머지 결과로 확인 화면을 띄운다 |
| 이미지 호출이 모든 사진에서 실패 | `MealAnalysis.status = FAILED`. **자동 재시도하지 않는다** |
| 식품DB 매칭 실패 | LLM 추정값 사용 + `source = LLM_ESTIMATED` (iOS가 「추정」 배지 표시) |
| 확정 중 `attachFile` 실패 | 트랜잭션 롤백. 사진은 `TEMP`로 남아 정리 배치가 수거한다 |
| 피드백 호출 실패 | 점수는 살리고 `feedback = null`, `Meal.status = FAILED`. 점수가 피드백보다 중요하다 |
| 하루 피드백 생성 실패 | `dayScore`만 반환, `feedback = null`. 마커가 남아 끼니가 바뀌기 전까지 재호출하지 않는다 |

**자동 재시도를 넣지 않는 이유** — OpenRouter 호출은 실제 비용이 나가므로, 실패가 반복되면
자동 재시도가 비용 폭주 경로가 된다. 사용자 2명 규모에서는 수동 재시도
(`POST /diet/analyses/{id}/retry`로 실패한 사진만, `POST /diet/meals/{id}/retry`로 피드백만)를
열어두는 것으로 충분하고, 1일 호출 상한 같은 방어도 필요 없다.

## 테스트

kotest `BehaviorSpec` + mockk, 픽스처는 `testFixtures`의 `dummyUser()`·`withId()`.

- `DietScoreCalculatorTest` — KDRIs 범위 안이면 100점(경계값 50/65·10/20·15/30 포함),
  탄 75·단 8·지 17 → 76점, `macroKcal 0` → null, 단백질 초과 무감점, 칼로리 0.9/1.1 경계,
  `scoreBasis`의 `status`·`penalty`가 실제 감점과 일치하는지
- `NutritionProfileServiceTest` — 성별·활동량·목표별 BMR·목표 g 계산, `birthDate`/`gender`
  결측 시 `INVALID_REQUEST`, 몸무게만 갱신했을 때 키·활동량·목표가 보존되고 목표 4개가 재계산되는지
- `DailyDietServiceTest` — 하루 목표를 **현재 프로필이 아니라 그날 첫 `Meal` 스냅샷**에서 읽는지
  (프로필 몸무게를 바꿔도 과거 `dayScore`가 그대로인지)
- `OpenRouterConfigTest` — `ApplicationContextRunner`로 키가 빈 문자열이면 `OpenRouterClient`
  빈이 없고, 값이 있으면 등록되는지. 키 없을 때 인식 요청이 `LLM_UNAVAILABLE`인지
- `FoodMatcherTest` — 완전일치 / 유사도 / 실패 시 fallback 경로
- `MealServiceTest` — 타인 `Meal` 접근 시 404, 항목 교체 시 영양소·점수 재계산,
  `FAILED`가 아닌 상태에서 retry 거절
- `MealAnalysisServiceTest` — 사진 6장 이상 `INVALID_REQUEST`, 일부 사진 실패 시 나머지 결과
  유지·`failed` 표시, 전부 실패 시 `FAILED`, retry가 실패한 사진만 재호출, 타인 분석 접근 404
- `MealConfirmTest` — 확정 시 사진 수만큼 `attachFile` 호출·`MealAnalysis` 삭제,
  사용자가 고친 `items`가 인식 결과 대신 저장되는지
- `DailyDietServiceTest` — 캐시 무효화 조건(`generatedAt` < 최종 `Meal.updatedAt`)

단위 테스트는 리포지토리를 목으로 대체하므로 트랜잭션 경계를 잡지 못한다(AGENTS.md).
**`@Async` 인식 경로와 확정 경로는 실제로 앱을 띄워 사진을 여러 장 올려 확인한다** —
이 설계에서 트랜잭션 경계가 가장 위험한 지점이고, 확정 시 `attachFile` × N과 `MealAnalysis`
삭제가 한 트랜잭션에 묶이는 부분은 목으로 검증되지 않는다.

## 리스크

1. ~~**`servingSizeG`(1인분 기준량) 결측**~~ — **2026-07-29 해소.** 실측 결과 `1인(회)분량 참고량`은
   전 행이 비어 있었고, 대신 `식품중량`에 1회 제공량이 들어 있었다. 여기서 뽑으면 `음식`
   6,090행 중 6,078행이 채워진다. 남은 12행만 기본값 200g으로 간다.
2. **음식명 매칭률** — LLM이 "제육볶음"을 주는데 DB에는 "돼지고기볶음"만 있을 수 있다.
   유사도 검색과 LLM fallback으로 완화하지만, 초기에는 매칭 성공률을 로그로 관찰하며
   정규화 규칙을 다듬어야 한다.
3. **한식 인식 정확도** — 반찬이 여러 개인 상차림에서 개별 음식을 분리하는 정확도는 모델에
   크게 의존한다. 모델을 환경변수로 뺀 이유가 이것이다.
4. **사진 수만큼 비용이 곱해진다** — 사진별 호출이라 5장이면 이미지 호출도 5회다. 상한 5장은
   초기 추정치이므로, **실제 사용 패턴에서 평균 몇 장을 올리는지 관찰해 조정한다.** 대부분
   1~2장이면 문제가 없지만 습관적으로 5장을 올린다면 상한을 낮추거나 한 호출에 여러 장을 묶는
   방식(항목별 출처 추적을 포기)을 다시 검토한다.
5. **확인 단계 이탈률** — 확인을 귀찮아해 인식만 하고 저장하지 않으면 LLM 비용만 나가고 기록은
   남지 않는다. 확인 화면이 무거우면 이 값이 올라가므로 iOS 쪽 UX와 함께 봐야 한다.

## 기준 출처

점수 근거를 사용자에게 보여주는 기능이 있으므로, 인용한 기준의 출처를 남긴다.

- **2025 한국인 영양소 섭취기준(KDRIs)** — 에너지적정비율 탄수화물 50~65% · 단백질 10~20% ·
  지방 15~30%. 2020년 기준(탄 55~65 · 단 7~20)에서 탄수화물을 낮추고 단백질을 올린 개정이다.
  - [보건복지부 보도자료 — 영양소 적정 섭취기준 개정](https://www.mohw.go.kr/board.es?mid=a10503010100&bid=0027&act=view&list_no=1488441)
  - [한국영양학회 — 한국인 영양소 섭취기준 자료실](https://www.kns.or.kr/FileRoom/FileRoom_view.asp?idx=108&BoardID=Kdr)
  - [탄수화물 섭취기준 개정 근거 (Journal of Nutrition and Health)](https://e-jnh.org/DOIx.php?id=10.4163%2Fjnh.2026.59.2.148)
- **Mifflin-St Jeor** — BMR 추정식(1990). 비만·비비만 성인 모두에서 정확도가 높다고 평가된다.
- **PAL 활동 계수** — 1.2~1.9. FAO/WHO/UNU 에너지 요구량 보고서 계열의 통용값.

**채택하지 않은 것 — KHEI(한국인 식생활평가지수).** 질병관리청 국민건강영양조사 기반의 14개
항목 100점 척도로, 국내 식사 품질 지표로는 가장 표준에 가깝다. 다만 잡곡·과일·채소·나트륨·
포화지방 등 **식품군과 미량영양소 단위 평가**라, 탄단지 g만 수집하는 이 설계로는 계산할 수
없다. 사진에서 "잡곡밥인지 흰쌀밥인지"를 신뢰성 있게 얻을 수 있게 되면 그때 재검토한다.
([KHEI 개발 논문](https://e-nrp.org/DOIx.php?id=10.4162%2Fnrp.2022.16.2.233))

## 범위 외

배우자 공유 · 주간 리포트 · 기존 `dailyrecords`/`daily-overeats` 연동 · 바코드·영수증 인식 ·
물 섭취 기록 · 목표치 수동 오버라이드.
