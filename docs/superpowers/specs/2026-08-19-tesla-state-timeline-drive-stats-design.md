# TeslaMate 상태 타임라인·주행 통계 설계

**한 줄 요약.** 차량이 최근 며칠을 어떤 상태로 보냈는지 **구간**으로 내는 `GET /tesla/state-timeline`을 새로 두고, 기존 `GET /tesla/drive-insights`에 역대 최고 속도와 이번 달·올해 주행거리를 더한다.

## 배경

`/tesla/status`는 **한 시점**만 낸다 — 지금 오프라인이고 3시간째라는 것까지는 알지만, 어제 몇 시간 깨어 있었는지는 알 길이 없다. TeslaMate의 Grafana 대시보드는 그 답을 시간축 띠로 그리는데, 앱에는 그 재료가 없다.

`/tesla/drive-insights`도 마찬가지로 **창(`months`) 안의 분포**만 낸다. 온도별 전비·시간대·거리 분포·자주 가는 곳은 「어떻게 탔나」에는 답하지만 「지금까지 얼마나」에는 답하지 않는다.

### `states`에 `driving`·`charging`이 없다

이미 `8cb61d9`에서 확인한 사실이다. TeslaMate의 열거형은 셋뿐이다.

```sql
CREATE TYPE states_status AS ENUM ('online', 'offline', 'asleep')
```

「주행 중」·「충전 중」은 `drives`·`charging_processes`의 **열린 행 존재**로 파생시킨다. Grafana 대시보드도 같은 방식으로 세 계열을 겹쳐 그린다.

## 실측 (2026-08-19, 이 설계의 전제)

라즈베리파이의 실제 TeslaMate DB에서 확인했다.

| 항목 | 값 |
|---|---|
| `states` 전체 | `online` 16,578 / `asleep` 11,039 / `offline` 5,635 |
| **최근 7일 상태 구간** | `online` 72개(36.3시간) / `offline` 73개(131.3시간) / `asleep` **0개** |
| 최근 7일 주행 | 22건 (최소 1분, 중앙 18분, 최대 93분) |
| 최근 7일 충전 | 1건 |
| **최근 7일 구간 길이** | `online` 최소 11.2분·중앙 12.2분 / `offline` 최소 0.7분·중앙 120.5분 |
| 2분 미만 구간 | 145개 중 **2개** (둘 다 `offline`) |
| `drives.speed_max` | 전 기간 최고 **138 km/h**, null **0건**, 완료 주행 5,057건 |
| **138 km/h 동률** | **최소 3건** — 2025-09-13, 2025-03-22, 2024-03-09 |
| 이번 달 주행거리(KST) | **1,331.3 km** |
| 올해 주행거리(KST) | **13,440.4 km** |
| 연도별 주행거리 | 2021 9,585 / 2022 25,295 / 2023 24,281 / 2024 14,931 / 2025 19,726 / 2026 13,440 km |

**열린 행(`end_date IS NULL`) 현황 — 이 설계의 핵심 위험:**

| 테이블 | 열린 행 | 가장 오래된 | 최신 |
|---|---|---|---|
| `drives` | **12** | 2022-06-25 | 2024-04-20 |
| `charging_processes` | **6** | 2021-09-22 | 2025-12-18 |
| `states` | 1 | 2026-08-19 02:02 | 2026-08-19 02:02 |

`drives`·`charging_processes`의 열린 행은 **유령**이다 — TeslaMate가 세션 중에 죽거나 차가 오프라인이 되어 마감되지 않은 것이다. `states`의 열린 행 1건은 유령이 아니라 **현재 상태**이고, 유니크 인덱스(`states_car_id__end_date_IS_NULL_index`)가 차당 최대 하나를 보장한다.

### `asleep`이 최근 7일에 0인 것은 정상이다

2026년에도 2월 1건·4월 2건·5월 1건·6월 1건·7월 4건이 있었다. 창에 안 잡히는 것뿐이므로 **색 팔레트에서 빼지 않는다.**

## 목표

