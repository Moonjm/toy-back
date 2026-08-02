# 식품DB 영양소 3종 추가 · 검색 `dataset` 필터 (백엔드) 설계

작성일: 2026-07-30
짝 문서: `woori-haru/docs/superpowers/specs/2026-07-30-diet-item-picker-design.md`

iOS 항목 고르기 화면 개편에서 나온 백엔드 작업 두 가지다. **서로 독립이므로 따로 해도 된다.**

- **작업 A** — `Food`에 포화지방산·트랜스지방산·콜레스테롤 추가
- **작업 B** — `GET /diet/foods`에 `dataset` 필터 파라미터 추가

---

# 작업 A — 영양소 3종 추가

## 배경

iOS 상세 시트가 포화지방·트랜스지방·콜레스테롤을 보여주려 하는데 `Food`에 컬럼이
없다. **원본에는 처음부터 있었다** — 정제 스크립트가 52~166개 컬럼 중 8개만 남기면서 버렸다.

2026-07-30 원본 직접 확인:

| 원본 | 컬럼 수 | 세 컬럼 |
| --- | --- | --- |
| 음식 (`음식DB.xlsx`) | 160 | `콜레스테롤(mg)`(38) · `포화지방산(g)`(39) · `트랜스지방산(g)`(40) |
| 원재료성식품 (`...원재료성식품_표준데이터-20260730.xls`) | 55 | 같은 인덱스에 셋 다 있음 |
| 가공식품 (`가공식품DB.xlsx`) | 166 | 같은 인덱스에 셋 다 있음 (+`불포화지방산(g)`(73), 트랜스 리놀레산 등) |

**세 원본의 인덱스가 모두 같다** — `지방(g)`(20) · `콜레스테롤`(38) · `포화지방산`(39) ·
`트랜스지방산`(40) · `불포화지방산`(73). `불포화지방산(g)`은 `포화지방산(g)`을 부분 문자열로
포함하지만 **뒤에 있어서** 원본 순서로 훑는 `xlsx-to-csv.py`에는 걸리지 않는다.

원본 세 개 모두 `~/Downloads`에 있다. **재다운로드 불필요.**

## 범위 — A1만 한다

| | 범위 | 무엇이 바뀌나 |
| --- | --- | --- |
| **A1 (이번)** | 고를 때 보여주기만 | `Food` · `FoodResponse` · 정제 스크립트 · 재적재 |
| A2 (안 함) | 끼니에 저장·하루 합산 | 위에 더해 `MealItem`·`MealItemRequest`·`MealItemResponse`·`Meal` 합계·`DayResponse`·`FrequentItemResponse`·`AnalyzedItem`, 그리고 **LLM 인식 스키마·프롬프트** |

**A2는 하지 않는다.** 미매칭 항목은 LLM 추정값으로 채우므로 OpenRouter `json_schema`와 프롬프트까지
번지고, iOS의 다섯 경로 통과 테스트가 7필드 → 10필드가 된다. 화면을 먼저 써 보고 판단한다.

A1만 하면 한계가 하나 남는다 — **자주 먹는 음식과 사진 인식 항목은 이 값을 못 보여준다.**
그쪽은 `Food`가 아니라 저장된 `MealItem`에서 오기 때문이다. 검색 결과에서만 세 줄이 보인다.
이건 알고 받는 한계다.

## ⚠️ 먼저 읽을 함정 둘

### 함정 1 — `"지방"` 부분 문자열이 `"포화지방산"`을 잡는다

두 스크립트 모두 컬럼을 **부분 문자열**로 찾는다.

`scripts/xlsx-to-csv.py`의 `find_column`은 원본 헤더를 **원본 순서대로** 훑는다. 그래서 음식
원본에서는 `지방(g)`(21번)이 `포화지방산(g)`(40번)보다 먼저 잡힌다. 함수 docstring에 그 의도가
적혀 있다.

