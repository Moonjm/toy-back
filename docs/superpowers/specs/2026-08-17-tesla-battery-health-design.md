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
| 사용 가능 용량 | `end_battery_level >= 80` **AND** `end_rated_range_km IS NOT NULL` **AND** `end_battery_level - start_battery_level >= 40` | 짧은 보충은 분모가 작아 오차가 크다. 앞의 둘은 공통 WHERE에서 물려받는 것이지 용량만의 독립 조건이 아니다 — 아래 「표가 SQL과 어긋나 있었다」 참고 |
| 공통 | `end_date IS NOT NULL` | 진행 중인 충전은 값이 흔들린다 |

두 조건이 달라 표본 수도 따로 낸다(`sampleCount`·`capacitySampleCount`). 한 숫자로 합치면 용량이 null인 이유가 「표본이 없어서」인지 「값이 없어서」인지 화면에서 갈리지 않는다.

**표가 SQL과 어긋나 있었다(2026-08-17 발견):** 이 표는 처음에 용량 행의 조건을 ΔSoC ≥ 40만으로 적었다. 그런데 실제 SQL(`BATTERY_HEALTH_MONTHLY_SQL`)은 두 지표를 하나의 CTE 공통 WHERE 위에 얹으므로, 용량 표본도 `end_battery_level >= 80`을 함께 통과해야 한다 — 20%→70%(ΔSoC 50, end 70%) 같은 충전은 표만 보면 유효한 용량 표본이지만 실제로는 세지 않는다. SQL은 그대로 두기로 결정했다(80% 조건을 용량 쪽에서만 풀면 `full_range_km`도 CASE로 감싸야 해 `fullRangeKm`의 non-null 보장이 깨지고, 실효 차이는 3.5%뿐이다). 2단계 표를 작성할 때는 표를 SQL과 줄 단위로 대조한다.

**`end_rated_range_km IS NOT NULL`도 같은 커플링이다(2026-08-17 지적받음).** 용량은 에너지와 SoC만으로 나오므로 주행가능거리가 있든 없든 낼 수 있는데, 공통 WHERE에 있어 함께 걸린다. 이것도 풀지 않았다 — 빼면 `full_range_km`이 null일 수 있게 되어 `COUNT(*)`를 `COUNT(full_range_km)`로 바꿔야 하고 `fullRangeKm`의 nullable이 앱까지 올라간다. 실측으로 이 커플링에 걸리는 용량 표본은 0건이었고, **「`charge_energy_added`는 있는데 `end_rated_range_km`만 없는」 행이 484건 중 하나도 없다** — TeslaMate가 세션을 마감할 때 둘을 함께 쓰기 때문이다. 유일한 null 행(`id=15`, 2021-10-10)은 네 컬럼이 전부 null이라 어느 조건에든 걸린다. **같은 계열의 지적이 세 번 나왔다는 것 자체가 신호다** — 2단계 SQL은 처음부터 지표별 조건을 `CASE` 안에 두고 공통 WHERE에는 진짜 공통인 것만 남긴다.

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
- **`start_battery_level`을 공통 WHERE에서 거르지 않는다.** 만충 환산은 시작 레벨을 쓰지 않으므로 거기서 걸러 버리면 용량만의 조건이 만충 환산 표본까지 끌고 내려간다. 용량 쪽 `CASE`가 이미 null-safe다 — start가 null이면 `NULL >= 40`이 NULL이라 `WHEN`이 참이 되지 않고 `capacity_kwh`가 NULL로 떨어진다. **초안 SQL에는 이 조건이 공통 WHERE에 있었다**(2026-08-17 지적받아 제거). 이 차량 데이터에서 실제로 빠지던 행은 0건이었지만(유일한 null-start 행 `id=15`는 end 레벨·주행가능거리도 전부 null이라 다른 조건에 이미 걸린다), TeslaMate가 「end는 있고 start만 없는」 행을 쓰면 그 달의 열화 표본이 조용히 사라질 자리였다.
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
    "efficiencyKwhPerKm": 0.1367,
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
- **`ratedRangeUsedKm <= 0`인 주행도 제외한다.** 넣으면 전비가 무한대가 된다. **근거가 실측과 달랐다(2026-08-17 발견):** 애초에는 「내리막 회생·주차 중 보정으로 나올 수 있고」라고 적었으나, 실측으로는 447건 중 431건이 차이가 정확히 0(평균 거리 0.16km, 최대 3.9km)이었다 — 지배적인 것은 회생·보정이 아니라 **주행가능거리 표시가 1km 단위로 움직이지 않을 만큼 짧은 주행**, 즉 반올림이다. 결론(제외한다)은 바뀌지 않는다. 제외한 건수를 `driveCount`가 이미 반영하므로 앱이 「N건 기준」을 적을 수 있다.

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

