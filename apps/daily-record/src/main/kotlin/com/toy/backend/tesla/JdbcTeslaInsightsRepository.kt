package com.toy.backend.tesla

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.YearMonth

/**
 * nullable 정수는 `getObject`로 읽는다 — `rs.getInt`는 SQL NULL에 0을 준다
 * (`JdbcTeslaVehicleRepository`와 같은 규칙).
 *
 * 모든 SQL이 `:start`·`:end`를 **UTC**로 받는다. KST를 넘기면 9시간이 어긋난다.
 */
@Repository
class JdbcTeslaInsightsRepository(
    @Qualifier("teslaMateJdbcClient") private val teslaMateJdbcClient: JdbcClient,
) : TeslaInsightsRepository {
    override fun firstDriveMonth(): YearMonth? =
        teslaMateJdbcClient
            .sql(FIRST_DRIVE_MONTH_SQL)
            .query { rs, _ -> rs.getObject("month_start", LocalDate::class.java)?.let { YearMonth.from(it) } }
            .optional()
            .orElse(null)

    companion object {
        /**
         * **KST로 자른다.** UTC로 자르면 KST 9월 1일 새벽에 시작한 첫 주행이 8월로 잡혀
         * 전체 기간이 한 달 길어진다.
         *
         * `MIN`이라 `GROUP BY`가 없어 행은 늘 오지만, `drives`가 비면 `month_start`가 null이다.
         */
        private const val FIRST_DRIVE_MONTH_SQL = """
            SELECT date_trunc(
                       'month',
                       MIN(d.start_date) AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul'
                   )::date AS month_start
              FROM drives d
             WHERE d.end_date IS NOT NULL
        """
    }
}
