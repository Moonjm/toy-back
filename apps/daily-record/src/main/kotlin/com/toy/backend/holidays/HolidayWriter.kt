package com.toy.backend.holidays

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

private val log = KotlinLogging.logger {}

@Service
class HolidayWriter(
    private val repository: HolidayRepository,
) {
    @Transactional
    fun saveHolidays(
        year: Int,
        holidays: List<HolidayItem>,
    ) {
        val start = LocalDate.of(year, 1, 1)
        val end = LocalDate.of(year, 12, 31)
        repository.deleteByDateBetween(start, end)

        val entities = holidays.map { Holiday(date = it.date, name = it.name) }
        repository.saveAll(entities)
        log.info { "공휴일 저장 완료: year=$year, count=${entities.size}" }
    }
}