- 최근 며칠의 차량 상태를 시간축 구간으로 낸다.
- 그 구간에 주행·충전을 겹칠 수 있게 세 계열을 함께 낸다.
- 역대 최고 속도와 이번 달·올해 주행거리를 낸다.
- 유령 세션이 타임라인을 통째로 칠하는 일을 구조적으로 막는다.

## 비목표

- **구간을 서버가 하나의 띠로 합치지 않는다.** 겹침 해소는 화면이 한다(아래 참조).
- 실시간 스트림을 만들지 않는다. 요청 시점의 스냅숏이다.
- 연도별 주행거리 계열을 내지 않는다 — 앱이 타일 셋만 그리기로 확정했다.
- `car_id`를 파라미터로도 응답으로도 두지 않는다. 차량이 1대다.

---

## API 1 — `GET /tesla/state-timeline`

```
GET /tesla/state-timeline?days=7
```

| 파라미터 | 범위 | 기본 | 비고 |
|---|---|---|---|
| `days` | 1~30 | 7 | 범위 밖은 400 `ErrorCode.INVALID_REQUEST` |

```json
{ "data": {
  "days": 7,
  "from": "2026-08-13T00:00:00",
  "to":   "2026-08-19T12:34:56",
  "states": [
    { "state": "offline", "from": "2026-08-13T00:00:00", "to": "2026-08-13T06:14:22" },
    { "state": "online",  "from": "2026-08-13T06:14:22", "to": "2026-08-13T06:26:31" }
  ],
  "drives":  [ { "from": "2026-08-13T06:18:02", "to": "2026-08-13T06:36:44" } ],
  "charges": [ { "from": "2026-08-15T22:11:03", "to": "2026-08-16T05:40:19" } ]
}}
```

### 세 배열을 그대로 내는 이유

하나의 겹치지 않는 띠로 합치려면 구간 산술(빼기·쪼개기)이 필요하다. `states`의 `online` 구간 하나가 주행 둘에 걸리면 그 구간은 셋으로 갈라져야 하고, 그 로직은 SQL로도 코틀린으로도 만만치 않다. **세 계열을 그대로 내면 서버는 단순 조회 셋으로 끝나고, 겹칠 때 무엇이 이기는지는 화면이 정한다.** Grafana 대시보드가 세 계열을 레이어로 겹쳐 그리는 것과 같은 구조다.

겹침 우선순위는 서버의 관심사가 아니지만, 앱이 쓸 순서를 여기 적어 둔다: **상태 → 주행 → 충전** 순으로 덧칠한다. 주행과 충전이 동시에 열리는 일은 없다.

### 창의 경계는 KST 자정에 맞춘다

앱이 하루에 한 행씩 그리므로, 창이 임의 시각에서 시작하면 첫 행과 마지막 행이 둘 다 잘린 반쪽이 된다.

- `to` = 요청 시각(KST)
- `from` = **KST 오늘 자정 − (`days` − 1)일**

`days=7`이면 온전한 6일 + 오늘 부분 = 7행이 된다. 이 계산은 `TeslaTime`이 한다.

### 유령 세션을 여기서 막는다

**창 안에 열린 유령 행이 하나라도 들어오면 창 전체가 주행이나 충전으로 칠해진다.** `8cb61d9`가 `/tesla/status`에서 고친 것과 같은 결함이 타임라인에서는 훨씬 크게 드러난다.

`drives`·`charging_processes`의 **열린 행은 `start_date >= now − 24h`인 것만** 「지금 진행 중」으로 인정하고, 그보다 오래된 열린 행은 결과에서 **버린다.** 기존 `ACTIVITY_SQL`과 같은 규칙이고, 같은 이유로 24시간이다 — 완속 오버나이트가 10시간쯤이고 24시간 연속 주행은 없다. **새로 생긴 유령도 하루면 스스로 낫는다.**

`states`에는 이 규칙을 적용하지 않는다. 유니크 인덱스가 열린 행을 차당 하나로 강제하므로 그것은 현재 상태다.

### 구간은 서버가 창에 맞춰 자른다

앱이 창 밖 값을 받아 스스로 자르게 두지 않는다 — 자르는 규칙이 두 곳에 생긴다.

```sql
GREATEST(start_date, :windowStart)                        AS from_utc
LEAST(COALESCE(end_date, :windowEnd), :windowEnd)         AS to_utc
```

