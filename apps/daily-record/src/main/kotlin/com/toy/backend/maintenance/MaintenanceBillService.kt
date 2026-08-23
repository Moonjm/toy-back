package com.toy.backend.maintenance

import com.toy.backend.common.exception.CustomException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth

private val log = KotlinLogging.logger {}

/**
 * 검수를 마친 고지서를 저장하고 조회한다.
 *
 * **사용자에 묶지 않는다.** 집이 하나고 부부가 함께 보는 데이터다. 대신 API를 무인증으로
 * 열지도 않는다 — 응답에 동·호가 들어간다.
 */
@Service
@Transactional(readOnly = true)
class MaintenanceBillService(
    private val repository: MaintenanceBillRepository,
) {
    /**
     * **같은 달이 이미 있으면 409로 거부한다.** 조용히 덮어쓰면 검수를 마친 값이 인식 직후
     * 값으로 되돌아간다. 고칠 때는 `replace`를 쓴다.
     *
     * **DB id가 아니라 `yearMonth` 키를 돌려준다.** 이 리소스는 `/maintenance/bills/2026-03`
     * 처럼 연월로 주소가 매겨진다. id를 돌려주면 `ResponseCreatedAspect`가 만드는 Location이
     * 열리지 않는 주소를 가리킨다.
     */
    @Transactional
    fun create(request: BillSaveRequest): String {
        val key = request.yearMonth.toString()
        if (repository.existsByYearMonth(key)) {
            throw CustomException(MaintenanceErrorCode.BILL_ALREADY_EXISTS, key)
        }
        val bill =
            MaintenanceBill(
                yearMonth = key,
                chargedAmount = request.chargedAmount,
                dueAmount = request.dueAmount,
            )
        bill.fill(request)
        repository.save(bill)
        return key
    }

    /**
     * **path의 연월과 본문의 연월이 다르면 400으로 거부한다.** 앱이 POST와 같은
     * `BillSaveRequest`를 쓰다 보니 검수 화면 상태를 그대로 실어 보낸다. 다른 달을
     * 인식해 본문의 `yearMonth`가 바뀐 채로 이 주소에 보내면, path 기준으로만 찾고
     * 본문 값을 무시하는 순간 이 달이 엉뚱한 달의 값으로 통째로 덮인다. `create`의
     * 409가 막아 둔 조용한 덮어쓰기가 PUT으로 뒷문이 열리는 셈이라 진입부에서 막는다.
     */
    @Transactional
    fun replace(
        yearMonth: YearMonth,
        request: BillSaveRequest,
    ) {
        if (yearMonth != request.yearMonth) {
            throw CustomException(MaintenanceErrorCode.YEAR_MONTH_MISMATCH, yearMonth.toString(), request.yearMonth.toString())
        }
        val bill = find(yearMonth)
        bill.chargedAmount = request.chargedAmount
        bill.dueAmount = request.dueAmount
        bill.fill(request)
    }

    fun findOne(yearMonth: YearMonth): BillResponse = find(yearMonth).toResponse()

    fun findAll(): BillListResponse = BillListResponse(repository.findAllByOrderByYearMonthDesc().map { it.toResponse() })

    @Transactional
    fun delete(yearMonth: YearMonth) {
        repository.delete(find(yearMonth))
    }

    /** 없는 달은 404로 존재 자체를 숨긴다(저장소 관례). */
    private fun find(yearMonth: YearMonth): MaintenanceBill =
        repository.findByYearMonth(yearMonth.toString())
            ?: throw CustomException(MaintenanceErrorCode.BILL_NOT_FOUND, yearMonth.toString())

    /**
     * 요약 금액을 뺀 나머지를 요청값으로 채운다. `create`와 `replace`가 같은 자리를 두 번
     * 적지 않게 모아 둔다 — 한쪽에만 필드를 더하는 사고를 막는다.
     */
    private fun MaintenanceBill.fill(request: BillSaveRequest) {
        dong = request.dong
        ho = request.ho
        areaM2 = request.areaM2
        discountTotal = request.discountTotal
        unpaidAmount = request.unpaidAmount
        unpaidLateFee = request.unpaidLateFee
        dueDate = request.dueDate
        electricityKwh = request.usage.electricityKwh
        waterM3 = request.usage.waterM3
        hotWaterM3 = request.usage.hotWaterM3
        heatingGcal = request.usage.heatingGcal
        foodKg = request.usage.foodKg
        replaceItems(request.items.map { it.name to it.amount })

        // 400으로 막지 않는다. sumMatched는 검수 화면용 플래그이고, 저장할지는 검수한
        // 사람이 정한다(스펙) — 예를 들어 사용자가 원본을 대조해 실제로는 맞다고 확인한
        // 경우까지 서버가 거부하면 안 된다. 다만 어긋난 채로 저장되면 GET /trends의
        // chargedAmount 선과 items 막대가 어긋나게 그려지므로, 사후 추적을 위해 warn만 남긴다.
        val itemTotal = itemTotal()
        if (itemTotal.compareTo(chargedAmount) != 0) {
            log.warn { "$yearMonth 항목 합계($itemTotal)가 당월부과액($chargedAmount)과 다릅니다." }
        }
    }
}
