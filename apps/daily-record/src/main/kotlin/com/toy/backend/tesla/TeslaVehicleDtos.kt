package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 화면이 하나라 목록과 합계를 한 응답에 싣는다. 둘로 나누면 같은 화면이 두 번 부르고,
 * 그중 하나는 반드시 다른 하나를 기다린다.
 */
data class TeslaSummaryResponse(
    val month: MonthlyStat,
    val previous: MonthlyStat,
    /** 기준 달 포함 거슬러 12개월, 오래된 것부터. 데이터가 없는 달도 자리를 채운다. */
    val trend: List<MonthlyStat>,
    val charges: List<ChargeListItem>,
)

/**
 * 그 달에 기록이 없는 필드는 **0이 아니라 null**이다. 0은 「안 탔다」는 뜻이 되어
 * 「기록이 없다」와 구분되지 않는다.
 *
 * km당 비용·전비는 서버가 계산하지 않는다 — 분모가 0이거나 null일 때의 처리를 서버가
 * 정해 버리면 화면이 그것을 따라야 한다.
 */
data class MonthlyStat(
    val yearMonth: YearMonth,
    val distanceKm: BigDecimal?,
    val drivingMin: Int?,
    val driveCount: Int?,
    val energyAddedKwh: BigDecimal?,
    val energyUsedKwh: BigDecimal?,
    val cost: BigDecimal?,
    val chargeCount: Int?,
)

/**
 * `positions`의 최신 1행 + `states`의 열린 행. **값과 `asOf`를 항상 함께 낸다** —
 * 주차 중에는 위치가 뜸하게 쌓여 몇 시간 전 값일 수 있고, 시각 없이 배터리 %만 보면
 * 지금 값으로 읽힌다.
 *
 * 좌표를 싣지 않는다 — 생활 동선이 그대로 드러나고, 앱이 지도를 그리지 않는다.
 */
data class TeslaStatusResponse(
    /** 위치 행의 시각(KST). 기록이 하나도 없으면 null이다. */
    val asOf: LocalDateTime?,
    /**
     * `charging`·`driving`·`online`·`offline`·`asleep`.
     * 앞의 둘은 열린 행에서 파생한 것이고, 나머지는 `states`의 값 그대로다.
     */
    val state: String?,
    /** `states`의 열린 행이 시작된 시각(KST). 파생 상태에는 해당하지 않아 null일 수 있다. */
    val stateSince: LocalDateTime?,
    val batteryLevel: Int?,
    val usableBatteryLevel: Int?,
    val ratedRangeKm: BigDecimal?,
    val estRangeKm: BigDecimal?,
    val odometerKm: Double?,
    val insideTempC: BigDecimal?,
    val outsideTempC: BigDecimal?,
    val climateOn: Boolean?,
    /** 반경 안의 지오펜스 이름. 없으면 null이다(주소는 내지 않는다). */
    val locationName: String?,
    /** 위치 기록이 없으면 통째로 null이다. */
    val tpmsBar: TpmsBar?,
)

/** TeslaMate 저장 단위인 bar 그대로. psi 병기는 앱이 한다. */
data class TpmsBar(
    val fl: BigDecimal?,
    val fr: BigDecimal?,
    val rl: BigDecimal?,
    val rr: BigDecimal?,
)

/**
 * 월별 배터리 열화 표본. **파라미터가 없고 전 기간을 낸다** — 몇 년을 타도 월 행 수는 수십이라
 * 자를 이유가 없고, 열화는 시작점부터 봐야 의미가 있다. 몇 개월을 그릴지는 앱이 정한다.
 *
 * **잔존율·열화율을 내지 않는다.** 신차 기준값(차종·연식 상수)은 TeslaMate에 없고, 서버가
 * 잔존율을 내면 그 반올림·경계 처리를 화면이 따라야 한다. km당 비용을 내지 않는 것과 같은 이유다.
 */
data class TeslaBatteryHealthResponse(
    /** 오래된 것부터. **표본이 없는 달은 빠진다** — `trend`가 빈 달의 자리를 채우는 것과 다르다. */
    val samples: List<BatteryHealthSample>,
)

/**
 * `end_rated_range_km ÷ end_battery_level × 100`(만충 환산)과
 * `charge_energy_added ÷ ΔSoC × 100`(사용 가능 용량)의 **그 달 중앙값**이다.
 *
 * 표본 조건이 서로 달라 개수를 따로 낸다 — 한 숫자로 합치면 `capacityKwh`가 null인 이유가
 * 「표본이 없어서」인지 「값이 없어서」인지 화면에서 갈리지 않는다.
 */
