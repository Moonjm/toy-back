# 관리비 고지서 사진 인식·기록 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 종이 관리비 영수증을 사진으로 올리면 항목별 금액과 사용량을 인식해 검수 후 저장하고, 달마다의 추이를 볼 수 있게 한다.

**Architecture:** `apps/daily-record` 안에 `com.toy.backend.maintenance` 패키지를 새로 만든다. 사진 → `gemini-3.7-flash` 인식(저장 없음) → 검수 → 확정 저장 경로를 타며, `dispatch`의 인식·검수 분리 구조를 그대로 따른다. 고지서 한 건은 `maintenance_bills` 한 행과 `maintenance_bill_items` 여러 행으로 나뉘고, 사용량 5종은 `maintenance_bills`의 컬럼이다.

**Tech Stack:** Kotlin, Spring Boot, JPA, WebClient, Kotest(BehaviorSpec), mockk, OpenRouter(`google/gemini-3.7-flash`)

**Spec:** `docs/superpowers/specs/2026-08-21-maintenance-bill-design.md`

## Global Constraints

- 모델은 `google/gemini-3.7-flash`, `vision-max-tokens`는 **30000**. 식단용 기본값(4,000)으로 두면 reasoning 토큰이 한도를 먹어 `content`가 빈 채로 온다.
- 금액은 `BigDecimal`, 컬럼은 `precision = 19, scale = 4`.
- **금액에 음수 제약을 걸지 않는다.** `관리비차감`이 `-13,790`으로 들어온다.
- enum 컬럼은 `columnDefinition`을 명시해 CHECK 제약이 생기지 않게 한다(이 계획에는 enum 컬럼이 없다).
- 커밋 전 `./gradlew spotlessApply` 필수(ktlint).
- 생성은 `@ResponseCreated`로 201 + Location, 수정·삭제는 204 No Content, 조회는 `DataResponseBody`.
- 테스트는 kotest `BehaviorSpec` + mockk. 격리 모드가 `InstancePerLeaf`라 리프에서만 초기화하려면 `beforeContainer`를 쓴다.
- 사진은 저장하지 않는다. `common/file`을 쓰지 않는다.
- 관리비 API는 **무인증으로 열지 않는다.** `DispatchPublicEndpoints` 같은 공개 목록에 넣지 않는다.
- 테스트 실행: `./gradlew :daily-record:test --tests '<클래스 FQCN>'`

---

## 파일 구조

**새로 만드는 파일**

| 파일 | 책임 |
|---|---|
| `maintenance/MaintenanceBill.kt` | 고지서 엔티티. 요약 금액 + 사용량 5개 컬럼 |
| `maintenance/MaintenanceBillItem.kt` | 항목 엔티티 |
| `maintenance/MaintenanceBillRepository.kt` | 조회 |
| `maintenance/MaintenanceErrorCode.kt` | 앱 전용 에러 코드 |
| `maintenance/MaintenanceDtos.kt` | 요청·응답 DTO와 사용량 매핑 |
| `maintenance/MaintenanceController.kt` | 엔드포인트 |
| `maintenance/MaintenanceRecognitionService.kt` | 인식 + 검증 플래그 |
| `maintenance/MaintenanceBillService.kt` | 저장·조회·수정·삭제 |
| `maintenance/MaintenanceTrendService.kt` | 추이 |
| `maintenance/llm/MaintenanceVisionClient.kt` | 프롬프트·스키마·파싱·재시도 |
| `maintenance/llm/MaintenanceVisionConfig.kt` | WebClient 빈 |
| `maintenance/llm/MaintenanceVisionProperties.kt` | `maintenance.*` 설정 |
| `scripts/maintenance-import/2025-08_2025-11.sql` | 과거 4개월 이관 |

**고치는 파일**

| 파일 | 이유 |
|---|---|
| `dispatch/llm/DispatchVisionProperties.kt` | 기본 모델을 3.7로 |
| `apps/daily-record/src/main/resources/application.yml` | `dispatch` 기본값 3.7, `maintenance.*` 추가 |
| `dispatch/llm/DispatchVisionClientTest.kt` | 모델 문자열 단언 갱신 |

**서비스를 셋으로 나누는 이유** — 인식은 외부 호출이라 트랜잭션을 걸면 안 되고, 저장은 트랜잭션이 필요하며, 추이는 읽기 전용이다. 트랜잭션 성격이 다른 셋을 한 클래스에 두면 `@Transactional`을 클래스에 못 걸고 메서드마다 붙이게 되는데, 그러면 빠뜨린 자리가 눈에 안 띈다.

---

### Task 1: 배차 모델 기본값을 3.7로 맞춘다

운영은 이미 `gemini-3.7-flash`로 돌고 있는데 코드 기본값이 `3.6-flash`로 남아 있다. 환경변수를 지우면 조용히 3.6으로 돌아간다.

**2026-08-11 배차 설계·계획 문서는 고치지 않는다.** 그때 실제로 잰 측정의 기록이다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/llm/DispatchVisionProperties.kt`
- Modify: `apps/daily-record/src/main/resources/application.yml`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/llm/DispatchVisionClientTest.kt:90`

**Interfaces:**
- Consumes: 없음
- Produces: 없음 (설정값 변경만)

- [ ] **Step 1: 실패하는 테스트로 바꾼다**

`DispatchVisionClientTest.kt`의 90번째 줄 단언을 새 기본값으로 고친다.

```kotlin
                body["model"] shouldBe "google/gemini-3.7-flash"
```

같은 파일 17번째 줄 근처 KDoc의 모델 이름도 함께 고친다.

```kotlin
 * **`max_tokens`를 크게 잡아야 한다.** `gemini-3.7-flash`는 reasoning 토큰을 1,300~3,000
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.dispatch.llm.DispatchVisionClientTest'`
Expected: FAIL — `expected:<"google/gemini-3.7-flash"> but was:<"google/gemini-3.6-flash">`

- [ ] **Step 3: 기본값을 고친다**

`DispatchVisionProperties.kt`의 KDoc과 기본값을 고친다. **왜 3.7인지의 근거를 바꿔 적는다** — 3.6을 고른 실측은 그때 실제로 있었던 일이므로 지우지 말고, 운영에서 3.7로 옮겼다는 사실을 덧붙인다.

```kotlin
/**
 * **식단용 `openrouter.*`와 분리한다.** 식단 인식은 `gemini-2.5-flash`로 잘 돌고 있고,
 * 배차표 판독 때문에 그쪽 비용을 올릴 이유가 없다.
 *
 * 표 판독에서 `2.5-flash`는 **같은 사진도 호출마다 답이 달라진다**(실측 5회에 5/9~9/9).
 * `2.5-pro`도 흔들렸다. 그래서 `3.6-flash`를 골랐고(6회 연속 정답과 일치), 이후 같은 계열의
 * **`3.7-flash`가 절반 값에 나와 운영을 그쪽으로 옮겼다.** 기본값이 이 사실을 따라간다.
 *
 * **관리비(`maintenance.*`)와 값이 같아졌지만 설정은 합치지 않는다.** 배차표가 더 어려운
 * 판독이라 문제가 생기면 한쪽만 되돌릴 수 있어야 한다.
 */
@ConfigurationProperties(prefix = "dispatch")
data class DispatchVisionProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val visionModel: String = "google/gemini-3.7-flash",
```

`vision-max-tokens`·`timeoutSeconds`·`fatherName`은 건드리지 않는다.

- [ ] **Step 4: `application.yml`의 배차 기본값을 고친다**

```yaml
dispatch:
  api-key: ${OPENROUTER_API_KEY:}
  base-url: https://openrouter.ai/api/v1
  # 표 판독은 식단 인식과 요구가 다르다. gemini-2.5-flash는 같은 사진도 호출마다
  # 답이 달라져(실측 5회에 5/9~9/9) 쓸 수 없다. 3.6-flash가 6회 연속 정답과 일치했고,
  # 같은 계열의 3.7-flash가 절반 값에 나와 운영을 그쪽으로 옮겼다.
  vision-model: ${DISPATCH_VISION_MODEL:google/gemini-3.7-flash}
```

- [ ] **Step 5: 테스트를 돌려 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.dispatch.llm.DispatchVisionClientTest'`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/llm/DispatchVisionProperties.kt \
        apps/daily-record/src/main/resources/application.yml \
        apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/llm/DispatchVisionClientTest.kt
git commit -m "chore: 배차 판독 기본 모델을 3.7-flash로 맞춘다

운영은 이미 DISPATCH_VISION_MODEL로 3.7을 쓰고 있는데 코드 기본값이 3.6에
남아 있었다. 환경변수를 지우면 조용히 3.6으로 돌아간다.

3.6을 고른 실측 근거는 그때 실제로 있었던 일이라 주석에서 지우지 않고,
같은 계열의 3.7이 절반 값에 나와 옮겼다는 사실을 덧붙였다."
```

---

### Task 2: 엔티티와 리포지토리

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/MaintenanceBill.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/MaintenanceBillItem.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/MaintenanceBillRepository.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/maintenance/MaintenanceBillTest.kt`

**Interfaces:**
- Consumes: `com.toy.backend.common.entity.BaseEntity`
- Produces:
  - `MaintenanceBill(yearMonth: String, chargedAmount: BigDecimal, dueAmount: BigDecimal, dong: String?, ho: String?, areaM2: BigDecimal?, discountTotal: BigDecimal, unpaidAmount: BigDecimal, unpaidLateFee: BigDecimal, dueDate: LocalDate?, electricityKwh: BigDecimal?, waterM3: BigDecimal?, hotWaterM3: BigDecimal?, heatingGcal: BigDecimal?, foodKg: BigDecimal?)`
  - `MaintenanceBill.items: MutableList<MaintenanceBillItem>`
  - `MaintenanceBill.replaceItems(items: List<Pair<String, BigDecimal>>)`
  - `MaintenanceBill.itemTotal(): BigDecimal`
  - `MaintenanceBillItem(bill: MaintenanceBill, name: String, amount: BigDecimal, displayOrder: Int)`
  - `MaintenanceBillItem.NAME_MAX_LENGTH = 50`
  - `MaintenanceBillRepository.findByYearMonth(yearMonth: String): MaintenanceBill?`
  - `MaintenanceBillRepository.existsByYearMonth(yearMonth: String): Boolean`
  - `MaintenanceBillRepository.findByYearMonthGreaterThanEqualOrderByYearMonth(start: String): List<MaintenanceBill>`
  - `MaintenanceBillRepository.findAllByOrderByYearMonthDesc(): List<MaintenanceBill>`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`replaceItems`가 순서를 매기고 `itemTotal`이 **음수 항목까지 더하는지**가 핵심이다. `관리비차감 -13,790`을 빼먹거나 절댓값으로 더하면 합계 검증이 통째로 거짓말을 한다.

