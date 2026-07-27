# 식단 사진 분석·점수·피드백 (백엔드) 설계

작성일: 2026-07-27
모듈: `apps/daily-record`
짝 문서: `woori-haru/docs/superpowers/specs/2026-07-27-diet-tracking-ios-design.md`

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
| 점수 | 룰 기반 계산 | 결정적·테스트 가능·설명 가능 |
| LLM 호출 | 끼니당 2회 (이미지 인식 + 텍스트 피드백) | 피드백은 DB 매칭 후 확정된 수치·목표·점수가 필요하다 |
| 사진 저장 | `common-file` 재사용 (MinIO) | `family-tree`에서 검증된 경로. 새로 만들 게 없다 |
| 사진 접근 제어 | presigned URL (10분) | `FileService`가 이미 제공한다 |
| 분석 실행 | `@Async` (큐 없음) | 사용자 2명·하루 수십 건. 큐는 과하다 |
| 하루 피드백 | lazy 생성 + 캐시 무효화 | 크론 불필요, 안 보는 날 LLM 비용이 안 든다 |
| 활동 에너지 | 표시·피드백 맥락으로만 사용, 목표에는 반영하지 않음 | 목표가 매일 흔들리면 점수를 설명할 수 없다 |

## 도메인 모델

패키지는 `com.toy.backend.diet.*` (`ledger` 패턴). 앱 전용 에러 코드는 `DietErrorCode` enum.

### `NutritionProfile` — 사용자당 1개

`userId`, `heightCm`, `weightKg`, `activityLevel`, `goal`,
그리고 서버가 계산해 저장하는 `targetKcal`·`targetCarbsG`·`targetProteinG`·`targetFatG`.

나이·성별은 `common-auth`의 `User.birthDate`·`gender`를 재사용한다. 둘 중 하나라도 없으면
BMR을 계산할 수 없으므로 프로필 저장을 `INVALID_REQUEST`로 거절한다.

**목표치를 계산해서 저장하는 이유** — 몸무게를 갱신했을 때 과거 점수의 기준이 소급 변경되면
안 된다. 점수는 `Meal`에 확정값으로 남고, 프로필은 현재 목표만 들고 있는다.

### `Meal` — 끼니 1건 = 사진 1장

`userId`, `date`, `mealType`, `fileId`, `status`, `score`, `totalKcal`,
`carbsG`·`proteinG`·`fatG`, `feedback`, `items`(OneToMany).

### `MealItem` — 끼니 안의 개별 음식

`foodName`(정규화된 이름), `foodCode`(식품DB 코드, nullable), `quantityG`,
`kcal`, `carbsG`·`proteinG`·`fatG`, `source`.

**`MealItem`을 별도 테이블로 쪼개는 게 이 설계의 핵심이다.** 영양소를 `Meal`에 뭉쳐 저장하면
① 음식별 빈도 집계("이번 주 제육볶음 3회")가 불가능하고 ② 인식이 틀렸을 때 항목 단위로
수정·재계산할 수 없다. 두 기능 모두 이 도메인의 존재 이유에 해당한다.

### `DailyDietFeedback` — 하루 마감 피드백 캐시

`userId`, `date`, `dayScore`, `feedback`, `generatedAt`.

하루 집계값은 테이블로 만들지 않고 `Meal` 합산으로 구한다. 이 엔티티는 LLM 호출 결과를
재사용하기 위한 캐시일 뿐이다.

### `DailyActivity` — 하루 활동 에너지

`userId`, `date`, `activeEnergyKcal`. iOS가 HealthKit에서 읽어 올린 값을 upsert 한다.

### `Food` — 식품 영양 성분

`code`, `name`, `normalizedName`, `servingSizeG`,
`kcalPer100g`·`carbsPer100g`·`proteinPer100g`·`fatPer100g`.

### enum 컬럼

`MealType`(BREAKFAST/LUNCH/DINNER/SNACK), `AnalysisStatus`(PENDING/COMPLETED/FAILED),
`ActivityLevel`(SEDENTARY/LIGHT/MODERATE/ACTIVE/VERY_ACTIVE), `DietGoal`(LOSE/MAINTAIN/GAIN),
`NutritionSource`(DB_MATCHED/LLM_ESTIMATED).

`ddl-auto: update`를 쓰므로 **5개 전부 `columnDefinition`을 명시**해 CHECK 제약이 생기지 않게
한다(AGENTS.md). 제약이 생기면 나중에 enum 값을 추가할 때 기존 DB에서 INSERT가 깨진다.

