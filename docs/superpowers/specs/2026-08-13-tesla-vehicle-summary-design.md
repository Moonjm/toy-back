# TeslaMate 월별 차량 요약·현재 상태 설계

**한 줄 요약.** 충전만 읽던 TeslaMate 보조 연결을 `drives`·`positions`·`states`까지 넓혀,
월 단위 차량 요약(주행+충전)과 현재 상태를 낸다. 쓰는 것은 여전히 `charging_processes.cost` 하나뿐이다.

## 배경

[2026-08-13 충전 내역 설계](2026-08-13-teslamate-charging-design.md)로 충전 내역 조회와 금액 수정을 냈다.
앱에는 「충전 내역」 화면 하나가 붙었다.

그런데 「이번 달 차에 얼마 썼나」는 충전 비용만으로 답이 안 된다. **같은 달 주행 km가 있어야
km당 비용과 전비가 나온다.** 주행은 `drives` 한 테이블의 합이고, 충전 집계는 이미 만들어 뒀다.
둘을 한 응답으로 합치면 화면 하나가 월 단위로 다 답한다.

두 번째로, 앱에서 가장 자주 볼 것은 **지금 차가 어떤 상태인가**다. 배터리·주행가능거리·주행거리·
실내외 온도·에어컨·위치·타이어 공기압이 `positions` 최신 1행과 `states`의 열린 행에 다 있다.
공기압은 계절이 바뀔 때 실제로 쓸모가 있다.

세 번째로, 금액이 빈 충전을 **연달아 채워 넣는 화면**이 필요하다. 지금은 달을 옮겨 가며 목록에서
한 건씩 찾아 들어가야 한다.

## 목표

- 월 단위 차량 요약: 주행 km·소요 시간, 충전 kWh·비용·건수, 지난달 대비, 최근 12개월 추이,
  그리고 **그 달의 충전 목록**을 한 응답에 낸다.
- 차량 현재 상태를 **그 값이 언제 기준인지와 함께** 낸다.
- 금액이 빈 충전을 기간과 무관하게 최신순으로 낸다.

## 비목표

- **차량 데이터 쓰기.** 여전히 `charging_processes.cost` 하나만 쓴다. 주행·위치·상태는 읽기 전용이다.
- **차를 깨우기.** TeslaMate에 그런 API가 없고, 있어도 배터리를 먹는다. 상태는 DB에 쌓인 마지막 값이다.
- **좌표·지도.** 위치는 지오펜스 이름(없으면 주소)만 낸다. 위경도를 응답에 싣지 않는다 —
  생활 동선이 그대로 드러나는 값이고, 앱이 지도를 그리지 않는다.
- **주행 1건 목록.** 이번 화면은 월 합계만 묻는다. 개별 주행 기록(`drives` 행)을 나열하지 않는다.
- **차량 필터.** 차량이 1대다. `car_id`를 파라미터로도 응답으로도 두지 않는다(충전과 같다).
- **나눗셈.** km당 비용·전비·효율을 서버가 계산하지 않는다. 분모가 0이거나 null일 때의 처리를
  서버가 정해 버리면 화면이 그것을 따라야 한다(충전 단가와 같은 규칙).

---

## API

### `GET /tesla/summary?yearMonth=2026-08`

```json
{ "month": { "yearMonth": "2026-08",
             "distanceKm": 842.3, "drivingMin": 1043, "driveCount": 61,
             "energyAddedKwh": 186.4, "energyUsedKwh": 201.7,
             "cost": 52300, "chargeCount": 5 },
  "previous": { "yearMonth": "2026-07", "distanceKm": 701.0, "...": "month와 같은 모양" },
  "trend": [ { "yearMonth": "2025-09", "distanceKm": 655.1,
               "energyAddedKwh": 141.0, "energyUsedKwh": 152.2, "cost": 39800 } ],
  "charges": [ { "id": 3312, "startedAt": "2026-08-11T22:14:00", "...": "기존 ChargeListItem" } ] }
```

**`yearMonth` 하나만 받는다. 없으면 400이다.** 충전 목록 엔드포인트가 받던 `from`/`to`는
이 설계에서 사라진다(아래 「없어지는 것」 참고) — 화면이 월 단위이고, 쓰는 곳이 없었다.

`month`·`previous`·`trend`의 항목은 같은 모양이다. `previous`는 직전 달이고, 그 달에 아무것도
없으면 **0이 아니라 null 필드를 가진 항목**이다. 0은 「안 탔다」는 뜻이 되어 「기록이 없다」와
구분되지 않는다.

