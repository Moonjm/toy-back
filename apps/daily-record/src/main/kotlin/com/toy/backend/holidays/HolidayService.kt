package com.toy.backend.holidays

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

private val log = KotlinLogging.logger {}

@Service
class HolidayService(
    private val repository: HolidayRepository,
    private val apiClient: HolidayApiClient,
    private val writer: HolidayWriter,
    private val virtualThreadExecutor: ExecutorService,
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

    fun fetchAndSaveHolidays(year: Int) {
        val holidays =
            (1..12)
                .map { month ->
                    CompletableFuture.supplyAsync(
                        { apiClient.fetchHolidays(year, month) },
                        virtualThreadExecutor,
                    )
                }.flatMap { it.join() }
        if (holidays.isEmpty()) {
            log.warn { "공휴일 API 응답 없음: year=$year" }
            return
        }
        writer.saveHolidays(year, holidays)
    }
}