## API

응답 규칙은 저장소 관례를 따른다 — 생성은 `@ResponseCreated` 201 + Location, 수정·삭제는
204, 조회는 `DataResponseBody`. **타인 소유 리소스는 404(`RESOURCE_NOT_FOUND`)로 존재를 숨긴다.**

```
GET    /diet/profile              내 프로필 + 계산된 목표
PUT    /diet/profile              키·몸무게·활동량·목표 저장 → 서버가 목표 재계산, 204

POST   /diet/meals                {date, mealType, fileId} → 201 + Location, 분석은 @Async
GET    /diet/meals/{id}           단건 (분석 완료 폴링용)
GET    /diet/meals?from=&to=      기간 목록
PUT    /diet/meals/{id}/items     항목 전체 교체 → 영양소·점수·피드백 재계산, 204
DELETE /diet/meals/{id}           204
POST   /diet/meals/{id}/retry     분석 재시도 (FAILED 상태에서만), 204

PUT    /diet/activity             {date, activeEnergyKcal} upsert, 204
GET    /diet/days/{date}          하루 집계 + dayScore + 마감 피드백(lazy 생성)
GET    /diet/foods?q=&size=       식품DB 검색 (iOS 항목 수정 화면용)
```

### 사진 업로드는 `common-file`의 기존 2단계 흐름

```
POST /files (multipart)                    → fileId, temp/ 프리픽스에 저장
POST /diet/meals {date, mealType, fileId}  → FileService.attachFile(fileId, "meals/")
조회 시 FileService.getPresignedUrl(fileId) → 10분 만료 URL을 응답에 담는다
DELETE /diet/meals/{id}                    → FileService.detachFile(fileId)
```

**`Meal` 삭제 시 파일을 물리 삭제하지 않고 `detach`한다.** `2026-07-27-file-detach-and-temp-cleanup`
변경으로 `FileService`는 `delete` 대신 `detach`를 제공한다 — 상태를 `TEMP`로 되돌리기만 하고
S3 객체는 매일 04:00 정리 배치가 수거한다. 도메인 트랜잭션이 롤백되면 상태 변경도 함께
되돌아가므로 "레코드는 살아났는데 객체는 사라진" 상태가 생기지 않는다.

부수 효과로 **사용자가 사진만 올리고 끼니 등록을 취소한 경우도 자동 정리된다.**
`POST /files`만 호출되고 `attachFile`이 안 된 파일은 TTL 24시간 뒤 배치가 수거한다.
고아 파일 처리를 이 도메인에서 따로 만들 필요가 없다.

`DailyRecordApplication`에는 `@EnableScheduling`이 이미 있어 정리 배치가 그대로 동작한다.

`daily-record`는 아직 `common-file`을 의존하지 않으므로 `build.gradle.kts`에
`implementation(project(":common-file"))`을 추가하고, `application.yml`에 `s3.*` 블록을
`family-tree`에서 복사한다(버킷만 `daily-record`). `@SpringBootApplication`이 `com.toy.backend`에
있어 `com.toy.backend.file`의 빈·엔티티는 자동으로 스캔된다.

**`POST /diet/meals`가 즉시 201을 반환하고 분석은 뒤에서 돌린다.** OpenRouter 이미지 호출이
수 초 걸려 동기로 처리하면 업로드 응답이 그만큼 지연된다. iOS는 받은 id로 `GET /diet/meals/{id}`를
폴링한다. `@EnableAsync`를 `DailyRecordApplication`에 추가한다(`@EnableScheduling`은 이미 있다).

## 분석 파이프라인 (`MealAnalyzer`, `@Async`)

1. MinIO에서 이미지를 읽어 base64로 인코딩한다.
   **리사이즈는 하지 않는다** — iOS가 업로드 전에 장변 1024px로 줄여서 보낸다.
   라즈베리파이에서 이미지를 재인코딩하는 건 낭비다.
2. OpenRouter `chat/completions` 호출 (vision 모델, `response_format: json_schema` strict).
   응답 스키마: `{items:[{name, portion, estimatedKcal, estimatedCarbsG, estimatedProteinG, estimatedFatG}]}`
   `name`은 한국어 음식명, `portion`은 1인분 대비 배수(0.5 = 반 인분).
