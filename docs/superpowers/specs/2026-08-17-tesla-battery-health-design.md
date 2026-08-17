# TeslaMate 차량 대시보드 집계 설계

**한 줄 요약.** 배터리 열화 표본(1단계)과 주행 인사이트(2단계)를 집계해 낸다. 새로 읽는 테이블도, 쓰는 것도 없다.

## 배경

[2026-08-13 차량 요약·상태 설계](2026-08-13-tesla-vehicle-summary-design.md)로 `/tesla/status`가 나갔다. 그 응답에는 **지금 이 순간의 값만** 있다 — 배터리 82%, 주행가능 430km. 어제와 견줄 수 없으니 열어 봐야 알게 되는 것이 없다.

전기차를 오래 타면서 궁금해지는 것은 **배터리가 얼마나 줄었나**인데, 그 답은 이미 읽고 있는 테이블에 들어 있다. `charging_processes` 한 행에는 충전이 끝난 시점의 배터리 %(`end_battery_level`)와 주행가능거리(`end_rated_range_km`)가 함께 남는다. 둘을 나누면 그날의 만충 환산 주행거리가 나오고, 시간축에 늘어놓으면 열화 곡선이 된다.

용량도 같은 행에서 나온다. `charge_energy_added ÷ ΔSoC × 100`이 그 시점의 사용 가능 용량이다.

주행 쪽도 같다. `drives`에는 거리·소요 시간·외기 온도 평균·주행가능거리 증감이 매 건 남아 있어, 온도별 전비와 주행 습관이 한 테이블에서 나온다.

**새 테이블을 읽지 않는다.** `charging_processes`·`drives`는 이미 `/tesla/summary`가 훑고 있고, 행 수도 수백~수천 단위다 — `positions`(3,000만 행)에서 겪은 문제가 여기엔 없다.

### 실측으로 확인한 것

2026-08-15 충전 한 건이 `17% → 99%`, 주행가능거리 `90km → 520km`, `charge_energy_added` 58.7kWh였다. 100% 환산하면 주행거리 약 525km, 용량 약 71.6kWh다. 대상 차량(2021년 9월 출고 모델 3 롱 레인지)의 EPA 제원 568km / 사용 가능 약 78.5kWh와 견주면 잔존 92%·91%로 두 지표가 같은 자리를 가리킨다. **TeslaMate가 저장하는 `rated_battery_range_km`는 EPA 기준이다**(국내 인증 528km가 아니다).

이 기준선 상수는 **서버에 두지 않는다.** 아래 「서버가 하지 않는 것」 참고.

## 목표

- 월별 만충 환산 주행거리와 사용 가능 용량을 표본 수와 함께 낸다.
- 표본으로 쓸 만한 충전만 골라 낸다 — 얕은 보충 충전이 곡선을 흔들지 않게.
- 온도별 전비·주행 시간대·거리 분포·자주 가는 곳을 한 응답으로 낸다.

## 비목표

- **쓰기.** 이 엔드포인트들은 읽기만 한다. 여전히 유일한 쓰기는 `charging_processes.cost`다.
- **잔존율·열화율 계산.** 신차 기준값은 차종 상수라 서버가 알 이유가 없다.
- **진행 중인 충전·주행.** `end_date IS NULL`인 행은 제외한다. 마감 전 값은 흔들린다.
- **`positions` 조회.** 경로·속도 샘플은 이 설계에 없다. 아래 「보류」 참고.

---

## 단계

| 단계 | 엔드포인트 | 읽는 테이블 |
|---|---|---|
| **1단계** | `GET /tesla/battery-health` | `charging_processes` |
| **2단계** | `GET /tesla/drive-insights` | `drives`, `geofences`, `cars` |
| **3단계** | 누적 합계·충전 곡선 | `charging_processes`, `charges` |
| **보류** | 주행 경로·속도 샘플 | `positions` — 인덱스 확인 선행 |

---

# 1단계 — `GET /tesla/battery-health`

```json
{
  "data": {
    "samples": [
      { "yearMonth": "2026-07", "fullRangeKm": 527.1, "capacityKwh": null, "sampleCount": 1, "capacitySampleCount": 0 },
      { "yearMonth": "2026-08", "fullRangeKm": 525.3, "capacityKwh": 71.6, "sampleCount": 3, "capacitySampleCount": 1 }
    ]
  }
}
```

