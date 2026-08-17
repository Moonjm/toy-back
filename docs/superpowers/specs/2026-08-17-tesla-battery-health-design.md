# TeslaMate 배터리 건강 집계 설계

**한 줄 요약.** `charging_processes`에서 만충 환산 주행거리와 사용 가능 용량 표본을 월별 중앙값으로 집계해 낸다. 새로 읽는 테이블도, 쓰는 것도 없다.

## 배경

[2026-08-13 차량 요약·상태 설계](2026-08-13-tesla-vehicle-summary-design.md)로 `/tesla/status`가 나갔다. 그 응답에는 **지금 이 순간의 값만** 있다 — 배터리 82%, 주행가능 430km. 어제와 견줄 수 없으니 열어 봐야 알게 되는 것이 없다.

전기차를 오래 타면서 궁금해지는 것은 **배터리가 얼마나 줄었나**인데, 그 답은 이미 읽고 있는 테이블에 들어 있다. `charging_processes` 한 행에는 충전이 끝난 시점의 배터리 %(`end_battery_level`)와 주행가능거리(`end_rated_range_km`)가 함께 남는다. 둘을 나누면 그날의 만충 환산 주행거리가 나오고, 시간축에 늘어놓으면 열화 곡선이 된다.

용량도 같은 행에서 나온다. `charge_energy_added ÷ ΔSoC × 100`이 그 시점의 사용 가능 용량이다.

**새 테이블을 읽지 않는다.** `charging_processes`는 이미 `/tesla/summary`가 월별로 훑고 있는 테이블이고, 행 수도 수백 단위다 — `positions`(3,000만 행)에서 겪은 문제가 여기엔 없다.

### 실측으로 확인한 것

2026-08-15 충전 한 건이 `17% → 99%`, 주행가능거리 `90km → 520km`, `charge_energy_added` 58.7kWh였다. 100% 환산하면 주행거리 약 525km, 용량 약 71.6kWh다. 대상 차량(2021년 9월 출고 모델 3 롱 레인지)의 EPA 제원 568km / 사용 가능 약 78.5kWh와 견주면 잔존 92%·91%로 두 지표가 같은 자리를 가리킨다. **TeslaMate가 저장하는 `rated_battery_range_km`는 EPA 기준이다**(국내 인증 528km가 아니다).

이 기준선 상수는 **서버에 두지 않는다.** 아래 「서버가 하지 않는 것」 참고.

## 목표

- 월별 만충 환산 주행거리와 사용 가능 용량을 표본 수와 함께 낸다.
- 표본으로 쓸 만한 충전만 골라 낸다 — 얕은 보충 충전이 곡선을 흔들지 않게.

## 비목표

- **쓰기.** 이 엔드포인트는 읽기만 한다. 여전히 유일한 쓰기는 `charging_processes.cost`다.
- **잔존율·열화율 계산.** 신차 기준값은 차종 상수라 서버가 알 이유가 없다.
- **진행 중인 충전.** `end_date IS NULL`인 행은 제외한다. 마감 전 값은 흔들린다.
- **주행(drives) 기반 지표.** 온도별 전비·시간대 분포는 별건이다.

---

## API

### `GET /tesla/battery-health`

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

#### 표본 규칙

| 지표 | 조건 | 이유 |
|---|---|---|
| 만충 환산 주행거리 | `end_battery_level >= 80` | 낮은 SoC에서 100%로 환산하면 오차가 커진다. 80%에서 1% 오차는 1.25km, 20%에서는 5km다 |
| 사용 가능 용량 | `end_battery_level - start_battery_level >= 40` | 짧은 보충은 분모가 작아 오차가 크다. 이 조건을 채우는 충전은 몇 달에 한 번이라 `capacityKwh`는 자주 null이다 |
| 공통 | `end_date IS NOT NULL` | 진행 중인 충전은 값이 흔들린다 |

두 조건이 달라 표본 수도 따로 낸다(`sampleCount`·`capacitySampleCount`). 한 숫자로 합치면 용량이 null인 이유가 「표본이 없어서」인지 「값이 없어서」인지 화면에서 갈리지 않는다.

#### 나눗셈을 서버가 하는 예외

이 저장소는 **나눗셈을 앱에 맡겨 왔다**(`MonthlyStat`이 km당 비용·전비를 내지 않는 이유). 분모가 0이거나 null일 때의 처리를 서버가 정해 버리면 화면이 그것을 따라야 하기 때문이다.

여기는 예외다. **중앙값은 표본 집합 위의 연산이라 나누기 전에는 낼 수 없다.** 원자료를 그대로 보내면 앱이 수백 건을 받아 월별로 묶고 정렬해 중앙값을 내야 하는데, 그건 SQL 한 줄이 하는 일이다.

대신 **분모가 0이 될 길을 WHERE에서 막는다.** `end_battery_level >= 80`이면 0이 아니고, 용량 쪽은 ΔSoC ≥ 40이라 역시 0이 아니다. 서버가 「0일 때 어떻게 할지」를 정하는 상황 자체가 생기지 않는다.