```kotlin
package com.toy.backend.maintenance

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

/**
 * 항목 합계는 **인식 결과가 맞았는지 판정하는 유일한 자동 검사**다(설계 문서 함정 1).
 * 음수 항목(`관리비차감`)을 빠뜨리거나 절댓값으로 더하면 그 검사가 조용히 거짓말을 한다.
 */
class MaintenanceBillTest :
    BehaviorSpec({
        fun bill() =
            MaintenanceBill(
                yearMonth = "2026-03",
                chargedAmount = BigDecimal("238370"),
                dueAmount = BigDecimal("238370"),
            )

        Given("음수 항목이 섞인 고지서") {
            When("항목을 채우면") {
                val target = bill()
                target.replaceItems(
                    listOf(
                        "일반관리비" to BigDecimal("34700"),
                        "관리비차감" to BigDecimal("-13790"),
                        "전기" to BigDecimal("47450"),
                    ),
                )

                Then("합계가 음수를 반영한다") {
                    target.itemTotal() shouldBe BigDecimal("68360")
                }

                Then("보낸 순서대로 번호가 매겨진다") {
                    target.items.map { it.name } shouldBe listOf("일반관리비", "관리비차감", "전기")
                    target.items.map { it.displayOrder } shouldBe listOf(0, 1, 2)
                }
            }
        }

        Given("이미 항목이 있는 고지서") {
            When("항목을 다시 채우면") {
                val target = bill()
                target.replaceItems(listOf("전기" to BigDecimal("100")))
                target.replaceItems(listOf("수도" to BigDecimal("200")))

                Then("이전 항목이 남지 않는다") {
                    target.items.map { it.name } shouldBe listOf("수도")
                }
            }
        }

        Given("항목이 하나도 없는 고지서") {
            When("합계를 내면") {
                Then("0이다") {
                    bill().itemTotal() shouldBe BigDecimal.ZERO
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.maintenance.MaintenanceBillTest'`
Expected: FAIL — `Unresolved reference: MaintenanceBill`

- [ ] **Step 3: 엔티티를 만든다**

`MaintenanceBillItem.kt`:

```kotlin
package com.toy.backend.maintenance

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal

/**
 * 고지서의 항목 한 줄.
 *
 * **금액에 음수 제약을 걸지 않는다.** `관리비차감`이 `-13,790`으로 들어온다.
 * 제약을 걸면 그 달의 저장이 통째로 실패한다.
 */
@Entity
@Table(name = "maintenance_bill_items")
class MaintenanceBillItem(
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "bill_id", nullable = false)
    val bill: MaintenanceBill,
    @field:Column(nullable = false, length = NAME_MAX_LENGTH)
    val name: String,
    @field:Column(nullable = false, precision = 19, scale = 4)
    val amount: BigDecimal,
    /** 영수증에 적힌 순서. 화면이 영수증과 같은 차례로 보여 주는 데 쓴다. */
    @field:Column(name = "display_order", nullable = false)
    val displayOrder: Int,
) : BaseEntity() {
    companion object {
        /** 가장 긴 실측 항목명이 `작은도서관운영비`(9자)다. 새 항목이 생길 것에 대비해 넉넉히 둔다. */
        const val NAME_MAX_LENGTH = 50
    }
}
```

`MaintenanceBill.kt`:

```kotlin
package com.toy.backend.maintenance

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 관리비 고지서 한 장. **한 달에 한 장이다** — `yearMonth`가 unique다.
 *
 * `yearMonth`를 `2026-07` 형태의 문자열로 둔다. 사전순 정렬이 시간순과 같아 추이 조회가
 * 범위 비교 하나로 끝나고, `dispatch_roster`가 이미 같은 방식을 쓴다.
 * 컬럼명이 `year_month_value`인 것도 그쪽 선례를 따른 것이다.
 *
 * **사용량 5종은 컬럼으로 둔다.** 종류가 고정이고 목적이 추이 그래프다. 별도 테이블로 빼면
 * 추이 쿼리마다 피벗해야 한다. 항목(`items`)을 반대로 판단한 이유는 그쪽이 달마다 개수와
 * 이름이 바뀌기 때문이다(`관리비차감`·`선거관리운영비`).
 *
 * 여름에는 난방이 없다 — 사용량은 전부 null을 허용한다.
 */
@Entity
@Table(name = "maintenance_bills")
class MaintenanceBill(
    @field:Column(name = "year_month_value", nullable = false, unique = true, length = 7)
    val yearMonth: String,
    @field:Column(name = "charged_amount", nullable = false, precision = 19, scale = 4)
    var chargedAmount: BigDecimal,
    @field:Column(name = "due_amount", nullable = false, precision = 19, scale = 4)
    var dueAmount: BigDecimal,
    @field:Column(length = 20)
    var dong: String? = null,
    @field:Column(length = 20)
    var ho: String? = null,
    @field:Column(name = "area_m2", precision = 19, scale = 4)
    var areaM2: BigDecimal? = null,
    @field:Column(name = "discount_total", nullable = false, precision = 19, scale = 4)
    var discountTotal: BigDecimal = BigDecimal.ZERO,
    @field:Column(name = "unpaid_amount", nullable = false, precision = 19, scale = 4)
    var unpaidAmount: BigDecimal = BigDecimal.ZERO,
    @field:Column(name = "unpaid_late_fee", nullable = false, precision = 19, scale = 4)
    var unpaidLateFee: BigDecimal = BigDecimal.ZERO,
    /** 앱 화면 캡처에서 옮겨 온 과거 넉 달은 납기일이 없다. */
    @field:Column(name = "due_date")
    var dueDate: LocalDate? = null,
    @field:Column(name = "electricity_kwh", precision = 19, scale = 4)
    var electricityKwh: BigDecimal? = null,
    @field:Column(name = "water_m3", precision = 19, scale = 4)
    var waterM3: BigDecimal? = null,
    @field:Column(name = "hot_water_m3", precision = 19, scale = 4)
    var hotWaterM3: BigDecimal? = null,
    @field:Column(name = "heating_gcal", precision = 19, scale = 4)
    var heatingGcal: BigDecimal? = null,
    @field:Column(name = "food_kg", precision = 19, scale = 4)
    var foodKg: BigDecimal? = null,
) : BaseEntity() {
    @OneToMany(mappedBy = "bill", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("displayOrder asc")
    var items: MutableList<MaintenanceBillItem> = mutableListOf()

    /** 보낸 순서를 그대로 `displayOrder`로 새긴다. 영수증에 적힌 차례가 곧 화면의 차례다. */
    fun replaceItems(items: List<Pair<String, BigDecimal>>) {
        this.items.clear()
        items.forEachIndexed { index, (name, amount) ->
            this.items.add(MaintenanceBillItem(this, name, amount, index))
        }
    }

    /**
     * 항목 합계. **음수 항목을 그대로 더한다** — `관리비차감`을 빼먹거나 절댓값으로 더하면
     * 「합계 == 당월부과액」 검사가 통과할 리 없는 달에 통과하거나 그 반대가 된다.
     */
    fun itemTotal(): BigDecimal = items.fold(BigDecimal.ZERO) { acc, item -> acc + item.amount }
}
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.maintenance.MaintenanceBillTest'`
Expected: PASS

`BigDecimal.shouldBe`는 스케일까지 비교한다. `68360`과 `68360.0000`은 다르게 본다. 실패하면 단언을 `target.itemTotal().compareTo(BigDecimal("68360")) shouldBe 0`으로 바꾼다.

- [ ] **Step 5: 리포지토리를 만든다**

```kotlin
package com.toy.backend.maintenance

import org.springframework.data.jpa.repository.JpaRepository

interface MaintenanceBillRepository : JpaRepository<MaintenanceBill, Long> {
    fun findByYearMonth(yearMonth: String): MaintenanceBill?

    fun existsByYearMonth(yearMonth: String): Boolean

    /** 추이 조회. `yearMonth`가 `2026-07` 형태라 사전순 비교가 곧 시간순 비교다. */
    fun findByYearMonthGreaterThanEqualOrderByYearMonth(start: String): List<MaintenanceBill>

    fun findAllByOrderByYearMonthDesc(): List<MaintenanceBill>
}
```

- [ ] **Step 6: 컴파일을 확인하고 커밋**

Run: `./gradlew :daily-record:compileKotlin :daily-record:test --tests 'com.toy.backend.maintenance.*'`
Expected: PASS

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/ \
        apps/daily-record/src/test/kotlin/com/toy/backend/maintenance/
git commit -m "feat: 관리비 고지서 엔티티와 리포지토리를 만든다

한 달에 한 장이라 year_month를 unique로 두고, dispatch_roster처럼
'2026-07' 문자열로 저장한다 — 사전순 정렬이 시간순과 같아 추이 조회가
범위 비교 하나로 끝난다.

사용량 5종은 컬럼, 항목은 별도 테이블로 갈랐다. 사용량은 종류가 고정이고
목적이 추이 그래프라 테이블로 빼면 쿼리마다 피벗해야 하고, 항목은 달마다
개수와 이름이 바뀐다(관리비차감·선거관리운영비).

