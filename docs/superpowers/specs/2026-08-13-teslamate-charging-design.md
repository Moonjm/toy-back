# TeslaMate 충전 내역 조회·금액 수정 설계

**한 줄 요약.** TeslaMate의 PostgreSQL에 읽기 전용 성격의 보조 연결을 하나 더 열어 충전 내역을 월·기간 단위로 조회하고, `charging_processes.cost` 한 컬럼만 고친다.

## 배경

라즈베리파이에서 TeslaMate(v4.0.1)가 차량 데이터를 쌓고 있다. 충전 1건은 `charging_processes` 한 행이고, 금액은 그 행의 `cost` 컬럼이다.

**TeslaMate에는 쓸 수 있는 데이터 API가 없다.** v4.0.1의 `/api` 스코프에 있는 것은 `PUT /api/car/:id/logging/resume`과 `.../suspend` 둘뿐이다. 충전 내역을 읽는 엔드포인트가 없고, 금액 수정은 `/charge-cost/:id` **LiveView 화면**으로만 제공된다 — HTML + 웹소켓이라 서버 대 서버로 부를 물건이 아니다.

남은 경로도 전부 막힌다. MQTT는 실시간 차량 상태만 발행해 과거 목록이 없고 쓰기도 안 된다. Grafana의 `/api/ds/query`로 SQL을 던질 수는 있지만 조회 전용이고, 토큰이 하나 더 늘며, 결국 같은 DB를 한 단계 돌아가는 것이다.

**그래서 TeslaMate DB에 직접 붙는다.** TeslaMate 자신도 그 화면에서 같은 컬럼을 UPDATE 한다.

`charging_processes.cost`는 원래 `numeric(6,2)`(최대 9999.99)였다. 상류가 2026-07-18 마이그레이션(`increase_geofence_cost_precision`)으로 `numeric(14,2)`로 넓혔지만 그것은 v4.0.1(2026-06-14 릴리스) 이후다. **이 DB는 이미 손으로 `numeric(10,2)`로 바꿔 둔 상태다** — 원화 금액을 그대로 넣어도 된다(최대 99,999,999.99).

## 목표

- 충전 내역을 **월 단위**로, 또는 **날짜 범위**로 조회한다.
- 목록에 기간 합계(건수·총 kWh·총 금액)를 함께 낸다.
- 목록에서 항목을 누르면 볼 상세를 낸다.
- `cost`를 고친다. **TeslaMate DB에 쓰는 것은 이 컬럼 하나뿐이다.**

## 비목표

- **TeslaMate의 다른 데이터 쓰기.** 지오펜스·설정·차량·주행 기록은 읽지도 쓰지도 않는다.
- **충전 곡선 그래프.** `charges`(샘플 로그)는 집계 수치로만 쓰고 시계열은 내보내지 않는다. 세션당 수백~수천 행이라 다운샘플링 정책이 따라붙는데, 지금 화면이 요구하지 않는다.
- **가계부 연동.** 충전 금액을 `ledger_entries`로 옮기지 않는다.
- **차량 필터.** 차량이 1대다. `car_id` 파라미터도 응답의 차량명도 두지 않는다.
- **금액 비우기.** `cost`를 `null`로 되돌리는 수단을 두지 않는다. 잘못 넣은 금액은 다시 보내 바로잡는다.
- **사용자별 격리.** 차량은 두 사용자 공용이다. 인증만 요구하고 소유자 검사를 하지 않는다.

---

## 접근 계층

### JPA를 붙이지 않고 `JdbcClient`를 쓴다

TeslaMate DB에 EntityManagerFactory를 만들지 않는다.

- 이 앱은 `ddl-auto: update`다(`AGENTS.md`: "스키마 마이그레이션 도구 없음"). 남의 DB에 Hibernate를 붙이면 스키마를 건드릴 여지가 생긴다. 보조 EntityManagerFactory에 `none`을 주면 막히지만, `LocalContainerEntityManagerFactoryBean`·`JpaTransactionManager`·`@EnableJpaRepositories(basePackages=...)` 분리가 따라온다 — 얻는 것보다 크다.
- 필요한 것은 SELECT 넷(목록·합계·상세·`charges` 집계)과 UPDATE 하나다. 대신 `geofences`·`addresses` 조인과 집계가 들어가서 SQL 쪽이 더 읽기 쉽다.

