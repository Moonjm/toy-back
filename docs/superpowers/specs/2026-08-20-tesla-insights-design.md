# TeslaMate 통계 응답 설계

**한 줄 요약.** 앱의 통계 화면 하나를 채우는 `GET /tesla/insights`를 신설해 기존 `GET /tesla/drive-insights`를 흡수하고, 개요 화면의 배터리 창을 내는 `GET /tesla/battery-window`를 따로 둔다. **`positions`(3,000만 행)를 훑는 계열은 배터리 창 하나뿐이고, 나머지는 전부 `drives`·`charging_processes`만 읽는다.**

**앞선 작업:** `docs/superpowers/specs/2026-08-19-tesla-state-timeline-drive-stats-design.md`가 `main`에 있다. 이 설계는 그 위에서 시작한다.

**앱 설계:** `../woori-haru/docs/superpowers/specs/2026-08-20-vehicle-insights-tabs-design.md`

## 배경

앱이 차량 미니앱의 탭을 `개요 / 통계 / 충전`으로 개편하면서, 가운데 탭이 차트 26장짜리 통계 화면이 된다. 그중 12장의 재료가 서버에 없다.

지금 서버가 내는 것은 이렇다.

| 엔드포인트 | 읽는 테이블 | 내용 |
|---|---|---|
| `/tesla/summary` | `drives`, `charging_processes` | 월 집계 · 직전 달 · 12개월 추이 · 그 달 충전 목록 |
| `/tesla/status` | `positions` | 한 시점 |
| `/tesla/battery-health` | `charging_processes` | 월별 열화 표본 |
| `/tesla/drive-insights` | `drives`, `geofences` | 온도별 · 시간대 · 거리 분포 · 자주 가는 곳 |
| `/tesla/state-timeline` | `states`, `drives`, `charging_processes` | 최근 N시간 구간 |
| `/tesla/charges/*` | `charging_processes`, `charges` | 누적 · 상세 · 곡선 |

**`/tesla/summary`의 `trend`가 이미 12개월치 주행·충전을 다 낸다.** 앱은 그중 충전금액만 그리고 있었고, 개편 1단계에서 서버를 건드리지 않고 차트 9장을 늘린다. 이 설계가 다루는 것은 **그 뒤에 남는 12장**이다.

## 이 설계의 핵심 — `positions`를 훑지 않는다

`AGENTS.md`가 못 박아 둔 전제가 있다.

> `positions`는 3,000만 행에 `date`가 BRIN뿐이라 **창 없는 `ORDER BY date DESC`가 11.7초**다 — 7일 창을 먼저 돌리고 PK 역순으로 폴백한다.

앱이 요구하는 12장 중 셋(주차 시간·팬텀 드레인·SOC 추이)이 언뜻 `positions` 전체 스캔을 부른다. **그중 둘은 `drives`·`charging_processes`만으로 구할 수 있다.**

### 팬텀 드레인 — 주행 사이의 정격거리 차이로 낸다

주차 구간의 배터리 하락을 `positions`에서 구간마다 계산하면 전 기간 스캔이다. 대신 **연속한 두 주행 사이**를 본다.

```
drives[n].end_rated_range_km  ──(주차)──▶  drives[n+1].start_rated_range_km
```

그 사이에 `charging_processes`가 하나도 없으면 이 구간은 **순수 주차**이고, 두 값의 차이가 그동안 샌 정격거리다. 사이에 충전이 있으면 그 구간은 버린다.

읽는 행은 `drives` 약 5천 행과 `charging_processes` 몇백 행뿐이다. `LAG`/`LEAD` 윈도우 함수로 한 번에 낸다.

**이 방식이 놓치는 것을 적어 둔다.** 마지막 주행 이후 지금까지의 주차는 다음 주행이 없어 계산되지 않고, 유령 주행(`end_date IS NULL`)의 앞뒤 구간도 빠진다. 월별 막대 하나가 표본 몇 개로 만들어졌는지 함께 내서 앱이 「표본 3건」을 적을 수 있게 한다.