금액에 음수 제약을 걸지 않는다. 관리비차감이 -13,790으로 들어온다."
```

---

### Task 3: Vision 클라이언트

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/llm/MaintenanceVisionProperties.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/llm/MaintenanceVisionClient.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/llm/MaintenanceVisionConfig.kt`
- Modify: `apps/daily-record/src/main/resources/application.yml`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/maintenance/llm/MaintenanceVisionClientTest.kt`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `RecognizedItem(name: String, amount: BigDecimal)`
  - `RecognizedUsage(name: String, value: BigDecimal, unit: String)`
  - `RecognizedBill(year: Int, month: Int, dong: String, ho: String, areaM2: BigDecimal, items: List<RecognizedItem>, usages: List<RecognizedUsage>, chargedAmount: BigDecimal, discountTotal: BigDecimal, unpaidAmount: BigDecimal, unpaidLateFee: BigDecimal, dueAmount: BigDecimal, dueDate: String)`
  - `MaintenanceVisionClient.read(imageBase64: String, mediaType: String): RecognizedBill?`
  - `MaintenanceVisionClient.visionBody(imageBase64: String, mediaType: String): Map<String, Any>` (internal — 테스트용)
  - `MaintenanceVisionProperties(apiKey, baseUrl, visionModel, visionMaxTokens, timeoutSeconds)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```kotlin
package com.toy.backend.maintenance.llm

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as stringShouldContain
import io.mockk.mockk
import org.springframework.web.reactive.function.client.WebClient

/**
 * **`max_tokens`를 크게 잡아야 한다.** `gemini-3.7-flash`는 reasoning 토큰을 이 한도에
 * 함께 쓴다(실측 completion 1,773~2,333). 식단용 기본값(4,000)으로 두면 `content`가
 * 빈 채로 온다. 아예 안 보내면 OpenRouter가 모델 최대 출력만큼 잔액을 선점해 402가 난다.
 */
class MaintenanceVisionClientTest :
    BehaviorSpec({
        val webClient = mockk<WebClient>()

        fun client(properties: MaintenanceVisionProperties = MaintenanceVisionProperties(apiKey = "sk-test")) =
            MaintenanceVisionClient(properties, webClient)

        Given("요청 본문") {
            val body = client().visionBody("QUJD", "image/jpeg")

            Then("설정된 모델을 쓴다") {
                body["model"] shouldBe "google/gemini-3.7-flash"
            }

            Then("max_tokens를 보낸다") {
                body["max_tokens"] shouldBe 30000
            }

            Then("이미지를 보낸 media type의 data URL로 싣는다") {
                val messages = body["messages"] as List<*>
                val content = (messages.first() as Map<*, *>)["content"] as List<*>
                val image = content.last() as Map<*, *>
                val url = (image["image_url"] as Map<*, *>)["url"]
                url shouldBe "data:image/jpeg;base64,QUJD"
            }

            Then("프롬프트가 음수 항목을 명시한다") {
                val messages = body["messages"] as List<*>
                val content = (messages.first() as Map<*, *>)["content"] as List<*>
                val text = (content.first() as Map<*, *>)["text"] as String
                text stringShouldContain "관리비차감"
                text stringShouldContain "음수"
            }

            Then("strict 스키마의 required가 properties를 모두 덮는다") {
                // strict: true라 properties에 있는 키가 required에 없으면 호출 자체가 거부된다.
                val format = body["response_format"] as Map<*, *>
                val schema = (format["json_schema"] as Map<*, *>)["schema"] as Map<*, *>
                val properties = (schema["properties"] as Map<*, *>).keys
                val required = schema["required"] as List<*>
                required.toSet() shouldBe properties
            }
        }

        Given("정상 응답 JSON") {
            val json =
                """
                {"year":2026,"month":3,"dong":"5103","ho":"1404","areaM2":98.8,
                 "items":[{"name":"일반관리비","amount":34700},{"name":"관리비차감","amount":-13790}],
                 "usages":[{"name":"전기","value":261,"unit":"kwh"}],
                 "summary":{"chargedAmount":238370,"discountTotal":0,"unpaidAmount":0,
                            "unpaidLateFee":0,"dueAmount":238370,"dueDate":"2026-04-30"}}
                """.trimIndent()

            When("파싱하면") {
                val parsed = client().parse(json)

                Then("음수 항목이 음수로 남는다") {
                    parsed.items.map { it.name to it.amount.toInt() } shouldContain ("관리비차감" to -13790)
                }

                Then("사용량과 요약을 읽는다") {
                    parsed.usages.single().name shouldBe "전기"
                    parsed.chargedAmount.toInt() shouldBe 238370
                    parsed.dueDate shouldBe "2026-04-30"
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.maintenance.llm.MaintenanceVisionClientTest'`
Expected: FAIL — `Unresolved reference: MaintenanceVisionClient`

- [ ] **Step 3: 설정 클래스를 만든다**

```kotlin
package com.toy.backend.maintenance.llm

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * **배차(`dispatch.*`)와 값이 같아졌지만 설정은 합치지 않는다.** 두 기능은 각자 자기 도메인
 * 설정을 달고 있고(`dispatch.father-name`), 무엇보다 한쪽만 되돌릴 수 있어야 한다.
 * 배차표는 빈 칸이 절반 이상이고 집계 컬럼이 끼어드는 더 어려운 판독이라, 관리비에서 잘
 * 나온 모델이 배차에서도 잘 나온다는 보장이 없다.
 *
 * 영수증 8장 × 2회 실측에서 `3.7-flash`가 합계 검증 16/16, 실행 간 완전일치 8/8이었다.
 * 장당 $0.004다.
 */
@ConfigurationProperties(prefix = "maintenance")
data class MaintenanceVisionProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val visionModel: String = "google/gemini-3.7-flash",
    /**
     * **reasoning 토큰이 이 한도에 함께 잡힌다.** 실측 completion이 1,773~2,333이라
     * 식단용 기본값(4,000)으로 두면 `content`가 빈 채로 온다.
     */
    val visionMaxTokens: Int = 30000,
    val timeoutSeconds: Long = 120,
)
```

- [ ] **Step 4: 클라이언트를 만든다**

프롬프트는 스파이크에서 16/16을 낸 것을 그대로 쓴다. **문구를 다듬지 마라** — 그 문장들이 측정된 대상이다.

```kotlin
package com.toy.backend.maintenance.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

private val log = KotlinLogging.logger {}

/**
 * `post()` 한 번의 결과. **재시도할 가치가 있는 실패와 없는 실패를 구분**한다 —
 * `finish_reason == "length"`나 빈 content는 이미지·프롬프트·토큰 한도의 거의
 * 결정론적 결과라 다시 불러도 똑같지만, 네트워크 예외는 다시 불러볼 가치가 있다.
 */
private sealed interface PostOutcome {
    data class Content(
        val text: String,
    ) : PostOutcome

    data object Empty : PostOutcome

    data object Retryable : PostOutcome
}

data class RecognizedItem(
    val name: String,
    /** **음수가 온다.** `관리비차감`이 `-13,790`이다. */
    val amount: BigDecimal,
)

data class RecognizedUsage(
    val name: String,
    val value: BigDecimal,
    /** 영수증에 보이는 그대로. 표기가 없으면 빈 문자열이다. */
    val unit: String,
)

data class RecognizedBill(
    val year: Int,
    val month: Int,
    val dong: String,
    val ho: String,
    val areaM2: BigDecimal,
    val items: List<RecognizedItem>,
    val usages: List<RecognizedUsage>,
    val chargedAmount: BigDecimal,
    val discountTotal: BigDecimal,
    val unpaidAmount: BigDecimal,
    val unpaidLateFee: BigDecimal,
    val dueAmount: BigDecimal,
    /** 모델이 읽은 그대로의 `YYYY-MM-DD`. 해석은 서비스가 한다 — 못 읽으면 빈 문자열이다. */
    val dueDate: String,
)

/**
 * 관리비 영수증 한 장을 읽는다. 실패는 null로 돌려주되 **한 번은 재시도한다** —
 * 사용자가 사진을 다시 올려야 하는 대면 흐름이라 그 한 번은 서버가 삼킨다. 장당 $0.004다.
 *
 * **`3.7-flash`를 쓴다.** 영수증 8장 × 2회에서 합계 검증 16/16, 실행 간 완전일치 8/8이었다.
 * `2.5-flash`는 9회 중 1회 수도 사용량 10.8을 10.6으로 읽었는데 **그 실행도 금액 합계는
 * 통과했다** — 사용량 오독은 어떤 자동 검사에도 안 걸린다.
 */
class MaintenanceVisionClient(
    private val properties: MaintenanceVisionProperties,
    private val webClient: WebClient,
) {
    fun read(
        imageBase64: String,
        mediaType: String,
    ): RecognizedBill? {
        val body = visionBody(imageBase64, mediaType)
        repeat(MAX_ATTEMPTS) { attempt ->
            when (val outcome = post(body)) {
                is PostOutcome.Content -> {
                    try {
                        return parse(outcome.text)
                    } catch (e: Exception) {
                        log.warn(e) {
                            "관리비 인식 응답 파싱 실패 (${attempt + 1}/$MAX_ATTEMPTS): " +
                                outcome.text.take(LOG_CONTENT_LIMIT)
                        }
                    }
                }

                // max_tokens에 걸려 잘렸거나 content가 빈 경우는 거의 결정론적이라
                // 다시 불러도 똑같다. 재시도하지 않고 바로 포기한다.
                PostOutcome.Empty -> return null

                PostOutcome.Retryable -> {}
            }
        }
        return null
    }

    internal fun visionBody(
        imageBase64: String,
        mediaType: String,
    ): Map<String, Any> =
        mapOf(
            "model" to properties.visionModel,
            "messages" to
                listOf(
                    mapOf(
                        "role" to "user",
                        "content" to
                            listOf(
                                mapOf("type" to "text", "text" to PROMPT),
                                mapOf(
                                    "type" to "image_url",
                                    "image_url" to mapOf("url" to "data:$mediaType;base64,$imageBase64"),
                                ),
                            ),
                    ),
                ),
            "response_format" to RESPONSE_FORMAT,
            // 안 보내면 잔액이 남았는데도 402가 난다.
            "max_tokens" to properties.visionMaxTokens,
        )

    private fun post(body: Map<String, Any>): PostOutcome =
        try {
            val response =
                webClient
                    .post()
                    .uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono<JsonNode>()
                    .block()
            val choice = response?.path("choices")?.path(0)
            if (choice?.path("finish_reason")?.asString() == "length") {
                log.error { "관리비 인식이 max_tokens에 걸려 잘렸다 — 한도를 올려야 한다" }
                return PostOutcome.Empty
            }
            val content =
                choice
                    ?.path("message")
                    ?.path("content")
                    ?.asString()
                    ?.takeIf { it.isNotBlank() }
            if (content != null) PostOutcome.Content(content) else PostOutcome.Empty
        } catch (e: WebClientResponseException) {
            log.error(e) { "관리비 인식 호출 실패 (${e.statusCode})" }
            // 401·402·429는 같은 요청을 바로 다시 보내도 같은 답이 온다.
            if (e.statusCode.is4xxClientError) PostOutcome.Empty else PostOutcome.Retryable
        } catch (e: Exception) {
            log.error(e) { "관리비 인식 호출 실패" }
            PostOutcome.Retryable
        }

    internal fun parse(content: String): RecognizedBill {
        val root = JSON_MAPPER.readTree(content)
        val summary = root.path("summary")
        val items: Iterable<JsonNode> = root.path("items")
        val usages: Iterable<JsonNode> = root.path("usages")
        return RecognizedBill(
            year = root.path("year").asInt(),
            month = root.path("month").asInt(),
            dong = root.path("dong").asString(),
            ho = root.path("ho").asString(),
            areaM2 = root.path("areaM2").decimalValue(),
            items =
                items.map {
                    RecognizedItem(it.path("name").asString(), it.path("amount").decimalValue())
                },
            usages =
                usages.map {
                    RecognizedUsage(
                        it.path("name").asString(),
                        it.path("value").decimalValue(),
                        it.path("unit").asString(),
                    )
                },
            chargedAmount = summary.path("chargedAmount").decimalValue(),
            discountTotal = summary.path("discountTotal").decimalValue(),
            unpaidAmount = summary.path("unpaidAmount").decimalValue(),
            unpaidLateFee = summary.path("unpaidLateFee").decimalValue(),
            dueAmount = summary.path("dueAmount").decimalValue(),
            dueDate = summary.path("dueDate").asString(),
        )
    }

    companion object {
        private const val MAX_ATTEMPTS = 2

        /** 스레드 안전하고 상태가 없다. 파싱마다 새로 만들 이유가 없다. */
        private val JSON_MAPPER = JsonMapper.builder().build()

        private const val LOG_CONTENT_LIMIT = 500

        /**
         * **실측에서 16/16을 낸 프롬프트다. 문구를 다듬지 마라** — 이 문장들이 측정된 대상이다.
         */
        private val PROMPT =
            """
            이 사진은 아파트 관리비 납입영수증(입주자용) 종이 고지서를 찍은 것이다.

            표 구조:
            - 맨 위에 "YYYY 년 M 월 XXXX 동 XXXX 호 NN.N㎡"가 적혀 있다.
            - 그 아래 큰 상자는 **2단 표**다. 왼쪽 단과 오른쪽 단에 각각 [항목명] [금액]이 있다.
              왼쪽 단을 위에서 아래로 다 읽은 뒤, 오른쪽 단을 위에서 아래로 읽어라.
            - 오른쪽 바깥에 당월부과액 / 할인총계 / 미납액 / 미납연체료, 그리고 납기내 날짜와 금액이 있다.

            items — 금액이 적힌 모든 줄을 넣어라.
            - name은 항목명에서 **사용량 부분을 뺀 이름만** 넣어라. "전기 290kwh"는 name이 "전기",
              "수도 10.3 ㎥"는 "수도", "온수 4.3㎥"는 "온수", "난방 0.45"는 "난방"이다.
            - amount는 쉼표를 뺀 정수다. **"관리비차감"처럼 앞에 -가 붙은 값은 음수 그대로** 넣어라.
            - 금액 칸이 비어 있는 줄(예: "음식물 2.75")은 items에 넣지 마라. usages에만 넣는다.
            - 표에 없는 항목을 지어내지 마라. 달마다 있는 항목이 다르다(여름에는 난방이 없다).

            usages — 항목명 옆에 붙어 있는 사용량만 넣어라.
            - 예: 전기 290 kwh / 수도 10.3 ㎥ / 난방 0.45 / 온수 4.3 ㎥ / 음식물 2.75
            - unit은 보이는 그대로 넣되(kwh, ㎥), 단위 표기가 없으면 빈 문자열("")로 두어라.
            - 사용량이 적힌 항목이 없으면 빈 배열로 두어라.

            summary:
            - chargedAmount는 "당월부과액", dueAmount는 "납 기 내" 상자 안의 큰 금액이다.
            - discountTotal(할인총계) / unpaidAmount(미납액) / unpaidLateFee(미납연체료)는
              **칸이 비어 있으면 0이다. 값을 지어내지 마라.**
            - dueDate는 "납 기 내" 옆 날짜를 YYYY-MM-DD로 넣어라.

            읽을 수 없는 값을 추측해서 채우지 마라.
            """.trimIndent()

        private val SUMMARY_PROPERTIES =
            mapOf(
                "chargedAmount" to mapOf("type" to "integer"),
                "discountTotal" to mapOf("type" to "integer"),
                "unpaidAmount" to mapOf("type" to "integer"),
                "unpaidLateFee" to mapOf("type" to "integer"),
                "dueAmount" to mapOf("type" to "integer"),
                "dueDate" to mapOf("type" to "string"),
            )

        private val SCHEMA_PROPERTIES =
            mapOf(
                "year" to mapOf("type" to "integer"),
                "month" to mapOf("type" to "integer"),
                "dong" to mapOf("type" to "string"),
                "ho" to mapOf("type" to "string"),
                "areaM2" to mapOf("type" to "number"),
                "items" to
                    mapOf(
                        "type" to "array",
                        "items" to
                            mapOf(
                                "type" to "object",
                                "properties" to
                                    mapOf(
                                        "name" to mapOf("type" to "string"),
                                        "amount" to mapOf("type" to "integer"),
                                    ),
                                "required" to listOf("name", "amount"),
                                "additionalProperties" to false,
                            ),
                    ),
                "usages" to
                    mapOf(
                        "type" to "array",
                        "items" to
                            mapOf(
                                "type" to "object",
                                "properties" to
                                    mapOf(
                                        "name" to mapOf("type" to "string"),
                                        "value" to mapOf("type" to "number"),
                                        "unit" to mapOf("type" to "string"),
                                    ),
                                "required" to listOf("name", "value", "unit"),
                                "additionalProperties" to false,
                            ),
                    ),
                "summary" to
                    mapOf(
                        "type" to "object",
                        "properties" to SUMMARY_PROPERTIES,
                        "required" to SUMMARY_PROPERTIES.keys.toList(),
                        "additionalProperties" to false,
                    ),
            )

        private val RESPONSE_FORMAT =
            mapOf(
                "type" to "json_schema",
                "json_schema" to
                    mapOf(
                        "name" to "maintenance_bill",
                        "strict" to true,
                        "schema" to
                            mapOf(
                                "type" to "object",
                                "properties" to SCHEMA_PROPERTIES,
                                // strict: true라 properties에 있는 키가 required에 없으면
                                // 호출 자체가 거부된다. 손으로 나열하면 키를 더할 때 빠뜨린다.
                                "required" to SCHEMA_PROPERTIES.keys.toList(),
                                "additionalProperties" to false,
                            ),
                    ),
            )
    }
}
```