Spring Boot 4.1이라 `JdbcClient`를 그대로 쓴다. **TeslaMate DataSource에는 EntityManagerFactory가 없으므로 Hibernate가 닿지 않는다.**

### 두 DataSource를 **둘 다** 명시적으로 정의한다

보조 `DataSource` 빈을 등록하는 순간 `DataSourceAutoConfiguration`의 `@ConditionalOnMissingBean(DataSource.class)`가 꺼진다. **그러면 기존 daily-record DataSource가 통째로 사라진다.** 자동설정에 기대던 것을 손으로 다시 만들어야 한다.

```kotlin
@Bean @Primary @ConfigurationProperties("spring.datasource")
fun dataSourceProperties(): DataSourceProperties

@Bean @Primary @ConfigurationProperties("spring.datasource.hikari")
fun dataSource(properties: DataSourceProperties): DataSource

@Bean @ConfigurationProperties("teslamate.datasource")
fun teslaMateDataSourceProperties(): DataSourceProperties

@Bean @ConfigurationProperties("teslamate.datasource.hikari")
fun teslaMateDataSource(...): DataSource

@Bean
fun teslaMateJdbcClient(teslaMateDataSource: DataSource): JdbcClient
```

`@Primary`가 빠지면 JPA·트랜잭션 매니저가 어느 DataSource를 쓸지 몰라 기동에 실패하거나, 더 나쁘게는 **TeslaMate DB를 daily-record로 착각해 `ddl-auto: update`가 거기에 테이블을 만든다.** 이 배선은 단위 테스트가 잡지 못한다(`AGENTS.md`: "단위 테스트는 트랜잭션 경계·DB 제약 문제를 잡지 못한다"). **앱을 띄워 두 DB가 각자 제 위치에 붙었는지 확인한다.**

### 트랜잭션 매니저를 따로 만들지 않는다

TeslaMate 쪽 작업은 UPDATE 한 건이라 autocommit으로 충분하다. **`TeslaChargeService`에는 `@Transactional`을 붙이지 않는다.** 붙이면 기본(JPA) 트랜잭션 매니저에 잡히는데, 그 트랜잭션은 daily-record 커넥션의 것이라 TeslaMate 쪽 SQL에 아무 효력이 없다. 있는 것처럼 보이는 경계가 없는 것보다 나쁘다.

### 설정

```yaml
teslamate:
  datasource:
    url: jdbc:postgresql://${TESLAMATE_DB_HOST:localhost}:${TESLAMATE_DB_PORT:5432}/teslamate
    username: ${TESLAMATE_DB_USERNAME:teslamate}
    password: ${TESLAMATE_DB_PASSWORD:}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 2
```

비밀번호 기본값은 **비워 둔다**(`AGENTS.md`: "자격 증명 노출"은 규모와 무관하게 엄격히). 커넥션 2개면 사용자 2명·하루 수십 건에 충분하다.

---

## 시간대

**TeslaMate는 UTC 값을 타임존 없는 `timestamp` 컬럼에 넣는다.** Ecto의 `:utc_datetime_usec`가 그렇게 매핑된다. JDBC로 읽으면 UTC 벽시계 값을 담은 `LocalDateTime`이 나온다.

그래서 양쪽에서 변환한다.

- **조회 경계**는 KST로 받아 UTC로 바꿔 비교한다. `yearMonth=2026-08` → `2026-07-31T15:00 ~ 2026-08-31T15:00`.
- **응답 시각**은 UTC → KST로 되돌려 `LocalDateTime`으로 낸다. 가계부 `entryAt`과 같은 형식이라 앱이 하던 대로 읽는다.

이 변환을 빠뜨리면 월초·월말 9시간이 옆 달로 새어 나간다. **단위 테스트가 덮어야 할 첫 번째 항목이다.**

---

## API

세 엔드포인트다. 경로 앞에 `/tesla`를 둬서 차량 데이터임을 드러낸다.

### `GET /tesla/charges`

```
GET /tesla/charges?yearMonth=2026-08
GET /tesla/charges?from=2026-06-01&to=2026-08-13
```