data class BatteryHealthSample(
    val yearMonth: YearMonth,
    /** 만충 환산 주행거리(km). `end_battery_level >= 80`인 충전만 표본이다. */
    val fullRangeKm: BigDecimal,
    /**
     * 사용 가능 용량(kWh). 표본 조건은 `end_battery_level >= 80`이고 `end_rated_range_km`이
     * 있으면서 ΔSoC ≥ 40인 충전이다. 그런 충전이 그 달에 없으면 **null이다. 0이 아니다.**
     *
     * **앞의 두 조건은 용량 지표가 쓰지도 않는 것인데 공통 WHERE에서 물려받는다.** 용량은
     * 에너지와 SoC만으로 나오기 때문이다. 그래도 풀지 않은 이유는 대가에 있다 — 공통 WHERE에서
     * 빼면 `full_range_km`이 null일 수 있게 되어 `fullRangeKm`의 non-null 보장이 깨지고, 그
     * nullable이 앱까지 올라간다. 실측(2026-08-17)으로 재 보니 이 커플링 때문에 빠지는 용량
     * 표본은 0건이었고, 「에너지는 있는데 주행가능거리만 없는」 행은 484건 중 하나도 없다 —
     * TeslaMate가 둘을 세션 마감 때 함께 쓰기 때문이다.
     */
    val capacityKwh: BigDecimal?,
    val sampleCount: Int,
    val capacitySampleCount: Int,
)

/**
 * 주행 인사이트 네 카드. **한 화면이 넷을 함께 그리므로 한 응답에 싣는다** — 나누면 같은 화면이
 * 네 번 부르고 그중 셋은 나머지 하나를 기다린다(`/tesla/summary`가 목록과 합계를 함께 싣는 것과
 * 같은 이유다).
 *
 * **나눗셈은 앱이 한다.** 서버는 버킷별 합만 낸다 — 전비는
 * `distanceKm ÷ (ratedRangeUsedKm × efficiencyKwhPerKm)`이고, 분모가 0인 버킷 처리는 화면이
 * 정한다. 1단계의 예외(중앙값)와 달리 여기는 합이라 나눌 이유가 없다.
 */
data class TeslaDriveInsightsResponse(
    /** 받은 범위를 되돌려 싣는다 — 앱이 무엇을 받았는지 알 수 있게. */
    val months: Int,
    /**
     * `cars.efficiency` 그대로(kWh/km). **null일 수 있다** — TeslaMate가 아직 못 채운
     * 경우다. 그때 앱은 전비 카드를 감춘다.
     */
    val efficiencyKwhPerKm: BigDecimal?,
    /** 다섯 개가 늘 온다. 빈 버킷도 자리를 지킨다. */
    val temperatureBuckets: List<TemperatureBucket>,
    /** **0인 칸은 빠진다.** 168칸 중 대부분이 0이라 히트맵이 빈칸으로 그리면 된다. */
    val driveTimes: List<DriveTime>,
    /** 다섯 개가 늘 온다. */
    val distanceBuckets: List<DistanceBucket>,
    /**
     * 도착지, 건수 많은 순 상위 10개. 이름은 **지오펜스 → 주소** 순으로 떨어진다.
     *
     * **표시 이름으로 묶여 오므로 이 목록 안에서 `name`은 유일하다** — 앱이 이름을 행 식별에
     * 써도 안전하다. 좌표는 내지 않는다.
     */
    val places: List<DrivePlace>,
    /**
     * 역대 최고 속도(km/h). **`months` 범위를 따르지 않는다** — 범위가 바뀔 때마다 바뀌면
     * 기록이 아니다. 앱은 이 값의 라벨을 **「역대 최고」**로 적어 옆 두 타일과 범위가
     * 다름을 글자로 드러낸다.
     *
     * 주행이 하나도 없으면 null이다 — 「역대 최고」라는 값 자체가 존재하지 않는다.
     * 아래 거리 둘과 반대다.
     *
     * **그 주행의 날짜(`maxSpeedAt`)를 함께 내지 않는다.** 실측 138 km/h가 최소 3건
     * 동률이라(2025-09-13, 2025-03-22, 2024-03-09) 하나를 골라 「그날 기록했다」고 하면
     * 거짓이 된다. 어느 것을 고를지 규칙을 만들 값어치가 없다.
     */
    val maxSpeedKmh: Int?,
    /**
     * 이번 달 주행거리(km, KST 경계). **`/tesla/summary`의 이번 달 `distanceKm`과 같은
     * 값이다** — 두 화면에 다른 숫자가 뜨면 어느 쪽도 못 믿는다.
     *
     * **0을 낸다. null이 아니다.** 이 저장소의 규칙은 「0은 안 탔다, null은 기록이 없다」인데
     * 이것은 기간이 못박힌 합계라 그 달에 주행이 없으면 「0km 탔다」가 사실이다. null로
     * 두면 매달 1일 새벽마다 화면에 「—」가 뜬다.
     */
    val monthDistanceKm: BigDecimal,
    /** 올해 주행거리(km, KST 경계). `monthDistanceKm`과 같은 이유로 0을 낸다. */
    val yearDistanceKm: BigDecimal,
)