- [ ] **Step 5: WebClient 빈을 만든다**

```kotlin
package com.toy.backend.maintenance.llm

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.util.concurrent.TimeUnit

@Configuration
@EnableConfigurationProperties(MaintenanceVisionProperties::class)
class MaintenanceVisionConfig {
    @Bean
    fun maintenanceVisionClient(properties: MaintenanceVisionProperties): MaintenanceVisionClient {
        val httpClient =
            HttpClient
                .create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .doOnConnected {
                    it.addHandlerLast(ReadTimeoutHandler(properties.timeoutSeconds, TimeUnit.SECONDS))
                }

        val webClient =
            WebClient
                .builder()
                .baseUrl(properties.baseUrl)
                .defaultHeader("Authorization", "Bearer ${properties.apiKey}")
                // 사진 한 장이 base64로 수백 KB다. 기본 256KB 버퍼로는 터질 수 있다.
                .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
                .clientConnector(ReactorClientHttpConnector(httpClient))
                .build()

        return MaintenanceVisionClient(properties, webClient)
    }
}
```

- [ ] **Step 6: `application.yml`에 설정을 넣는다**

`dispatch:` 블록 바로 아래에 넣는다.

```yaml
maintenance:
  api-key: ${OPENROUTER_API_KEY:}
  base-url: https://openrouter.ai/api/v1
  # 영수증 8장 x 2회 실측에서 합계 검증 16/16, 실행 간 완전일치 8/8. 장당 $0.004.
  # 배차와 값이 같지만 설정은 나눠 둔다 — 배차가 더 어려운 판독이라 한쪽만 되돌릴 수 있어야 한다.
  vision-model: ${MAINTENANCE_VISION_MODEL:google/gemini-3.7-flash}
  # reasoning 토큰이 이 한도에 함께 잡힌다(실측 completion 1,773~2,333).
  # 식단용 4,000으로 두면 content가 빈 채로 온다.
  vision-max-tokens: ${MAINTENANCE_VISION_MAX_TOKENS:30000}
  timeout-seconds: 120
```

- [ ] **Step 7: 테스트를 돌려 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.maintenance.llm.MaintenanceVisionClientTest'`
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/llm/ \
        apps/daily-record/src/test/kotlin/com/toy/backend/maintenance/llm/ \
        apps/daily-record/src/main/resources/application.yml
git commit -m "feat: 관리비 영수증을 gemini-3.7-flash로 읽는다

영수증 8장 x 2회 실측에서 합계 검증 16/16, 실행 간 완전일치 8/8이었다.
2.5-flash는 9회 중 1회 수도 사용량 10.8을 10.6으로 읽었는데 그 실행도
금액 합계는 통과했다 — 사용량 오독은 자동 검사에 안 걸린다.

max_tokens를 30000으로 잡는다. reasoning 토큰이 이 한도에 함께 잡혀
식단용 4,000으로 두면 content가 빈 채로 온다.

프롬프트는 실측된 그대로다. 문구가 곧 측정 대상이라 다듬지 않았다."
```

---

### Task 4: 인식 서비스와 엔드포인트

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/MaintenanceErrorCode.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/MaintenanceDtos.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/MaintenanceRecognitionService.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/MaintenanceController.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/maintenance/MaintenanceRecognitionServiceTest.kt`