```json
{ "summary": { "count": 12, "totalEnergyAddedKwh": 412.5, "totalCost": 98400 },
  "items": [
    { "id": 3312,
      "startedAt": "2026-08-11T22:14:00", "endedAt": "2026-08-12T02:31:00",
      "durationMin": 257, "locationName": "집",
      "energyAddedKwh": 48.2, "startBatteryLevel": 18, "endBatteryLevel": 90,
      "cost": 14100 } ] }
```

**`yearMonth`와 `from`/`to`를 둘 다 받고, 하나도 없으면 400이다.** `LedgerEntryController.list`와 같은 모양이다 — 그쪽은 `yearMonth`나 `keyword` 중 하나를 요구하고, 없으면 `INVALID_REQUEST`를 던진다. 기본을 「이번 달」로 채우지 않는 이유는 저장소 관례와 어긋나서이기도 하지만, 조회 범위가 응답에 안 실리므로 서버가 몰래 고른 범위를 앱이 모른 채 화면에 그리게 되기 때문이다.

`from`/`to`는 `LocalDate`이고 **`to`는 포함**이다. `to` 다음 날 자정(KST)을 상한으로 쓴다. 둘 중 하나만 오는 것은 400이다 — 열린 범위는 전체 조회와 다를 바 없고, 실수로 한쪽을 빠뜨린 요청과 구분되지 않는다.

`yearMonth`와 `from`/`to`가 함께 오면 400이다. 어느 쪽을 이기게 할지 정하는 순간 호출자가 그 규칙을 알아야 한다.

`{date}`류 파라미터는 **Spring이 변환하게 둔다.** `LocalDate.parse`를 직접 부르면 오타가 `DateTimeParseException` → 공통 핸들러의 500으로 떨어져 서버 결함처럼 보인다(`DispatchController.recognize`와 같은 이유).

**진행 중인 충전(`end_date IS NULL`)은 제외한다.** 금액을 매기는 화면인데 아직 끝나지 않은 행은 kWh도 시간도 확정 전이라 방해가 된다. 정렬은 `start_date DESC`.

`locationName`은 `COALESCE(g.name, a.name, a.display_name)`이다. 등록한 지오펜스 이름이 있으면 그것을, 없으면 주소를 쓴다. 셋 다 없으면 `null`이다.

`summary`는 **같은 필터의 SQL 집계**로 구한다. 목록을 순회해 더하지 않는다 — 지금은 결과가 다르지 않지만, 나중에 페이지네이션이 붙는 순간 조용히 틀린 합계가 된다.

### `GET /tesla/charges/{id}`

목록 항목의 모든 필드에 다음을 더한다.

| 필드 | 출처 | 비고 |
|---|---|---|
| `energyUsedKwh` | `charge_energy_used` | 벽에서 뽑아쓴 양. 구버전 데이터에서 `null`일 수 있다 |
| `startRatedRangeKm` / `endRatedRangeKm` | `start/end_rated_range_km` | 주행가능거리 증가분 |
| `outsideTempAvg` | `outside_temp_avg` | |
| `geofenceName` | `geofences.name` | 등록 안 한 장소면 `null` |
| `address` | `addresses.display_name` | |
| `maxPowerKw` | `MAX(charges.charger_power)` | |
| `avgPowerKw` | `AVG(charges.charger_power)` | `charger_power > 0`인 샘플만 |
| `fastCharger` | `BOOL_OR(charges.fast_charger_present)` | 급속(DC) 여부 |
| `fastChargerBrand` / `fastChargerType` | `charges.fast_charger_brand` / `_type` | 완속이면 `null` |

목록은 `locationName` 하나로 합치지만 **상세는 `geofenceName`과 `address`를 따로 낸다.** 상세 화면에는 둘 다 놓을 자리가 있고, 「집」이라고만 적힌 항목의 실제 주소를 확인하려는 것이 상세를 여는 이유 중 하나다.

**상세도 `end_date IS NOT NULL`을 건다.** 목록과 같은 필터다. 진행 중인 행은 kWh·소요시간·금액이 아직 확정 전이라 보여줄 것도 고칠 것도 없다. 그런 `id`로 상세를 부르면 404다 — 목록에서 닿을 수 없는 행이므로 정상 경로에서는 나오지 않는다.