`trend`는 **`yearMonth`를 포함해 거슬러 12개월 고정**이다. 데이터가 없는 달도 자리를 채운다
(값은 null) — 앱이 차트의 x축을 만들 때 빈 달을 건너뛰면 계절 비교가 어긋난다.

`charges`는 기존 충전 목록 항목 그대로다(진행 중인 충전 제외, `start_date DESC`).
목록과 합계를 한 응답에 싣는 이유는 **화면이 하나이기 때문이다.** 둘을 나누면 같은 화면이
두 번 부르고, 그중 하나는 반드시 다른 하나를 기다린다.

**전비의 분자는 `charge_energy_added`**(차에 들어간 양), **km당 비용의 분자는 `cost`**다.
둘 다 분모는 같은 달 주행 km고, 나눗셈은 앱이 한다.

**이 값은 근사다.** 그 달에 충전한 전기를 그 달에 다 쓰는 것이 아니라, 월말에 채운 것은 다음 달에
쓰인다. 월 경계에서 흔들린다는 뜻을 앱 화면이 한 줄로 적는다. 그럼에도 이 수치를 쓰는 이유는,
계절 단위로 보면 그 흔들림보다 에어컨·히터의 차이가 훨씬 크기 때문이다.

#### 집계 출처

| 필드 | 출처 |
|---|---|
| `distanceKm` | `SUM(drives.distance)` — km 단위 `double precision` |
| `drivingMin` | `SUM(drives.duration_min)` |
| `driveCount` | `COUNT(*)` of `drives` |
| `energyAddedKwh` / `energyUsedKwh` / `cost` / `chargeCount` | `charging_processes` — 충전 설계의 집계와 **같은 SQL을 공유한다** |

주행의 기간 경계는 `drives.start_date`, 충전은 `charging_processes.start_date`다. 둘 다 UTC이고
KST 경계로 변환하는 규칙은 충전 설계와 같다(`2026-08` → `2026-07-31T15:00 ~ 2026-08-31T15:00`).

`drives`에도 **끝나지 않은 행이 있을 수 있다**(`end_date IS NULL`). 주행 중에는 `distance`가
아직 확정 전이므로 **충전과 같이 제외한다.**

12개월 추이는 달마다 쿼리를 돌리지 않는다. 주행·충전 각각 한 번씩, `date_trunc('month', ...)`로
묶어 12행을 받는다. 그 위에서 달을 키로 합친다 — **주행만 있는 달과 충전만 있는 달이 각각 있다.**

### `GET /tesla/status`

```json
{ "asOf": "2026-08-13T14:02:00",
  "state": "asleep", "stateSince": "2026-08-13T09:30:00",
  "batteryLevel": 72, "usableBatteryLevel": 70,
  "ratedRangeKm": 312.4, "estRangeKm": 288.0, "odometerKm": 41203.8,
  "insideTempC": 31.5, "outsideTempC": 33.0, "climateOn": false,
  "locationName": "집",
  "tpmsBar": { "fl": 2.9, "fr": 2.9, "rl": 2.8, "rr": 2.9 } }
```

`positions`의 최신 1행 + `states`의 열린 행(`end_date IS NULL`)이다.

**`asOf`는 그 위치 행의 시각이다(KST).** 주차 중에는 `positions`가 뜸하게 쌓여서 몇 시간 전
값일 수 있다. 그래서 값과 시각을 **항상 함께** 낸다 — 시각 없이 배터리 %만 보면 지금 값으로 읽힌다.

`state`는 `states.state`를 그대로(`online`·`asleep`·`offline`·`driving`·`charging`) 낸다.
**우리 이름으로 번역하지 않는다.** 상류가 값을 늘리면 매핑이 조용히 틀리는 쪽보다, 모르는 값이
그대로 올라와 앱이 원문을 보여주는 쪽이 낫다.

위치는 `positions.latitude/longitude`가 아니라, TeslaMate가 그 위치에 붙여 둔 지오펜스 이름을 쓴다.
`positions`에는 지오펜스 참조가 없으므로 **좌표로 지오펜스를 찾는다** —
`geofences`는 `latitude`·`longitude`·`radius`를 가지고 있어, 반경 안에 드는 것 중 가장 가까운 하나다.
없으면 null이다(주소는 내지 않는다 — `positions`에 주소 참조가 없고, 역지오코딩을 새로 붙이지 않는다).