### 정지 시간 — 빼기로 낸다

`states`에서 구간을 뽑아 `drives`·`charging_processes`와 겹치는 부분을 SQL로 빼는 것은 가능하지만 까다롭고, **`states`는 이 차량에서 이미 신뢰가 낮다** — 앞선 설계의 실측에서 최근 7일 `offline`이 131시간, `asleep`이 0개였다. 오프라인이 곧 주차는 아니다.

대신 **그 달의 총 시간에서 주행 시간과 충전 시간을 뺀다.**

```
idleMin = 그 달 총 분 − Σ drives.duration_min − Σ charging_processes.duration_min
```

`states`를 아예 읽지 않는다. 「정지 시간」이 답해야 하는 질문(「차가 얼마나 서 있었나」)에 이 값이 정확히 답하고, 주행 중 충전 같은 겹침은 이 차량에 없다. 요일별도 같은 방식이다 — 요일별 총 시간(그 기간에 그 요일이 몇 번 있었나 × 24시간)에서 뺀다.

### SOC 추이 — 창이 있으므로 괜찮다

48시간 창은 BRIN이 잘 듣는다. 기존 `/tesla/status`가 7일 창을 먼저 돌리는 것과 같은 형태다. **이것 하나만 `positions`를 읽는다.**

### 「최다 상승」은 뺀다

명예의 전당 후보 중 「최다 고도 상승」만 `positions.elevation`을 주행마다 훑어야 한다 — 창이 없는 전 기간 스캔이다. **얻는 것이 타일 한 칸이라 값이 비용을 못 넘는다.** 최장거리·최장시간·최고효율 셋으로 간다.

---

## 목표

- 앱 통계 화면 한 장이 **응답 하나**로 채워진다.
- `positions`를 읽는 계열이 **창이 있는 하나**로 국한된다.
- `/tesla/drive-insights`가 내던 것을 그대로 포함해 **엔드포인트가 둘로 늘지 않는다.**

## 비목표

- **나눗셈을 하지 않는다.** 저장소 관례대로 분자와 분모를 낸다(평균 전비·요일 평균·팬텀 드레인율 전부). 앱이 나눈다.
- **`states`를 새로 읽지 않는다.** 위 근거 참조. `/tesla/state-timeline`은 그대로 둔다.
- **캐시를 두지 않는다.** 사용자 2명·하루 수십 건이다. 느리면 쿼리를 고친다.
- 자율주행(FSD) 관련 필드를 두지 않는다 — TeslaMate에 컬럼이 없다.
- 정비·차계부 테이블을 만들지 않는다.

---

## 확인이 필요한 전제

**이 설계는 실측 없이 썼다.** 앞선 설계들과 달리 DB를 직접 재지 못했으므로, 구현 전에 파이에서 아래를 돌리고 결과를 이 문서에 표로 남긴다. 값에 따라 설계가 바뀌는 자리를 함께 적는다.

```sql
-- 1. 오토파일럿 컬럼이 정말 없는지 (있으면 앱 설계의 「그리지 않는 것」이 바뀐다)
\d positions

-- 2. 주행 사이 순수 주차 구간이 몇 개나 잡히는지 (팬텀 드레인 표본 수)
WITH d AS (
  SELECT end_date, end_rated_range_km,
         LEAD(start_date)          OVER (ORDER BY start_date) AS next_start,
         LEAD(start_rated_range_km) OVER (ORDER BY start_date) AS next_range
  FROM drives WHERE end_date IS NOT NULL
)
SELECT date_trunc('month', end_date) AS m, count(*),
       sum(end_rated_range_km - next_range) AS drop_km
FROM d
WHERE next_start IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM charging_processes c
                  WHERE c.start_date BETWEEN d.end_date AND d.next_start)
GROUP BY 1 ORDER BY 1 DESC LIMIT 24;

-- 3. speed_max 분포 (버킷 경계를 정한다)
SELECT width_bucket(speed_max, 0, 160, 8) AS b, count(*)
FROM drives WHERE speed_max IS NOT NULL GROUP BY 1 ORDER BY 1;

-- 4. 충전 시작·종료 배터리 분포 (버킷 폭 10%로 충분한지)
SELECT width_bucket(start_battery_level, 0, 100, 10) AS b, count(*)
FROM charging_processes WHERE end_date IS NOT NULL GROUP BY 1 ORDER BY 1;

-- 5. 지오펜스·주소가 있는지 (위치 섹션이 통째로 비는지)
SELECT count(*) FROM geofences;
SELECT count(DISTINCT city), count(DISTINCT state), count(DISTINCT country) FROM addresses;

-- 6. 48시간 창 SOC 표본 수와 소요 시간
EXPLAIN ANALYZE
SELECT date, battery_level, usable_battery_level, rated_battery_range_km
FROM positions
WHERE car_id = 1 AND date >= now() - interval '48 hours'
ORDER BY date;
```