**Interfaces:**
- Consumes: `MaintenanceVisionClient.read`, `RecognizedBill`, `RecognizedItem`, `RecognizedUsage`
- Produces:
  - `BillUsage(electricityKwh: BigDecimal?, waterM3: BigDecimal?, hotWaterM3: BigDecimal?, heatingGcal: BigDecimal?, foodKg: BigDecimal?)`
  - `BillUsage.Companion.from(usages: List<RecognizedUsage>): Pair<BillUsage, List<String>>` — 두 번째가 경고
  - `BillItemResponse(name: String, amount: BigDecimal)`
  - `RecognitionResponse(yearMonth: String?, dong: String?, ho: String?, areaM2: BigDecimal?, items: List<BillItemResponse>, usage: BillUsage, chargedAmount: BigDecimal, discountTotal: BigDecimal, unpaidAmount: BigDecimal, unpaidLateFee: BigDecimal, dueAmount: BigDecimal, dueDate: LocalDate?, sumMatched: Boolean, warnings: List<String>)`
  - `MaintenanceRecognitionService.recognize(bytes: ByteArray, contentType: String?): RecognitionResponse`
  - `MaintenanceErrorCode.{BILL_NOT_FOUND, BILL_ALREADY_EXISTS, VISION_UNAVAILABLE, IMAGE_REQUIRED}`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```kotlin
package com.toy.backend.maintenance

import com.toy.backend.common.exception.CustomException
import com.toy.backend.maintenance.llm.MaintenanceVisionClient
import com.toy.backend.maintenance.llm.RecognizedBill
import com.toy.backend.maintenance.llm.RecognizedItem
import com.toy.backend.maintenance.llm.RecognizedUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.LocalDate

/**
 * **합계 검증은 금액 오독만 잡는다.** 실측에서 `2.5-flash`가 수도 사용량을 10.8→10.6으로
 * 틀렸는데 그 실행도 합계는 통과했다. 그래서 사용량에는 대응하는 플래그를 두지 않는다 —
 * 없는 안전망을 있는 척 만들면 검수하는 사람이 눈으로 볼 이유를 잃는다.
 */
class MaintenanceRecognitionServiceTest :
    BehaviorSpec({
        val visionClient = mockk<MaintenanceVisionClient>()
        val service = MaintenanceRecognitionService(visionClient)

        fun recognized(
            year: Int = 2026,
            month: Int = 3,
            items: List<Pair<String, Int>> = listOf("일반관리비" to 34700, "관리비차감" to -13790),
            usages: List<Triple<String, String, String>> = listOf(Triple("전기", "261", "kwh")),
            chargedAmount: Int = 20910,
            dueDate: String = "2026-04-30",
        ) = RecognizedBill(
            year = year,
            month = month,
            dong = "5103",
            ho = "1404",
            areaM2 = BigDecimal("98.8"),
            items = items.map { RecognizedItem(it.first, BigDecimal(it.second)) },
            usages = usages.map { RecognizedUsage(it.first, BigDecimal(it.second), it.third) },
            chargedAmount = BigDecimal(chargedAmount),
            discountTotal = BigDecimal.ZERO,
            unpaidAmount = BigDecimal.ZERO,
            unpaidLateFee = BigDecimal.ZERO,
            dueAmount = BigDecimal(chargedAmount),
            dueDate = dueDate,
        )

        Given("정상적으로 읽힌 영수증") {
            every { visionClient.read(any(), any()) } returns recognized()

            When("인식하면") {
                val response = service.recognize(byteArrayOf(1), "image/jpeg")

                Then("연월을 읽는다") {
                    response.yearMonth shouldBe "2026-03"
                }

                Then("항목 합계가 부과액과 맞으면 sumMatched가 참이다") {
                    response.sumMatched shouldBe true
                }

                Then("사용량을 이름별 자리에 넣는다") {
                    response.usage.electricityKwh shouldBe BigDecimal("261")
                    response.usage.heatingGcal.shouldBeNull()
                }

                Then("납기일을 해석한다") {
                    response.dueDate shouldBe LocalDate.of(2026, 4, 30)
                }
            }
        }

        Given("합계가 부과액과 어긋난 영수증") {
            every { visionClient.read(any(), any()) } returns recognized(chargedAmount = 999999)

            When("인식하면") {
                val response = service.recognize(byteArrayOf(1), "image/jpeg")

                Then("sumMatched가 거짓이고 경고가 붙는다") {
                    response.sumMatched shouldBe false
                    response.warnings.any { it.contains("합계") } shouldBe true
                }
            }
        }

        Given("연월을 못 읽은 영수증") {
            // 프롬프트가 「못 읽으면 0」을 약속한다. strict 스키마는 정수라는 것만 보장하므로
            // 13 같은 값도 올 수 있고, 그대로 YearMonth.of에 넣으면 500이 된다.
            every { visionClient.read(any(), any()) } returns recognized(year = 0, month = 0)

            When("인식하면") {
                val response = service.recognize(byteArrayOf(1), "image/jpeg")

                Then("연월이 비고 검수 화면이 채우게 둔다") {
                    response.yearMonth.shouldBeNull()
                }
            }
        }

        Given("월이 범위를 벗어난 영수증") {
            every { visionClient.read(any(), any()) } returns recognized(month = 13)

            When("인식하면") {
                Then("예외가 아니라 빈 연월로 넘어간다") {
                    service.recognize(byteArrayOf(1), "image/jpeg").yearMonth.shouldBeNull()
                }
            }
        }

        Given("모르는 이름의 사용량") {
            every { visionClient.read(any(), any()) } returns
                recognized(usages = listOf(Triple("가스", "1.2", "㎥")))

            When("인식하면") {
                val response = service.recognize(byteArrayOf(1), "image/jpeg")

                Then("버리지 않고 경고로 알린다") {
                    response.warnings.any { it.contains("가스") } shouldBe true
                }
            }
        }

        Given("납기일을 못 읽은 영수증") {
            every { visionClient.read(any(), any()) } returns recognized(dueDate = "")

            When("인식하면") {
                Then("납기일이 비고 예외가 나지 않는다") {
                    service.recognize(byteArrayOf(1), "image/jpeg").dueDate.shouldBeNull()
                }
            }
        }

        Given("인식이 실패한 경우") {
            every { visionClient.read(any(), any()) } returns null

            When("인식하면") {
                Then("VISION_UNAVAILABLE로 거부한다") {
                    val e = shouldThrow<CustomException> { service.recognize(byteArrayOf(1), "image/jpeg") }
                    e.errorCode shouldBe MaintenanceErrorCode.VISION_UNAVAILABLE
                }
            }
        }

        Given("빈 이미지") {
            When("인식하면") {
                Then("IMAGE_REQUIRED로 거부한다") {
                    val e = shouldThrow<CustomException> { service.recognize(byteArrayOf(), "image/jpeg") }
                    e.errorCode shouldBe MaintenanceErrorCode.IMAGE_REQUIRED
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.maintenance.MaintenanceRecognitionServiceTest'`
Expected: FAIL — `Unresolved reference: MaintenanceRecognitionService`

- [ ] **Step 3: 에러 코드를 만든다**

```kotlin
package com.toy.backend.maintenance

import com.toy.backend.common.constant.Code
import org.springframework.http.HttpStatus

enum class MaintenanceErrorCode(
    private val httpStatus: HttpStatus,
    private val message: String,
) : Code {
    BILL_NOT_FOUND(HttpStatus.NOT_FOUND, "%s 관리비 내역이 없습니다."),

    // 조용히 덮어쓰면 검수를 마친 값이 인식 직후 값으로 되돌아간다. 고칠 때는 수정을 쓴다.
    BILL_ALREADY_EXISTS(HttpStatus.CONFLICT, "%s 관리비 내역이 이미 있습니다. 고치려면 수정해 주세요."),
    VISION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "고지서 인식에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    IMAGE_REQUIRED(HttpStatus.BAD_REQUEST, "이미지가 비어 있습니다."),
    ;

    override fun getHttpStatus(): HttpStatus = httpStatus

    override fun getMessage(): String = message

    override fun getStatusName(): String = httpStatus.name

    override fun getCodeName(): String = name
}
```

- [ ] **Step 4: DTO와 사용량 매핑을 만든다**

```kotlin
package com.toy.backend.maintenance

import com.toy.backend.maintenance.llm.RecognizedUsage
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
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
                        "전기" -> usage.copy(electricityKwh = it.value)
                        "수도" -> usage.copy(waterM3 = it.value)
                        "온수" -> usage.copy(hotWaterM3 = it.value)
                        "난방" -> usage.copy(heatingGcal = it.value)
                        "음식물" -> usage.copy(foodKg = it.value)
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
data class RecognitionResponse(
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
    @field:NotNull
    val amount: BigDecimal,
)

data class BillSaveRequest(
    @field:NotNull val yearMonth: YearMonth,
    @field:NotEmpty val items: List<@Valid BillItemRequest>,
    @field:NotNull val chargedAmount: BigDecimal,
    @field:NotNull val dueAmount: BigDecimal,
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
```

- [ ] **Step 5: 인식 서비스를 만든다**

```kotlin
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
```

- [ ] **Step 6: 테스트를 돌려 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.maintenance.MaintenanceRecognitionServiceTest'`
Expected: PASS

- [ ] **Step 7: 컨트롤러에 인식 엔드포인트를 만든다**

```kotlin
package com.toy.backend.maintenance

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@Tag(name = "관리비", description = "고지서 사진 인식 → 검수 → 확정 저장, 그리고 조회·추이")
@RestController
@RequestMapping("/maintenance")
class MaintenanceController(
    private val recognitionService: MaintenanceRecognitionService,
) {
    /**
     * **무인증으로 열지 않는다.** 응답에 동·호가 들어간다.
     *
     * 사진은 저장하지 않는다 — `common/file`의 `FileEntity`에 소유자가 없는 미해결 이슈를
     * 건드리지 않는다(AGENTS.md).
     */
    @PostMapping("/recognitions")
    @Operation(summary = "고지서 사진 인식 — 저장하지 않고 결과만 준다(검수용)")
    fun recognize(
        @RequestPart("file") file: MultipartFile,
    ): ResponseEntity<DataResponseBody<RecognitionResponse>> =
        ResponseEntity.ok(DataResponseBody(recognitionService.recognize(file.bytes, file.contentType)))
}
```

- [ ] **Step 8: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/ \
        apps/daily-record/src/test/kotlin/com/toy/backend/maintenance/
git commit -m "feat: 관리비 고지서 사진을 인식해 검수 결과를 낸다

인식과 저장을 분리한다. 인식 결과를 바로 저장하면 틀린 값이 조용히 박히고
관리비는 틀렸다는 사실을 알려주지 않는다.

sumMatched는 금액 오독만 잡는다. 실측에서 사용량만 틀린 실행이 합계 검증을
통과했으므로 사용량에는 대응 플래그를 두지 않는다 — 없는 안전망을 있는 척
만들면 검수하는 사람이 눈으로 볼 이유를 잃는다.

연월은 YearMonth.of에 넣기 전에 범위를 확인한다. strict 스키마는 정수라는
것만 보장해서 month=13이 오면 그대로 500이 된다."
```

---

### Task 5: 저장·조회·수정·삭제

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/MaintenanceBillService.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/MaintenanceController.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/maintenance/MaintenanceBillServiceTest.kt`

**Interfaces:**
- Consumes: `MaintenanceBillRepository`, `MaintenanceBill`, `BillSaveRequest`, `BillResponse`, `BillListResponse`, `MaintenanceBill.toResponse()`, `MaintenanceErrorCode`
- Produces:
  - `MaintenanceBillService.create(request: BillSaveRequest): String` — 저장한 `yearMonth` 키를 돌려준다(Location에 쓴다)
  - `MaintenanceBillService.replace(yearMonth: YearMonth, request: BillSaveRequest)`
  - `MaintenanceBillService.findOne(yearMonth: YearMonth): BillResponse`
  - `MaintenanceBillService.findAll(): BillListResponse`
  - `MaintenanceBillService.delete(yearMonth: YearMonth)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```kotlin
package com.toy.backend.maintenance

import com.toy.backend.common.exception.CustomException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.math.BigDecimal
import java.time.YearMonth

