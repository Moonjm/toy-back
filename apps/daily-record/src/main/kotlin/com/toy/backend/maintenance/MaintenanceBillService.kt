package com.toy.backend.maintenance

import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth

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

    @Transactional
    fun replace(
        yearMonth: YearMonth,
        request: BillSaveRequest,
    ) {
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
    }
}
