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

## 실측 (2026-08-20)

**이 설계는 처음에 실측 없이 썼고, 구현 직전에 파이에서 재 확인했다.** 아래가 그 결과다.
값이 설계를 바꾼 자리는 표 밑에 따로 적는다.

| # | 잰 것 | 결과 |
|---|---|---|
| 1 | `positions` 컬럼 | 오토파일럿 관련 컬럼 **없음**. `battery_level`·`usable_battery_level`·`rated_battery_range_km`은 있다 |
| 2 | 주행 사이 순수 주차 구간 | 월 **14~99건**, 하락 합 월 75~169km. 표본은 넉넉하다 |
| 3 | `drives.speed_max` 분포 | 0~140에 다 든다(140 이상 0건). 20km/h 폭이면 **7칸** |
| 3b | 평균 속도(`distance ÷ duration`) 분포 | 0~100에 다 든다(100 이상 0건). 20km/h 폭이면 **5칸** |
| 4 | 충전 시작 SoC 분포 | 10~50%에 몰려 있다(0~10% 2건, 90~100% 4건). 10% 폭으로 충분 |
| 4b | 충전 종료 SoC 분포 | 90~100%가 260건 + **정확히 100%가 71건** |
| 5 | `geofences` | **0행** — 앞선 설계에서 확인된 그대로다 |
| 5b | `addresses` | 전 기간 도시 109·주 10·나라 1. 최근 12개월 도착지 기준 **도시 21·주 5·나라 1** |
| 5c | 충전소별 집계 | 지오펜스가 0행이어도 **주소로 이름이 붙어 12곳이 나온다**(상위 138·126·73건). 금액 미입력이 섞인 곳이 있다 |
| 6 | 48시간 SOC 창 | **32ms · 12,517행**. 168시간은 102,141행 |
| 7 | `drives` NULL | 완료 주행 5,058건에 `speed_max`·`duration_min`·`distance`·`start_rated_range_km`·`outside_temp_avg` **전부 NULL 0건** |
| 8 | 전체 기간 | 2021-09-03 ~ 2026-08-20, 완료 주행 5,058건 = **60개월** |

### 실측이 바꾼 것 넷

**1. `battery-window`의 표본을 5분 슬롯으로 솎는다.** 스펙 초안은 「48시간이면 수백 개라
솎지 않는다」고 썼는데 실측이 **12,517행**이었다(약 750KB). 쿼리는 32ms라 초안이 정한 폴백
조건(「1초를 넘으면」)에 걸리지 않지만, **그 조건이 막으려던 것은 시간이 아니라 무게다.**

5분마다 첫 행 하나만 남기면 48시간 **82개**, 168시간 **423개**다(67ms·298ms). 12,517행이
82슬롯으로 줄어드는 이유는 표본이 고르게 깔려 있지 않기 때문이다 — TeslaMate는 차가 깨어
있을 때만 위치를 쌓아서, 12,517행이 48시간 중 주행·충전한 몇 시간에 몰려 있다. 주차 중에는
애초에 행이 없다. **즉 솎아서 잃는 것은 주행 중의 초 단위 해상도뿐이고, 48시간을 한 화면에
그리는 차트에서 그 해상도는 픽셀로도 안 보인다.**

**2. `usableBatteryLevel`은 대부분 null이다.** 최근 30일 392,054행 중 **11,575행(3.0%)**만
채워져 있다. 계약에서 빼지는 않는다(있는 3%는 진짜 값이다). **앱이 이 필드로 선을 그리려
하면 거의 다 끊긴다** — 주 계열은 `batteryLevel`이고 `usableBatteryLevel`은 있을 때만 점을
찍는 보조 계열이다.

**3. `bestEfficiency`에 거리 하한이 있어야 한다.** 하한 없이 `distance ÷ ΔratedRange` 최대를
뽑으면 **0.2km 주행이 8.2배로 1등**이 된다(정격거리 표시가 안 움직인 짧은 주행이다). 하한
20km를 걸면 26.7km/15.3km(1.74배)가 1등이고 2·3위도 47.0km·26.5km으로 말이 된다.