# 3단계 — `GET /tesla/charges/totals` · `GET /tesla/charges/{id}/curve`

```json
GET /tesla/charges/totals

{ "data": {
  "chargeCount": 474,
  "energyAddedKwh": 17442.0,
  "energyUsedKwh": 18197.2,
  "cost": 3644562,
  "costMissingCount": 35,
  "costMissingEnergyUsedKwh": 977.0,
  "firstChargedAt": "2021-09-03",
  "fast": { "chargeCount": 42,  "energyAddedKwh": 1358.4,  "energyUsedKwh": 1329.0,
            "cost": 143337,  "costMissingCount": 24, "costMissingEnergyUsedKwh": 833.9 },
  "slow": { "chargeCount": 432, "energyAddedKwh": 16083.6, "energyUsedKwh": 16868.2,
            "cost": 3501225, "costMissingCount": 11, "costMissingEnergyUsedKwh": 143.1 }
} }
```

```json
GET /tesla/charges/{id}/curve

{ "data": { "samples": [
  { "at": "2026-05-04T13:02:11", "powerKw": 90, "batteryLevel": 42 }
] } }
```

- **파라미터가 없다.** 전 기간이다 — 1단계와 같은 판단으로, 몇 년을 타도 요약이 궁금한 것이지 기간을 자르고 싶은 것이 아니다.
- **`fast + slow = 최상위` 불변식이 선다.** 최상위 필드와 `fast`·`slow` 안의 같은 이름 필드가 중복인데, 의도된 것이다 — 헤드라인과 내역이다.
- **누적 주행거리를 내지 않는다.** `/tesla/status`의 odometer가 이미 낸다. 두 번 낼 이유가 없다.
- **주유비 대비 절감액을 내지 않는다.** 유가 상수가 필요한데, 신차 기준값(1단계)·전비 계수(2단계)와 같은 이유로 서버에 두지 않는다 — 손으로 심는 상수는 화면에 가까운 쪽이 낫고, 값을 고칠 때 배포가 필요해지는 것도 피한다.
- 곡선은 `charges`의 `date`·`charger_power`·`battery_level` 셋만 시각순으로 낸다. **시각은 KST 그대로** 내고 경과 분은 서버가 계산하지 않는다 — x축을 무엇으로 할지는 앱이 정한다.

## 모집단 정의 (두 SQL이 공유)

| 조건 | 왜 |
|---|---|
| `end_date IS NOT NULL` | 진행 중인 충전은 값이 흔들린다. 저장소 전체가 이미 이 조건을 건다 |
| `charge_energy_added > 0 OR cost IS NOT NULL` | 「에너지가 들어갔거나, 돈을 냈거나」. 아래 참고 |

이 저장소는 **지표별 조건**(1단계의 `end_battery_level >= 80` 같은 것)과 **모집단 정의**(2단계의 `distance > 0` 같은 것)를 구분해 다루기로 했다(2단계 최종 리뷰). 3단계에는 지표별 조건이 없다 — 급속·완속·미입력은 전부 `FILTER`로 가르고 WHERE로 깎지 않으므로, 어느 지표가 다른 지표의 표본을 줄일 길이 구조적으로 없다.