- **오래된 것부터.** `/tesla/summary`의 `trend`와 같은 방향이다.
- **파라미터가 없다.** 전 기간을 낸다. 몇 년을 타도 월 행 수는 수십이라 자를 이유가 없고, 열화는 시작점부터 봐야 의미가 있다. 화면에서 몇 개월을 그릴지는 앱이 정한다.
- **표본이 없는 달은 배열에서 빠진다.** `trend`가 빈 달의 자리를 채우는 것과 다르다 — 그쪽은 「그 달에 안 탔다」와 「기록이 없다」를 구분해야 하지만, 열화는 월 경계가 의미를 갖는 값이 아니다. 없는 달은 없는 대로 두고, 선을 이을지 끊을지는 앱이 정한다.
- `capacityKwh`는 그 달에 용량 표본 조건을 채운 충전이 없으면 **null**이다. 0이 아니다.

## 표본 규칙

| 지표 | 조건 | 이유 |
|---|---|---|
| 만충 환산 주행거리 | `end_battery_level >= 80` | 낮은 SoC에서 100%로 환산하면 오차가 커진다. 주행가능거리가 1km 틀리면 80%에서는 1.25km, 20%에서는 5km 틀린 값이 나온다 |
| 사용 가능 용량 | `end_battery_level >= 80` **AND** `end_battery_level - start_battery_level >= 40` | 짧은 보충은 분모가 작아 오차가 크다. 앞의 조건은 공통 WHERE에서 물려받는 것이지 용량만의 독립 조건이 아니다 — 아래 「표가 SQL과 어긋나 있었다」 참고 |
| 공통 | `end_date IS NOT NULL` | 진행 중인 충전은 값이 흔들린다 |

두 조건이 달라 표본 수도 따로 낸다(`sampleCount`·`capacitySampleCount`). 한 숫자로 합치면 용량이 null인 이유가 「표본이 없어서」인지 「값이 없어서」인지 화면에서 갈리지 않는다.

**표가 SQL과 어긋나 있었다(2026-08-17 발견):** 이 표는 처음에 용량 행의 조건을 ΔSoC ≥ 40만으로 적었다. 그런데 실제 SQL(`BATTERY_HEALTH_MONTHLY_SQL`)은 두 지표를 하나의 CTE 공통 WHERE 위에 얹으므로, 용량 표본도 `end_battery_level >= 80`을 함께 통과해야 한다 — 20%→70%(ΔSoC 50, end 70%) 같은 충전은 표만 보면 유효한 용량 표본이지만 실제로는 세지 않는다. SQL은 그대로 두기로 결정했다(80% 조건을 용량 쪽에서만 풀면 `full_range_km`도 CASE로 감싸야 해 `fullRangeKm`의 non-null 보장이 깨지고, 실효 차이는 3.5%뿐이다). 2단계 표를 작성할 때는 표를 SQL과 줄 단위로 대조한다.

**2026-08-17 실측:** 이 차량의 60개월 전 구간(2021-09~2026-08)을 실 DB에서 돌려 보니 `capacityKwh`가 null인 달이 하나도 없었다 — 공통 WHERE(`end_battery_level >= 80`)와 ΔSoC ≥ 40을 함께 채우는 충전이 매달 최소 1건씩은 있었다. 전체 누적으로는 range 표본 384건 대, 엔드포인트가 실제로 내는 capacity 표본 329건으로 약 14.3% 적다(공통 WHERE의 80% 조건을 빼고 셌던 341건은 실제 응답과 맞지 않는 잘못된 값이었다). 「자주 null이다」는 예측은 이 차량의 실데이터와 맞지 않는다 — null 처리 자체는 표본이 더 적은 차량·기간을 위한 옳은 방어이므로 코드는 그대로 두되, null이 흔하다는 전제로 화면을 설계하지 않는다.

## 나눗셈을 서버가 하는 예외

이 저장소는 **나눗셈을 앱에 맡겨 왔다**(`MonthlyStat`이 km당 비용·전비를 내지 않는 이유). 분모가 0이거나 null일 때의 처리를 서버가 정해 버리면 화면이 그것을 따라야 하기 때문이다.

여기는 예외다. **중앙값은 표본 집합 위의 연산이라 나누기 전에는 낼 수 없다.** 원자료를 그대로 보내면 앱이 수백 건을 받아 월별로 묶고 정렬해 중앙값을 내야 하는데, 그건 SQL 한 줄이 하는 일이다.

대신 **분모가 0이 될 길을 WHERE에서 막는다.** `end_battery_level >= 80`이면 0이 아니고, 용량 쪽은 ΔSoC ≥ 40이라 역시 0이 아니다. 서버가 「0일 때 어떻게 할지」를 정하는 상황 자체가 생기지 않는다.