**5번이 0이면** 앱의 위치 섹션(도시 수·자주 가는 곳·충전소별 비용)은 통째로 감춰진다 — 앞선 설계에서 이미 `geofences` 0행이 이 차량의 기본 상태로 확인됐다. 그래도 계약에는 필드를 두고 빈 배열을 낸다.

**6번이 1초를 넘으면** 표본을 5분 간격으로 솎아 내리거나 창을 24시간으로 줄인다.

---

## 계약 1 — `GET /tesla/insights`

```
GET /tesla/insights?months=12
```

`months`는 `1~60`, **`0`은 전체 기간**이다. 앱의 기간 칩 넷(`3 / 6 / 12 / 0`)이 그대로 들어온다. 기본값은 `12`.

`/tesla/drive-insights`가 내던 여섯 필드(`efficiencyKwhPerKm`·`temperatureBuckets`·`driveTimes`·`distanceBuckets`·`places`·`maxSpeedKmh`·`totalDistanceKm`·`recordedMonths`)를 **그대로 이름까지 유지한 채** 싣는다. 앱이 기존 카드 넷을 옮겨 쓰는 데 매핑 코드가 필요 없게 하려는 것이다.

```jsonc
{
  "months": 12,

  // 기준 달 포함 거슬러 N개월. 기록 없는 달도 자리를 지킨다(0과 null은 다르다).
  // `/tesla/summary`의 trend와 겹치지만 여기가 기간 칩을 따르므로 따로 낸다.
  "monthly": [{
    "yearMonth": "2026-08",
    "distanceKm": 780.4, "driveCount": 41, "drivingMin": 1120,
    "energyAddedKwh": 152.8, "energyUsedKwh": 161.0, "cost": 32700, "chargeCount": 7,
    "chargingMin": 640,
    "ratedRangeUsedKm": 812.1,     // 효율 추세의 분모 재료
    "idleMin": 42800,              // 그 달 총 분 − 주행 − 충전
    "parkDrainRatedKm": 18.4,      // 주차 구간 정격거리 하락 합
    "parkDrainSamples": 34         // 그 합이 몇 구간에서 나왔나. 0이면 앱이 막대를 안 그린다
  }],

  // 1 = 월요일. 요일 평균의 분자와 분모를 함께 낸다 — 서버는 나누지 않는다.
  "weekday": [{
    "weekday": 1, "driveCount": 38, "distanceKm": 612.0,
    "occurrences": 52,             // 그 기간에 월요일이 몇 번 있었나
    "idleMin": 61200
  }],

  "driveTimes":  [{ "weekday": 1, "hour": 8, "count": 12 }],
  "chargeTimes": [{ "weekday": 1, "hour": 23, "count": 4 }],

  "distanceBuckets":    [{ "fromKm": 0, "toKm": 5, "driveCount": 120, "distanceKm": 380.2 }],
  "temperatureBuckets": [{ "fromC": null, "toC": 0, "driveCount": 88,
                           "distanceKm": 910.0, "ratedRangeUsedKm": 1180.4 }],

  "speedBuckets":       [{ "fromKmh": 0, "toKmh": 20, "driveCount": 210 }],
  "speedEnergyBuckets": [{ "fromKmh": 0, "toKmh": 20,
                           "distanceKm": 302.1, "ratedRangeUsedKm": 410.8 }],

  "chargeStartLevels": [{ "fromPct": 0, "toPct": 10, "count": 3 }],
  "chargeEndLevels":   [{ "fromPct": 90, "toPct": 100, "count": 41 }],

  "places":   [{ "name": "집", "driveCount": 302, "distanceKm": 4120.8 }],
  "chargers": [{ "name": "집", "chargeCount": 210, "energyAddedKwh": 4820.1,
                 "cost": 612000, "costMissingCount": 4 }],

  "regions": { "cities": 34, "states": 8, "countries": 1 },

  "records": {
    "longestDistance": { "driveId": 4821, "startedAt": "2025-09-13T07:12:00", "distanceKm": 412.8 },
    "longestDuration": { "driveId": 3910, "startedAt": "2025-03-22T09:00:00", "durationMin": 388 },
    "bestEfficiency":  { "driveId": 5002, "startedAt": "2026-05-02T14:20:00",
                         "distanceKm": 88.2, "ratedRangeUsedKm": 71.0 }
  },

  "efficiencyKwhPerKm": 0.168,
  "maxSpeedKmh": 138,
  "totalDistanceKm": 107258.4,
  "recordedMonths": 59
}
```