응답 시각은 `TeslaTime.toKst`로 되돌린다.

### SQL

```sql
-- STATE_SEGMENTS_SQL
SELECT s.state::text                                        AS state,
       GREATEST(s.start_date, :windowStart)                 AS from_utc,
       LEAST(COALESCE(s.end_date, :windowEnd), :windowEnd)  AS to_utc
  FROM states s
 WHERE s.start_date < :windowEnd
   AND COALESCE(s.end_date, :windowEnd) > :windowStart
 ORDER BY s.start_date
```

```sql
-- DRIVE_SEGMENTS_SQL  (charging_processes도 같은 모양)
SELECT GREATEST(d.start_date, :windowStart)                 AS from_utc,
       LEAST(COALESCE(d.end_date, :windowEnd), :windowEnd)  AS to_utc
  FROM drives d
 WHERE d.start_date < :windowEnd
   AND COALESCE(d.end_date, :windowEnd) > :windowStart
   AND (d.end_date IS NOT NULL
        OR d.start_date >= (now() AT TIME ZONE 'UTC') - interval '24 hours')
 ORDER BY d.start_date
```

`:windowStart`·`:windowEnd`는 **UTC**로 넘긴다. TeslaMate는 UTC 값을 타임존 없는 `timestamp`에 넣으므로 `now()`(timestamptz)와 직접 비교하면 세션 타임존만큼 어긋난다 — 기존 주석이 이미 기록한 함정이다.

### 응답 크기

실측 최근 7일은 상태 145 + 주행 22 + 충전 1 = **168 구간**이다. `days=30`이면 상태 600 안팎으로 추정된다. 페이지네이션도 다운샘플링도 두지 않는다 — 상한이 30일이고, 그 크기는 한 응답으로 충분하다.

---

## API 2 — `GET /tesla/drive-insights` 필드 셋 추가

새 엔드포인트를 만들지 않는다. 주행 탭이 이미 이 응답 **하나로** 카드 넷을 그리고 있고, 「네 카드를 한 응답에 싣는다」는 기존 컨트롤러 주석의 논리가 그대로 적용된다.

```kotlin
val maxSpeedKmh: Int?              // 역대 최고. months 창과 무관하다
val monthDistanceKm: BigDecimal    // 이번 달, KST 경계. 0을 낸다
val yearDistanceKm: BigDecimal     // 올해, KST 경계. 0을 낸다
```

### 최고 속도만 `months` 창을 따르지 않는다

창이 바뀔 때마다 바뀌면 기록이 아니다. 실측 138 km/h는 2024~2025년 것이라 12개월 창으로 자르면 **134**가 나온다. 앱은 이 값의 라벨을 **「역대 최고」**로 적어 옆 두 타일과 범위가 다름을 글자로 드러낸다.

### `maxSpeedAt`을 두지 않는다

처음에는 그 주행의 날짜를 함께 낼 생각이었으나, **138 km/h가 최소 3건 동률**이다(2025-09-13, 2025-03-22, 2024-03-09). 그중 하나를 골라 「그날 기록했다」고 말하면 거짓이 된다. 어느 것을 고를지에 규칙을 만들 만한 값어치가 없으므로 필드 자체를 두지 않는다.

### 월·연 거리는 `COALESCE(…, 0)`로 0을 낸다

이 저장소의 규칙은 「0은 안 탔다, nil은 기록이 없다」다. 그런데 이 둘은 **기간이 못박힌 합계**라, 그 기간에 주행이 없으면 「0km 탔다」가 사실이지 기록 부재가 아니다. null로 두면 **매달 1일 새벽마다 화면에 「—」가 뜬다.**

`maxSpeedKmh`는 반대로 nullable이다 — 주행이 하나도 없으면 「역대 최고」라는 값 자체가 존재하지 않는다.

### 요약과 같은 숫자가 나와야 한다

`monthDistanceKm`은 `/tesla/summary`의 이번 달 `distanceKm`과 **같은 값이어야 한다.** 두 화면에 다른 숫자가 뜨면 어느 쪽도 못 믿는다. 그래서 모집단 조건과 반올림을 `DRIVE_MONTHLY_SQL`과 정확히 맞춘다.

