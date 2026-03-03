package com.toy.backend.holidays

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

private val log = KotlinLogging.logger {}

@Component
class HolidayScheduler(
    private val service: HolidayService,
    private val properties: HolidayApiProperties,
) {
    @Scheduled(cron = "0 0 3 * * *")
    fun updateHolidays() =
        runBlocking {
            if (properties.serviceKey.isBlank()) return@runBlocking

            val currentYear = LocalDate.now().year
            log.info { "공휴일 데이터 업데이트: $currentYear, ${currentYear + 1}" }
            listOf(currentYear, currentYear + 1)
                .map { year ->
                    async {
                        runCatching { service.fetchAndSaveHolidays(year) }
                            .onFailure { log.error(it) { "공휴일 업데이트 실패: year=$year" } }
                    }
                }.awaitAll()
        }
}