2단계는 이 예외에 해당하지 않는다 — 거기서는 합만 내고 나눗셈은 앱이 한다.

## 평균이 아니라 중앙값인 이유

급속 충전 직후에는 `rated_battery_range_km`가 실제보다 높거나 낮게 잡히는 일이 있다. 한 달에 표본이 두세 개뿐인 달에서 평균은 그 한 건에 끌려간다. 중앙값은 끌려가지 않는다.

## 월 경계는 `end_date` 기준으로 KST에서 자른다

측정 시점은 충전이 **끝난** 때다. `start_date`로 자르면 자정을 넘긴 오버나이트 충전이 앞 달로 들어간다.

KST로 옮긴 뒤 자르는 것은 `DRIVE_MONTHLY_SQL`과 같은 이유다 — UTC 기준으로 자르면 월초 9시간이 옆 달로 샌다.

## SQL

```sql
WITH sample AS (
    SELECT date_trunc(
               'month',
               cp.end_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul'
           )::date AS month_start,
           cp.end_rated_range_km / cp.end_battery_level * 100 AS full_range_km,
           CASE
               WHEN cp.end_battery_level - cp.start_battery_level >= 40
                AND cp.charge_energy_added > 0
               THEN cp.charge_energy_added
                    / (cp.end_battery_level - cp.start_battery_level) * 100
           END AS capacity_kwh
      FROM charging_processes cp
     WHERE cp.end_date IS NOT NULL
       AND cp.end_battery_level >= 80
       AND cp.end_rated_range_km IS NOT NULL
       AND cp.start_battery_level IS NOT NULL
)
SELECT month_start,
       COUNT(*)                AS row_count,
       COUNT(capacity_kwh)     AS capacity_row_count,
       ROUND(percentile_cont(0.5) WITHIN GROUP (
           ORDER BY full_range_km::double precision)::numeric, 1) AS full_range_km,
       ROUND(percentile_cont(0.5) WITHIN GROUP (
           ORDER BY capacity_kwh::double precision)::numeric, 1)  AS capacity_kwh
  FROM sample
 GROUP BY month_start
 ORDER BY month_start
```

- `percentile_cont`는 `double precision`을 받으므로 명시적으로 캐스팅한다. 결과는 `numeric`으로 되돌려 소수 한 자리로 반올림한다 — 부동소수 잡음이 응답에 그대로 나가지 않게 한다.
- `percentile_cont`는 null 입력을 무시한다. 그 달에 용량 표본이 하나도 없으면 `capacity_kwh`가 null로 나오고, `COUNT(capacity_kwh)`가 0이 된다.
- 만충 환산이 신차 기준을 넘는 값(냉간·BMS 재보정)은 나올 수 있다. **자르지 않고 그대로 낸다.**

## 성능

`charging_processes`는 수백 행이다. 전체 스캔이라도 즉시 끝나므로 창을 두지 않는다. `positions`에서 겪은 문제(3,000만 행·BRIN만 있는 `date`)와는 무관한 테이블이다.

인덱스를 새로 만들지 않는다 — 남의 스키마다.

## 서버가 하지 않는 것

**신차 기준값(568km / 78.5kWh)은 앱 상수다.** 서버에 두지 않는 이유는 셋이다.

1. 차종·연식마다 다른 값인데, TeslaMate에는 제원표가 없다(`cars`에 있는 것은 `model`·`trim_badging`·`efficiency`뿐이다). 어차피 손으로 심는 상수라면 화면에 가까운 쪽이 낫다.
2. 서버가 잔존율을 내면 그 반올림·경계 처리를 화면이 따라야 한다. 이 저장소가 km당 비용을 내지 않는 것과 같은 이유다.
3. 상수가 서버에 있으면 값을 고칠 때 배포가 필요하다.

---

# 2단계 — `GET /tesla/drive-insights?months=12`

한 화면이 네 카드를 함께 그리므로 **한 응답에 넷을 싣는다.** 나누면 같은 화면이 네 번 부르고, 그중 셋은 나머지 하나를 기다린다(`/tesla/summary`가 목록과 합계를 함께 싣는 것과 같은 이유다).

```json
{
  "data": {
    "months": 12,
    "efficiencyKwhPerKm": 0.153,
    "temperatureBuckets": [
      { "fromC": null, "toC": 0, "driveCount": 12, "distanceKm": 320.5, "ratedRangeUsedKm": 380.1 },
      { "fromC": 0, "toC": 10, "driveCount": 34, "distanceKm": 810.2, "ratedRangeUsedKm": 902.7 }
    ],
    "driveTimes": [ { "weekday": 1, "hour": 8, "count": 12 } ],
    "distanceBuckets": [ { "fromKm": 0, "toKm": 5, "driveCount": 62, "distanceKm": 180.2 } ],
    "places": [ { "name": "집", "driveCount": 124, "distanceKm": 812.4 } ]
  }
}
```

