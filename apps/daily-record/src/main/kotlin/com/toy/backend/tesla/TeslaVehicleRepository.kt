package com.toy.backend.tesla

import java.time.LocalDateTime

/**
 * TeslaMate의 주행·위치·상태를 읽는다. **전부 읽기 전용이다** — 쓰는 것은
 * `TeslaChargeRepository.updateCost` 하나뿐이다.
 *
 * 행 타입의 시각은 전부 UTC다. KST 변환은 서비스가 한다.
 */
interface TeslaVehicleRepository {
    /**
     * 월별 주행 집계. **월 경계는 KST 기준**이고 진행 중인 주행(`end_date IS NULL`)은 제외한다 —
     * 끝나기 전에는 `distance`가 확정 전이다. 데이터가 없는 달은 행이 오지 않는다.
     */
    fun driveMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<DriveMonthRow>

    /**
     * 가장 최근 위치 1행. 없으면 null.
     *
     * `positions`는 3,000만 행이고 `date`에 BRIN만 있어, 창 없는 `ORDER BY date DESC`는
     * 실측 11.7초다. 7일 창(실측 123ms)을 먼저 돌리고 비면 PK 역순으로 폴백한다.
     */
    fun findLatestPosition(): PositionRow?

    /** `states`의 열린 행(`end_date IS NULL`). 없으면 null. */
    fun findOpenState(): StateRow?

    /** 열린 충전·주행 행의 존재 여부. `state` 파생에 쓴다. */
    fun findActivity(): ActivityRow

    /** 전부 읽는다 — 지오펜스는 몇 개 수준이라 반경 판정을 서버에서 한다. */
    fun findGeofences(): List<GeofenceRow>
}