문제는 그다음이다. `scripts/build-food-csv.py`는 **xlsx-to-csv가 뱉은 중간 CSV**를 읽고, 그
CSV의 컬럼 순서는 `WANTED` 리스트 순서다. 그러니 `WANTED`에 `포화지방산(g)`을 `지방(g)`보다
**앞에 넣으면** 중간 CSV에서 포화지방산이 먼저 오고, `build-food-csv.py`의 `"fat": ["지방"]`이
**포화지방산을 지방으로 읽는다.** 아무 오류 없이 지방 값이 통째로 틀어진다.

**→ 세 항목은 반드시 `지방(g)` 뒤에 넣는다.** `WANTED`와 `COLUMN_HINTS` 양쪽 모두.
안전하게 하려면 힌트를 `"지방(g)"`처럼 괄호까지 붙여 더 구체적으로 쓰는 것도 방법이다.

작업 후 **반드시 눈으로 확인한다**: 정제된 CSV에서 임의의 몇 행을 골라 원본 엑셀의 같은
식품코드와 지방·포화지방산 값을 대조한다. 뒤바뀌어도 프로그램은 아무 말도 하지 않는다.

### 함정 2 — `FoodCsvParser`는 마지막 조각이 이름이라는 데 기대고 있다

```kotlin
private const val COLUMN_COUNT = 10
val columns = line.split(',', limit = COLUMN_COUNT)
val name = columns[9]
```

`limit = 10`이라 **10번째 조각이 줄의 나머지 전부**다. 식품명에 쉼표가 들어가도 안전한 이유가
이것이고, 파일 주석에도 그렇게 적혀 있다.

**→ 새 컬럼 셋은 `name` 앞에 넣고, `name`은 계속 마지막이어야 한다.** `COLUMN_COUNT`는 13으로
바꾼다. 순서를 어기면 이름에 쉼표가 있는 식품에서 컬럼이 밀린다.

현재 정제본 헤더:
```
code,servingSizeG,kcalPer100g,carbsPer100g,proteinPer100g,fatPer100g,sugarPer100g,sodiumMgPer100g,fiberPer100g,name
```
바꿀 헤더:
```
code,servingSizeG,kcalPer100g,carbsPer100g,proteinPer100g,fatPer100g,sugarPer100g,sodiumMgPer100g,fiberPer100g,saturatedFatPer100g,transFatPer100g,cholesterolMgPer100g,name
```

## 해야 할 일

### 1. `scripts/xlsx-to-csv.py`

`WANTED`에 세 항목을 **`식이섬유(g)` 뒤, `1인(회)분량참고량` 앞**에 넣는다.
`OPTIONAL`에도 셋을 더한다 — 판본에 따라 빠질 수 있고, 그때 탄단지까지 죽으면 안 된다.

### 2. `scripts/build-food-csv.py`

- `COLUMN_HINTS`에 `saturatedFat`·`transFat`·`cholesterol` 추가. **`fat` 항목보다 아래에 둔다**
- `OPTIONAL_COLUMNS`에 셋 추가
- 출력 컬럼 순서를 위 헤더대로 — 셋은 `fiber` 뒤, `name` 앞
- **기준량 정규화를 똑같이 태운다.** 기존 영양소가 `영양성분함량기준량`으로 100g 환산되는
  경로를 그대로 타야 한다. 이걸 빼면 기준량이 200g인 행만 값이 2배가 된다

### 3. `Food` 엔티티

```kotlin
@Column(name = "saturated_fat_per_100g", nullable = false, columnDefinition = "double precision not null default 0")
val saturatedFatPer100g: Double = 0.0,
@Column(name = "trans_fat_per_100g", nullable = false, columnDefinition = "double precision not null default 0")
val transFatPer100g: Double = 0.0,
@Column(name = "cholesterol_mg_per_100g", nullable = false, columnDefinition = "double precision not null default 0")
val cholesterolMgPer100g: Double = 0.0,
```