**쿼리를 둘로 나눈다.** `charging_processes` + 조인이 하나, `charges` 집계가 하나다. LATERAL로 묶을 수 있지만 읽기가 나빠진다. 없는 `id`는 첫 쿼리에서 404로 끊는다. **`charges` 행이 하나도 없으면**(오래된 세션에 있을 수 있다) 집계 필드는 전부 `null`이다 — 0이 아니다. 0은 "출력 0kW로 충전했다"는 뜻이 되어 없는 데이터와 구분되지 않는다.

효율(`added/used`)과 kWh당 단가(`cost/added`)는 **서버에서 계산하지 않는다.** 두 값이 다 내려가니 앱에서 나눗셈 한 번이면 되고, 분모가 0이거나 `null`일 때의 처리를 서버가 정해 버리면 화면이 그것을 따라야 한다.

### `PUT /tesla/charges/{id}/cost`

```
PUT /tesla/charges/3312/cost

{ "cost": 15000 }
```

→ `204 No Content` (`AGENTS.md`: "수정·삭제는 204 No Content")

```kotlin
data class ChargeCostRequest(
    @field:NotNull
    @field:DecimalMin("0")
    @field:Digits(integer = 8, fraction = 2)
    val cost: BigDecimal?,
)
```

`@Digits`가 `numeric(10,2)`의 상한(99,999,999.99)을 넘는 값을 **DB 오류가 아니라 400으로** 돌려준다. 정수부 8자리는 컬럼의 `precision 10 - scale 2`다. 금액은 `BigDecimal`이다(`AGENTS.md`).

`@NotNull`이라 `cost`를 비울 수 없다. 되돌리기를 두지 않기로 한 결정의 표현이다.

**UPDATE의 영향 행 수로 404를 판정한다.** `updated == 0`이면 `RESOURCE_NOT_FOUND`다. SELECT로 존재를 확인하고 UPDATE 하는 것보다 왕복이 하나 적고, 결과도 같다.

**UPDATE에도 `end_date IS NOT NULL`을 건다.** 진행 중인 충전에 금액을 적어도 **TeslaMate가 세션을 마감하면서 지오펜스 요금 설정으로 `cost`를 덮어쓴다.** 막지 않으면 204를 받고도 값이 조용히 사라진다. 필터에 걸린 행은 영향 행 0이 되어 404로 나가고, 그것이 사실에 가깝다.

### 인증

기존 `SecurityConfig`가 기본으로 인증을 요구하므로 **더할 것이 없다.** `PublicEndpoint` 빈을 만들지 않는다 — 충전 시각·장소·금액은 생활 패턴이 그대로 드러나는 값이라 공휴일 조회와 성격이 다르다.

`authentication.name`으로 소유자를 거르지 않는다. TeslaMate 데이터는 사용자에 묶여 있지 않고, 차량은 두 사용자 공용이다.

---

## 컴포넌트

```
apps/daily-record/src/main/kotlin/com/toy/backend/tesla/
    TeslaMateDataSourceConfig.kt   두 DataSource 명시 정의 + JdbcClient 빈
    TeslaChargeController.kt       세 엔드포인트
    TeslaChargeDtos.kt             목록·상세·합계 응답, 금액 요청
    TeslaChargeService.kt          기간 해석, KST↔UTC, 404 판정
    TeslaChargeRepository.kt       인터페이스 + JdbcClient 구현
```

| 파일 | 변경 |
|---|---|
| `application.yml` | `teslamate.datasource` 블록 추가 |
| `AGENTS.md` | 구조 섹션에 "daily-record가 TeslaMate DB에 보조 연결한다(읽기 + `cost` 쓰기)" 한 줄 |

**리포지토리를 인터페이스로 둔다.** 서비스 테스트에서 목으로 갈아끼우기 위해서다. 구현은 같은 파일 안에 둔다 — 이 저장소는 파일을 잘게 쪼개지 않는다.

새 `Code` 구현 enum은 만들지 않는다. 이 기능이 내는 오류는 `INVALID_REQUEST`와 `RESOURCE_NOT_FOUND` 둘뿐이고 **`ErrorCode`에 이미 있다**(`AGENTS.md`: 앱 전용 에러 코드는 앱 모듈에 두되, 공통에 있는 것을 굳이 복제하지 않는다).