### 계약에 관한 결정

- **`monthly`가 `/tesla/summary`의 `trend`와 겹친다.** 없애지 않는다 — `trend`는 12개월 고정이고 `monthly`는 기간 칩을 따른다. 앱은 개요·충전 탭에서 `trend`를, 통계 탭에서 `monthly`를 본다.
- **`records`에 `driveId`를 싣는다.** 앱이 나중에 그 주행 상세로 보내고 싶어질 자리다. 지금 앱에 주행 상세 화면이 없으므로 쓰이지 않지만, 이 값을 안 실으면 나중에 계약을 또 고쳐야 한다.
- **`chargers[].costMissingCount`를 함께 낸다.** 금액 미입력 충전이 섞이면 「충전소별 비용 TOP」 순위가 뒤집힌다. 앱이 「4건 금액 없음」을 적을 수 있어야 한다. `/tesla/charges/totals`가 같은 이유로 이미 이 필드를 낸다.
- **`temperatureBuckets`에 `distanceKm`과 `ratedRangeUsedKm`이 둘 다 있다.** 기존 계약 그대로다. 종합 효율(정격 대비 실주행)은 이 배열의 합으로 앱이 낸다 — 별도 필드를 두지 않는다.
- **`fromC`가 `null`인 첫 버킷**은 「0℃ 미만」을 뜻한다. 기존 계약 그대로다.

### 응답 크기

전 기간(`months=0`)일 때 `monthly` 59개 + `driveTimes` 최대 168개 + `chargeTimes` 최대 168개 + 나머지 배열들로, 대략 30~50KB로 본다. 사용자 2명이라 문제될 규모가 아니다. **`EXPLAIN ANALYZE`로 전체 응답이 2초를 넘으면** `driveTimes`/`chargeTimes`를 요일×3시간 구간으로 묶어 크기를 8분의 1로 줄인다.

### 쿼리 방향

전부 `drives`와 `charging_processes`만 읽는다. 공통 규칙 셋을 지킨다.

1. **월 경계는 KST로 자른다.** `date_trunc('month', d.start_date AT TIME ZONE 'Asia/Seoul')`. UTC로 자르면 월초 9시간이 옆 달로 샌다 — 이미 `TeslaTime`에 있는 관례다.
2. **유령 행을 뺀다.** `end_date IS NOT NULL`. `drives` 12건·`charging_processes` 6건이 마감되지 않은 채 남아 있다(앞선 설계 실측).
3. **`GROUP BY`로 빈 달이 사라지지 않게** 달 축을 `generate_series`로 만들고 좌측 조인한다. 기존 `trend`가 쓰는 방식과 같다.