공기압은 **TeslaMate 저장 단위인 bar 그대로** 낸다. psi 병기는 앱이 한다.

#### 성능

`positions`는 수백만 행이고 인덱스가 BRIN이다. **`ORDER BY date DESC LIMIT 1`을 그냥 두지 않는다** —
BRIN은 정렬을 돕지 못해 전체를 훑을 수 있다. 최근 창을 먼저 좁힌다.

```sql
SELECT ... FROM positions
 WHERE date >= now() - interval '7 days'
 ORDER BY date DESC LIMIT 1
```

7일 안에 행이 없으면(장기 주차·차량 오프라인) 창 없이 한 번 더 돌린다. 두 번째 쿼리는 느려도
되는 자리다 — 그때는 애초에 보여 줄 최신 값이 없다.

**배포 전에 실제 DB에서 `EXPLAIN ANALYZE`로 확인한다.** 라즈베리파이의 실제 행 수와 인덱스
구성을 보지 않고 정한 수치라(7일), 결과에 따라 창을 조정하거나 `date` B-tree 인덱스 추가를 검토한다.
**남의 DB에 인덱스를 만드는 것은 TeslaMate 업그레이드와 충돌하지 않는지 확인한 뒤에 한다.**

#### 컬럼 확인

`tpms_pressure_fl`·`fr`·`rl`·`rr`, `usable_battery_level`, `est_battery_range_km`는
TeslaMate 버전에 따라 없을 수 있다. **구현 전에 실제 컬럼 목록을 확인하고, 없는 것은 응답에서 뺀다.**
있는 줄 알고 SELECT에 넣으면 상태 조회 전체가 죽는다.

### `GET /tesla/charges/missing-cost?limit=50`

```json
{ "totalCount": 37, "items": [ { "id": 3120, "...": "기존 ChargeListItem" } ] }
```

`cost IS NULL AND end_date IS NOT NULL`을 `start_date DESC`로. **기간 파라미터가 없다.**
채워 넣으려는 사람에게 필요한 것은 「어느 달의 빈 건」이 아니라 「빈 건 전부」다.

`totalCount`는 `limit`과 무관한 전체 개수다 — 앱이 배지에 「미등록 37건」을 띄우고, 채울수록
줄어드는 것을 본다. `limit` 기본값 50, 최대 200. 한 번에 다 내리지 않는 이유는 오래된 기록이
수백 건일 수 있어서고, 더 필요하면 다시 부르면 된다(커서를 두지 않는다 — 채우면 목록에서
빠지므로 다시 부르면 자연히 다음 것들이 온다).

기존 목록 엔드포인트에 `costMissing=true` 필터를 얹지 않는다. 그쪽은 「기간 필수」가 계약인데
이쪽은 기간이 없다. 한 엔드포인트에 두 계약을 섞으면 「기간 없이 부르면 400」이 조건부가 된다.

### 그대로 두는 것

- `GET /tesla/charges/{id}` — 요약 화면의 목록 항목을 눌렀을 때의 상세
- `PUT /tesla/charges/{id}/cost` — 금액 수정. **등록 화면도 이것을 쓴다.** 새 쓰기 경로를 만들지 않는다

### 없어지는 것

- **`GET /tesla/charges`(월/기간 목록) 제거.** 목록이 `/tesla/summary`의 `charges`로 들어가면서
  부를 곳이 없어진다. `from`/`to` 분기와 그 조합 검증(넷 중 셋)도 함께 사라진다 —
  애초에 앱이 쓰지 않던 파라미터다.

  **배포 순서에 주의한다.** 서버를 먼저 올리면 구버전 앱의 충전 화면이 404를 받는다.
  사용자 2명·개인 배포라 앱 갱신과 붙여서 하면 되지만, 몇 시간 어긋나는 동안은 그 화면만 깨진다는
  것을 알고 하는 것과 모르고 하는 것은 다르다.

### 인증

기존 `SecurityConfig`가 기본으로 인증을 요구한다. `PublicEndpoint`를 두지 않는다 —
주행 거리·위치·차량 상태는 충전 시각보다 더 직접적으로 생활을 드러낸다.

소유자 검사는 하지 않는다(차량이 두 사용자 공용, 충전과 같다).

---

## 컴포넌트