#### 평균이 아니라 중앙값인 이유

급속 충전 직후에는 `rated_battery_range_km`가 실제보다 높거나 낮게 잡히는 일이 있다. 한 달에 표본이 두세 개뿐인 달에서 평균은 그 한 건에 끌려간다. 중앙값은 끌려가지 않는다.

#### 월 경계는 `end_date` 기준으로 KST에서 자른다

측정 시점은 충전이 **끝난** 때다. `start_date`로 자르면 자정을 넘긴 오버나이트 충전이 앞 달로 들어간다.

KST로 옮긴 뒤 자르는 것은 `DRIVE_MONTHLY_SQL`과 같은 이유다 — UTC 기준으로 자르면 월초 9시간이 옆 달로 샌다.

#### SQL

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
- `end_battery_level`이 100을 넘는 일은 없지만, 만충 환산이 신차 기준을 넘는 값(냉간·BMS 재보정)은 나올 수 있다. **자르지 않고 그대로 낸다.**

#### 성능

`charging_processes`는 수백 행이다. 전체 스캔이라도 즉시 끝나므로 창을 두지 않는다. `positions`에서 겪은 문제(3,000만 행·BRIN만 있는 `date`)와는 무관한 테이블이다.

인덱스를 새로 만들지 않는다 — 남의 스키마다.

### 서버가 하지 않는 것

**신차 기준값(568km / 78.5kWh)은 앱 상수다.** 서버에 두지 않는 이유는 셋이다.

1. 차종·연식마다 다른 값인데, TeslaMate에는 제원표가 없다(`cars`에 있는 것은 `model`·`trim_badging`·`efficiency`뿐이다). 어차피 손으로 심는 상수라면 화면에 가까운 쪽이 낫다.
2. 서버가 잔존율을 내면 그 반올림·경계 처리를 화면이 따라야 한다. 이 저장소가 km당 비용을 내지 않는 것과 같은 이유다.
3. 상수가 서버에 있으면 값을 고칠 때 배포가 필요하다.

### 인증

기존 `SecurityConfig`가 요구하는 그대로다. `PublicEndpoint`를 두지 않는다 — 차량 상태와 같은 계열의 데이터다.

---

## 컴포넌트

기존 차량 쪽 구조를 그대로 따른다. 충전(`TeslaCharge*`)이 아니라 **차량(`TeslaVehicle*`)에 붙인다** — 읽는 테이블은 `charging_processes`지만, 이 값이 답하는 질문은 「차가 어떤 상태인가」다.

| 파일 | 변경 |
|---|---|
| `TeslaVehicleController` | `GET /tesla/battery-health` 추가 |
| `TeslaVehicleService` | `batteryHealth()` — 행을 DTO로 옮긴다. 계산은 SQL이 끝냈다 |
| `TeslaVehicleRepository` | `batteryHealthMonthly(): List<BatteryHealthMonthRow>` 추가 |
| `JdbcTeslaVehicleRepository` | 위 SQL 구현 |
| `TeslaVehicleRows` | `BatteryHealthMonthRow` 추가 |
| `TeslaVehicleDtos` | `TeslaBatteryHealthResponse`·`BatteryHealthSample` 추가 |

**nullable 정수는 `getObject`로 읽는다.** `rs.getInt`는 SQL NULL에 0을 돌려줘 없는 값과 진짜 0이 구분되지 않는다 — 기존 주석과 같은 규칙이다. `capacity_kwh`는 `getBigDecimal`이라 null이 그대로 온다.

## 오류 처리

TeslaMate DB에 못 붙으면 기존 차량 API와 같은 경로로 실패한다. 이 엔드포인트만의 오류 코드를 만들지 않는다.

앱에서는 이 호출이 `/tesla/status`와 **독립**이다 — 하나가 실패해도 다른 카드는 그려진다.

## 테스트

`TeslaVehicleServiceTest`의 관례(가짜 리포지토리)를 따른다. SQL 자체는 단위 테스트로 검증되지 않으므로, **서비스가 검증하는 것은 행 → DTO 변환과 정렬뿐**이다.

- 표본이 하나도 없다 → `samples`가 빈 배열이다(null이 아니다)
- 용량 표본이 없는 달 → `capacityKwh`가 null, `capacitySampleCount`가 0
- 여러 달 → 오래된 것부터 정렬돼 나온다

SQL은 실 DB에서 한 번 돌려 눈으로 확인한다 — 특히 **월 경계가 KST로 잘렸는지**(자정 직후에 끝난 충전이 어느 달로 들어가는지)와 `percentile_cont`가 null을 건너뛰는지.

## 열린 항목

- **배터리를 교체하면** 곡선이 위로 튄다. 그때 기준선을 어떻게 할지는 그때 정한다. 서버는 표본을 그대로 내므로 고칠 것이 없다.
- 급속 충전 직후 표본이 계속 튀는 것이 실제로 관찰되면, 표본 조건에 「직전이 급속이 아닐 것」을 더할 수 있다(`charges.fast_charger_present`). 중앙값으로 충분한지 먼저 본다.