/**
 * 하한/상한이 없으면 null이다(`fromC: null`이 영하, `toC: null`이 30 이상).
 * 경계는 **`fromC` 포함, `toC` 미만**이다.
 *
 * **빈 버킷의 숫자는 0이지 null이 아니다.** `MonthlyStat`이 「기록이 없다」를 null로 내는 것과
 * 반대인데 뜻이 다르기 때문이다 — 여기서 0은 「그 온도대에 실제로 안 탔다」는 사실이다.
 *
 * **`DistanceBucket`과 달리, 범위 안의 모든 주행이 어느 한 버킷에 들어가는 것은 아니다.**
 * `outside_temp_avg IS NULL`이거나 `ΔratedRange <= 0`인 주행은 `driveCount`에서 빠진 뒤 집계되므로
 * (아래 참고) 어느 버킷에도 들어가지 않는다 — 온도 버킷 다섯 개의 합이 거리 버킷 다섯 개의 합보다
 * 작을 수 있다. `months=1`처럼 표본이 적은 범위에서는 「그 온도대에 탔지만 전부 Δrated≤0로 걸러진」
 * 버킷이 `driveCount: 0`으로 보일 수 있다 — 그 온도대에 «기록이 없다»는 뜻이 아니다.
 */
data class TemperatureBucket(
    val fromC: Int?,
    val toC: Int?,
    /** `outside_temp_avg`가 없거나 주행가능거리 소모가 0 이하인 주행은 빠진 뒤의 건수다. */
    val driveCount: Int,
    val distanceKm: BigDecimal,
    /** `start_rated_range_km - end_rated_range_km`의 합. kWh 환산은 앱이 한다. */
    val ratedRangeUsedKm: BigDecimal,
)

/** `weekday`는 **0이 일요일**이다(PostgreSQL `dow` 그대로). 시각은 KST다. */
data class DriveTime(
    val weekday: Int,
    val hour: Int,
    val count: Int,
)

/** `toKm`이 null이면 상한이 없다는 뜻이다. 경계는 **`fromKm` 포함, `toKm` 미만**이다. */
data class DistanceBucket(
    val fromKm: Int,
    val toKm: Int?,
    val driveCount: Int,
    val distanceKm: BigDecimal,
)

data class DrivePlace(
    val name: String,
    val driveCount: Int,
    val distanceKm: BigDecimal,
)

/**
 * 최근 며칠의 차량 상태를 시간축 구간으로 낸다. **시각은 전부 KST다.**
 *
 * **세 계열을 그대로 낸다 — 하나의 띠로 합치지 않는다.** 합치려면 구간 산술(빼기·쪼개기)이
 * 필요하다: `online` 구간 하나가 주행 둘에 걸치면 셋으로 갈라져야 하고, 그 로직은 SQL로도
 * 코틀린으로도 만만치 않다. 세 계열을 그대로 내면 서버는 단순 조회 셋으로 끝나고, 겹칠 때
 * 무엇이 이기는지는 화면이 정한다(TeslaMate의 Grafana 대시보드가 세 계열을 레이어로 겹쳐
 * 그리는 것과 같은 구조다).
 *
 * 앱이 쓰는 순서는 **상태 → 주행 → 충전**으로 덧칠하는 것이다. 주행과 충전이 동시에
 * 열리는 일은 없다.
 */
data class TeslaStateTimelineResponse(
    /** 받은 범위를 되돌려 싣는다 — 앱이 무엇을 받았는지 알 수 있게. */
    val days: Int,
    /** 범위 시작(KST). **자정에 맞춰져 있다** — 앱이 하루에 한 행씩 그린다. */
    val from: LocalDateTime,
    /** 범위 끝(KST) = 요청 시각. 진행 중인 구간의 `to`가 이 값이다. */
    val to: LocalDateTime,
    /**
     * `states`의 구간, 오래된 것부터. **범위 밖은 잘려서 온다.**
     *
     * 그 기간에 기록이 없으면 빈 배열이다 — 404가 아니다(「없는 리소스」가 아니라
     * 「그 기간에 기록이 없다」).
     */
    val states: List<StateSegment>,
    /** 주행 구간. **마감되지 않은 유령 세션은 빠진다.** */
    val drives: List<TimeSegment>,
    /** 충전 구간. 유령 규칙은 `drives`와 같다. */
    val charges: List<TimeSegment>,
)

/**
 * `state`는 `online`·`offline`·`asleep`이다. **`/tesla/status`와 달리 `charging`·`driving`이
 * 여기 오지 않는다** — 그 둘은 `states` 테이블에 없고, 이 응답에서는 별도 배열로 나간다.
 *
 * `asleep`이 최근 며칠에 하나도 없을 수 있다(실측 최근 7일 0개). **그래도 앱의 색 팔레트에서
 * 빼지 않는다** — 2026년에도 2월·4월·5월·6월·7월에 있었고, 범위에 안 잡히는 것뿐이다.
 */
data class StateSegment(
    val state: String,
    val from: LocalDateTime,
    val to: LocalDateTime,
)

/** 주행·충전 구간. 둘이 같은 모양이라 타입을 함께 쓴다. */
data class TimeSegment(
    val from: LocalDateTime,
    val to: LocalDateTime,
)
