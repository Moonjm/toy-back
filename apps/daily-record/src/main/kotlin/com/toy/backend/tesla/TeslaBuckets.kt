package com.toy.backend.tesla

/**
 * 버킷의 **응답 라벨**이다. 값은 `bucket` 번호 → (`from`, `to`)이고, 경계는 늘
 * **`from` 포함·`to` 미만**이다(하나 있는 예외는 `CHARGE_LEVEL`의 마지막 칸이다).
 *
 * **여기 숫자는 `JdbcTeslaVehicleRepository`·`JdbcTeslaInsightsRepository`의 `CASE`와 같아야
 * 한다** — 거기는 임계값으로, 여기는 라벨로 쓴다. 한쪽만 고치면 응답의 라벨과 실제 집계가
 * 어긋나고, **행 수는 그대로라 정상처럼 보인다.**
 *
 * 두 서비스가 함께 본다. `/tesla/drive-insights`(옛것)와 `/tesla/insights`(새것)가 같은 기간
 * 동안 나란히 살아 있고 같은 라벨을 내야 하기 때문이다.
 */
object TeslaBuckets {
    /** 온도(℃). 하한/상한이 없으면 null이다. */
    val TEMPERATURE: List<Pair<Int, Pair<Int?, Int?>>> =
        listOf(
            1 to (null to 0),
            2 to (0 to 10),
            3 to (10 to 20),
            4 to (20 to 30),
            5 to (30 to null),
        )

    /** 주행 거리(km). */
    val DISTANCE: List<Pair<Int, Pair<Int, Int?>>> =
        listOf(
            1 to (0 to 5),
            2 to (5 to 20),
            3 to (20 to 50),
            4 to (50 to 100),
            5 to (100 to null),
        )

    /**
     * 주행 한 건의 **최고 속도**(km/h). 20 폭에 일곱 칸이다 —
     * 실측(2026-08-20)으로 `speed_max`가 140 이상인 주행이 0건이라 `120~`이 마지막이다.
     */
    val SPEED: List<Pair<Int, Pair<Int, Int?>>> =
        listOf(
            1 to (0 to 20),
            2 to (20 to 40),
            3 to (40 to 60),
            4 to (60 to 80),
            5 to (80 to 100),
            6 to (100 to 120),
            7 to (120 to null),
        )

    /**
     * 주행 한 건의 **평균 속도**(km/h = 거리 ÷ 시간). 20 폭에 다섯 칸이다 —
     * 실측으로 평균 100km/h를 넘는 주행이 0건이라 `80~`이 마지막이다. 최고 속도보다 두 칸
     * 짧은 것이 맞다.
     */
    val SPEED_ENERGY: List<Pair<Int, Pair<Int, Int?>>> =
        listOf(
            1 to (0 to 20),
            2 to (20 to 40),
            3 to (40 to 60),
            4 to (60 to 80),
            5 to (80 to null),
        )

    /**
     * 충전의 시작·종료 SoC(%). 10 폭에 열 칸이다.
     *
     * **마지막 칸만 양끝이 닫힌다**(`90 이상 100 이하`). 다른 배열처럼 「`to` 미만」으로 두면
     * 정확히 100%로 끝난 충전이 어느 칸에도 안 들어가는데, 실측으로 그런 충전이 71건이다 —
     * 가장 흔한 값이 통째로 사라진다.
     */
    val CHARGE_LEVEL: List<Pair<Int, Pair<Int, Int>>> =
        (1..10).map { it to ((it - 1) * 10 to it * 10) }
}
