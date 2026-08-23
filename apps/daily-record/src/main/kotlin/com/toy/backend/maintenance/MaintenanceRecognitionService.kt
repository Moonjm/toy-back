package com.toy.backend.maintenance

import com.toy.backend.common.exception.CustomException
import com.toy.backend.maintenance.llm.MaintenanceVisionClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
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
    ): MaintenanceRecognitionResponse {
        if (bytes.isEmpty()) throw CustomException(MaintenanceErrorCode.IMAGE_REQUIRED)

        // contentType이 있는데 image/*가 아니면(PDF 등) 여기서 거부한다. 예전에는 image/jpeg로
        // 갈아 끼워 그대로 통과시켰는데, 그러면 이미지가 아닌 파일도 유료 OpenRouter 호출(장당
        // $0.004)로 나간다. contentType이 null인 경우는 일부 클라이언트가 안 보내는 것뿐이라
        // 지금처럼 image/jpeg로 가정하고 통과시킨다.
        if (contentType != null && !contentType.startsWith("image/")) {
            throw CustomException(MaintenanceErrorCode.IMAGE_TYPE_NOT_SUPPORTED, contentType)
        }
        val mediaType = contentType ?: DEFAULT_MEDIA_TYPE
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

        // **모델은 미납액·납기내 금액을 읽지만 저장할 곳이 없다.** 「미납할 일이 없고 납기내
        // 금액은 늘 당월부과액과 같다」를 전제로 컬럼을 걷어냈기 때문이다. 그 전제가 깨진 달을
        // 조용히 넘기면 아무도 모르므로 검수 화면에 올린다. 지어낸 값일 수도 있어 거부는 안 한다.
        if (recognized.unpaidAmount.signum() != 0 || recognized.unpaidLateFee.signum() != 0) {
            warnings.add(
                "미납액(${recognized.unpaidAmount})이나 연체료(${recognized.unpaidLateFee})가 읽혔습니다. " +
                    "저장되지 않으니 영수증을 확인해 주세요.",
            )
        }
        if (recognized.dueAmount.compareTo(recognized.chargedAmount) != 0) {
            warnings.add(
                "납기내 금액(${recognized.dueAmount})이 당월부과액(${recognized.chargedAmount})과 다릅니다. " +
                    "저장되지 않으니 영수증을 확인해 주세요.",
            )
        }

        return MaintenanceRecognitionResponse(
            yearMonth = yearMonth?.toString(),
            dong = recognized.dong.takeIf { it.isNotBlank() },
            ho = recognized.ho.takeIf { it.isNotBlank() },
            areaM2 = recognized.areaM2.takeIf { it.signum() > 0 },
            items = recognized.items.map { BillItemResponse(it.name, it.amount) },
            usage = usage,
            chargedAmount = recognized.chargedAmount,
            discountTotal = recognized.discountTotal,
            sumMatched = sumMatched,
            warnings = warnings,
        )
    }

    companion object {
        private val PLAUSIBLE_YEARS = 2000..2100
        private const val DEFAULT_MEDIA_TYPE = "image/jpeg"
    }
}