다만 `charge_energy_added > 0 OR cost IS NOT NULL` 조건 자체는 문법상 `charge_energy_added`(지표 A)의 컬럼으로 쓰여 있어서, 원리상 다른 지표(`charge_energy_used`, 지표 B)의 표본을 깎을 수 있는 모양이다. 최종 리뷰가 이 자리를 다시 지적했고, 실측(2026-08-18)으로 확인했다 — 이 조건으로 제외되는 10건 중 `charge_energy_used > 0`인 것이 **0건**이고, 손실이 **0.00 kWh**다. 문법상 지표 A의 조건이지만 실제로 지표 B의 표본을 깎지 않는다.

**모집단에서 축퇴 세션을 빼되 돈 낸 것은 남긴다.** `charging_processes` 완료 484건 중 11건이 축퇴다 — SoC가 그대로(`95→95`, `35→35`)이고 `charge_energy_added`가 0이거나 null인, 케이블만 꽂았다 뺀 세션이다. 2단계가 `distance > 0`으로 0km 주행을 뺀 것과 같은 규칙이다.

### `id=15` — 계획서가 틀렸던 곳 (2026-08-18 정정)

이 계획을 세울 때는 축퇴 11건 중 `id=15`(2021-10-10, 10,360원)를 **「TeslaMate가 데이터를 통째로 잃은 행」**으로 적었다. **틀렸다.** 실측(2026-08-18)으로 확인한 실체는 이렇다.

```
id=15의 charges 샘플: 1,330개
2021-10-10 12:12:35 ~ 19:44:52 · charger_power 0~7kW · battery_level 27% → 90%
```

**못 채운 것은 `charging_processes`의 집계 컬럼(`charge_energy_added`·`duration_min`·`start/end_battery_level`)뿐이고, `charges` 원자료는 1,330개 행 그대로 남아 있다.** 7kW 완속으로 7시간 반 충전한 실제 세션이고, 10,360원도 그래서 맞는 금액이다 — 「데이터를 잃었는데 돈만 남았다」가 아니라 「집계만 못 냈을 뿐 실제로 충전했다」다.

이 정정은 두 설계 판단을 약화시키지 않고 오히려 **더 강하게 만든다.**

- 모집단에 `OR cost IS NOT NULL`을 두는 것 — 「돈은 냈는데 데이터가 없는 예외 행」을 봐주는 것이 아니라, **실제로 일어난 충전 세션을 정당하게 세는 것**이다.
- `FAST_CHARGE`(`COALESCE(..., 0) >= 15`)가 이 행을 완속으로 보내는 것 — 원래 근거는 「평균 전력을 못 내니 불변식을 위해 편의상 완속으로 본다」였는데, **실측 최대 7kW라 완속 판정이 사실과도 맞는다.** 편의적 기본값이 아니라 옳은 값이었다.

## 급속 파생: `charges`를 조인하지 않는다

```
평균 전력 = charge_energy_added / duration_min * 60   (kW)
급속      = 평균 전력 >= 15
```

`charges`가 485,830행이라 세션마다 `bool_or(fast_charger_present)`로 조회를 돌면 **877ms**, 이 식으로 파생하면 **9.6ms**다(2026-08-18 실측). 두 방법을 474건 전부 대조하면 **일치 474건, 불일치 0건**이다 — 조인을 대체할 근거가 실측으로 선다.

**임계값 15가 안전한 이유는 분포에 골이 있어서다.** 완속 432건은 전부 1.2~9.3kW, 급속 42건은 20.8~135.3kW다. 15는 그 골 한가운데이고, 10이나 20으로 옮겨도 어느 쪽에도 걸리는 세션이 없을 만큼 여유가 있다.

`COALESCE(..., 0)`이 평균을 못 내는 세션(`duration_min`이 null)을 완속으로 보낸다 — 이 DB에서는 `id=15` 하나뿐이고, 위 정정대로 실측과도 맞는 판정이다.

## `costMissing*`을 「무료」라고 부르지 않는 이유

금액 미입력 35건은 급속 24건·완속 11건으로, 미입력율이 **급속 57%(24/42) 대 완속 2.5%(11/432)**로 크게 갈린다. 실제로 급속은 슈퍼차저 무료 시기, 완속은 데스티네이션 차저라 둘 다 무료로 이용한 충전이 맞다. 그런데 DB에 `cost = 0`인 행은 하나도 없다 — 「무료를 0원으로 적는」 관례가 이 데이터에 없다.

