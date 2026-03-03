package com.toy.backend.holidays

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

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
            withTimeout(SCHEDULER_TIMEOUT_MS) {
                listOf(currentYear, currentYear + 1)
                    .map { year ->
                        async {
                            try {
                                service.fetchAndSaveHolidays(year)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.error(e) { "공휴일 업데이트 실패: year=$year" }
                            }
                        }
                    }.awaitAll()
            }
        }

    companion object {
        private const val SCHEDULER_TIMEOUT_MS = 300_000L // 5분
    }
}
