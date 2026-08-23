package com.toy.backend.maintenance

import com.toy.backend.common.exception.CustomException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth

private val log = KotlinLogging.logger {}

/** 사용자에 묶지 않는다(집이 하나다). 대신 무인증으로도 열지 않는다 — 응답에 동·호가 있다. */
@Service
@Transactional(readOnly = true)
class MaintenanceBillService(
    private val repository: MaintenanceBillRepository,
) {
    /**
     * 같은 달이 있으면 409 — 조용히 덮어쓰면 검수한 값이 인식 직후 값으로 되돌아간다.
     * 반환은 DB id가 아니라 연월이다. 리소스 주소가 연월이라 Location이 그래야 열린다.
     */
    @Transactional
    fun create(request: BillSaveRequest): String {
        val key = request.yearMonth.toString()
        if (repository.existsByYearMonth(key)) {
            throw CustomException(MaintenanceErrorCode.BILL_ALREADY_EXISTS, key)
        }
        val bill = MaintenanceBill(yearMonth = key)
        bill.fill(request)
        repository.save(bill)
        return key
    }

    /**
     * 본문의 연월이 path와 다르면 400. 무시하면 이 달이 다른 달 값으로 조용히 덮인다 —
     * create의 409로 막아 둔 덮어쓰기가 PUT으로 뒷문이 열린다.
     */
    @Transactional
    fun replace(
        yearMonth: YearMonth,
        request: BillSaveRequest,
    ) {
        if (yearMonth != request.yearMonth) {
            throw CustomException(MaintenanceErrorCode.YEAR_MONTH_MISMATCH, yearMonth.toString(), request.yearMonth.toString())
        }
        find(yearMonth).fill(request)
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
     * **요청의 모든 필드를 여기서 반영한다.** 하나라도 밖에 두면 create나 replace 한쪽에서
     * 빠뜨리게 되고, 그 누락은 아무 테스트도 깨뜨리지 않는다.
     */
    private fun MaintenanceBill.fill(request: BillSaveRequest) {
        chargedAmount = request.chargedAmount
        dong = request.dong
        ho = request.ho
        areaM2 = request.areaM2
        discountTotal = request.discountTotal
        electricityKwh = request.usage.electricityKwh
        waterM3 = request.usage.waterM3
        hotWaterM3 = request.usage.hotWaterM3
        heatingGcal = request.usage.heatingGcal
        foodKg = request.usage.foodKg
        replaceItems(request.items.map { it.name to it.amount })

        // 400으로 막지 않는다 — 저장할지는 검수한 사람이 정한다. 다만 어긋난 채로 저장되면
        // 추이 그래프의 선과 막대가 어긋나므로 흔적은 남긴다.
        val itemTotal = itemTotal()
        if (itemTotal.compareTo(chargedAmount) != 0) {
            log.warn { "$yearMonth 항목 합계($itemTotal)가 당월부과액($chargedAmount)과 다릅니다." }
        }
    }
}