**그래도 서버는 이것을 「무료 충전」이라 부르지 않는다.** DB에 기록된 것은 「금액 없음」이지 「0원」이 아니다. 서버는 `costMissingCount`·`costMissingEnergyUsedKwh`로만 내고, 「무료」라는 해석은 앱이 붙인다 — 신차 기준값·유가 상수를 서버에 두지 않는 것과 같은 계열이고, 나중에 유료 충전을 깜빡 안 적었을 때 서버가 그것을 조용히 「무료」로 단정해 버리는 것도 막는다.

**`costMissingEnergyUsedKwh`는 해석 문제가 아니라 앱의 단가 분모다.** 이 값이 없으면 단가가 틀린다.

```
잘못:  3,644,562 ÷ 18,197.2           = 200.3 원/kWh
옳게:  3,644,562 ÷ (18,197.2 − 977.0) = 211.6 원/kWh    ← 5.6% 차이
```

분모가 `energyUsedKwh`(벽에서 뽑은 양)인 것은 `ChargeListItem.energyUsedKwh`의 기존 주석이 이미 정해 둔 규칙이다.

## `급속 + 완속 = 합계` 불변식과 완속을 뺄셈으로 만드는 이유

SQL은 합계와 급속분만 낸다. 완속은 서비스가 **뺄셈으로** 만든다 — `slow = totals - fast`. 급속분을 SQL이 별도 `FILTER`로 다시 세고 완속도 별도 `FILTER (WHERE NOT ...)`로 또 세면, 반올림이나 조건 누락으로 둘이 어긋날 여지가 남는다. 뺄셈으로 내면 **불변식이 코드 구조로 강제된다** — 애초에 어긋날 수 있는 형태가 아니다. 2단계 최종 리뷰가 「온도 카드와 거리 카드의 총합이 다르다」를 지적했던 계열이라, 3단계는 그 지적이 성립할 자리 자체를 없앴다.

2026-08-18 실측으로 다섯 필드 전부 확인했다.

| 불변식 | 계산 | 결과 |
|---|---|---|
| `chargeCount` | 474 − 42 | 432 |
| `energyAddedKwh` | 17442.0 − 1358.4 | 16083.6 |
| `cost` | 3644562 − 143337 | 3501225 |
| `costMissingCount` | 35 − 24 | 11 |
| `costMissingEnergyUsedKwh` | 977.0 − 833.9 | 143.1 |

## 곡선을 줄이지 않는 이유, 404 판정을 `existsCompleted`가 따로 하는 이유

**샘플을 줄이지 않는다.** 완속 세션은 최대 1,704개(2026-08-18 실측, `id=490`)까지 간다. 「어느 점을 버릴지」를 서버가 정하면 화면이 그 판단을 따라야 한다 — 이 저장소가 나눗셈을 앱에 맡겨 온 것과 같은 계열이다. 사용자 규모(1대·하루 수십 건)에서 1,700행 JSON은 수십 KB이고, 줄여야 할 성능상의 이유가 없다.

**404 판정은 `existsCompleted`가 따로 한다.** `findCurve`가 빈 리스트를 주는 이유가 둘이다 — 「없는 id·진행 중인 세션」과 「세션은 있는데 샘플이 하나도 없다」. 앞은 404여야 하고 뒤는 정상 응답(빈 배열)이어야 하는데, `findCurve`의 결과만으로는 둘이 구분되지 않는다. `existsCompleted`가 `end_date IS NOT NULL`만 따로 확인해 그 경계를 가른다.

**계획서는 `id=15`를 「샘플이 없는 세션」의 예로 들었는데, 이것도 틀렸다.** 위에서 정정했듯 `id=15`는 `charges` 샘플이 1,330개 있다. **이 DB에는 완료된 세션인데 곡선 샘플이 0개인 행이 하나도 없다**(2026-08-18 실측). `existsCompleted`를 따로 두는 논리(빈 리스트가 「없는 id·진행 중」과 「샘플 없는 세션」 두 가지 뜻을 가진다는 것) 자체는 여전히 옳다 — TeslaMate가 세션 메타는 만들고 `charges` 기록만 놓치는 경우가 이론적으로 있을 수 있다. 다만 **이 실데이터에서는 그 경로가 한 번도 발화하지 않는 방어**라는 것을 남겨 둔다.

