package com.toy.backend.holidays

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

@Component
class HolidayApiClient(
    private val properties: HolidayApiProperties,
    restClientBuilder: RestClient.Builder,
) {
    private val restClient = restClientBuilder.build()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun fetchHolidays(
        year: Int,
        month: Int,
    ): List<HolidayItem> {
        if (properties.serviceKey.isBlank()) return emptyList()

        val uri =
            URI.create(
                "${properties.baseUrl}/getRestDeInfo" +
                    "?serviceKey=${properties.serviceKey}" +
                    "&solYear=$year" +
                    "&solMonth=${month.toString().padStart(2, '0')}" +
                    "&_type=json&numOfRows=100",
            )

        return try {
            val response =
                restClient
                    .get()
                    .uri(uri)
                    .retrieve()
                    .body(JsonNode::class.java)
                    ?: return emptyList()

            val item =
                response
                    .path("response")
                    .path("body")
                    .path("items")
                    .path("item")
            if (item.isMissingNode || item.isNull) return emptyList()

            if (item.isArray) {
                item.mapNotNull { parseItem(it) }
            } else if (item.isObject) {
                listOfNotNull(parseItem(item))
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            log.error(e) { "공휴일 API 호출 실패: year=$year, month=$month" }
            emptyList()
        }
    }

    private fun parseItem(node: JsonNode): HolidayItem? {
        val locdate = node.path("locdate").asString()
        val dateName = node.path("dateName").asString()
        val isHoliday = node.path("isHoliday").asString()
        if (locdate.isBlank() || dateName.isBlank() || isHoliday != "Y") return null

        val date = LocalDate.parse(locdate, dateFormatter)
        return HolidayItem(date = date, name = dateName)
    }
}

data class HolidayItem(
    val date: LocalDate,
    val name: String,
)