```
apps/daily-record/src/main/kotlin/com/toy/backend/tesla/
    TeslaChargeController.kt       상세·금액 수정·미등록 목록 (월 목록 제거)
    TeslaVehicleController.kt      요약·상태  ← 새 파일
    TeslaVehicleDtos.kt            요약·상태 응답  ← 새 파일
    TeslaVehicleService.kt         기간 해석, KST↔UTC, 달 병합  ← 새 파일
    TeslaVehicleRepository.kt      인터페이스 + JdbcClient 구현  ← 새 파일
    TeslaChargeService.kt          월 목록 관련 제거, 집계는 요약과 공유
    TeslaChargeRepository.kt       미등록 조회 추가, 월 집계 SQL을 공유 가능한 형태로
```

**컨트롤러를 나눈다.** 충전(`/tesla/charges/*`)과 차량(`/tesla/summary`·`/tesla/status`)은
읽는 테이블도 갱신 주기도 다르다. 한 파일에 다섯 엔드포인트를 두면 그 경계가 안 보인다.

**월 집계 SQL은 한 벌만 둔다.** 요약의 `month`·`previous`·`trend`가 쓰는 충전 집계와 기존
`summarize`가 같은 계산이다. 두 벌을 두면 한쪽만 고쳐진다.

`KST`↔UTC 변환은 `TeslaChargeService`의 것을 그대로 쓴다. **공용 자리로 옮긴다** —
지금은 companion object에 있는데, 차량 쪽에서도 필요하다.

---

## 오류 처리

| 상황 | 결과 |
|---|---|
| `yearMonth` 없음·형식 오류 | 400 `INVALID_REQUEST` (Spring 변환 + 공통 핸들러) |
| `limit`이 1 미만·200 초과 | 400 `INVALID_REQUEST` |
| `positions`에 행이 하나도 없음 | 200. **`asOf`가 null인 빈 상태** — 「기록이 아직 없다」와 「못 읽었다」는 다르다 |
| `states`에 열린 행이 없음 | 200. `state`·`stateSince`만 null이고 나머지는 낸다 |
| 그 달에 주행·충전이 없음 | 200. 필드가 null인 항목(0이 아니다) |
| TeslaMate DB에 못 붙음 | 500. **가리지 않는다** — 빈 값으로 눙치면 「안 탔다」와 구분되지 않는다 |

---

## 테스트

`TeslaVehicleServiceTest` (kotest `BehaviorSpec` + mockk, 리포지토리는 목).

- `yearMonth=2026-08`이 UTC `2026-07-31T15:00 ~ 2026-08-31T15:00`으로 번역된다(충전과 같은 규칙)
- `previous`가 직전 달로 잡힌다 — **1월이면 전년 12월이다**
- `trend`가 **기준 달 포함 12개월**이고, 데이터가 없는 달도 자리를 채운다
- 주행만 있는 달과 충전만 있는 달이 **하나의 추이 항목으로 합쳐진다**
- 그 달에 아무것도 없으면 필드가 null이다(0이 아니다)
- `positions` 행이 없으면 `asOf`가 null인 상태를 낸다
- `states` 열린 행이 없어도 나머지 필드는 나온다
- 미등록 목록의 `totalCount`가 `limit`과 무관하다
- 응답 시각이 UTC → KST로 되돌아온다

**단위 테스트가 못 잡는 것을 명시한다.** SQL 자체, BRIN 위의 실제 실행 계획, 없는 컬럼,
지오펜스 반경 판정은 전부 목 뒤에 숨는다. `AGENTS.md`가 이 경우를 못 박아 두었다 —
**앱을 띄워 세 엔드포인트를 호출하고, `EXPLAIN ANALYZE`로 상태 쿼리를 확인한다.**

---

## 열린 항목

- **`positions` 실행 계획.** 7일 창은 추정이다. 실제 행 수·BRIN 구성을 보고 조정한다.
- **컬럼 존재.** `tpms_*`·`usable_battery_level`·`est_battery_range_km`를 실제 스키마에서 확인한다.
- **지오펜스 반경 판정.** 좌표로 반경 안을 찾는 계산을 SQL에서 할지(`earthdistance`·`cube` 확장이
  깔려 있는지 확인 필요) 단순 근사(위경도 차이)로 할지는 실제 지오펜스 수(수 개)를 보고 정한다.
  **개수가 적으면 전부 읽어 앱 서버에서 판정하는 것이 확장 의존보다 낫다.**
- **`drives`의 진행 중 행.** 제외하기로 했지만, 주행이 끝나기 전에는 그 달 주행 km가 실제보다
  적게 보인다. 하루 수십 km 규모라 감수한다.