## 최종 리뷰 실측(2026-08-18) — 급속 합계 반전, 단가 분모 오염, `/tesla/summary`와의 모집단 차이

브랜치 전체 최종 코드 리뷰가 Important 3건을 냈다. SQL·로직은 실측으로 옳다고 확인됐고, 응답을 읽을 때 사실과 안 맞아 보이는 자리 셋을 여기 적는다.

**급속 합계가 `fast.energyUsedKwh`(1329.0) < `fast.energyAddedKwh`(1358.4)로 뒤집힌다 — TeslaMate가 급속을 과소 기록하는 것이 아니다.** 벽에서 뽑아쓴 양이 배터리에 들어간 양보다 작은 것은 물리적으로 불가능한데, 응답이 그렇게 나간다. 원인은 `used`가 NULL인 세션 2건(`id=14`·`id=29`, 2021년 급속)의 `added`(합 90.5kWh)만 합에 들어가고 `used`에는 안 들어가서다. **개별 행이 뒤집힌 것은 42건 중 0건이다.** 그 둘을 빼면 나머지 40건은 `added` 1267.9kWh, `used` 1329.0kWh로 **4.8% 손실**이고, 완속(`added` 16083.6, `used` 16868.2)의 **4.9% 손실과 같다** — 급속이 유별나게 나쁜 것이 아니라 NULL 2건 때문에 급속 그룹의 분자·분모 모집단이 어긋난 것이다. 이 값들로 「충전 효율」(added ÷ used)을 내면 안 된다 — 급속 카드가 102.2%를 표시하게 된다.

**단가 분모가 1건(10,360원)만큼 오염돼 있다.** `costMissingEnergyUsedKwh`로 뺀 분모(`cost ÷ (energyUsedKwh − costMissingEnergyUsedKwh)`)는 대수적으로 `SUM(used) FILTER (cost IS NOT NULL)`과 같은데, `cost`는 있고 `used`가 NULL인 세션(`id=15`, 10,360원)은 분자에만 들어가고 분모에는 0으로 들어간다. 실측 단가는 **211.64 vs 211.04원/kWh로 0.28% 차이**다 — 이 저장소가 분모 처리를 앱에 맡겨 온 방침을 바꿀 크기가 아니라 SQL·응답 계약은 그대로 둔다.

**`totals`는 `/tesla/summary`와 모집단이 다르다.** `/tesla/summary`가 쓰는 `CHARGE_MONTHLY_SQL`·`MISSING_COST_SQL`은 WHERE가 `end_date IS NOT NULL`뿐이라 축퇴 11건을 그대로 센다(**484 기준**). `totals`는 축퇴를 모집단에서 뺀다(**474 기준**). 두 응답의 건수·비용을 같은 숫자로 기대하면 안 된다 — 월별 집계가 축퇴를 세는 것은 「그 달에 뭐가 있었나」라 그것대로 말이 된다.

## 실측 (2026-08-18)

전량 실 DB(psql)에서 확인한 값이다.

**`GET /tesla/charges/{id}/curve`는 배포 후 실호출로도 확인했다(2026-08-18).** `/tesla/charges/409/curve` 응답을 DB와 대 보니 샘플 **1,084개**(줄이지 않았다), 첫 샘플 `2025-10-18T09:42:41.359`·끝 샘플 `2025-10-18T15:54:20.931`이 DB의 KST 값과 밀리초까지 같고 SoC 8→55도 일치한다. **이 한 번이 닫은 것은 JDBC 매핑이다** — SQL은 psql로 검증했지만 `charges.date`를 `getObject(LocalDateTime)`로, `charger_power`·`battery_level`을 `getObject as Int?`로 읽는 경로는 그때까지 안 돌았다. 특히 **마지막 샘플의 `powerKw`가 `0`으로 나온 것**이 `getObject`를 쓴 판단의 증거다 — 이 세션은 `charger_power`가 NULL인 샘플이 0건이고 0인 샘플이 정확히 1건인데, `rs.getInt`였다면 둘이 구분되지 않았다.

