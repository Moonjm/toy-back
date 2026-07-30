# 식품DB 적재 데이터

이 디렉터리의 `*.csv`는 **저장소에 커밋하지 않는다**(`.gitignore`). 가공식품 정제본이 23MB라
저장소와 도커 이미지가 그만큼 무거워지는 데 비해, 원본에서 언제든 다시 만들 수 있는 파일이다.

| 파일 | 내용 | 대략 크기 |
| --- | --- | --- |
| `food-nutrition.csv` | 음식(조리된 한식) 6,090행 | 440KB |
| `raw-food-nutrition.csv` | 원재료성식품(과일·채소·달걀·육류) 523행 | 40KB |
| `processed-food-nutrition.csv` | 가공식품(브랜드 제품) 306,293행 | 26MB |

**파일이 없어도 앱은 뜬다.** `FoodSeeder`가 경고만 남기고 넘어가고, 음식 매칭이 전부 실패해
LLM 추정값(`source = LLM_ESTIMATED`)으로 떨어질 뿐이다. 다만 그 상태에서는 이 도메인이 노리는
"영양소는 식품DB에서, 결정적으로"가 성립하지 않으므로 배포 전에는 채워 넣어야 한다.

**한쪽만 먼저 만들어도 된다.** 시더는 데이터셋별로 적재 여부를 보므로, 음식만 넣고 기동한 뒤
나중에 가공식품 CSV를 채워 다시 기동하면 그때 적재된다. 다시 넣고 싶으면 그 데이터셋만 지운다:

```sql
delete from food where dataset = 'PROCESSED';
```

## `RAW`를 처음 적재할 때 — CHECK 제약을 먼저 지운다

**이미 돌던 DB에는 `dataset` 값을 `DISH`/`PROCESSED`로 묶는 CHECK 제약이 남아 있다.**
`ddl-auto: update`는 이 제약을 갱신하지 못하므로 `RAW` 삽입이 통째로 실패한다.

```sql
alter table food drop constraint if exists food_dataset_check;
```

한 번만 하면 된다 — 엔티티에 `columnDefinition = "varchar(20)"`이 있어 Hibernate가 다시
만들지 않는다(드롭 후 재기동해 확인했다). 이후 enum 값을 더 늘릴 때도 같은 문제가 없다.

## 다시 만드는 법

원본은 공공데이터포털 `전국통합식품영양성분정보` 표준데이터다. **세 종류를 모두 받는다.**

- 음식: <https://www.data.go.kr/data/15100070/standard.do>
- 원재료성식품: <https://www.data.go.kr/data/15100065/standard.do>
- 가공식품: <https://www.data.go.kr/data/15100066/standard.do> — 판본이 자주 갱신된다. 최신 판을 받으면 행 수와 컬럼 수가 달라질 수 있고, `1인(회)분량 참고량`처럼 컬럼이 통째로 사라지기도 한다(2026-07-28 판에서 실제로 사라졌다). 스크립트가 `식품중량`으로 폴백하되, **1회 제공량으로 볼 수 없는 값(500g 초과)은 파서가 결측으로 되돌린다**

> **2026-07-30 — 원재료성식품을 추가했다.** 원래 "조리된 음식과 가공식품만" 받기로 했는데,
> 실기동에서 과일 접시 사진의 「복숭아」가 가공식품(말린 것으로 보이는 225kcal/100g)에 걸려
> 200g이 450kcal로 잡혔다(생복숭아는 80kcal). 바나나는 실제의 5배, 계란·고구마는 아예 다른
> 음식(계란빵·고구마전)이 잡혔다. **사진에 찍히는 게 전부 조리된 음식이라는 전제가 틀렸다** —
> 과일 접시처럼 원재료 그대로 먹는 경우가 흔하다.
> 그리드 다운로드는 5만 건 제한이 있으니 행 수가 그보다 많으면 오픈API로 받아야 한다.

```bash
pip3 install openpyxl

# 음식
python3 scripts/xlsx-to-csv.py ~/Downloads/음식DB.xlsx /tmp/food-raw.csv
python3 scripts/build-food-csv.py /tmp/food-raw.csv \
  apps/daily-record/src/main/resources/food/food-nutrition.csv

# 원재료성식품 — 포털이 .xls(구형)로 준다. --representative 가 필수다
python3 scripts/xlsx-to-csv.py "~/Downloads/전국통합식품영양성분정보_원재료성식품_표준데이터-YYYYMMDD.xls" /tmp/raw-raw.csv
python3 scripts/build-food-csv.py --representative /tmp/raw-raw.csv \
  apps/daily-record/src/main/resources/food/raw-food-nutrition.csv

# 가공식품
python3 scripts/xlsx-to-csv.py ~/Downloads/가공식품DB.xlsx /tmp/processed-raw.csv
python3 scripts/build-food-csv.py /tmp/processed-raw.csv \
  apps/daily-record/src/main/resources/food/processed-food-nutrition.csv
```