`columnDefinition`에 default를 넣는 이유 — `ddl-auto: update`가 **행이 있는 테이블에 NOT NULL
컬럼을 붙이면 실패**한다. `serving_size_known`이 같은 이유로 default를 달고 있다(그 선례를 따른다).

### 4. `FoodCsvParser`

`COLUMN_COUNT`를 13으로, 인덱스 9·10·11에서 셋을 읽고 `name`은 12로. 기존 당류·나트륨·식이섬유와
같이 **못 읽으면 `0.0`으로 떨어뜨린다**(`?: 0.0`) — 판본에 컬럼이 없을 수 있다.

### 4-2. `FoodSeeder` — **이 문서가 빠뜨렸던 자리다**

적재는 JPA가 아니라 `JdbcTemplate` 배치라 `INSERT_SQL`의 컬럼 목록·물음표 개수·`ps.setX` 인덱스를
**손으로** 맞춰야 한다. 15 → 18로 늘리고 `created_at`·`updated_at`이 뒤로 밀린다.
셋 다 안 고치면 조용히 어긋난 값이 들어간다.

눈이 아니라 스크립트로 대조한다(`AGENTS.md`의 그 규칙이 나온 자리다):

```bash
python3 - <<'PY'
import re
src = open("apps/daily-record/src/main/kotlin/com/toy/backend/diet/food/FoodSeeder.kt").read()
m = re.search(r"insert into food \((.*?)\)\s*\n\s*values \((.*?)\)", src, re.S)
names = [c.strip() for c in m.group(1).replace("\n", " ").split(",")]
idx = sorted(int(i) for i in re.findall(r"ps\.set\w+\((\d+),", src))
print(len(names), m.group(2).count("?"), idx)
PY
```

### 5. `FoodDtos.kt`

`FoodResponse`에 셋 추가하고 `toResponse()`에 매핑. **`nutritionFor`/`NutritionAmount`는 건드리지
않는다** — A1은 표시 전용이라 끼니 저장 경로로 흐르지 않는다.

### 6. `README.md` 갱신

`apps/daily-record/src/main/resources/food/README.md`의 재생성 절차와 컬럼 설명에 셋을 반영한다.
함정 1·2도 여기 남긴다 — 다음에 컬럼을 더할 사람이 같은 데 빠진다.

### 7. 재정제 · 재적재

```bash
# 세 데이터셋 모두 다시 만든다 (원본은 ~/Downloads에 있다)
python3 scripts/xlsx-to-csv.py ~/Downloads/음식DB.xlsx /tmp/food-raw.csv
python3 scripts/build-food-csv.py /tmp/food-raw.csv \
  apps/daily-record/src/main/resources/food/food-nutrition.csv
# 원재료 — --representative 필수
# 가공식품 — 30만 행, 시간이 걸린다
```

**순서가 중요하다:**
1. 먼저 `delete from food;` — 행이 있는 채로 새 스키마를 올리면 NOT NULL 추가가 걸린다
2. 그다음 재기동 — 빈 테이블에 컬럼이 붙고 `FoodSeeder`가 세 데이터셋을 적재한다

`FoodSeeder`는 `existsByDataset`으로 이미 적재된 데이터셋을 건너뛰므로, **지우지 않으면 새 컬럼이
0인 옛 행이 그대로 남는다.** 30만 행 적재는 수 분 걸린다.

## 테스트

- `FoodCsvParserTest` — 13컬럼 파싱, 세 값이 제자리에 들어가는지, **이름에 쉼표가 있는 행**이
  안 밀리는지, 컬럼이 모자란 줄이 `0.0`으로 떨어지는지
- 기준량 정규화 — 기준량 `200g`인 행의 세 값이 절반으로 환산되는지
- **지방과 포화지방산이 뒤바뀌지 않았는지** — 둘이 다른 값인 픽스처로 고정한다. 함정 1이
  실제로 재현되는 자리다

---

# 작업 B — `GET /diet/foods`에 `dataset` 필터

## 배경

