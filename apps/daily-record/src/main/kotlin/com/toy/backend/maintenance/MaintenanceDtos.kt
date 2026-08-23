package com.toy.backend.maintenance

import com.toy.backend.maintenance.llm.RecognizedUsage
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * 사용량 5종. **여름에는 난방이 없다** — 전부 null을 허용한다.
 *
 * 인식 응답·저장 요청·조회 응답이 같은 타입을 쓴다. 세 벌로 나누면 한 곳에 필드를 더하고
 * 나머지를 빠뜨리는데, 이 저장소는 그 사고로 「사진으로 기록한 모든 끼니가 나트륨 0」을
 * 이미 한 번 겪었다(AGENTS.md).
 */
data class BillUsage(
    val electricityKwh: BigDecimal? = null,
    val waterM3: BigDecimal? = null,
    val hotWaterM3: BigDecimal? = null,
    val heatingGcal: BigDecimal? = null,
    val foodKg: BigDecimal? = null,
) {
    companion object {
        /**
         * 모델이 읽은 사용량 이름을 자리에 꽂는다. **모르는 이름은 버리지 않고 경고로 올린다** —
         * 조용히 버리면 영수증 서식이 바뀌어 항목이 늘어난 것을 아무도 모른다.
         */
        fun from(usages: List<RecognizedUsage>): Pair<BillUsage, List<String>> {
            var usage = BillUsage()
            val warnings = mutableListOf<String>()
            usages.forEach {
                usage =
                    when (it.name.replace(" ", "")) {
                        "전기" -> {
                            usage.copy(electricityKwh = it.value)
                        }

                        "수도" -> {
                            usage.copy(waterM3 = it.value)
                        }

                        "온수" -> {
                            usage.copy(hotWaterM3 = it.value)
                        }

                        "난방" -> {
                            usage.copy(heatingGcal = it.value)
                        }

                        "음식물" -> {
                            usage.copy(foodKg = it.value)
                        }

                        else -> {
                            warnings.add("모르는 사용량 항목입니다: ${it.name} ${it.value}${it.unit}")
                            usage
                        }
                    }
            }
            return usage to warnings
        }
    }
}

data class BillItemResponse(
    val name: String,
    val amount: BigDecimal,
)

/**
 * 검수 화면에 넘기는 인식 결과. **아무것도 저장되지 않은 상태다.**
 *
 * `sumMatched`는 금액 오독만 잡는다. **사용량에는 대응하는 플래그가 없다** —
 * 실측에서 사용량만 틀린 실행이 합계 검증을 통과했다. 없는 안전망을 있는 척 만들지 않는다.
 */
data class MaintenanceRecognitionResponse(
    /** 사진에서 읽지 못하면 `null`이다. 검수 화면이 채운다. */
    val yearMonth: String?,
    val dong: String?,
    val ho: String?,
    val areaM2: BigDecimal?,
    val items: List<BillItemResponse>,
    val usage: BillUsage,
    val chargedAmount: BigDecimal,
    val discountTotal: BigDecimal,
    val unpaidAmount: BigDecimal,
    val unpaidLateFee: BigDecimal,
    val dueAmount: BigDecimal,
    val dueDate: LocalDate?,
    val sumMatched: Boolean,
    val warnings: List<String>,
)

data class BillItemRequest(
    @field:NotBlank
    @field:Size(max = MaintenanceBillItem.NAME_MAX_LENGTH)
    val name: String,
    /** **음수를 허용한다.** `관리비차감`이 `-13,790`이다. */
    val amount: BigDecimal,
)

data class BillSaveRequest(
    val yearMonth: YearMonth,
    @field:NotEmpty val items: List<@Valid BillItemRequest>,
    val chargedAmount: BigDecimal,
    val dueAmount: BigDecimal,
    val dong: String? = null,
    val ho: String? = null,
    val areaM2: BigDecimal? = null,
    val usage: BillUsage = BillUsage(),
    val discountTotal: BigDecimal = BigDecimal.ZERO,
    val unpaidAmount: BigDecimal = BigDecimal.ZERO,
    val unpaidLateFee: BigDecimal = BigDecimal.ZERO,
    val dueDate: LocalDate? = null,
)

data class BillResponse(
    val yearMonth: String,
    val dong: String?,
    val ho: String?,
    val areaM2: BigDecimal?,
    val items: List<BillItemResponse>,
    val usage: BillUsage,
    val chargedAmount: BigDecimal,
    val discountTotal: BigDecimal,
    val unpaidAmount: BigDecimal,
    val unpaidLateFee: BigDecimal,
    val dueAmount: BigDecimal,
    val dueDate: LocalDate?,
)

data class BillListResponse(
    val bills: List<BillResponse>,
)

fun MaintenanceBill.toResponse(): BillResponse =
    BillResponse(
        yearMonth = yearMonth,
        dong = dong,
        ho = ho,
        areaM2 = areaM2,
        items = items.map { BillItemResponse(it.name, it.amount) },
        usage =
            BillUsage(
                electricityKwh = electricityKwh,
                waterM3 = waterM3,
                hotWaterM3 = hotWaterM3,
                heatingGcal = heatingGcal,
                foodKg = foodKg,
            ),
        chargedAmount = chargedAmount,
        discountTotal = discountTotal,
        unpaidAmount = unpaidAmount,
        unpaidLateFee = unpaidLateFee,
        dueAmount = dueAmount,
        dueDate = dueDate,
    )

/**
 * 한 달치 추이. **없던 항목을 0으로 채우지 않는다** — 「난방을 안 썼다」와 「그 달에 난방
 * 항목이 아예 없었다」는 다른 사실이고, 여름 넉 달이 실제로 후자다.
 */
data class TrendMonth(
    val yearMonth: String,
    val chargedAmount: BigDecimal,
    val items: List<BillItemResponse>,
    val usage: BillUsage,
)

data class TrendResponse(
    val months: List<TrendMonth>,
)