**4. 팬텀 드레인에 음수 구간이 3,960:628로 섞여 있다.** 충전 기록이 없는 주차인데 정격거리가
**늘어난** 구간이다(BMS 재보정, 또는 TeslaMate가 세션으로 못 잡은 충전). **0으로 자르지
않고 부호 그대로 더한다** — 자르면 합이 위로 편향된다. 월별 합은 어차피 전부 양수로
나온다(75~169km).

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
- **진행 중인 달의 `idleMin`은 「지금」까지만 센다.** 달 전체 분에서 빼면 8월 20일에 8월의 정지 시간이 아직 오지 않은 11일치까지 포함한다 — 그 달만 막대가 솟는다. 요일별 `occurrences`도 같다: 아직 오지 않은 그 달의 요일은 세지 않는다.
- **`parkDrainRatedKm`은 음수 구간을 0으로 자르지 않는다.** 충전 기록 없이 정격거리가 늘어난 구간이 3,960:628로 섞여 있고(실측), 자르면 합이 위로 편향된다. 월 합은 어차피 전부 양수다.

### 응답 크기

전 기간(`months=0`)일 때 `monthly` 59개 + `driveTimes` 최대 168개 + `chargeTimes` 최대 168개 + 나머지 배열들로, 대략 30~50KB로 본다. 사용자 2명이라 문제될 규모가 아니다. **`EXPLAIN ANALYZE`로 전체 응답이 2초를 넘으면** `driveTimes`/`chargeTimes`를 요일×3시간 구간으로 묶어 크기를 8분의 1로 줄인다.

### 쿼리 방향

전부 `drives`와 `charging_processes`만 읽는다. 공통 규칙 셋을 지킨다.

1. **월 경계는 KST로 자른다.** `date_trunc('month', d.start_date AT TIME ZONE 'Asia/Seoul')`. UTC로 자르면 월초 9시간이 옆 달로 샌다 — 이미 `TeslaTime`에 있는 관례다.
2. **유령 행을 뺀다.** `end_date IS NOT NULL`. `drives` 12건·`charging_processes` 6건이 마감되지 않은 채 남아 있다(앞선 설계 실측).
3. **`GROUP BY`로 빈 달이 사라지지 않게** 달 축을 `generate_series`로 만들고 좌측 조인한다. 기존 `trend`가 쓰는 방식과 같다.

`speedEnergyBuckets`의 평균 속도는 `distance / (duration_min / 60.0)`으로 주행마다 구해 버킷을 나눈다. **이 나눗셈은 버킷을 고르기 위한 것이라 응답에 나가지 않으므로** 「나눗셈을 하지 않는다」와 부딪히지 않는다.

**버킷 경계는 실측이 정했다.**

| 배열 | 폭 | 칸 수 | 근거 |
|---|---|---|---|
| `speedBuckets`(`speed_max`) | 20 km/h | 7 (`0~20` … `120~`) | 실측 3 — 140 이상 0건 |
| `speedEnergyBuckets`(평균 속도) | 20 km/h | 5 (`0~20` … `80~`) | 실측 3b — 100 이상 0건 |
| `chargeStartLevels`·`chargeEndLevels` | 10 %p | 10 (`0~10` … `90~100`) | 실측 4·4b |

**충전 SoC 버킷의 마지막 칸만 양끝이 닫힌다**(`90 이상 100 이하`). 실측 4b에서 정확히 100%로 끝난 충전이 71건인데, 다른 배열처럼 「`to` 미만」으로 두면 그 71건이 어느 칸에도 안 들어간다. `start_battery_level`·`end_battery_level`이 null인 충전이 1건 있어 세지 않는다.

`records.bestEfficiency`에는 **거리 하한 20km**를 건다. 하한이 없으면 정격거리 표시가 움직이지 않은 0.2km 주행이 1등이 된다(실측 3번 아래 「실측이 바꾼 것」 3).

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
    { "at": "2026-08-18T15:02:00", "batteryLevel": 62, "usableBatteryLevel": 61 },
    { "at": "2026-08-18T15:07:00", "batteryLevel": 61, "usableBatteryLevel": null }
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
- **표본을 5분 슬롯으로 솎는다.** 초안은 「48시간이면 수백 개라 솎지 않는다」였는데 실측이 12,517행이었다(위 실측 6). 5분마다 첫 행 하나만 남겨 48시간 82개·168시간 423개로 낸다 — 자세한 근거는 「실측이 바꾼 것 넷」의 1번.
- **`usableBatteryLevel`은 대부분 null이다**(실측 3.0%). 주 계열은 `batteryLevel`이고, 이 값은 있을 때만 찍는 보조 계열이다.
- **`at`은 그 슬롯의 실제 표본 시각이다.** 슬롯 경계(5분 눈금)로 옮기지 않는다 — 옮기면 없는 시각의 값이 된다.

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