iOS 검색 화면에 `전체 / 음식 / 원재료 / 가공식품` 필터 칩을 넣는다. 지금은 서버에 파라미터가
없어 **받아 온 페이지를 앱에서 거른다.** 그래서 상위 결과가 전부 가공식품이면 「음식」 칩이 빈
목록을 보여준다 — 실제로 매칭되는 조리 음식이 있는데도 그렇다.

앱은 우선 `size`를 20에서 50(서버 상한)으로 올려 완화하지만, 근본 해법은 서버 필터다.

## 해야 할 일

리포지토리에 **이미 `searchByDatasetAndNormalizedName`이 있다.** 새로 쓸 쿼리가 없다.

- `FoodController.search`에 `@RequestParam(required = false) dataset: FoodDataset?` 추가
- `FoodMatcher.search(keyword, size, dataset)` — `dataset`이 있으면
  `searchByDatasetAndNormalizedName`, 없으면 기존 `searchByNormalizedName`
- 기존 호출부(파라미터 없는 검색)가 그대로 동작해야 한다 — **인식 파이프라인의 `match`는
  건드리지 않는다.** 그쪽은 데이터셋 우선순위 로직이 따로 있다

## 테스트

- `dataset=DISH`면 `DISH` 행만 오는지
- 파라미터가 없으면 기존과 동일하게 전 데이터셋에서 오는지
- 잘못된 값이면 — **확인 결과 손댈 것이 없다.** Spring이 `MethodArgumentTypeMismatchException`을
  던지고 `CustomExceptionHandler`가 이미 400으로 받는다(`"요청 파라미터(dataset)의 형식이
  잘못되었습니다."`). `error`는 `INVALID_REQUEST`가 아니라 `BAD_REQUEST`다

---

# 구현 결과 (2026-07-30)

**함정 1은 발화하지 않았다.** 재정제본의 기존 10컬럼이 옛 정제본과 **전 행 0차이**다
(음식 6,090 · 원재료 523 · 가공식품 306,293 — 행 수도 그대로).

새 세 컬럼은 **원본 워크북에서 컬럼 인덱스로 직접 읽어** 대조했다. 중간 CSV를 거치면 매핑
자체를 검증할 수 없기 때문이다. 가공식품은 표본이 아니라 **306,293행 전부 대조해 불일치 0건**,
음식·원재료도 불일치 0건이다.

**기준량 환산은 실데이터로 검증되지 않는다** — 세 원본 모두 `영양성분함량기준량`이 전 행 100g
(또는 100ml)이라 `factor`가 항상 1이다. 기준량 200g짜리 합성 입력으로 확인했다: 포화 6→3,
트랜스 0.4→0.2, 콜레스테롤 80→40. 판본이 바뀌어 기준량이 섞여 들어오면 그때 다시 볼 자리다.

「포화지방산 ≤ 지방」 위반이 음식 18/6,090 · 원재료 1/523 · 가공식품 363/306,293건 있는데
**원본 자체가 그렇다**(망고 쥬스: 지방 0.03 / 포화지방산 0.28). 매핑이 뒤바뀌었다면 소수가
아니라 전 행이 깨진다.

## 아직 안 한 것

- **서버 재적재.** 배포처 DB는 옛 스키마·옛 값 그대로다. `delete from food;` → 재기동
  순서를 지켜야 한다(행이 있으면 NOT NULL 컬럼 추가가 걸리고, `existsByDataset` 때문에
  안 지우면 새 컬럼이 0인 옛 행이 남는다). 30만 행 적재는 수 분 걸린다
- 실기동 확인 — `GET /diet/foods?q=...&dataset=DISH`와 세 영양소가 응답에 실리는지

# 범위 밖

A2(끼니 저장·하루 합산·LLM 스키마) · 즐겨찾기 테이블 · 인기도(조회수) · 사용자 정의 단위
(「내 밥공기 = 210g」) · 불포화지방산 등 나머지 미량영양소.
