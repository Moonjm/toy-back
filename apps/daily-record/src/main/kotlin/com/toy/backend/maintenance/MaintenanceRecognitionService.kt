package com.toy.backend.maintenance

import com.toy.backend.common.exception.CustomException
import com.toy.backend.maintenance.llm.MaintenanceVisionClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.Base64

private val log = KotlinLogging.logger {}

/**
 * 사진 한 장을 읽어 검수 화면에 넘긴다. **아무것도 저장하지 않는다.**
 * 인식 결과를 바로 저장하면 틀린 값이 조용히 박히고, 관리비는 틀렸다는 사실을 알려주지 않는다.
 *
 * **트랜잭션으로 감싸지 않는다.** 최대 2회를 `timeout-seconds = 120`으로 호출하므로
 * 감싸면 DB 커넥션을 4분 붙잡는다. 애초에 DB를 건드리지 않는다.
 */
@Service
class MaintenanceRecognitionService(
    private val visionClient: MaintenanceVisionClient,
) {
    fun recognize(
        bytes: ByteArray,
        contentType: String?,
    ): RecognitionResponse {
        if (bytes.isEmpty()) throw CustomException(MaintenanceErrorCode.IMAGE_REQUIRED)

        val mediaType = contentType?.takeIf { it.startsWith("image/") } ?: DEFAULT_MEDIA_TYPE
        val recognized =
            visionClient.read(Base64.getEncoder().encodeToString(bytes), mediaType)
                ?: throw CustomException(MaintenanceErrorCode.VISION_UNAVAILABLE)

        val warnings = mutableListOf<String>()

        // **`YearMonth.of`에 넣기 전에 범위를 확인한다.** strict 스키마는 정수라는 것만
        // 보장하므로 month = 13 같은 값이 오면 DateTimeException이 그대로 500이 된다.
        // 사진을 잘못 읽은 것뿐인데 서버 결함처럼 보인다. `0`은 「못 읽었다」는 약속이다.
        val yearMonth =
            if (recognized.month in 1..12 && recognized.year in PLAUSIBLE_YEARS) {
                YearMonth.of(recognized.year, recognized.month)
            } else {
                if (recognized.year != 0 || recognized.month != 0) {
                    log.warn { "영수증의 연월을 해석할 수 없다: year=${recognized.year}, month=${recognized.month}" }
                    warnings.add("연월을 읽지 못했습니다. 직접 골라 주세요.")
                }
                null
            }

        val (usage, usageWarnings) = BillUsage.from(recognized.usages)
        warnings.addAll(usageWarnings)

        val itemTotal = recognized.items.fold(BigDecimal.ZERO) { acc, item -> acc + item.amount }
        val sumMatched = itemTotal.compareTo(recognized.chargedAmount) == 0
        if (!sumMatched) {
            warnings.add("항목 합계($itemTotal)가 당월부과액(${recognized.chargedAmount})과 다릅니다. 금액을 확인해 주세요.")
        }

        val dueDate =
            recognized.dueDate.takeIf { it.isNotBlank() }?.let {
                try {
                    LocalDate.parse(it)
                } catch (e: Exception) {
                    log.warn(e) { "납기일을 해석할 수 없다: $it" }
                    warnings.add("납기일을 읽지 못했습니다.")
                    null
                }
            }

        return RecognitionResponse(
            yearMonth = yearMonth?.toString(),
            dong = recognized.dong.takeIf { it.isNotBlank() },
            ho = recognized.ho.takeIf { it.isNotBlank() },
            areaM2 = recognized.areaM2.takeIf { it.signum() > 0 },
            items = recognized.items.map { BillItemResponse(it.name, it.amount) },
            usage = usage,
            chargedAmount = recognized.chargedAmount,
            discountTotal = recognized.discountTotal,
            unpaidAmount = recognized.unpaidAmount,
            unpaidLateFee = recognized.unpaidLateFee,
            dueAmount = recognized.dueAmount,
            dueDate = dueDate,
            sumMatched = sumMatched,
            warnings = warnings,
        )
    }

    companion object {
        private val PLAUSIBLE_YEARS = 2000..2100
        private const val DEFAULT_MEDIA_TYPE = "image/jpeg"
    }
}