- `months`는 1~60. 기본 12. 응답에 되돌려 실어 앱이 무엇을 받았는지 알 수 있게 한다.
- **나눗셈은 앱이 한다.** 서버는 버킷별 **합**만 낸다 — 전비는 `distanceKm ÷ (ratedRangeUsedKm × efficiencyKwhPerKm)`이고, 분모가 0인 버킷 처리는 화면이 정한다. 1단계의 예외(중앙값)와 달리 여기는 합이라 나눌 이유가 없다.
- `efficiencyKwhPerKm`은 `cars.efficiency` 그대로다. **null일 수 있다**(TeslaMate가 아직 못 채운 경우). 그때 앱은 전비 카드를 감춘다.

## 온도별 전비

`drives`에는 kWh가 없다. 주행가능거리 소모량으로 환산한다.

```
ratedRangeUsedKm = start_rated_range_km − end_rated_range_km
소비 kWh          = ratedRangeUsedKm × cars.efficiency
```

- 버킷은 서버가 고정한다: **영하 / 0~10 / 10~20 / 20~30 / 30 이상**(℃). 다섯 개는 계절이 갈리는 최소 단위이고, 앱이 버킷을 정하면 서버가 원자료를 통째로 보내야 한다.
- **빈 버킷도 자리를 지킨다.** 다섯 개가 늘 온다(`driveCount: 0`). 비교하려고 보는 차트라 자리가 비면 축이 흔들린다. 열화 추이(빈 달을 뺀다)와 반대인 이유가 이것이다.
- `outside_temp_avg IS NULL`인 주행은 제외한다. 어느 버킷에도 넣을 수 없다.
- **`ratedRangeUsedKm <= 0`인 주행도 제외한다.** 내리막 회생·주차 중 보정으로 나올 수 있고, 넣으면 전비가 무한대가 된다. 제외한 건수를 `driveCount`가 이미 반영하므로 앱이 「N건 기준」을 적을 수 있다.

## 주행 시간대

```sql
EXTRACT(dow  FROM d.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul') AS weekday,
EXTRACT(hour FROM d.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul') AS hour
```

- `dow`는 **0이 일요일**이다. 앱도 그대로 읽는다.
- **0인 칸은 응답에서 뺀다.** 168칸 중 대부분이 0이고, 히트맵은 없는 칸을 빈칸으로 그리면 된다.
- KST로 옮긴 뒤 뽑는다. UTC로 뽑으면 아침 8시 출근이 밤 11시로 찍힌다.

## 거리 분포

버킷은 서버 고정: `0-5 / 5-20 / 20-50 / 50-100 / 100+`(km). 빈 버킷도 자리를 지킨다. `toKm`이 null이면 상한이 없다는 뜻이다.

## 자주 가는 곳

`end_geofence_id`로 묶어 `geofences.name`을 낸다. 상위 10개.

**주소는 내지 않는다.** 지오펜스가 없는 도착지는 아예 세지 않는다 — `/tesla/status`가 좌표와 주소를 싣지 않는 방침과 같다. 「어디 갔었나」를 알려면 이름을 붙인 곳이면 충분하다.

## 공통 조건과 성능

- `end_date IS NOT NULL AND distance > 0`, 기간은 `end_date >= now() - interval 'N months'`.
- `drives`는 수천 행, `geofences`는 수십 행이다. 전체 스캔으로 충분하다.
- **`positions`를 건드리지 않는다.** 이 엔드포인트가 느려질 길이 없다.

---

# 3단계 — 누적 합계·충전 곡선

- **누적 합계** — 누적 충전 kWh·충전비. `charging_processes` 전체 합이라 쿼리 한 줄이다. 누적 주행거리는 `/tesla/status`의 odometer가 이미 낸다. 주유비 대비 절감액은 **유가 상수가 필요한데 서버에 두지 않는다** — 신차 기준선과 같은 이유다.
- **충전 곡선** — 세션 하나의 kW 샘플(`charges`: `date`·`charger_power`·`battery_level`). `charging_process_id`가 FK라 인덱스가 있고, 한 세션은 수백 행이다. 지난 기록이므로 「실시간을 내지 않는다」는 방침과 부딪히지 않는다.

# 보류 — `positions`가 필요한 것