class MaintenanceBillServiceTest :
    BehaviorSpec({
        val repository = mockk<MaintenanceBillRepository>(relaxed = true)
        // relaxed 모드는 JpaRepository.save()의 제네릭 반환 타입을 못 풀어
        // ClassCastException을 낸다. 이 저장소의 다른 테스트들처럼 직접 답한다.
        every { repository.save(any()) } answers { firstArg() }
        val service = MaintenanceBillService(repository)

        fun request(yearMonth: String = "2026-03") =
            BillSaveRequest(
                yearMonth = YearMonth.parse(yearMonth),
                items =
                    listOf(
                        BillItemRequest("일반관리비", BigDecimal("34700")),
                        BillItemRequest("관리비차감", BigDecimal("-13790")),
                    ),
                chargedAmount = BigDecimal("20910"),
                dueAmount = BigDecimal("20910"),
                usage = BillUsage(electricityKwh = BigDecimal("261")),
            )

        Given("같은 달이 아직 없을 때") {
            every { repository.existsByYearMonth("2026-03") } returns false

            When("저장하면") {
                val captured = slot<MaintenanceBill>()
                every { repository.save(capture(captured)) } answers { firstArg() }
                service.create(request())

                Then("항목이 보낸 순서대로 들어간다") {
                    captured.captured.items.map { it.name } shouldBe listOf("일반관리비", "관리비차감")
                }

                Then("음수 항목이 음수로 남는다") {
                    captured.captured.itemTotal() shouldBe BigDecimal("20910")
                }

                Then("사용량이 컬럼에 들어간다") {
                    captured.captured.electricityKwh shouldBe BigDecimal("261")
                }
            }
        }

        Given("같은 달이 이미 있을 때") {
            every { repository.existsByYearMonth("2026-03") } returns true

            When("저장하면") {
                Then("409로 거부한다 — 조용히 덮어쓰지 않는다") {
                    val e = shouldThrow<CustomException> { service.create(request()) }
                    e.errorCode shouldBe MaintenanceErrorCode.BILL_ALREADY_EXISTS
                }

                Then("아무것도 저장하지 않는다") {
                    runCatching { service.create(request()) }
                    verify(exactly = 0) { repository.save(any()) }
                }
            }
        }

        Given("있는 달을 수정할 때") {
            val existing =
                MaintenanceBill(
                    yearMonth = "2026-03",
                    chargedAmount = BigDecimal("1"),
                    dueAmount = BigDecimal("1"),
                )
            existing.replaceItems(listOf("옛항목" to BigDecimal("1")))
            every { repository.findByYearMonth("2026-03") } returns existing

            When("수정하면") {
                service.replace(YearMonth.parse("2026-03"), request())

                Then("옛 항목이 남지 않는다") {
                    existing.items.map { it.name } shouldBe listOf("일반관리비", "관리비차감")
                }

                Then("요약 금액이 갱신된다") {
                    existing.chargedAmount shouldBe BigDecimal("20910")
                }
            }
        }

        Given("없는 달") {
            every { repository.findByYearMonth("2099-01") } returns null

            When("조회하면") {
                Then("404로 존재를 숨긴다") {
                    val e = shouldThrow<CustomException> { service.findOne(YearMonth.parse("2099-01")) }
                    e.errorCode shouldBe MaintenanceErrorCode.BILL_NOT_FOUND
                }
            }

            When("수정하면") {
                Then("404다") {
                    shouldThrow<CustomException> { service.replace(YearMonth.parse("2099-01"), request("2099-01")) }
                }
            }

            When("삭제하면") {
                Then("404다") {
                    shouldThrow<CustomException> { service.delete(YearMonth.parse("2099-01")) }
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.maintenance.MaintenanceBillServiceTest'`
Expected: FAIL — `Unresolved reference: MaintenanceBillService`

- [ ] **Step 3: 서비스를 만든다**

```kotlin
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
     */
    /**
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
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.maintenance.MaintenanceBillServiceTest'`
Expected: PASS

- [ ] **Step 5: 컨트롤러에 엔드포인트를 더한다**

`MaintenanceController`에 `billService`를 주입하고 아래 메서드를 더한다. import에 `com.toy.backend.common.annotation.ResponseCreated`, `jakarta.validation.Valid`, `org.springframework.web.bind.annotation.{DeleteMapping, GetMapping, PathVariable, PutMapping, RequestBody}`, `java.time.YearMonth`를 더한다.

```kotlin
    /**
     * **`{id}` 자리에 연월이 들어간다.** `ResponseCreatedAspect`가 반환 본문을 그 자리에
     * 끼워 넣는데, 이 리소스의 주소는 DB id가 아니라 연월이다(`GET /maintenance/bills/2026-03`).
     */
    @PostMapping("/bills")
    @ResponseCreated("/maintenance/bills/{id}")
    @Operation(summary = "검수 확정분 저장 — 같은 달이 있으면 409")
    fun createBill(
        @Valid @RequestBody request: BillSaveRequest,
    ): ResponseEntity<String> = ResponseEntity.ok(billService.create(request))

    /**
     * **`YearMonth`로 받아 Spring이 변환하게 둔다.** 본문에서 `YearMonth.parse`를 부르면
     * 오타 하나가 `DateTimeParseException`이 되어 공통 핸들러의 500으로 떨어진다.
     */
    @GetMapping("/bills/{yearMonth}")
    @Operation(summary = "한 달 관리비 상세")
    fun findBill(
        @PathVariable yearMonth: YearMonth,
    ): ResponseEntity<DataResponseBody<BillResponse>> = ResponseEntity.ok(DataResponseBody(billService.findOne(yearMonth)))

    @GetMapping("/bills")
    @Operation(summary = "관리비 목록 — 최근 달부터")
    fun findBills(): ResponseEntity<DataResponseBody<BillListResponse>> = ResponseEntity.ok(DataResponseBody(billService.findAll()))

    @PutMapping("/bills/{yearMonth}")
    @Operation(summary = "한 달 관리비 수정 — 항목을 통째로 갈아 끼운다")
    fun replaceBill(
        @PathVariable yearMonth: YearMonth,
        @Valid @RequestBody request: BillSaveRequest,
    ): ResponseEntity<Void> {
        billService.replace(yearMonth, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/bills/{yearMonth}")
    @Operation(summary = "한 달 관리비 삭제")
    fun deleteBill(
        @PathVariable yearMonth: YearMonth,
    ): ResponseEntity<Void> {
        billService.delete(yearMonth)
        return ResponseEntity.noContent().build()
    }
```

- [ ] **Step 6: 실제로 앱을 띄워 확인한다**

단위 테스트는 리포지토리를 목으로 대체하므로 **`year_month_value` unique 제약과 409 분기를 잡지 못한다.** 실제로 확인한다.

```bash
./gradlew :daily-record:bootRun
# 다른 터미널에서 — 같은 달을 두 번 저장해 두 번째가 409인지 본다
curl -i -X POST localhost:8080/maintenance/bills -H 'Content-Type: application/json' -d '{
  "yearMonth":"2026-03","chargedAmount":20910,"dueAmount":20910,
  "items":[{"name":"일반관리비","amount":34700},{"name":"관리비차감","amount":-13790}],
  "usage":{"electricityKwh":261}}'
curl -i -X POST localhost:8080/maintenance/bills -H 'Content-Type: application/json' -d '{
  "yearMonth":"2026-03","chargedAmount":20910,"dueAmount":20910,
  "items":[{"name":"일반관리비","amount":34700}]}'
curl -s localhost:8080/maintenance/bills/2026-03
curl -i -X DELETE localhost:8080/maintenance/bills/2026-03
```

Expected: 첫 번째 201에 `Location: /maintenance/bills/2026-03`, 두 번째 409, 조회에 음수 항목이 `-13790`으로 살아 있음, 삭제 204.

**음수 항목이 조회에서 사라지거나 부호가 뒤집혀 있으면 멈추고 원인을 찾아라.** 그 값이 틀리면 합계 검증이 통째로 거짓말을 한다.

- [ ] **Step 7: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/ \
        apps/daily-record/src/test/kotlin/com/toy/backend/maintenance/
git commit -m "feat: 검수한 관리비를 저장하고 조회한다

같은 달을 다시 저장하면 409다. 조용히 덮어쓰면 검수를 마친 값이 인식 직후
값으로 되돌아간다 — 고칠 때는 PUT을 쓴다.

사용자에 묶지 않는다. 집이 하나고 부부가 함께 보는 데이터다. 대신 무인증으로
열지도 않는다 — 응답에 동·호가 들어간다."
```

---

### Task 6: 추이 API

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/MaintenanceTrendService.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/MaintenanceDtos.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/MaintenanceController.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/maintenance/MaintenanceTrendServiceTest.kt`

**Interfaces:**
- Consumes: `MaintenanceBillRepository.findByYearMonthGreaterThanEqualOrderByYearMonth`, `MaintenanceBill`, `BillUsage`, `BillItemResponse`
- Produces:
  - `TrendMonth(yearMonth: String, chargedAmount: BigDecimal, items: List<BillItemResponse>, usage: BillUsage)`
  - `TrendResponse(months: List<TrendMonth>)`
  - `MaintenanceTrendService.trend(months: Int, today: LocalDate = LocalDate.now()): TrendResponse`
  - `MaintenanceTrendService.DEFAULT_MONTHS = 13`, `MAX_MONTHS = 60`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

**여름에 난방이 빠지는 달**이 이 API의 핵심 경계다. 그 달에 `난방` 항목을 0으로 채워 넣으면 「난방을 안 썼다」와 「그 달에 난방 항목이 없었다」가 구분되지 않는다.

```kotlin
package com.toy.backend.maintenance

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.time.LocalDate

class MaintenanceTrendServiceTest :
    BehaviorSpec({
        val repository = mockk<MaintenanceBillRepository>()
        val service = MaintenanceTrendService(repository)
        val today = LocalDate.of(2026, 8, 21)

        fun bill(
            yearMonth: String,
            items: List<Pair<String, Int>>,
            heating: String? = null,
        ) = MaintenanceBill(
            yearMonth = yearMonth,
            chargedAmount = BigDecimal(items.sumOf { it.second }),
            dueAmount = BigDecimal(items.sumOf { it.second }),
            heatingGcal = heating?.let { BigDecimal(it) },
        ).also { it.replaceItems(items.map { (n, a) -> n to BigDecimal(a) }) }

        Given("난방이 있는 달과 없는 달") {
            every { repository.findByYearMonthGreaterThanEqualOrderByYearMonth(any()) } returns
                listOf(
                    bill("2026-03", listOf("전기" to 47450, "난방" to 24430), heating = "0.19"),
                    bill("2026-07", listOf("전기" to 95740)),
                )

            When("추이를 내면") {
                val response = service.trend(MaintenanceTrendService.DEFAULT_MONTHS, today)

                Then("난방이 없던 달에 0을 지어내지 않는다") {
                    val july = response.months.single { it.yearMonth == "2026-07" }
                    july.items.map { it.name } shouldBe listOf("전기")
                    july.usage.heatingGcal.shouldBeNull()
                }

                Then("난방이 있던 달은 그대로 나온다") {
                    val march = response.months.single { it.yearMonth == "2026-03" }
                    march.usage.heatingGcal shouldBe BigDecimal("0.19")
                }

                Then("오래된 달부터 나온다") {
                    response.months.map { it.yearMonth } shouldBe listOf("2026-03", "2026-07")
                }
            }
        }

        Given("기본 개월 수") {
            every { repository.findByYearMonthGreaterThanEqualOrderByYearMonth(any()) } returns emptyList()

            When("추이를 내면") {
                service.trend(MaintenanceTrendService.DEFAULT_MONTHS, today)

                Then("전년 동월이 범위에 들어오도록 13개월을 조회한다") {
                    // 2026-08 기준 13개월이면 2025-08부터다. 12로 두면 전년 동월이 빠져 비교가 안 된다.
                    verify { repository.findByYearMonthGreaterThanEqualOrderByYearMonth("2025-08") }
                }
            }
        }

        Given("터무니없이 큰 개월 수") {
            every { repository.findByYearMonthGreaterThanEqualOrderByYearMonth(any()) } returns emptyList()

            When("추이를 내면") {
                service.trend(9999, today)

                Then("상한으로 자른다") {
                    verify { repository.findByYearMonthGreaterThanEqualOrderByYearMonth("2021-09") }
                }
            }
        }

        Given("0 이하의 개월 수") {
            every { repository.findByYearMonthGreaterThanEqualOrderByYearMonth(any()) } returns emptyList()

            When("추이를 내면") {
                service.trend(0, today)

                Then("최소 한 달은 본다") {
                    verify { repository.findByYearMonthGreaterThanEqualOrderByYearMonth("2026-08") }
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.maintenance.MaintenanceTrendServiceTest'`
Expected: FAIL — `Unresolved reference: MaintenanceTrendService`

- [ ] **Step 3: DTO를 더한다**

`MaintenanceDtos.kt` 끝에 붙인다.

```kotlin
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
```

- [ ] **Step 4: 서비스를 만든다**

```kotlin
package com.toy.backend.maintenance

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.YearMonth

/**
 * 항목·사용량의 월별 추이.
 *
 * **기간을 자유롭게 받지 않고 「최근 N개월」만 받는다.** 임의의 `from`·`to`를 열면 요청
 * 한 번으로 범위를 무한정 넓힐 수 있다(`dispatch`가 같은 이유로 연월 하나만 받는다).
 *
 * **전년 동월 비교를 서버가 계산하지 않는다.** 13개월을 통째로 내려 주면 화면이 어떤 달과
 * 어떤 달을 견줄지 고를 수 있고, 그래프마다 따로 부르지 않아도 된다. 비교 방식이 바뀔 때마다
 * API를 고칠 이유도 사라진다.
 *
 * 2인 사용에 월 한 건이라 DB 집계 대신 그대로 읽어 옮긴다(`LedgerStatisticsService`와 같은 판단).
 */
@Service
@Transactional(readOnly = true)
class MaintenanceTrendService(
    private val repository: MaintenanceBillRepository,
) {
    fun trend(
        months: Int,
        today: LocalDate = LocalDate.now(),
    ): TrendResponse {
        val span = months.coerceIn(1, MAX_MONTHS)
        val start = YearMonth.from(today).minusMonths(span - 1L)
        return TrendResponse(
            repository
                .findByYearMonthGreaterThanEqualOrderByYearMonth(start.toString())
                .map { bill ->
                    TrendMonth(
                        yearMonth = bill.yearMonth,
                        chargedAmount = bill.chargedAmount,
                        items = bill.items.map { BillItemResponse(it.name, it.amount) },
                        usage =
                            BillUsage(
                                electricityKwh = bill.electricityKwh,
                                waterM3 = bill.waterM3,
                                hotWaterM3 = bill.hotWaterM3,
                                heatingGcal = bill.heatingGcal,
                                foodKg = bill.foodKg,
                            ),
                    )
                },
        )
    }

    companion object {
        /**
         * **13이다. 12가 아니다.** 12로 두면 전년 동월이 범위에서 빠져 비교 자체가 성립하지 않는다.
         */
        const val DEFAULT_MONTHS = 13
        const val MAX_MONTHS = 60
    }
}
```

- [ ] **Step 5: 테스트를 돌려 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.maintenance.MaintenanceTrendServiceTest'`
Expected: PASS

- [ ] **Step 6: 컨트롤러에 엔드포인트를 더한다**

`trendService`를 주입하고, import에 `org.springframework.web.bind.annotation.RequestParam`을 더한다.

```kotlin
    @GetMapping("/trends")
    @Operation(summary = "항목·사용량 월별 추이 — 기본 13개월(전년 동월이 범위에 들어온다)")
    fun trend(
        @RequestParam(required = false, defaultValue = "13") months: Int,
    ): ResponseEntity<DataResponseBody<TrendResponse>> = ResponseEntity.ok(DataResponseBody(trendService.trend(months)))
```

**`defaultValue`에 `MaintenanceTrendService.DEFAULT_MONTHS`를 못 쓴다.** 애너테이션 인자는 컴파일 타임 상수여야 하는데 `const val`을 참조하는 문자열 템플릿은 그 조건을 못 채운다. 리터럴 `"13"`을 쓰되, 두 자리가 어긋나지 않도록 아래 주석을 함께 단다.

```kotlin
    // defaultValue는 애너테이션 인자라 상수 참조를 못 쓴다.
    // MaintenanceTrendService.DEFAULT_MONTHS와 같은 값이어야 한다.
```

- [ ] **Step 7: 앱을 띄워 확인하고 커밋**

```bash
./gradlew :daily-record:bootRun
curl -s 'localhost:8080/maintenance/trends?months=13'
```

Expected: 저장한 달들이 오래된 순으로 나오고, 난방이 없던 달의 `heatingGcal`이 `null`이다(0이 아니다).

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/maintenance/ \
        apps/daily-record/src/test/kotlin/com/toy/backend/maintenance/
git commit -m "feat: 관리비 항목·사용량의 월별 추이를 낸다

기본이 13개월이다. 12로 두면 전년 동월이 범위에서 빠져 비교가 성립하지 않는다.

없던 항목을 0으로 채우지 않는다. '난방을 안 썼다'와 '그 달에 난방 항목이
아예 없었다'는 다른 사실이고, 여름 넉 달이 실제로 후자다.

전년 동월 비교는 서버가 계산하지 않는다. 13개월을 통째로 내려 주면 화면이
어느 달끼리 견줄지 고를 수 있고, 비교 방식이 바뀔 때마다 API를 고칠 이유가 없다."
```

---

### Task 7: 과거 넉 달 이관 SQL

앱 화면 캡처로 남은 2025-08 ~ 2025-11을 넣는다. **인식을 태우지 않는다** — 되풀이될 형식이 아니고, 값은 이미 사람이 판독해 합계까지 맞춰 두었다.

**Files:**
- Create: `scripts/maintenance-import/2025-08_2025-11.sql`
- Create: `scripts/maintenance-import/README.md`

**Interfaces:**
- Consumes: Task 2가 만든 `maintenance_bills`·`maintenance_bill_items` 스키마
- Produces: 없음

- [ ] **Step 1: 스키마가 실제로 만들어졌는지 확인한다**

`ddl-auto: update`라 앱을 한 번 띄워야 테이블이 생긴다. **컬럼 이름을 눈으로 짐작하지 말고 대 본다.**

```bash
./gradlew :daily-record:bootRun   # 한 번 띄웠다 내린다
psql -U toy -d daily-record -c '\d maintenance_bills'
psql -U toy -d daily-record -c '\d maintenance_bill_items'
```

Expected: `year_month_value`, `charged_amount`, `heating_gcal`, `food_kg`, `electricity_kwh`, `water_m3`, `hot_water_m3`가 있고 `maintenance_bill_items`에 `bill_id`·`name`·`amount`·`display_order`가 있다. **다르면 아래 SQL의 컬럼명을 실제 출력에 맞춘다.**

- [ ] **Step 2: SQL을 쓴다**

금액은 앱 화면 캡처에서, 사용량은 2025-12 영수증에 딸린 **호실별 사용량 추이표**에서 옮긴 값이다. 항목명은 종이 영수증 기준으로 바꿔 넣는다(`세대전기료→전기`, `세대급탕비→온수`, `세대수도료→수도`, `세대난방비→난방`).

```sql
-- 관리비 과거 이관: 2025-08 ~ 2025-11
--
-- 출처가 종이 영수증이 아니라 관리비 앱(아파트아이) 화면 캡처다. 그 형식에는 연도·동·호·
-- 면적·납기일·사용량이 없어 인식을 태우지 않고 사람이 판독한 값을 그대로 넣는다.
--
-- 금액은 캡처에서 읽었고, 네 달 모두 항목 합계가 화면의 '납부금액'과 일치하는 것을 확인했다
-- (275,570 / 287,690 / 217,740 / 236,190).
--
-- 사용량은 2025-12 영수증에 딸린 '호실별 사용량 추이표'에서 옮겼다. 그 표의 25/12 행이
-- 2025-12 영수증 본문의 사용량과 정확히 일치해 표 해석 자체가 검증됐다.
-- 25/07~25/10은 난방 칸이 비어 있다(여름) — 0이 아니라 NULL이다.
--
-- 항목명은 종이 영수증 기준으로 통일했다. 그러지 않으면 전기료 추이가 2025-11과
-- 2025-12 사이에서 한 번 끊긴다.
--   세대전기료→전기, 세대급탕비→온수, 세대수도료→수도, 세대난방비→난방
--
-- 납기일은 캡처에 없어 NULL이다.
--
-- 실행 전제: `ddl-auto: update`가 테이블을 만든 뒤여야 한다(앱을 한 번 띄운 뒤).
-- 두 번 돌리면 year_month_value unique 제약에 걸려 통째로 실패한다 — 그게 의도다.

BEGIN;

WITH inserted AS (
    INSERT INTO maintenance_bills (
        created_at, updated_at,
        year_month_value, dong, ho, area_m2,
        charged_amount, discount_total, unpaid_amount, unpaid_late_fee,
        due_amount, due_date,
        electricity_kwh, water_m3, hot_water_m3, heating_gcal, food_kg
    ) VALUES
        (now(), now(), '2025-08', '5103', '1404', 98.8, 275570, 0, 0, 0, 275570, NULL, 541, 9.3,  3.3, NULL, 2.75),
        (now(), now(), '2025-09', '5103', '1404', 98.8, 287690, 0, 0, 0, 287690, NULL, 554, 9.3,  3.3, NULL, 4.25),
        (now(), now(), '2025-10', '5103', '1404', 98.8, 217740, 0, 0, 0, 217740, NULL, 317, 9.2,  3.2, NULL, 3.10),
        (now(), now(), '2025-11', '5103', '1404', 98.8, 236190, 0, 0, 0, 236190, NULL, 276, 10.7, 4.7, 0.04, 3.05)
    RETURNING id, year_month_value
)
INSERT INTO maintenance_bill_items (created_at, updated_at, bill_id, name, amount, display_order)
SELECT now(), now(), inserted.id, item.name, item.amount, item.display_order
FROM inserted
JOIN (
    VALUES
        -- 2025-08 — 합계 275,570
        ('2025-08', '전기',           124720,  0),
        ('2025-08', '일반관리비',       33100,  1),
        ('2025-08', '청소비',          21750,  2),
        ('2025-08', '경비비',          19810,  3),
        ('2025-08', '수도',            12300,  4),
        ('2025-08', '온수',            11380,  5),
        ('2025-08', '장기수선충당금',   11270,  6),
        ('2025-08', '공동전기료',        9180,  7),
        ('2025-08', '수선유지비',        8690,  8),
        ('2025-08', '기본열요금',        5180,  9),
        ('2025-08', '커뮤니티운영비',    5000, 10),
        ('2025-08', '승강기전기',        2730, 11),
        ('2025-08', '보험료',           2670, 12),
        ('2025-08', 'TV수신료',         2500, 13),
        ('2025-08', '승강기유지비',      2150, 14),
        ('2025-08', '대표회의운영비',    1120, 15),
        ('2025-08', '작은도서관운영비',   720, 16),
        ('2025-08', '위탁수수료',         540, 17),
        ('2025-08', '소독비',            360, 18),
        ('2025-08', '선거관리운영비',      230, 19),
        ('2025-08', '음식물수거비',        170, 20),

        -- 2025-09 — 합계 287,690
        ('2025-09', '전기',           133920,  0),
        ('2025-09', '일반관리비',       33590,  1),
        ('2025-09', '청소비',          21750,  2),
        ('2025-09', '경비비',          19810,  3),
        ('2025-09', '온수',            13250,  4),
        ('2025-09', '수도',            11960,  5),
        ('2025-09', '장기수선충당금',   11270,  6),
        ('2025-09', '수선유지비',       10030,  7),
        ('2025-09', '공동전기료',        8770,  8),
        ('2025-09', '기본열요금',        5180,  9),
        ('2025-09', '커뮤니티운영비',    5000, 10),
        ('2025-09', '보험료',           2670, 11),
        ('2025-09', '승강기전기',        2640, 12),
        ('2025-09', 'TV수신료',         2500, 13),
        ('2025-09', '승강기유지비',      2150, 14),
        ('2025-09', '대표회의운영비',    1120, 15),
        ('2025-09', '작은도서관운영비',   720, 16),
        ('2025-09', '위탁수수료',         540, 17),
        ('2025-09', '소독비',            360, 18),
        ('2025-09', '음식물수거비',        260, 19),
        ('2025-09', '선거관리운영비',      200, 20),

        -- 2025-10 — 합계 217,740
        ('2025-10', '전기',            61580,  0),
        ('2025-10', '일반관리비',       33490,  1),
        ('2025-10', '청소비',          21570,  2),
        ('2025-10', '경비비',          19810,  3),
        ('2025-10', '온수',            16290,  4),
        ('2025-10', '수도',            12470,  5),
        ('2025-10', '장기수선충당금',   11270,  6),
        ('2025-10', '수선유지비',        9140,  7),
        ('2025-10', '공동전기료',        8710,  8),
        ('2025-10', '기본열요금',        5180,  9),
        ('2025-10', '커뮤니티운영비',    5000, 10),
        ('2025-10', '보험료',           2670, 11),
        ('2025-10', 'TV수신료',         2500, 12),
        ('2025-10', '승강기전기',        2470, 13),
        ('2025-10', '승강기유지비',      2150, 14),
        ('2025-10', '대표회의운영비',    1630, 15),
        ('2025-10', '작은도서관운영비',   720, 16),
        ('2025-10', '위탁수수료',         540, 17),
        ('2025-10', '소독비',            360, 18),
        ('2025-10', '음식물수거비',        190, 19),

        -- 2025-11 — 합계 236,190
        ('2025-11', '전기',            51270,  0),
        ('2025-11', '일반관리비',       33450,  1),
        ('2025-11', '온수',            25900,  2),
        ('2025-11', '청소비',          21750,  3),
        ('2025-11', '경비비',          19810,  4),
        ('2025-11', '공동전기료',       18300,  5),
        ('2025-11', '수도',            14260,  6),
        ('2025-11', '장기수선충당금',   11270,  7),
        ('2025-11', '수선유지비',       11000,  8),
        ('2025-11', '기본열요금',        5180,  9),
        ('2025-11', '난방',             5140, 10),
        ('2025-11', '커뮤니티운영비',    5000, 11),
        ('2025-11', '승강기전기',        3040, 12),
        ('2025-11', '보험료',           2730, 13),
        ('2025-11', 'TV수신료',         2500, 14),
        ('2025-11', '승강기유지비',      2150, 15),
        ('2025-11', '대표회의운영비',    1630, 16),
        ('2025-11', '작은도서관운영비',   720, 17),
        ('2025-11', '위탁수수료',         540, 18),
        ('2025-11', '소독비',            360, 19),
        ('2025-11', '음식물수거비',        190, 20)
) AS item(year_month_value, name, amount, display_order)
  ON item.year_month_value = inserted.year_month_value;

-- 넣은 값이 맞는지 스스로 증명한다. 네 줄 모두 ok여야 한다.
SELECT b.year_month_value,
       b.charged_amount,
       SUM(i.amount) AS item_total,
       CASE WHEN SUM(i.amount) = b.charged_amount THEN 'ok' ELSE 'MISMATCH' END AS verdict
FROM maintenance_bills b
JOIN maintenance_bill_items i ON i.bill_id = b.id
WHERE b.year_month_value BETWEEN '2025-08' AND '2025-11'
GROUP BY b.year_month_value, b.charged_amount
ORDER BY b.year_month_value;

COMMIT;
```

- [ ] **Step 3: README를 쓴다**

```markdown
# 관리비 과거 이관

`2025-08_2025-11.sql` — 관리비 앱 화면 캡처로 남은 넉 달을 넣는다.

## 전제

`ddl-auto: update`로 테이블이 만들어진 뒤에 돌린다(앱을 한 번 띄운 뒤).

## 실행

```bash
psql -U toy -d daily-record -f 2025-08_2025-11.sql
```

마지막 `SELECT`가 네 줄 모두 `ok`를 찍어야 한다. 하나라도 `MISMATCH`면
`COMMIT` 전에 멈추고 값을 다시 본다.

## 두 번 돌리면

`year_month_value` unique 제약에 걸려 트랜잭션이 통째로 실패한다. 의도한 동작이다.
다시 넣어야 하면 해당 연월을 지우고 돌린다.

```bash
psql -U toy -d daily-record -c "DELETE FROM maintenance_bills WHERE year_month_value BETWEEN '2025-08' AND '2025-11'"
```

항목은 `cascade`가 아니라 FK로 묶여 있으므로, 위 `DELETE`가 FK 위반으로 막히면
`maintenance_bill_items`를 먼저 지운다.
```

- [ ] **Step 4: 실제로 돌려 확인한다**

```bash
psql -U toy -d daily-record -f scripts/maintenance-import/2025-08_2025-11.sql
```

Expected: 검증 `SELECT`가 네 줄 모두 `ok`.

**하나라도 `MISMATCH`면 멈춘다.** `COMMIT`이 이미 나갔다면 위 README의 `DELETE`로 지우고 값을 고쳐 다시 돌린다.

- [ ] **Step 5: 추이에 이어 붙는지 눈으로 본다**

```bash
./gradlew :daily-record:bootRun
curl -s 'localhost:8080/maintenance/trends?months=24' | python3 -m json.tool
```

Expected: 2025-08부터 이어지고, **`전기`가 모든 달에 같은 이름으로 나온다**(`세대전기료`가 섞여 있으면 이름 통일이 안 된 것이다). 2025-08~10의 `heatingGcal`은 `null`이다.

- [ ] **Step 6: 커밋**

```bash
git add scripts/maintenance-import/
git commit -m "chore: 관리비 과거 넉 달(2025-08~11)을 넣는 SQL을 둔다

관리비 앱 화면 캡처라 인식을 태우지 않는다. 되풀이될 형식이 아닌데 프롬프트가
그것까지 감당하게 만들면 두 형식 중 어느 쪽으로도 확실하지 않은 프롬프트가 된다.

항목명은 종이 영수증 기준으로 통일했다(세대전기료→전기 등). 그러지 않으면
전기료 추이가 2025-11과 2025-12 사이에서 끊긴다.

사용량은 2025-12 영수증에 딸린 호실별 사용량 추이표에서 옮겼다. 그 표의 25/12
행이 2025-12 영수증 본문과 일치해 표 해석 자체가 검증됐다. 여름 난방은 0이
아니라 NULL이다.

스크립트가 마지막에 항목 합계와 부과액을 대조해 스스로 증명한다."
```

---

## 마무리: 새 심볼이 어느 파일에 나오는지 센다

AGENTS.md가 요구하는 검사다. **개수가 아니라 목록에 무엇이 빠졌는지가 기준이다.**

- [ ] **사용량 5필드가 응답 타입까지 갔는지 센다**

```bash
grep -rln "heatingGcal" --include='*.kt' apps/daily-record/src/main/
```

Expected: `MaintenanceBill.kt`, `MaintenanceDtos.kt`, `MaintenanceBillService.kt`, `MaintenanceTrendService.kt`가 모두 나와야 한다. **`MaintenanceDtos.kt`가 빠지면 그 값은 앱까지 가지 않는다** — 엔티티·서비스에 다 있어도 소용없다. 이 저장소는 같은 사고로 「사진으로 기록한 모든 끼니가 나트륨 0」을 이미 겪었다.

같은 검사를 `electricityKwh`·`waterM3`·`hotWaterM3`·`foodKg`에도 돌린다. 네 개의 파일 목록이 서로 같아야 한다.

- [ ] **전체 테스트를 돌린다**

Run: `./gradlew :daily-record:test`
Expected: PASS

- [ ] **ktlint를 돌린다**

Run: `./gradlew spotlessApply && git diff --stat`
Expected: 변경 없음(이미 각 태스크에서 돌렸다면).

- [ ] **changelog를 남긴다**

`docs/changelog/`에 이 기능을 한 항목으로 적는다. 저장소 관례다.