3. 각 `name`을 `FoodMatcher`로 식품DB에 매칭한다.
   - 성공: `quantityG = servingSizeG × portion` → 100g당 값으로 영양소 산출, `source = DB_MATCHED`
   - 실패: 2번에서 함께 받아둔 LLM 추정값을 그대로 쓰고 `source = LLM_ESTIMATED`
4. `MealItem` 합산 → `DietScoreCalculator`로 끼니 점수 산출
5. `DietFeedbackGenerator`로 2차 텍스트 호출 → 끼니 피드백
6. `Meal` 업데이트, `status = COMPLETED`

`@Async` 메서드는 별도 트랜잭션이므로 **`Meal`을 id로 다시 조회해서 다룬다.** 호출 측에서
넘긴 엔티티를 그대로 쓰면 준영속 상태 문제가 생긴다.

## 점수 계산 (`DietScoreCalculator`)

감점 계수는 전부 `DietScorePolicy` object의 상수로 모아 나중에 튜닝할 수 있게 한다.
아래 값은 초기 추정치다.

### 목표 산출 (`NutritionProfileService`)

BMR은 Mifflin-St Jeor:

```
남: 10×kg + 6.25×cm − 5×age + 5
여: 10×kg + 6.25×cm − 5×age − 161
```

활동 계수 — SEDENTARY 1.2 / LIGHT 1.375 / MODERATE 1.55 / ACTIVE 1.725 / VERY_ACTIVE 1.9.
`TDEE = BMR × 계수`.

목표 조정 — LOSE `×0.85` / MAINTAIN `×1.0` / GAIN `×1.1`.

매크로 분배 (탄/단/지 칼로리 비율) — LOSE 40/30/30 · MAINTAIN 50/20/30 · GAIN 50/25/25.
`g = kcal × 비율 / (탄 4, 단 4, 지 9)`.

### 끼니 점수 — 비율만 본다

```
macroKcal      = carbsG×4 + proteinG×4 + fatG×9
목표 비율 tc%  = targetCarbsG×4 / (targetCarbsG×4 + targetProteinG×4 + targetFatG×9) × 100
실제 비율 c%   = carbsG×4 / macroKcal × 100
deviation = |c%−tc%| + |p%−tp%| + |f%−tf%|
mealScore = round(max(0, 100 − 0.9 × deviation))
```

**비율 계산에는 `Meal.totalKcal`이 아니라 매크로에서 역산한 `macroKcal`을 쓴다.** 식품DB의
100g당 kcal은 탄단지 합산과 정확히 일치하지 않아(알코올·식이섬유·측정 오차) `totalKcal`을
분모로 쓰면 세 비율의 합이 100%가 되지 않고 편차가 왜곡된다.

예) 목표 50/20/30, 실제 60/10/30 → deviation 20 → 82점.

**끼니 점수에 칼로리를 넣지 않는다.** 아침을 가볍게 먹은 것을 감점하면 안 된다. 총량은
하루 단위에서만 평가한다.

`macroKcal == 0`이면(물·커피 사진 등) 비율을 정의할 수 없으므로 `score = null`로 둔다.

### 하루 점수 — 칼로리 40% + 매크로 60%

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

그날 `Meal`이 0건이면 `dayScore = null`이고 피드백도 만들지 않는다.

## 피드백 생성 (`DietFeedbackGenerator`)

텍스트 모델을 쓴다. 이미지 호출이 비싼 부분이고 텍스트 호출은 훨씬 저렴하므로,
정확한 수치를 얻은 뒤 2차로 나눠 부르는 비용이 크지 않다.

**끼니 피드백 프롬프트 입력** — 음식 목록과 영양소, 개인 목표, 계산된 끼니 점수,
**그날 지금까지의 누적 섭취량**, 그리고 `DailyActivity`가 있으면 활동 에너지.
누적치를 함께 넘기므로 한 끼만 보고 말하지 않고 하루 맥락이 담긴 조언이 나온다
("점심까지 단백질 28g, 저녁에 몰아 드세요").

**하루 마감 피드백 프롬프트 입력** — 그날 끼니 전체, 총 섭취량과 목표, `dayScore`, 활동 에너지.

**출력 규격 (프롬프트에 강제)** — 3요소 고정: ① 잘한 점 1개 ② 부족·과다 1개
③ **구체적 음식이 담긴 개선 행동 1개**. 2~3문장 한국어 존댓말. ③을 강제하지 않으면
"골고루 드세요"류로 흐른다. 의학적 진단·처방·특정 질환 언급은 금지 항목으로 명시한다.