주행 경로, 속도·전력·고도 샘플, 속도 분포.

**`positions.drive_id` 인덱스가 있는지 확인하는 것이 선행 조건이다.** 없으면 주행 하나를 열 때마다 3,000만 행을 훑는다(`date`가 BRIN뿐이라 창 없는 조회가 11.7초 걸린 전례가 있다). 남의 스키마라 인덱스를 우리가 만들 수도 없다.

```sql
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'positions';
```

이 한 줄로 갈린다. 있으면 설계하고, 없으면 접는다.

---

## 컴포넌트

기존 차량 쪽 구조를 그대로 따른다. 충전(`TeslaCharge*`)이 아니라 **차량(`TeslaVehicle*`)에 붙인다** — 1단계가 읽는 테이블은 `charging_processes`지만, 이 값이 답하는 질문은 「차가 어떤 상태인가」다.

| 파일 | 1단계 | 2단계 |
|---|---|---|
| `TeslaVehicleController` | `GET /tesla/battery-health` | `GET /tesla/drive-insights` |
| `TeslaVehicleService` | `batteryHealth()` | `driveInsights(months)` |
| `TeslaVehicleRepository` | `batteryHealthMonthly()` | `driveTemperatureBuckets()`·`driveTimes()`·`driveDistanceBuckets()`·`drivePlaces()`·`carEfficiency()` |
| `JdbcTeslaVehicleRepository` | 위 SQL | 위 SQL |
| `TeslaVehicleRows` | `BatteryHealthMonthRow` | 각 행 타입 |
| `TeslaVehicleDtos` | `TeslaBatteryHealthResponse`·`BatteryHealthSample` | `TeslaDriveInsightsResponse` 외 |

**nullable 정수는 `getObject`로 읽는다.** `rs.getInt`는 SQL NULL에 0을 돌려줘 없는 값과 진짜 0이 구분되지 않는다 — 기존 주석과 같은 규칙이다.

2단계는 쿼리를 다섯 개로 나눈다. 한 SQL에 CTE로 몰면 서로 다른 GROUP BY 네 개를 UNION으로 붙여야 하고, 그 결과를 다시 갈라 읽어야 한다. **`drives`는 수천 행이라 다섯 번 훑어도 싸다.**

## 오류 처리

TeslaMate DB에 못 붙으면 기존 차량 API와 같은 경로로 실패한다. 이 엔드포인트들만의 오류 코드를 만들지 않는다.

`months`가 범위를 벗어나면 400이다.

앱에서는 이 호출들이 `/tesla/status`와 **독립**이다 — 하나가 실패해도 다른 카드는 그려진다.

## 테스트

`TeslaVehicleServiceTest`의 관례(가짜 리포지토리)를 따른다. SQL 자체는 단위 테스트로 검증되지 않으므로, **서비스가 검증하는 것은 행 → DTO 변환과 정렬·자리 채움뿐**이다.

1단계

- 표본이 하나도 없다 → `samples`가 빈 배열이다(null이 아니다)
- 용량 표본이 없는 달 → `capacityKwh`가 null, `capacitySampleCount`가 0
- 여러 달 → 오래된 것부터 정렬돼 나온다

2단계

- 온도·거리 버킷은 **행이 없어도 다섯 개·다섯 개가 온다**(`driveCount: 0`)
- `cars.efficiency`가 null → 응답 `efficiencyKwhPerKm`이 null
- 지오펜스가 하나도 없다 → `places`가 빈 배열
- `months` 경계(1·60·0·61)

SQL은 실 DB에서 한 번 돌려 눈으로 확인한다 — 특히 **월 경계와 요일·시간이 KST로 잘렸는지**(자정 직후에 끝난 충전이 어느 달로 들어가는지, 아침 출근이 몇 시로 찍히는지)와 `percentile_cont`가 null을 건너뛰는지.

## 열린 항목

- **배터리를 교체하면** 곡선이 위로 튄다. 그때 기준선을 어떻게 할지는 그때 정한다. 서버는 표본을 그대로 내므로 고칠 것이 없다.
- 급속 충전 직후 표본이 계속 튀는 것이 실제로 관찰되면, 표본 조건에 「직전이 급속이 아닐 것」을 더할 수 있다(`charges.fast_charger_present`). 중앙값으로 충분한지 먼저 본다.
- **`cars.efficiency`가 실제로 무엇을 담고 있는지 확인이 필요하다.** kWh/km 계수로 알고 있으나 실 DB 값을 한 번 보고 2단계 전비 환산의 단위를 확정한다.