---

## 오류 처리

| 상황 | 결과 |
|---|---|
| `yearMonth`·`from`/`to` 모두 없음 | 400 `INVALID_REQUEST` |
| `from`만, 또는 `to`만 옴 | 400 `INVALID_REQUEST` |
| `yearMonth`와 `from`/`to`가 함께 옴 | 400 `INVALID_REQUEST` |
| `from > to` | 400 `INVALID_REQUEST` |
| 파라미터가 날짜로 안 읽힘 | 400 (Spring 변환 + 공통 핸들러) |
| `cost`가 없음·음수·8자리 초과 | 400 (`@Valid` + 공통 핸들러) |
| 없는 `id` 상세 조회 | 404 `RESOURCE_NOT_FOUND` |
| 없는 `id`에 금액 수정 | 404 `RESOURCE_NOT_FOUND` |
| TeslaMate DB에 못 붙음 | 500. **가리지 않는다** — 빈 목록으로 눙치면 "충전한 적 없음"과 구분되지 않는다 |

마지막 줄이 중요하다. 보조 DB는 우리 배포와 생명주기가 다르다(TeslaMate 컨테이너를 따로 재시작할 수 있다). 연결 실패를 빈 응답으로 삼키면 **금액을 매기려던 달이 통째로 비어 보이고**, 사용자는 데이터가 날아간 줄 안다.

---

## 테스트

`TeslaChargeServiceTest` (kotest `BehaviorSpec` + mockk, 리포지토리는 목).

- `yearMonth=2026-08`이 **UTC `2026-07-31T15:00` ~ `2026-08-31T15:00`**으로 번역된다
- `from`/`to`가 `to` **다음 날 자정(KST)**을 상한으로 번역된다
- 응답의 `startedAt`·`endedAt`이 **UTC → KST로 되돌아온다**
- 파라미터 조합 4가지 위반(없음 / 한쪽만 / 둘 다 / `from > to`)이 각각 400
- 없는 `id` 상세 조회가 404
- 없는 `id` 금액 수정(영향 행 0)이 404
- **`charges` 집계가 비면 상세의 출력·충전기 필드가 전부 `null`** (0이 아니다)
- `summary`가 리포지토리 집계 결과를 그대로 싣는다

**단위 테스트가 못 잡는 것을 명시한다.** SQL 자체, 두 DataSource 배선, `@Primary` 누락, TeslaMate 컬럼 타입 불일치는 전부 목 뒤에 숨는다. `AGENTS.md`가 이 경우를 못 박아 두었다 — **앱을 실제로 띄워 세 엔드포인트를 호출하고, daily-record DB에 TeslaMate 테이블이 생기지 않았는지 확인한다.**

---

## 열린 항목

- **네트워크 경로.** 라즈베리파이의 daily-record 컨테이너가 TeslaMate postgres 컨테이너에 닿아야 한다. 같은 docker network에 붙이거나 postgres 포트를 호스트에 노출하는 것 중 하나가 필요하다. **배포 전에 확인한다** — 로컬에서는 `TESLAMATE_DB_HOST` 기본값(`localhost`)으로 돌려 본다.
- **DB 계정.** 지금은 기존 `teslamate` 계정을 그대로 쓴다. 읽기 + `charging_processes.cost` UPDATE만 가진 롤을 따로 파는 것이 더 좁지만, 사용자 2명·단일 인스턴스 규모에서 얻는 것보다 관리 비용이 크다.
- **상류 마이그레이션 충돌.** `cost`를 손으로 `numeric(10,2)`로 바꿔 뒀다. TeslaMate를 최신으로 올리면 `20260718160000_increase_geofence_cost_precision`이 같은 컬럼을 `numeric(14,2)`로 넓힌다 — 넓히는 방향이라 데이터 손실 없이 통과한다. 그때 `@Digits(integer = 8)`을 함께 넓힐지는 선택이다.
- **가계부 연동.** 충전 금액을 `ledger_entries`로 흘려보내는 것은 이번 범위 밖이다. 붙인다면 `charging_process_id`를 무엇으로 기억할지(중복 반영 방지)가 첫 질문이다.