**하루 피드백 캐시 무효화** — `GET /diet/days/{date}` 시 `DailyDietFeedback`이 없거나
`generatedAt`이 그날 `Meal`의 최종 `updatedAt`보다 이르면 버리고 재생성한다. 당일에는 식사가
계속 추가되므로 이 조건 없이 캐시하면 미완성 데이터로 만든 피드백이 고정된다.

## 식품DB (`Food`, `FoodMatcher`, `FoodSeeder`)

**외부 공공 API를 요청마다 호출하지 않는다.** 식약처 식품영양성분DB를 초기 1회 내려받아
우리 DB에 적재한다. 매 분석마다 외부 API를 타면 지연·장애·트래픽 제한을 그대로 떠안는다.

### 데이터셋 선택 — `음식` 표준데이터만 쓴다

`전국통합식품영양성분정보`는 `음식`·`가공식품`·`원재료성식품`으로 나뉜다.
**`음식`**(<https://www.data.go.kr/data/15100070/standard.do>)만 적재한다. 국민건강영양조사의
음식별 식품재료량 자료집 기반이라 "제육볶음"·"김치찌개" 같은 조리된 한식이 그대로 들어 있어
식사 사진 인식 결과와 어휘가 맞는다.

**`가공식품`은 넣지 않는다.** 건수가 훨씬 많은데 이름이 "농심 신라면 봉지면" 같은 브랜드명
위주여서, LLM이 내놓는 "라면"과는 오히려 매칭되지 않는다. 테이블만 무거워지고 매칭률은
떨어진다. 필요해지면 그때 추가한다.

### 적재 절차

1. 공공데이터포털에서 CSV를 내려받는다. 그리드 다운로드는 5만 건 제한이 있어 전량이 안 나오면
   같은 페이지의 오픈API로 페이징해 덤프한다 — **준비 단계 1회이고 런타임 호출이 아니다**
2. `scripts/build-food-csv.py`로 정제한다. 33개 컬럼 중 8개만 남긴다:
   `식품코드`·`식품명`·`영양성분함량 기준량`·`에너지(kcal)`·`탄수화물(g)`·`단백질(g)`·`지방(g)`·`1인(회)분량 참고량`
3. 결과를 `apps/daily-record/src/main/resources/food/food-nutrition.csv`로 커밋한다.
   외부 다운로드 의존 없이 배포가 재현되고, SQL dump와 달리 diff가 보인다
4. `FoodSeeder`가 기동 시 `food` 테이블이 비어 있을 때만 **배치 삽입**한다(라즈베리파이 메모리)

### 정제 시 주의 — 기준량 정규화

**`영양성분함량 기준량` 컬럼이 따로 있다는 것은 영양소 값이 항상 100g 기준이 아니라는 뜻이다.**
기준량이 `200g`인 행의 값을 100g당으로 착각하면 그 음식만 칼로리가 2배로 잡힌다.
정제 스크립트에서 기준량을 파싱해 **모든 값을 100g 기준으로 환산**한 뒤 저장한다.
파싱할 수 없는 기준량은 행을 버린다 — 틀린 값을 넣는 것보다 매칭 실패로 LLM 추정에
맡기는 편이 낫다.

`normalizedName`은 공백·괄호·특수문자 제거 + 소문자화로 만든다.
`servingSizeG`는 `1인(회)분량 참고량`에서 숫자만 추출한다.

매칭 순서 (`FoodMatcher`):

1. `normalizedName` 완전일치
2. `normalizedName LIKE '%정규화된 입력%'` 후보 중 **이름이 가장 짧은 것**을 고른다.
   "제육볶음"으로 검색하면 "제육볶음(급식용)"·"제육볶음덮밥"이 같이 걸리는데, 짧은 쪽이
   더 일반적인 항목이라 사용자가 실제로 먹은 것에 가깝다
3. 실패 시 LLM 추정값 fallback (`source = LLM_ESTIMATED`)

**`pg_trgm` 같은 확장은 쓰지 않는다.** 후보가 수만 건이고 조회는 하루 수십 건이라
`LIKE` 스캔으로 충분하다. 매칭률을 관찰한 뒤 부족하면 그때 도입한다.

`GET /diet/foods?q=`(iOS 항목 수정 화면)도 같은 정규화 규칙으로 검색하되, 자동 선택 없이
후보 목록을 그대로 반환한다.

## 설정 (`application.yml`)

```yaml
s3:   # family-tree에서 복사, bucket만 daily-record
  endpoint: ${S3_ENDPOINT:http://localhost:9000}
  public-endpoint: ${S3_PUBLIC_ENDPOINT:http://localhost:9000}
  region: ${S3_REGION:ap-northeast-2}
  access-key: ${S3_ACCESS_KEY:}
  secret-key: ${S3_SECRET_KEY:}
  bucket: ${S3_BUCKET:daily-record}

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

**모델명은 환경변수로만 정한다.** 코드에 박지 않으면 한식 인식 정확도를 모델별로 비교하며
교체할 수 있다. 실제 모델은 착수 시점에 후보를 비교해 결정하고, **`json_schema` strict를
지원하는 모델인지 반드시 확인한다**(지원하지 않는 모델은 응답 파싱이 불안정해진다).

**프라이버시** — OpenRouter 계정에서 학습 미사용(data policy) 설정을 켠다. 식사 사진이 외부
AI 서비스로 전송되므로 앱 개인정보 처리방침에도 명시가 필요하다(iOS 스펙 참조).

## 실패 처리

| 상황 | 처리 |
| --- | --- |
| 1차(이미지) 호출 실패 | `status = FAILED`. **자동 재시도하지 않는다** |
| 식품DB 매칭 실패 | LLM 추정값 사용 + `source = LLM_ESTIMATED` (iOS가 「추정」 배지 표시) |
| 2차(피드백) 호출 실패 | 점수는 살리고 `feedback = null`. 점수가 피드백보다 중요하다 |
| 하루 피드백 생성 실패 | `dayScore`만 반환, `feedback = null` |

**자동 재시도를 넣지 않는 이유** — OpenRouter 호출은 실제 비용이 나가므로, 실패가 반복되면
자동 재시도가 비용 폭주 경로가 된다. 사용자 2명 규모에서는 `POST /diet/meals/{id}/retry`로
수동 재시도만 열어두는 것으로 충분하고, 1일 호출 상한 같은 방어도 필요 없다.

## 테스트

kotest `BehaviorSpec` + mockk, 픽스처는 `testFixtures`의 `dummyUser()`·`withId()`.

- `DietScoreCalculatorTest` — 목표 일치 시 100점, 편차 20 → 82점, `totalKcal 0` → null,
  단백질 초과 무감점, 칼로리 0.9/1.1 경계
- `NutritionProfileServiceTest` — 성별·활동량·목표별 BMR·목표 g 계산, `birthDate`/`gender`
  결측 시 `INVALID_REQUEST`
- `FoodMatcherTest` — 완전일치 / 유사도 / 실패 시 fallback 경로
- `MealServiceTest` — 타인 `Meal` 접근 시 404, 항목 교체 시 영양소·점수 재계산,
  `FAILED`가 아닌 상태에서 retry 거절
- `DailyDietServiceTest` — 캐시 무효화 조건(`generatedAt` < 최종 `Meal.updatedAt`)

단위 테스트는 리포지토리를 목으로 대체하므로 트랜잭션 경계를 잡지 못한다(AGENTS.md).
**`@Async` 분석 경로는 실제로 앱을 띄워 사진을 올려 확인한다** — 이 설계에서 트랜잭션 경계가
가장 위험한 지점이다.

## 리스크

1. **`servingSizeG`(1인분 기준량) 결측** — `음식` 표준데이터에 `1인(회)분량 참고량` 컬럼이
   존재하는 것은 확인했으나, 행마다 비어 있을 수 있다. 이 값이 없으면 `portion`을 g으로
   환산할 수 없다. 결측 시 기본값 200g으로 두되, **실제 CSV를 받아본 뒤 결측률을 확인해
   조정해야 한다.**
2. **음식명 매칭률** — LLM이 "제육볶음"을 주는데 DB에는 "돼지고기볶음"만 있을 수 있다.
   유사도 검색과 LLM fallback으로 완화하지만, 초기에는 매칭 성공률을 로그로 관찰하며
   정규화 규칙을 다듬어야 한다.
3. **한식 인식 정확도** — 반찬이 여러 개인 상차림에서 개별 음식을 분리하는 정확도는 모델에
   크게 의존한다. 모델을 환경변수로 뺀 이유가 이것이다.

## 범위 외

배우자 공유 · 주간 리포트 · 기존 `dailyrecords`/`daily-overeats` 연동 · 바코드·영수증 인식 ·
물 섭취 기록 · 목표치 수동 오버라이드.