`speedEnergyBuckets`의 평균 속도는 `distance / (duration_min / 60.0)`으로 주행마다 구해 버킷을 나눈다. **이 나눗셈은 버킷을 고르기 위한 것이라 응답에 나가지 않으므로** 「나눗셈을 하지 않는다」와 부딪히지 않는다.

`regions`는 `drives.end_address_id → addresses`를 조인해 `city`/`state`/`country`를 distinct 센다.

---

## 계약 2 — `GET /tesla/battery-window`

```
GET /tesla/battery-window?hours=48
```

개요 화면의 충전 레벨 카드 하나를 채운다. `hours`는 `1~168`, 기본 `48`.

```jsonc
{
  "from": "2026-08-18T15:00:00",
  "to":   "2026-08-20T15:00:00",
  "samples": [
    { "at": "2026-08-18T15:02:00", "batteryLevel": 62, "usableBatteryLevel": 61 }
  ],
  // 이 창 안의 충전 구간. 앱이 선 위에 다른 색으로 겹쳐 그린다.
  "charges": [{ "from": "2026-08-19T23:10:00", "to": "2026-08-20T02:40:00" }],

  // 최근 7일 팬텀 드레인. 창(hours)과 무관한 고정 7일이다.
  "parkDrain": { "ratedKm": 4.2, "hours": 96.4, "samples": 6 }
}
```

- **`to`는 요청 시각이다.** 자정에 맞추지 않는다 — 화면의 오른쪽 끝이 「지금」이어야 한다. `/tesla/state-timeline`이 4단계 개정에서 내린 결정과 같다.
- **`parkDrain`이 창을 안 따르는 이유:** 48시간 안에 순수 주차 구간이 하나도 없는 날이 흔하다. 「최근 7일」로 고정해야 숫자가 늘 나온다. `samples`가 0이면 앱이 그 줄을 감춘다.
- **`parkDrain`도 나누지 않는다.** 하락 정격거리와 그 시간을 함께 내고, 앱이 `km/시간` 또는 `%/일`로 만든다.
- **표본을 솎지 않는다.** 48시간이면 수백 개다. `/tesla/charges/{id}/curve`가 완속 1,700개를 줄이지 않는 것과 같은 판단이다. 위 6번 실측이 1초를 넘으면 그때 다시 본다.

---

## 기존 엔드포인트 처리

**`/tesla/drive-insights`는 앱이 넘어간 뒤 지운다.** 두 엔드포인트가 같은 값을 내는 기간이 생기는데, 앱 1단계가 서버 없이 나가므로 그 사이에는 옛 것만 쓰인다. 앱 2단계가 배포되면 옛 것을 지우고 `TeslaVehicleController`의 KDoc에서도 뺀다.

`/tesla/summary`·`/tesla/status`·`/tesla/battery-health`·`/tesla/state-timeline`·`/tesla/charges/*`는 손대지 않는다.

## 테스트

`TeslaVehicleServiceTest`의 기존 관례(`Given–When–Then` 한글 이름)를 따른다.

- 팬텀 드레인: 사이에 충전이 낀 구간이 빠지는지, 마지막 주행 뒤가 안 잡히는지, 유령 주행 앞뒤가 빠지는지, 표본 0일 때 `null`이 아니라 `samples: 0`으로 나오는지.
- 정지 시간: 그 달 총 분에서 주행·충전을 뺀 값이 맞는지, **음수가 나오지 않는지**(겹침이 있으면 나올 수 있다 — 0으로 자른다).
- 요일 집계: `occurrences`가 기간 안의 실제 요일 수인지(윤년·월말 경계).
- 빈 달: 기록 없는 달이 `monthly`에서 사라지지 않고 필드가 `null`로 오는지.
- `months=0`: 전체 기간으로 해석되는지. `months=61`은 400인지.
- 지오펜스 0행: `places`·`chargers`가 빈 배열인지(`null`이 아니라).
- KST 경계: 월초 00:30 KST 주행이 그 달에 잡히는지.