| 항목 | 값 |
|---|---|
| 모집단 | `d.end_date IS NOT NULL` (거리 조건 없음) |
| 경계 기준 컬럼 | `d.start_date` |
| 경계 시간대 | `AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul'` |
| 반올림 | `ROUND(…::numeric, 1)` |

### SQL

```sql
-- DRIVE_STATS_SQL
SELECT MAX(d.speed_max)                                          AS max_speed_kmh,
       ROUND(COALESCE(SUM(d.distance) FILTER (
           WHERE date_trunc('month', d.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul')
               = date_trunc('month', now() AT TIME ZONE 'Asia/Seoul')), 0)::numeric, 1) AS month_distance_km,
       ROUND(COALESCE(SUM(d.distance) FILTER (
           WHERE date_trunc('year',  d.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul')
               = date_trunc('year',  now() AT TIME ZONE 'Asia/Seoul')), 0)::numeric, 1) AS year_distance_km
  FROM drives d
 WHERE d.end_date IS NOT NULL
```

`GROUP BY`가 없으므로 `drives`가 비어도 한 행이 온다 — `max_speed_kmh`는 null, 거리 둘은 0이다. 이것이 위 nullable 결정과 그대로 맞물린다.

**`months` 파라미터를 쓰지 않는다.** 이 쿼리는 기존 네 쿼리와 창을 공유하지 않으므로, 나중에 `months`를 바꿔도 이 셋은 흔들리지 않는다.

---

## 경계와 오류

| 상황 | 처리 |
|---|---|
| `days`가 1~30 밖 | 400 `ErrorCode.INVALID_REQUEST` — 기존 `limit` 검증과 같은 방식 |
| 창 안에 상태 행이 하나도 없음 | `states: []`. 404가 아니다 — 「없는 리소스」가 아니라 「그 기간에 기록이 없다」 |
| 열린 유령 주행/충전 | 결과에서 제외 (24시간 룰) |
| 진행 중인 진짜 주행/충전 | `to`를 창 끝(`windowEnd`)으로 막아 포함 |
| `drives`가 비어 있음 | `maxSpeedKmh: null`, 거리 둘은 `0` |
| `speed_max`가 null인 주행 | `MAX`가 무시한다. 실측 null 0건이지만 계약은 유지 |

## 테스트

kotest `BehaviorSpec` + mockk, 격리 모드 `InstancePerLeaf`. 컨트롤러 단위 테스트는 이 저장소 관례대로 쓰지 않는다.

**`TeslaVehicleService` — 타임라인**

- `days` 기본값이 7이다
- `days`가 0·31이면 `CustomException(INVALID_REQUEST)`를 던진다
- 창 시작이 **KST 자정 − (days−1)일**로 계산된다 (`days=7`, 요청 시각 2026-08-19 12:34 KST → `from` = 2026-08-13T00:00)
- 리포지토리가 준 UTC 시각이 응답에서 KST로 바뀐다
- 세 배열이 각각 비어도 응답이 성립한다

**`TeslaVehicleService` — 주행 통계**

- `maxSpeedKmh`가 null이면 그대로 null로 나간다
- 거리 둘은 리포지토리가 준 0을 그대로 낸다 (서비스가 null로 바꾸지 않는다)

**리포지토리 통합 테스트를 새로 만들지 않는다.** 이 저장소에 TeslaMate DB를 띄우는 테스트가 없다. 대신 위 SQL은 **실측 DB에서 손으로 돌려 본 값**을 이 문서에 남겼다(이번 달 1,331.3 / 올해 13,440.4 / 최고 138).

## 앱 저장소

`../woori-haru`의 `docs/superpowers/specs/2026-08-19-vehicle-state-timeline-design.md`가 이 API를 쓰는 화면 설계다. 화면이 정한 것 중 이 문서에 영향을 주는 것은 셋이다.

- 겹침 우선순위: 상태 → 주행 → 충전
- 「역대 최고」라는 라벨 (창이 다름을 글자로 드러냄)
- 타임라인은 **매번 새로 받는다** — 「최근 7일」이 계속 움직이므로 캐시하지 않는다
