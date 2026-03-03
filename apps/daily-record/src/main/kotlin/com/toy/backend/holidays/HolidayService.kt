package com.toy.backend.holidays

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

private val log = KotlinLogging.logger {}

@Service
class HolidayService(
    private val repository: HolidayRepository,
    private val apiClient: HolidayApiClient,
    private val writer: HolidayWriter,
) {
    @Transactional(readOnly = true)
    fun getHolidaysByYear(year: Int): Map<String, List<String>> {
        val start = LocalDate.of(year, 1, 1)
        val end = LocalDate.of(year, 12, 31)
        return repository
            .findByDateBetweenOrderByDate(start, end)
            .groupBy { it.date.toString() }
            .mapValues { (_, holidays) -> holidays.map { it.name } }
    }

    suspend fun fetchAndSaveHolidays(year: Int) {
        val holidays =
            coroutineScope {
                (1..12)
                    .map { month -> async { apiClient.fetchHolidays(year, month) } }
                    .awaitAll()
                    .flatten()
            }
        if (holidays.isEmpty()) {
            log.warn { "공휴일 API 응답 없음: year=$year" }
            return
        }
        withContext(Dispatchers.IO) { writer.saveHolidays(year, holidays) }
    }
}