**`GET /tesla/charges/totals`는 아직 실호출로 확인하지 않았다.** 값은 psql로 대조됐고, 남은 것은 `fast.chargeCount + slow.chargeCount == chargeCount` 불변식이 실응답에서도 서는지 한 번 보는 것뿐이다.

| 필드 | 값 |
|---|---|
| `charge_count` | 474 |
| `energy_added_kwh` / `energy_used_kwh` | 17442.0 / 18197.2 |
| `cost` | 3644562 |
| `cost_missing_count` / `cost_missing_energy_used_kwh` | 35 / 977.0 |
| `fast_charge_count` | 42 |
| `fast_energy_added_kwh` / `fast_energy_used_kwh` | 1358.4 / 1329.0 |
| `fast_cost` | 143337 |
| `fast_cost_missing_count` / `fast_cost_missing_energy_used_kwh` | 24 / 833.9 |
| 첫 충전(`firstChargedAt`) | 2021-09-03 (KST) |
| 급속 파생 vs `fast_charger_present` | 474/474 일치, 불일치 0건 |
| 급속/완속 미입력율 | 급속 57%(24/42), 완속 2.5%(11/432) |
| 단가(잘못 vs 옳게) | 200.3원/kWh vs 211.6원/kWh (5.6% 차이) |
| `charges` 조인 vs 파생 | 877ms vs 9.6ms(`charges` 485,830행) |
| 곡선 샘플 수 | 급속 250~360, 완속 최대 1,704(`id=490`) |
| `id=15`의 `charges` 샘플 | 1,330개, 2021-10-10 12:12:35~19:44:52, 0~7kW, 27%→90% |
| 완료·곡선 샘플 0인 세션 | 0건 |

# 보류 — `positions`가 필요한 것

주행 경로, 속도·전력·고도 샘플, 속도 분포.

**인덱스는 있다 — 판정이 나왔다(2026-08-18 실측).** 설계 당시 걱정한 것은 「`positions.drive_id`에 인덱스가 없으면 주행 하나를 열 때마다 3,000만 행을 훑는다」였다.

```sql
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'positions';
```

`drive_id`에 `positions_drive_id_date_timestamp_minmax_multi_ops_index`(BRIN)가 있다. 조회 시간도 실측했다 — 최근 주행 **11.7ms**, 오래된 주행 **20ms**. 걱정한 「3,000만 행 전체 훑기」는 일어나지 않는다. `drive_id`가 `positions`의 append 순서와 강하게 상관돼 있어(같은 주행의 행이 물리적으로 뭉쳐 저장된다), BRIN의 페이지 범위 스킵이 실제로 작동한다 — B-tree가 아니어도 이 컬럼에는 BRIN으로 충분하다.

**「인덱스가 있으면 설계하고, 없으면 접는다」는 문장을 실제 판정으로 바꾼다: 인덱스는 있다. 다만 접는 대신 새 선행 조건이 생겼다.**

주행 하나에 평균 **4,281 샘플**, 최대 **14,386 샘플**, **km당 478개**다(2026-08-18 실측). 인덱스가 빠르다는 것과 이 샘플을 그대로 낼 수 있다는 것은 다른 문제다 — 3단계 곡선(완속 최대 1,704개)과도 자릿수가 다르다. 다운샘플링 방식(간격을 시간으로 둘지 거리로 둘지, 목표 개수, 어떤 알고리즘으로 점을 고를지)을 정하지 않고는 응답을 설계할 수 없다. 이것이 이 보류 항목의 **새 선행 조건**이다 — 「인덱스가 없어 접는다」에서 「인덱스는 풀렸고, 다운샘플링 설계가 끝나야 낼 수 있다」로 바뀌었다.

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
- ~~**`cars.efficiency`가 실제로 무엇을 담고 있는지 확인이 필요하다.**~~ **해결됨(2026-08-17).** 실 DB 값은 `0.1367`(kWh/km)이고 `cars`는 1행(`model=3`, `trim_badging=74D`)이다. kWh/km 계수로 알고 있던 것이 맞았다.