한글 파일명은 **경로를 통째로 따옴표로 감싼다** — macOS 파일명이 NFD라 글로브가 조용히 빈
결과를 내는 일이 있었다.

**원재료성식품은 다른 두 개와 형태가 다르다.** `식품명`이 `복숭아_천중도_생것`처럼 계층형이라
그대로 넣으면 「복숭아」와 완전일치하지 않는다. `--representative`가 `대표식품명`으로 바꾸고,
같은 대표명 안에서 **`생것`만 남긴 뒤 열량이 중앙값인 행**을 고른다(3,704행 → 523행).

- 생것만 남기는 이유 — 말린것·동결건조가 섞이면 열량이 몇 배 뛴다(바나나 77 / 말린것 314)
- 중앙값인 이유 — 처음엔 「이름이 가장 짧은 행」이었는데 `닭고기`는 부위별 행만 있어
  가장 짧은 `닭고기_목_생것`(342kcal, 가슴살의 3배)이 뽑혔다. 중앙값은 부위 편차를 타지 않는다
- 1인분량 컬럼이 아예 없어 523행 전부 `DEFAULT_SERVING_SIZE_G`(200g)로 채워진다

`build-food-csv.py`가 마지막에 적재 행 수·버린 행 수·1인분량 출처를 찍는다. 판본이 바뀌었을 때
이 숫자가 크게 달라지면 원본 컬럼 구성이 바뀐 것이니 스크립트를 확인해야 한다.

## 정제본 컬럼

```
code,servingSizeG,kcalPer100g,carbsPer100g,proteinPer100g,fatPer100g,
sugarPer100g,sodiumMgPer100g,fiberPer100g,
saturatedFatPer100g,transFatPer100g,cholesterolMgPer100g,name
```

영양소는 전부 100g당이고 원본의 `영양성분함량기준량`으로 환산된 값이다. 뒤 셋(포화지방산·
트랜스지방산·콜레스테롤)은 **검색 화면 표시 전용**이라 끼니에 저장되지 않는다.

### 컬럼을 더할 때 빠지는 함정 둘

**① `"지방"` 부분 문자열이 `"포화지방산(g)"`을 잡는다.**
두 스크립트 모두 컬럼을 부분 문자열로 찾는다. `xlsx-to-csv.py`는 **원본 순서**로 훑어서
`지방(g)`(20번)이 `포화지방산(g)`(39번)보다 먼저 잡히지만, `build-food-csv.py`가 읽는 중간
CSV의 컬럼 순서는 `WANTED` 순서다. 세 항목을 `지방(g)` **앞에** 넣으면 `"fat": ["지방"]`이
포화지방산을 지방으로 읽는다 — 오류 없이 지방 값이 통째로 틀어진다.
`WANTED`·`COLUMN_HINTS` 양쪽에서 `지방(g)` 뒤에 두고, `fat` 힌트는 `지방(g)`을 먼저 시도한다.

**② `name`은 반드시 마지막이다.**
Kotlin 파서가 `split(',', limit = COLUMN_COUNT)`로 읽어 마지막 조각이 줄의 나머지 전부다.
그래서 이름에 쉼표가 있어도 안전하다(`밥, 국`). 새 컬럼을 `name` 뒤에 넣으면 그런 행이 밀린다.
컬럼 수를 바꿨으면 `FoodCsvParser.COLUMN_COUNT`와 `FoodSeeder.INSERT_SQL`도 함께 고친다.

**작업 후 원본과 눈으로 대조한다.** 정제본에서 몇 행을 골라 같은 식품코드의 원본 지방·
포화지방산 값을 확인한다. 뒤바뀌어도 스크립트는 아무 말도 하지 않는다. 「포화지방산 ≤ 지방」이
전 행에서 성립하는지 보는 것도 값싼 검사다(원본 자체가 어긋난 행이 음식 6,090중 18건 있다).

**버려지는 행이 많은 것은 정상이다.** 음식 데이터셋 19,495행 중 13,405행은 외식 업체가 제공한
정보라 열량·단백질만 있고 탄수화물·지방이 비어 있다. 탄단지가 없으면 이 도메인의 점수를 계산할
수 없어, 틀린 값을 채우느니 버리고 LLM 추정에 맡긴다.

## 배포

`./deploy.sh daily-record`는 도커 이미지를 빌드해 라즈베리파이로 보낸다. 이 CSV들은 이미지에
포함되므로, **빌드하는 기계에 파일이 있어야 파이에서도 식품DB가 채워진다.** 빌드 전에 위
명령으로 생성해 두면 된다.
