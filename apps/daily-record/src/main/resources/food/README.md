# 식품DB 적재 데이터

이 디렉터리의 `*.csv`는 **저장소에 커밋하지 않는다**(`.gitignore`). 가공식품 정제본이 23MB라
저장소와 도커 이미지가 그만큼 무거워지는 데 비해, 원본에서 언제든 다시 만들 수 있는 파일이다.

| 파일 | 내용 | 대략 크기 |
| --- | --- | --- |
| `food-nutrition.csv` | 음식(조리된 한식) 6,090행 | 440KB |
| `processed-food-nutrition.csv` | 가공식품(브랜드 제품) 298,271행 | 23MB |

**파일이 없어도 앱은 뜬다.** `FoodSeeder`가 경고만 남기고 넘어가고, 음식 매칭이 전부 실패해
LLM 추정값(`source = LLM_ESTIMATED`)으로 떨어질 뿐이다. 다만 그 상태에서는 이 도메인이 노리는
"영양소는 식품DB에서, 결정적으로"가 성립하지 않으므로 배포 전에는 채워 넣어야 한다.

**한쪽만 먼저 만들어도 된다.** 시더는 데이터셋별로 적재 여부를 보므로, 음식만 넣고 기동한 뒤
나중에 가공식품 CSV를 채워 다시 기동하면 그때 적재된다. 다시 넣고 싶으면 그 데이터셋만 지운다:

```sql
delete from food where dataset = 'PROCESSED';
```

## 다시 만드는 법

원본은 공공데이터포털 `전국통합식품영양성분정보` 표준데이터다. **`음식`과 `가공식품`만 받는다**
(`원재료성식품`은 쓰지 않는다).

- 음식: <https://www.data.go.kr/data/15100070/standard.do>
- 가공식품: 같은 포털의 `전국통합식품영양성분정보(가공식품)` — 판본이 자주 갱신된다. 최신 판을 받으면 행 수와 컬럼 수가 달라질 수 있고, `1인(회)분량 참고량`처럼 컬럼이 통째로 사라지기도 한다(2026-07-28 판에서 실제로 사라졌다). 스크립트가 `식품중량`으로 폴백하므로 그대로 동작한다

```bash
pip3 install openpyxl

# 음식
python3 scripts/xlsx-to-csv.py ~/Downloads/음식DB.xlsx /tmp/food-raw.csv
python3 scripts/build-food-csv.py /tmp/food-raw.csv \
  apps/daily-record/src/main/resources/food/food-nutrition.csv

# 가공식품
python3 scripts/xlsx-to-csv.py ~/Downloads/가공식품DB.xlsx /tmp/processed-raw.csv
python3 scripts/build-food-csv.py /tmp/processed-raw.csv \
  apps/daily-record/src/main/resources/food/processed-food-nutrition.csv
```

`build-food-csv.py`가 마지막에 적재 행 수·버린 행 수·1인분량 출처를 찍는다. 판본이 바뀌었을 때
이 숫자가 크게 달라지면 원본 컬럼 구성이 바뀐 것이니 스크립트를 확인해야 한다.

**버려지는 행이 많은 것은 정상이다.** 음식 데이터셋 19,495행 중 13,405행은 외식 업체가 제공한
정보라 열량·단백질만 있고 탄수화물·지방이 비어 있다. 탄단지가 없으면 이 도메인의 점수를 계산할
수 없어, 틀린 값을 채우느니 버리고 LLM 추정에 맡긴다.

## 배포

`./deploy.sh daily-record`는 도커 이미지를 빌드해 라즈베리파이로 보낸다. 이 CSV들은 이미지에
포함되므로, **빌드하는 기계에 파일이 있어야 파이에서도 식품DB가 채워진다.** 빌드 전에 위
명령으로 생성해 두면 된다.
