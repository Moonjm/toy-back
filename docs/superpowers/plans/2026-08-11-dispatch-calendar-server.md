# 부모님 근무 달력 — 서버 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배차표 사진에서 아빠 근무를 인식하고, 엄마 근무는 반복 패턴으로 계산해, 무인증으로 조회할 수 있는 근무 달력 API를 만든다.

**Architecture:** `apps/daily-record` 안에 `com.toy.backend.dispatch` 패키지를 새로 만든다. 아빠는 사진 → 전처리(크롭·2등분·확대) → `gemini-3.6-flash` 인식 → 검수 → 저장 경로를 타고, 엄마는 `DispatchPattern` 한 행에서 조회 시점에 계산된다. `GET /dispatch/shifts`만 무인증으로 열리며 둘을 합쳐 반환한다.

**Tech Stack:** Kotlin, Spring Boot, JPA, WebClient, Kotest(BehaviorSpec), mockk, `java.awt.image`(전처리), OpenRouter(`google/gemini-3.6-flash`)

설계 문서: `docs/superpowers/specs/2026-08-11-dispatch-calendar-design.md`

## Global Constraints

- **판정은 `working`만 본다.** `slot`이 `null`이어도 `working=true`면 근무다(엄마). `slot`·`note`는 표시용이다.
- **실명·차량번호를 DB에 저장하지 않고 API 응답에도 싣지 않는다.** 이름은 `dispatch.father-name` 설정값으로만 존재한다.
- **모델은 `google/gemini-3.6-flash`, `max_tokens`는 30000.** 기존 `openrouter.vision-model`(식단용 `2.5-flash`)을 건드리지 않는다.
- **스키마 마이그레이션 도구가 없다**(`ddl-auto: update`). enum 컬럼은 `columnDefinition`을 명시해 CHECK 제약이 생기지 않게 한다.
- **동시성 방어를 하지 않는다.** 단일 인스턴스에 사용자 2명이다(`AGENTS.md`).
- 테스트는 **Kotest `BehaviorSpec`** + mockk. 기존 테스트 파일들과 같은 스타일을 따른다.
- 커밋 메시지는 **한국어**로 쓰고 기존 관례를 따른다.

---

## File Structure

**신규 — `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/`**

| 파일 | 책임 |
|---|---|
| `DispatchRole.kt` | `FATHER`/`MOTHER` enum |
| `DispatchErrorCode.kt` | 이 도메인의 에러 코드 |
| `DispatchShift.kt` · `DispatchShiftRepository.kt` | 확정된 근무 한 행 (아빠 전체 + 엄마 예외) |
| `DispatchRoster.kt` · `DispatchRosterRepository.kt` | 아빠 배차표의 월별 행 위치 |
| `DispatchPattern.kt` · `DispatchPatternRepository.kt` | 엄마 반복 규칙 |
| `DispatchPatternExpander.kt` | 패턴 → 날짜별 근무 여부 계산 (순수 함수) |
| `DispatchQueryService.kt` | 조회 병합 (아빠 저장분 + 엄마 패턴) |
| `DispatchCommandService.kt` | 확정 저장·패턴 저장 |
| `DispatchRecognitionService.kt` | 전처리 → 인식 → 조각 병합 |
| `DispatchDtos.kt` | 요청·응답 DTO |
| `DispatchController.kt` | 엔드포인트 4개 |
| `image/DispatchImageSlicer.kt` | 여백 트리밍·2등분·업스케일 |
| `llm/DispatchVisionProperties.kt` | `dispatch.*` 설정 |
| `llm/DispatchVisionConfig.kt` | WebClient 빈 |
| `llm/DispatchVisionClient.kt` | 프롬프트·스키마·호출·재시도 |

**수정**

| 파일 | 변경 |
|---|---|
| `common/auth/.../security/SecurityConfig.kt` | 앱이 무인증 경로를 기여할 확장 지점 추가 |
| `common/auth/.../security/PublicEndpoint.kt` (신규) | 확장 지점 인터페이스 |
| `apps/daily-record/src/main/resources/application.yml` | `dispatch.*` 설정 블록 |

---

### Task 1: 도메인 엔티티와 저장소

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchRole.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchErrorCode.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchShift.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchShiftRepository.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchRoster.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchRosterRepository.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchPattern.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchPatternRepository.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchShiftTest.kt`

**Interfaces:**
- Consumes: `com.toy.backend.common.entity.BaseEntity`, `com.toy.backend.common.constant.Code`
- Produces:
  - `enum class DispatchRole { FATHER, MOTHER }`
  - `class DispatchShift(role, workDate, working, slot, note)` — `var working: Boolean`, `var slot: Int?`, `var note: String?`
  - `DispatchShiftRepository.findByWorkDateBetween(from: LocalDate, to: LocalDate): List<DispatchShift>`
  - `DispatchShiftRepository.findByRoleAndWorkDate(role: DispatchRole, workDate: LocalDate): DispatchShift?`
  - `class DispatchRoster(yearMonth: String, rowIndex: Int, rowCount: Int)`
  - `DispatchRosterRepository.findByYearMonth(yearMonth: String): DispatchRoster?`
  - `class DispatchPattern(role: DispatchRole, cycleDays: Int, workingOffsets: String, anchorDate: LocalDate)`
  - `DispatchPattern.workingOffsetList: List<Int>`
  - `DispatchPatternRepository.findByRole(role: DispatchRole): DispatchPattern?`

- [ ] **Step 1: 실패하는 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchShiftTest.kt`

```kotlin
package com.toy.backend.dispatch

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * **판정은 `working`만 본다.** `slot`이 `null`이어도 근무일 수 있다(엄마는 순번이 아직 없다).
 * 「slot이 있으면 근무」로 판정하면 엄마 근무일이 전부 휴무로 읽힌다.
 */
class DispatchShiftTest :
    BehaviorSpec({
        Given("순번이 없는 근무일") {
            val shift =
                DispatchShift(
                    role = DispatchRole.MOTHER,
                    workDate = LocalDate.of(2026, 8, 1),
                    working = true,
                    slot = null,
                    note = null,
                )

            Then("slot이 없어도 근무다") {
                shift.working shouldBe true
                shift.slot shouldBe null
            }
        }

        Given("note만 있는 휴무일") {
            val shift =
                DispatchShift(
                    role = DispatchRole.FATHER,
                    workDate = LocalDate.of(2026, 8, 19),
                    working = false,
                    slot = null,
                    note = "간담회",
                )

            Then("note가 있어도 근무가 아니다") {
                shift.working shouldBe false
                shift.note shouldBe "간담회"
            }
        }

        Given("주기 안에서 일하는 날이 1,2인 패턴") {
            val pattern =
                DispatchPattern(
                    role = DispatchRole.MOTHER,
                    cycleDays = 3,
                    workingOffsets = "1,2",
                    anchorDate = LocalDate.of(2026, 8, 8),
                )

            Then("오프셋 목록으로 읽힌다") {
                pattern.workingOffsetList shouldBe listOf(1, 2)
            }
        }
    })
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.dispatch.DispatchShiftTest"`
Expected: 컴파일 실패 — `Unresolved reference: DispatchShift`

- [ ] **Step 3: enum과 에러 코드 작성**

`DispatchRole.kt`

```kotlin
package com.toy.backend.dispatch

enum class DispatchRole { FATHER, MOTHER }
```

`DispatchErrorCode.kt`

```kotlin
package com.toy.backend.dispatch

import com.toy.backend.common.constant.Code
import org.springframework.http.HttpStatus

enum class DispatchErrorCode(
    private val httpStatus: HttpStatus,
    private val message: String,
) : Code {
    // 잘린 사진에는 성명 컬럼이 없어 행 위치를 알 수 없다. 추측해서 저장하느니 거부한다.
    ROSTER_NOT_FOUND(HttpStatus.BAD_REQUEST, "%s 배차표 기준이 없습니다. 성명 컬럼이 보이는 사진을 먼저 올려 주세요."),
    TARGET_NAME_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "dispatch.father-name이 설정되지 않았습니다."),
    VISION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "사진 인식에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    IMAGE_UNREADABLE(HttpStatus.BAD_REQUEST, "이미지를 읽을 수 없습니다."),
    PATTERN_NOT_FOUND(HttpStatus.NOT_FOUND, "%s 근무 패턴이 없습니다."),
    INVALID_PATTERN(HttpStatus.BAD_REQUEST, "패턴이 올바르지 않습니다: %s"),
    ;

    override fun getHttpStatus(): HttpStatus = httpStatus

    override fun getMessage(): String = message

    override fun getStatusName(): String = httpStatus.name

    override fun getCodeName(): String = name
}
```

- [ ] **Step 4: 엔티티 세 개 작성**

`DispatchShift.kt`

```kotlin
package com.toy.backend.dispatch

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate

/**
 * 확정된 근무 한 행. **아빠는 모든 날이 여기 들어오고, 엄마는 패턴과 다른 날(예외)만 들어온다.**
 *
 * `working`이 판정의 유일한 근거다. `slot`은 배차 순번이고 **근무여도 미정이면 null**이다
 * (엄마는 순번 구분이 있지만 아직 넣지 않는다). 둘을 겹쳐 쓰면 순번을 채우는 날
 * 판정 로직을 읽는 쪽마다 전부 고쳐야 한다.
 */
@Entity
@Table(
    name = "dispatch_shift",
    uniqueConstraints = [UniqueConstraint(columnNames = ["role", "work_date"])],
)
class DispatchShift(
    // ddl-auto가 CHECK 제약을 갱신하지 못해, 명시하지 않으면 enum 값 추가 시 기존 DB에서 INSERT가 깨진다.
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "role", nullable = false, columnDefinition = "varchar(16)")
    val role: DispatchRole,
    @field:Column(name = "work_date", nullable = false)
    val workDate: LocalDate,
    @field:Column(name = "working", nullable = false)
    var working: Boolean,
    @field:Column(name = "slot")
    var slot: Int? = null,
    @field:Column(name = "note")
    var note: String? = null,
) : BaseEntity()
```

`DispatchRoster.kt`

```kotlin
package com.toy.backend.dispatch

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * 아빠 배차표의 월별 행 위치. **잘린 사진에는 성명 컬럼이 없어 이 값이 유일한 단서다.**
 *
 * `rowCount`는 표의 전체 데이터 행 수다. 인원이 바뀌어 행 순서가 밀리면 엉뚱한 기사의
 * 근무가 조용히 들어오는데, 이 값을 비교해 경고를 띄운다.
 *
 * **실명·차량번호는 두지 않는다.** 조회 API가 무인증으로 열려 있다.
 */
@Entity
@Table(name = "dispatch_roster")
class DispatchRoster(
    @field:Column(name = "year_month_value", nullable = false, unique = true, length = 7)
    val yearMonth: String,
    @field:Column(name = "row_index", nullable = false)
    var rowIndex: Int,
    @field:Column(name = "row_count", nullable = false)
    var rowCount: Int,
) : BaseEntity()
```

`DispatchPattern.kt`

```kotlin
package com.toy.backend.dispatch

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * 반복 근무 규칙. **행을 미리 만들어 두지 않고 조회할 때 계산한다** — 미리 만들면
 * 패턴을 고칠 때 낡은 행이 남고, 어디까지 만들어 뒀는지도 관리해야 한다.
 *
 * `anchorDate`는 **오프셋 0인 날**이다. `workingOffsets`에 없는 오프셋이 휴무다.
 * 엄마는 `cycleDays=3`, `workingOffsets="1,2"`, `anchorDate=2026-08-08`(휴무)이다.
 */
@Entity
@Table(name = "dispatch_pattern")
class DispatchPattern(
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "role", nullable = false, unique = true, columnDefinition = "varchar(16)")
    val role: DispatchRole,
    @field:Column(name = "cycle_days", nullable = false)
    var cycleDays: Int,
    @field:Column(name = "working_offsets", nullable = false, length = 64)
    var workingOffsets: String,
    @field:Column(name = "anchor_date", nullable = false)
    var anchorDate: LocalDate,
) : BaseEntity() {
    val workingOffsetList: List<Int>
        get() = workingOffsets.split(",").mapNotNull { it.trim().toIntOrNull() }
}
```

- [ ] **Step 5: 저장소 세 개 작성**

`DispatchShiftRepository.kt`

```kotlin
package com.toy.backend.dispatch

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface DispatchShiftRepository : JpaRepository<DispatchShift, Long> {
    fun findByWorkDateBetween(
        from: LocalDate,
        to: LocalDate,
    ): List<DispatchShift>

    fun findByRoleAndWorkDate(
        role: DispatchRole,
        workDate: LocalDate,
    ): DispatchShift?

    fun findByRoleAndWorkDateBetween(
        role: DispatchRole,
        from: LocalDate,
        to: LocalDate,
    ): List<DispatchShift>
}
```

`DispatchRosterRepository.kt`

```kotlin
package com.toy.backend.dispatch

import org.springframework.data.jpa.repository.JpaRepository

interface DispatchRosterRepository : JpaRepository<DispatchRoster, Long> {
    fun findByYearMonth(yearMonth: String): DispatchRoster?
}
```

`DispatchPatternRepository.kt`

```kotlin
package com.toy.backend.dispatch

import org.springframework.data.jpa.repository.JpaRepository

interface DispatchPatternRepository : JpaRepository<DispatchPattern, Long> {
    fun findByRole(role: DispatchRole): DispatchPattern?
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.dispatch.DispatchShiftTest"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/dispatch \
        apps/daily-record/src/test/kotlin/com/toy/backend/dispatch
git commit -m "feat: 근무 달력 도메인 엔티티를 만든다

판정 근거를 working 하나로 두고 slot을 표시용으로 뗀다. 엄마는 근무일이지만
순번이 아직 없어 slot이 null인데, 「slot이 있으면 근무」로 판정하면 근무일이
전부 휴무로 읽힌다.

배차표 기준(DispatchRoster)에 실명과 차량번호를 두지 않는다. 조회 API가
무인증으로 열려서다."
```

---

### Task 2: 패턴 전개기

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchPatternExpander.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchPatternExpanderTest.kt`

**Interfaces:**
- Consumes: `DispatchPattern`(Task 1)
- Produces: `object DispatchPatternExpander { fun isWorking(pattern: DispatchPattern, date: LocalDate): Boolean }`

- [ ] **Step 1: 실패하는 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchPatternExpanderTest.kt`

```kotlin
package com.toy.backend.dispatch

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * **`anchorDate` 이전 날짜에서 깨지기 쉽다.** Kotlin `%`는 음수 나머지를 음수로 주므로
 * `Math.floorMod`를 써야 한다. 기준일을 8/8로 잡고 8/1을 조회하는 것이 실제 사용 흐름이다.
 */
class DispatchPatternExpanderTest :
    BehaviorSpec({
        val pattern =
            DispatchPattern(
                role = DispatchRole.MOTHER,
                cycleDays = 3,
                workingOffsets = "1,2",
                anchorDate = LocalDate.of(2026, 8, 8),
            )

        Given("하루 휴무 뒤 이틀 근무가 3일 주기로 도는 패턴") {
            When("기준일 자신을 물으면") {
                Then("휴무다") {
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 8)) shouldBe false
                }
            }

            When("기준일 이후를 물으면") {
                Then("주기대로 돈다") {
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 9)) shouldBe true
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 10)) shouldBe true
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 11)) shouldBe false
                }
            }

            When("기준일 이전을 물으면") {
                Then("음수 오프셋이 올바로 감긴다") {
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 7)) shouldBe true
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 6)) shouldBe true
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 5)) shouldBe false
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 1)) shouldBe true
                }
            }

            When("8월 전체를 펼치면") {
                val offDays =
                    (1..31)
                        .map { LocalDate.of(2026, 8, it) }
                        .filterNot { DispatchPatternExpander.isWorking(pattern, it) }
                        .map { it.dayOfMonth }

                Then("휴무는 3일 간격으로 열 번이다") {
                    offDays shouldBe listOf(2, 5, 8, 11, 14, 17, 20, 23, 26, 29)
                }
            }

            When("해를 넘겨 물으면") {
                Then("연도 경계에서도 주기가 이어진다") {
                    // 2026-08-08부터 2027-01-01까지 146일 → floorMod(146, 3) = 2 → 근무
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2027, 1, 1)) shouldBe true
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.dispatch.DispatchPatternExpanderTest"`
Expected: 컴파일 실패 — `Unresolved reference: DispatchPatternExpander`

- [ ] **Step 3: 구현**

`DispatchPatternExpander.kt`

```kotlin
package com.toy.backend.dispatch

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 패턴을 날짜별 근무 여부로 펼친다. **상태가 없는 순수 계산**이라 object로 둔다.
 *
 * Kotlin `%`는 음수 피제수에 음수 나머지를 준다(`-7 % 3 == -1`). 기준일 이전 날짜를
 * 조회하는 것이 실제 흐름(기준 8/8, 조회 8/1)이므로 **`Math.floorMod`를 쓴다.**
 */
object DispatchPatternExpander {
    fun isWorking(
        pattern: DispatchPattern,
        date: LocalDate,
    ): Boolean {
        val elapsed = ChronoUnit.DAYS.between(pattern.anchorDate, date)
        val offset = Math.floorMod(elapsed, pattern.cycleDays.toLong()).toInt()
        return offset in pattern.workingOffsetList
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.dispatch.DispatchPatternExpanderTest"`
Expected: PASS (5개 시나리오)

- [ ] **Step 5: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchPatternExpander.kt \
        apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchPatternExpanderTest.kt
git commit -m "feat: 반복 패턴을 날짜별 근무 여부로 펼친다

기준일 이전을 조회하는 것이 실제 흐름이라(기준 8/8, 조회 8/1) Math.floorMod로
음수 오프셋을 감는다. Kotlin %는 음수 나머지를 음수로 준다."
```

---

### Task 3: 조회 병합과 무인증 엔드포인트

**Files:**
- Create: `common/auth/src/main/kotlin/com/toy/backend/auth/security/PublicEndpoint.kt`
- Modify: `common/auth/src/main/kotlin/com/toy/backend/auth/security/SecurityConfig.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchDtos.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchQueryService.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchPublicEndpoints.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchQueryServiceTest.kt`

**Interfaces:**
- Consumes: Task 1의 저장소 전부, `DispatchPatternExpander`(Task 2)
- Produces:
  - `data class ShiftDayResponse(date: LocalDate, role: DispatchRole, working: Boolean, slot: Int?, note: String?)`
  - `data class ShiftRangeResponse(days: List<ShiftDayResponse>)`
  - `DispatchQueryService.findRange(from: LocalDate, to: LocalDate): ShiftRangeResponse`
  - `interface PublicEndpoint { fun method(): HttpMethod?; fun pattern(): String }`

- [ ] **Step 1: 실패하는 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchQueryServiceTest.kt`

```kotlin
package com.toy.backend.dispatch

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate

/**
 * 아빠와 엄마는 저장 경로가 다르다 — **아빠는 확정 저장된 날짜만, 엄마는 패턴으로 범위 전체.**
 * 읽는 쪽(웹 달력)은 그 차이를 몰라야 하므로 여기서 같은 모양으로 합친다.
 *
 * 아빠의 「아직 인식하지 않은 날」을 휴무로 채우면 안 된다. 「쉬는 날」과 구분이 사라진다.
 */
class DispatchQueryServiceTest :
    BehaviorSpec({
        val shiftRepository = mockk<DispatchShiftRepository>()
        val patternRepository = mockk<DispatchPatternRepository>()
        val service = DispatchQueryService(shiftRepository, patternRepository)

        val from = LocalDate.of(2026, 8, 1)
        val to = LocalDate.of(2026, 8, 3)

        Given("아빠 확정분 하루와 엄마 패턴이 있을 때") {
            every { shiftRepository.findByWorkDateBetween(from, to) } returns
                listOf(
                    DispatchShift(DispatchRole.FATHER, LocalDate.of(2026, 8, 1), working = true, slot = 1),
                )
            every { patternRepository.findByRole(DispatchRole.MOTHER) } returns
                DispatchPattern(DispatchRole.MOTHER, 3, "1,2", LocalDate.of(2026, 8, 8))

            val days = service.findRange(from, to).days

            Then("아빠는 확정된 하루만 나온다") {
                val father = days.filter { it.role == DispatchRole.FATHER }
                father.size shouldBe 1
                father[0].date shouldBe LocalDate.of(2026, 8, 1)
                father[0].working shouldBe true
                father[0].slot shouldBe 1
            }

            Then("엄마는 범위 전체가 패턴으로 채워진다") {
                val mother = days.filter { it.role == DispatchRole.MOTHER }.sortedBy { it.date }
                mother.size shouldBe 3
                mother[0].working shouldBe true // 8/1
                mother[1].working shouldBe false // 8/2
                mother[2].working shouldBe true // 8/3
            }

            Then("엄마는 순번이 아직 없어 slot이 비어 있다") {
                days.filter { it.role == DispatchRole.MOTHER }.all { it.slot == null } shouldBe true
            }
        }

        Given("엄마 예외가 저장돼 있을 때") {
            every { shiftRepository.findByWorkDateBetween(from, to) } returns
                listOf(
                    // 패턴상 8/1은 근무인데 예외로 휴무를 저장했다
                    DispatchShift(DispatchRole.MOTHER, LocalDate.of(2026, 8, 1), working = false, note = "연차"),
                )
            every { patternRepository.findByRole(DispatchRole.MOTHER) } returns
                DispatchPattern(DispatchRole.MOTHER, 3, "1,2", LocalDate.of(2026, 8, 8))

            val days = service.findRange(from, to).days

            Then("예외가 패턴 계산을 덮어쓴다") {
                val day = days.first { it.role == DispatchRole.MOTHER && it.date == LocalDate.of(2026, 8, 1) }
                day.working shouldBe false
                day.note shouldBe "연차"
            }

            Then("예외가 없는 날은 그대로 패턴이다") {
                val day = days.first { it.role == DispatchRole.MOTHER && it.date == LocalDate.of(2026, 8, 3) }
                day.working shouldBe true
            }
        }

        Given("패턴이 등록되지 않았을 때") {
            every { shiftRepository.findByWorkDateBetween(from, to) } returns emptyList()
            every { patternRepository.findByRole(DispatchRole.MOTHER) } returns null

            Then("엄마 일정 없이 조회가 성공한다") {
                service.findRange(from, to).days.none { it.role == DispatchRole.MOTHER } shouldBe true
            }
        }
    })
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.dispatch.DispatchQueryServiceTest"`
Expected: 컴파일 실패 — `Unresolved reference: DispatchQueryService`

- [ ] **Step 3: DTO 작성**

`DispatchDtos.kt`

```kotlin
package com.toy.backend.dispatch

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.time.LocalDate

/** 무인증으로 나가는 응답이다 — **실명·차량번호를 넣지 않는다.** */
data class ShiftDayResponse(
    val date: LocalDate,
    val role: DispatchRole,
    val working: Boolean,
    val slot: Int?,
    val note: String?,
)

data class ShiftRangeResponse(
    val days: List<ShiftDayResponse>,
)

data class ShiftSaveRequest(
    @field:NotNull val role: DispatchRole,
    @field:NotEmpty val days: List<ShiftSaveDay>,
)

data class ShiftSaveDay(
    @field:NotNull val date: LocalDate,
    @field:NotNull val working: Boolean,
    val slot: Int?,
    val note: String?,
)

data class PatternSaveRequest(
    @field:Min(1) val cycleDays: Int,
    @field:NotEmpty val workingOffsets: List<Int>,
    @field:NotNull val anchorDate: LocalDate,
)

data class PatternResponse(
    val role: DispatchRole,
    val cycleDays: Int,
    val workingOffsets: List<Int>,
    val anchorDate: LocalDate,
)
```

- [ ] **Step 4: 조회 서비스 구현**

`DispatchQueryService.kt`

```kotlin
package com.toy.backend.dispatch

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 아빠·엄마를 **같은 모양으로 합쳐** 내보낸다. 읽는 쪽은 데이터가 사진에서 왔는지
 * 패턴에서 왔는지 알 필요가 없다.
 *
 * **아빠는 확정 저장된 날짜만 나간다.** 아직 인식·검수하지 않은 날을 휴무로 채우면
 * 「쉬는 날」과 「모르는 날」이 구분되지 않는다. 달력은 그 자리를 비운다.
 */
@Service
@Transactional(readOnly = true)
class DispatchQueryService(
    private val shiftRepository: DispatchShiftRepository,
    private val patternRepository: DispatchPatternRepository,
) {
    fun findRange(
        from: LocalDate,
        to: LocalDate,
    ): ShiftRangeResponse {
        val stored = shiftRepository.findByWorkDateBetween(from, to)
        val storedByKey = stored.associateBy { it.role to it.workDate }

        val fatherDays =
            stored
                .filter { it.role == DispatchRole.FATHER }
                .map { it.toResponse() }

        val motherDays =
            patternRepository.findByRole(DispatchRole.MOTHER)?.let { pattern ->
                generateSequence(from) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(to) }
                    .map { date ->
                        // 예외가 있으면 패턴 계산을 덮어쓴다.
                        storedByKey[DispatchRole.MOTHER to date]?.toResponse()
                            ?: ShiftDayResponse(
                                date = date,
                                role = DispatchRole.MOTHER,
                                working = DispatchPatternExpander.isWorking(pattern, date),
                                slot = null,
                                note = null,
                            )
                    }.toList()
            } ?: emptyList()

        return ShiftRangeResponse((fatherDays + motherDays).sortedWith(compareBy({ it.date }, { it.role })))
    }

    private fun DispatchShift.toResponse() =
        ShiftDayResponse(
            date = workDate,
            role = role,
            working = working,
            slot = slot,
            note = note,
        )
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.dispatch.DispatchQueryServiceTest"`
Expected: PASS

- [ ] **Step 6: 무인증 경로 확장 지점 추가**

`common/auth/src/main/kotlin/com/toy/backend/auth/security/PublicEndpoint.kt` (신규)

```kotlin
package com.toy.backend.auth.security

import org.springframework.http.HttpMethod

/**
 * 앱 모듈이 **인증 없이 열 경로**를 시큐리티 체인에 기여할 때 등록하는 빈.
 * `AdditionalAuthFilter`와 같은 확장 방식이다 — 앱별 경로를 `SecurityConfig`에
 * 하드코딩하면 `common-auth`가 앱을 알게 되고, 다른 앱에도 그 경로가 열린다.
 */
interface PublicEndpoint {
    /** null이면 모든 메서드 */
    fun method(): HttpMethod?

    fun pattern(): String
}
```

`SecurityConfig.kt` 수정 — 생성자에 `ObjectProvider<PublicEndpoint>`를 받고, `authorizeHttpRequests` 안에서 `anyRequest()` **앞에** 등록한다.

```kotlin
@Configuration
@EnableMethodSecurity
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
    additionalAuthFilters: ObjectProvider<AdditionalAuthFilter>,
    publicEndpoints: ObjectProvider<PublicEndpoint>,
) {
    private val additionalAuthFilters: List<AdditionalAuthFilter> = additionalAuthFilters.orderedStream().toList()
    private val publicEndpoints: List<PublicEndpoint> = publicEndpoints.orderedStream().toList()

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/auth/**")
                    .permitAll()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**")
                    .permitAll()

                // 앱 모듈이 기여한 무인증 경로. anyRequest() 앞에 와야 적용된다.
                publicEndpoints.forEach { endpoint ->
                    val method = endpoint.method()
                    if (method == null) {
                        it.requestMatchers(endpoint.pattern()).permitAll()
                    } else {
                        it.requestMatchers(method, endpoint.pattern()).permitAll()
                    }
                }

                it
                    .anyRequest()
                    .authenticated()
            }.exceptionHandling {
                it.authenticationEntryPoint(HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED))
            }.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        additionalAuthFilters.forEach { filter ->
            http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter::class.java)
        }

        return http.build()
    }
}
```

- [ ] **Step 7: daily-record가 조회 경로를 기여하게 한다**

`DispatchPublicEndpoints.kt`

```kotlin
package com.toy.backend.dispatch

import com.toy.backend.auth.security.PublicEndpoint
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod

/**
 * **조회만** 무인증으로 연다. 업로드·검수·저장은 인증된 앱에서만 한다.
 * 응답에 실명·차량번호가 없으므로(`ShiftDayResponse`) 공개해도 남는 것은 근무 여부와 순번뿐이다.
 */
@Configuration
class DispatchPublicEndpoints {
    @Bean
    fun dispatchShiftReadEndpoint(): PublicEndpoint =
        object : PublicEndpoint {
            override fun method(): HttpMethod = HttpMethod.GET

            override fun pattern(): String = "/dispatch/shifts"
        }
}
```

- [ ] **Step 8: 전체 빌드 확인**

Run: `./gradlew :daily-record:test :common-auth:test`
Expected: PASS — `common-auth` 기존 테스트가 깨지지 않아야 한다(`PublicEndpoint` 빈이 없으면 목록이 비고 동작이 이전과 같다)

- [ ] **Step 9: 커밋**

```bash
git add common/auth/src/main/kotlin/com/toy/backend/auth/security \
        apps/daily-record/src/main/kotlin/com/toy/backend/dispatch \
        apps/daily-record/src/test/kotlin/com/toy/backend/dispatch
git commit -m "feat: 근무 달력 조회를 무인증으로 연다

아빠(확정 저장분)와 엄마(패턴 계산)를 같은 모양으로 합쳐 내보낸다. 읽는 쪽은
데이터가 사진에서 왔는지 패턴에서 왔는지 알 필요가 없다.

앱별 경로를 SecurityConfig에 하드코딩하면 common-auth가 앱을 알게 되고 다른
앱에도 그 경로가 열린다. AdditionalAuthFilter와 같은 방식으로 PublicEndpoint
빈을 기여받는다."
```

---

### Task 4: 이미지 전처리

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/image/DispatchImageSlicer.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/image/DispatchImageSlicerTest.kt`

**Interfaces:**
- Consumes: 없음(순수 이미지 처리)
- Produces:
  - `data class ImageSlice(index: Int, base64: String, xFrom: Int, xTo: Int)` — `xFrom`/`xTo`는 **트리밍된 원본 기준** 픽셀 범위
  - `@Component class DispatchImageSlicer { fun slice(bytes: ByteArray): List<ImageSlice> }`
  - `internal fun trim(image: BufferedImage): BufferedImage`
  - `internal fun upscale(image: BufferedImage, targetLongEdge: Int): BufferedImage`

- [ ] **Step 1: 실패하는 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/image/DispatchImageSlicerTest.kt`

```kotlin
package com.toy.backend.dispatch.image

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * **전처리가 이 기능의 성패를 가른다.** 같은 사진·같은 모델인데 전처리 유무로 인식
 * 정확도가 0%와 100%로 갈렸다(설계 문서의 실측표).
 *
 * 사진은 두 형태로 온다 — 시간표만 확대해 찍어 **위아래가 검은 여백**인 것과,
 * 밴드 글을 통째로 찍어 **흰 배경**인 것. 둘 다 걷어내야 한다.
 */
class DispatchImageSlicerTest :
    BehaviorSpec({
        /** 가운데에만 내용이 있고 가장자리는 단색인 이미지를 만든다. */
        fun imageWithBorder(
            border: Color,
            width: Int = 400,
            height: Int = 200,
            contentX: Int = 50,
            contentY: Int = 60,
            contentW: Int = 300,
            contentH: Int = 80,
        ): BufferedImage {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = border
            g.fillRect(0, 0, width, height)
            // 내용부는 단색이 아니어야 트리밍이 멈춘다 — 줄무늬를 넣는다.
            for (x in contentX until contentX + contentW) {
                g.color = if ((x / 10) % 2 == 0) Color.WHITE else Color.BLUE
                g.fillRect(x, contentY, 1, contentH)
            }
            g.dispose()
            return image
        }

        fun toBytes(image: BufferedImage): ByteArray {
            val out = ByteArrayOutputStream()
            ImageIO.write(image, "png", out)
            return out.toByteArray()
        }

        Given("검은 여백에 둘러싸인 이미지") {
            val trimmed = trim(imageWithBorder(Color.BLACK))

            Then("여백이 걷힌다") {
                trimmed.width shouldBe 300
                trimmed.height shouldBe 80
            }
        }

        Given("흰 여백에 둘러싸인 이미지") {
            val trimmed = trim(imageWithBorder(Color.WHITE))

            Then("흰 여백도 걷힌다") {
                trimmed.width shouldBe 300
                trimmed.height shouldBe 80
            }
        }

        Given("여백이 없는 이미지") {
            val image = imageWithBorder(Color.BLACK, contentX = 0, contentY = 0, contentW = 400, contentH = 200)
            val trimmed = trim(image)

            Then("원본 크기가 유지된다") {
                trimmed.width shouldBe 400
                trimmed.height shouldBe 200
            }
        }

        Given("가로로 긴 표 사진") {
            val slices = DispatchImageSlicer().slice(toBytes(imageWithBorder(Color.BLACK)))

            Then("두 조각으로 나뉜다") {
                slices.size shouldBe 2
            }

            Then("경계가 겹친다 — 경계에 걸친 날짜가 유실되지 않도록") {
                // 조각1은 폭의 55%까지, 조각2는 45%부터. 겹침 폭은 트리밍 폭(300)의 10%.
                slices[0].xFrom shouldBe 0
                slices[1].xTo shouldBe 300
                slices[0].xTo shouldBeGreaterThan slices[1].xFrom
                (slices[0].xTo - slices[1].xFrom) shouldBe 30
            }

            Then("각 조각이 base64로 나온다") {
                slices.all { it.base64.isNotBlank() } shouldBe true
            }
        }

        Given("작은 이미지") {
            val upscaled = upscale(imageWithBorder(Color.BLACK, width = 200, height = 100), targetLongEdge = 1600)

            Then("긴 변이 목표치로 커진다") {
                upscaled.width shouldBe 1600
                upscaled.height shouldBe 800
            }
        }

        Given("이미 충분히 큰 이미지") {
            val upscaled = upscale(imageWithBorder(Color.BLACK, width = 2000, height = 1000), targetLongEdge = 1600)

            Then("줄이지는 않는다") {
                upscaled.width shouldBe 2000
            }
        }
    })
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.dispatch.image.DispatchImageSlicerTest"`
Expected: 컴파일 실패 — `Unresolved reference: trim`

- [ ] **Step 3: 구현**

`apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/image/DispatchImageSlicer.kt`

```kotlin
package com.toy.backend.dispatch.image

import com.toy.backend.common.exception.CustomException
import com.toy.backend.dispatch.DispatchErrorCode
import org.springframework.stereotype.Component
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs

/** 조각 하나. `xFrom`/`xTo`는 **트리밍된 원본** 기준 픽셀 범위다(겹침 판정에 쓴다). */
data class ImageSlice(
    val index: Int,
    val base64: String,
    val xFrom: Int,
    val xTo: Int,
)

/** 가장자리 한 줄이 단색인지 볼 때 허용할 채널 차이. JPEG 압축 노이즈를 흡수한다. */
private const val TOLERANCE = 12

/** 조각이 차지하는 비율. 0.55면 55%씩 두 조각이 되어 10%가 겹친다. */
private const val SLICE_RATIO = 0.55

private const val TARGET_LONG_EDGE = 1600

/**
 * 사진을 인식하기 좋게 다듬는다. **이 전처리가 없으면 모델은 빈 칸을 숫자로 메운다** —
 * 표가 가로로 길어 한 칸이 몇 픽셀밖에 안 되고, 그 크기에서는 빈 칸과 숫자가 구분되지
 * 않기 때문이다(설계 문서 함정 1).
 *
 * 표 경계를 찾지는 않는다. **여백 트리밍과 2등분까지**로 실측 100%가 나왔고,
 * 표 경계 검출은 별개 문제다.
 */
@Component
class DispatchImageSlicer {
    fun slice(bytes: ByteArray): List<ImageSlice> {
        val source =
            ImageIO.read(ByteArrayInputStream(bytes))
                ?: throw CustomException(DispatchErrorCode.IMAGE_UNREADABLE)
        val trimmed = trim(source)

        val width = trimmed.width
        val sliceWidth = (width * SLICE_RATIO).toInt().coerceAtLeast(1)
        val ranges =
            listOf(
                0 to sliceWidth.coerceAtMost(width),
                (width - sliceWidth).coerceAtLeast(0) to width,
            )

        return ranges.mapIndexed { index, (xFrom, xTo) ->
            val piece = trimmed.getSubimage(xFrom, 0, xTo - xFrom, trimmed.height)
            ImageSlice(
                index = index,
                base64 = Base64.getEncoder().encodeToString(toPng(upscale(piece, TARGET_LONG_EDGE))),
                xFrom = xFrom,
                xTo = xTo,
            )
        }
    }

    private fun toPng(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}

/**
 * 가장자리부터 **단색인 행·열을 걷어낸다.** 확대 캡처의 검은 여백과 밴드 스크린샷의
 * 흰 배경이 모두 여기서 빠진다. 색을 가정하지 않고 「그 줄이 한 가지 색인가」만 본다.
 */
internal fun trim(image: BufferedImage): BufferedImage {
    var top = 0
    var bottom = image.height - 1
    var left = 0
    var right = image.width - 1

    while (top < bottom && isUniformRow(image, top)) top++
    while (bottom > top && isUniformRow(image, bottom)) bottom--
    while (left < right && isUniformColumn(image, left, top, bottom)) left++
    while (right > left && isUniformColumn(image, right, top, bottom)) right--

    return image.getSubimage(left, top, right - left + 1, bottom - top + 1)
}

/**
 * 긴 변을 `targetLongEdge`로 키운다. **줄이지는 않는다** — 이미 큰 사진을 줄이면
 * 애써 확보한 해상도를 버리게 된다.
 */
internal fun upscale(
    image: BufferedImage,
    targetLongEdge: Int,
): BufferedImage {
    val longEdge = maxOf(image.width, image.height)
    if (longEdge >= targetLongEdge) return image

    val scale = targetLongEdge.toDouble() / longEdge
    val width = (image.width * scale).toInt()
    val height = (image.height * scale).toInt()

    val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = scaled.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(image, 0, 0, width, height, null)
    g.dispose()
    return scaled
}

private fun isUniformRow(
    image: BufferedImage,
    y: Int,
): Boolean {
    val first = image.getRGB(0, y)
    for (x in 1 until image.width) {
        if (!isNear(first, image.getRGB(x, y))) return false
    }
    return true
}

private fun isUniformColumn(
    image: BufferedImage,
    x: Int,
    top: Int,
    bottom: Int,
): Boolean {
    val first = image.getRGB(x, top)
    for (y in top + 1..bottom) {
        if (!isNear(first, image.getRGB(x, y))) return false
    }
    return true
}

private fun isNear(
    a: Int,
    b: Int,
): Boolean =
    abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= TOLERANCE &&
        abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= TOLERANCE &&
        abs((a and 0xFF) - (b and 0xFF)) <= TOLERANCE
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.dispatch.image.DispatchImageSlicerTest"`
Expected: PASS (7개 시나리오)

- [ ] **Step 5: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/image \
        apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/image
git commit -m "feat: 배차표 사진을 인식 전에 잘라 확대한다

전처리 유무로 인식 정확도가 0%와 100%로 갈린다. 표가 가로로 길어 한 칸이 몇
픽셀밖에 안 되고, 그 크기에서는 모델이 빈 칸과 숫자를 구분하지 못해 빈 칸을
숫자로 메운다.

여백은 색을 가정하지 않고 「그 줄이 한 가지 색인가」로 걷어낸다. 확대 캡처의
검은 여백과 밴드 스크린샷의 흰 배경이 모두 걸린다. 조각 경계는 10% 겹치게
잘라 경계에 걸린 날짜가 유실되지 않게 한다."
```

---

### Task 5: 인식 클라이언트

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/llm/DispatchVisionProperties.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/llm/DispatchVisionConfig.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/llm/DispatchVisionClient.kt`
- Modify: `apps/daily-record/src/main/resources/application.yml`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/llm/DispatchVisionClientTest.kt`

**Interfaces:**
- Consumes: `ImageSlice`(Task 4)
- Produces:
  - `data class RecognizedCell(day: Int, value: String)`
  - `data class RecognizedSlice(hasNameColumn: Boolean, rowIndex: Int, rowCount: Int, year: Int, month: Int, visibleDays: List<Int>, cells: List<RecognizedCell>)`
  - `DispatchVisionClient.read(slice: ImageSlice, targetName: String?, knownRowIndex: Int?): RecognizedSlice?`
  - `DispatchVisionProperties(apiKey, baseUrl, visionModel, visionMaxTokens, timeoutSeconds, fatherName)`

- [ ] **Step 1: 실패하는 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/llm/DispatchVisionClientTest.kt`

```kotlin
package com.toy.backend.dispatch.llm

import com.toy.backend.dispatch.image.ImageSlice
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

/**
 * **`max_tokens`를 크게 잡아야 한다.** `gemini-3.6-flash`는 reasoning 토큰을 1,300~3,000
 * 쓰는데 이 값이 `max_tokens`에 함께 잡힌다. 식단용 기본값 4,000으로 두면 `content`가
 * 빈 채로 온다(실측에서 `2.5-pro`로 재현됐다).
 *
 * 그리고 **재시도 1회**가 필요하다 — 실측에서 5회 중 1회 JSON 파싱에 실패했다.
 * 사용자가 사진을 다시 올려야 하는 대면 흐름이라 한 번은 서버가 삼킨다.
 */
class DispatchVisionClientTest :
    BehaviorSpec({
        val slice = ImageSlice(index = 0, base64 = "AAAA", xFrom = 0, xTo = 100)

        val validJson =
            """
            {"hasNameColumn":true,"rowIndex":2,"rowCount":13,"year":2026,"month":8,
             "visibleDays":[1,2,3],
             "cells":[{"day":1,"value":"1"},{"day":2,"value":""},{"day":3,"value":"*97"}]}
            """.trimIndent()

        fun clientWith(
            vararg responseBodies: String,
            properties: DispatchVisionProperties = DispatchVisionProperties(apiKey = "sk-test"),
        ): Pair<DispatchVisionClient, AtomicInteger> {
            val calls = AtomicInteger(0)
            val exchange =
                ExchangeFunction {
                    val body = responseBodies[minOf(calls.getAndIncrement(), responseBodies.size - 1)]
                    Mono.just(
                        ClientResponse
                            .create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(body)
                            .build(),
                    )
                }
            val webClient =
                WebClient
                    .builder()
                    .baseUrl("http://localhost")
                    .exchangeFunction(exchange)
                    .build()
            return DispatchVisionClient(properties, webClient) to calls
        }

        fun wrap(content: String) = """{"choices":[{"finish_reason":"stop","message":{"content":${jsonQuote(content)}}}]}"""

        Given("요청 본문") {
            val properties = DispatchVisionProperties(apiKey = "sk-test")
            val body = DispatchVisionClient(properties, WebClient.builder().build()).visionBody(slice, "홍길동", null)

            Then("모델이 배차 전용 설정을 따른다") {
                body["model"] shouldBe "google/gemini-3.6-flash"
            }

            Then("max_tokens가 들어간다") {
                body["max_tokens"] shouldBe 30000
            }

            Then("strict json_schema로 고정된다") {
                val format = body["response_format"] as Map<*, *>
                format["type"] shouldBe "json_schema"
                ((format["json_schema"] as Map<*, *>)["strict"]) shouldBe true
            }

            Then("집계 컬럼을 제외하라고 지시한다") {
                // 이걸 빼면 '계'를 날짜로 세어 그 뒤가 전부 밀린다.
                promptOf(body) shouldContain "계"
                promptOf(body) shouldContain "집계"
            }

            Then("빈 칸을 지어내지 말라고 지시한다") {
                promptOf(body) shouldContain "빈"
            }
        }

        Given("이름을 준 경우") {
            val body =
                DispatchVisionClient(DispatchVisionProperties(apiKey = "sk-test"), WebClient.builder().build())
                    .visionBody(slice, "홍길동", null)

            Then("이름으로 행을 찾으라고 지시한다") {
                promptOf(body) shouldContain "홍길동"
            }
        }

        Given("이름 없이 행 위치만 준 경우") {
            val body =
                DispatchVisionClient(DispatchVisionProperties(apiKey = "sk-test"), WebClient.builder().build())
                    .visionBody(slice, null, 2)

            Then("위에서 3번째 행을 읽으라고 지시한다") {
                // rowIndex는 0-based, 프롬프트는 사람이 세는 1-based로 준다.
                promptOf(body) shouldContain "3번째"
            }
        }

        Given("정상 응답") {
            val (client, calls) = clientWith(wrap(validJson))
            val result = client.read(slice, "홍길동", null)

            Then("파싱된다") {
                result?.rowIndex shouldBe 2
                result?.rowCount shouldBe 13
                result?.visibleDays shouldBe listOf(1, 2, 3)
                result?.cells?.size shouldBe 3
                result?.cells?.get(1)?.value shouldBe ""
            }

            Then("한 번만 부른다") {
                calls.get() shouldBe 1
            }
        }

        Given("첫 응답이 깨진 JSON인 경우") {
            val (client, calls) = clientWith(wrap("""{"hasNameColumn":"""), wrap(validJson))
            val result = client.read(slice, "홍길동", null)

            Then("한 번 재시도해 성공한다") {
                calls.get() shouldBe 2
                result?.rowIndex shouldBe 2
            }
        }

        Given("두 번 다 실패하는 경우") {
            val (client, calls) = clientWith(wrap("""{"broken"""))
            val result = client.read(slice, "홍길동", null)

            Then("두 번까지만 부르고 null을 준다") {
                calls.get() shouldBe 2
                result shouldBe null
            }
        }

        Given("content가 빈 응답") {
            val (client, _) = clientWith("""{"choices":[{"finish_reason":"length","message":{"content":""}}]}""")

            Then("null을 준다") {
                client.read(slice, "홍길동", null) shouldBe null
            }
        }
    })

private fun jsonQuote(raw: String): String =
    tools.jackson.databind.json.JsonMapper
        .builder()
        .build()
        .writeValueAsString(raw)

@Suppress("UNCHECKED_CAST")
private fun promptOf(body: Map<String, Any>): String {
    val messages = body["messages"] as List<Map<String, Any>>
    val content = messages[0]["content"] as List<Map<String, Any>>
    return content.first { it["type"] == "text" }["text"] as String
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.dispatch.llm.DispatchVisionClientTest"`
Expected: 컴파일 실패 — `Unresolved reference: DispatchVisionClient`

- [ ] **Step 3: 설정 클래스 작성**

`DispatchVisionProperties.kt`

```kotlin
package com.toy.backend.dispatch.llm

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * **식단용 `openrouter.*`와 분리한다.** 식단 인식은 `gemini-2.5-flash`로 잘 돌고 있고,
 * 배차표 판독 때문에 그쪽 비용을 5배로 올릴 이유가 없다.
 *
 * 표 판독에서 `2.5-flash`는 **같은 사진도 호출마다 답이 달라진다**(실측 5회에 5/9~9/9).
 * `2.5-pro`도 흔들렸다. `3.6-flash`만 6회 연속 정답과 일치했다.
 */
@ConfigurationProperties(prefix = "dispatch")
data class DispatchVisionProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val visionModel: String = "google/gemini-3.6-flash",
    /**
     * **reasoning 토큰이 이 한도에 함께 잡힌다.** `3.6-flash`는 조각 하나에
     * 1,300~3,000을 쓰므로 식단용 기본값(4,000)으로 두면 `content`가 빈 채로 온다.
     */
    val visionMaxTokens: Int = 30000,
    val timeoutSeconds: Long = 120,
    /**
     * 전체본에서 행을 찾을 때만 쓰는 대상 이름. **DB에도 응답에도 저장하지 않는다** —
     * 조회 API가 무인증으로 열려 있다.
     */
    val fatherName: String = "",
)
```

`DispatchVisionConfig.kt`

```kotlin
package com.toy.backend.dispatch.llm

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
@EnableConfigurationProperties(DispatchVisionProperties::class)
class DispatchVisionConfig {
    @Bean
    fun dispatchVisionClient(properties: DispatchVisionProperties): DispatchVisionClient {
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
                // 조각 하나가 base64로 수백 KB다. 기본 256KB 버퍼로는 응답 처리 중 터질 수 있다.
                .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
                .clientConnector(ReactorClientHttpConnector(httpClient))
                .build()

        return DispatchVisionClient(properties, webClient)
    }
}
```

- [ ] **Step 4: 클라이언트 구현**

`DispatchVisionClient.kt`

```kotlin
package com.toy.backend.dispatch.llm

import com.toy.backend.dispatch.image.ImageSlice
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private val log = KotlinLogging.logger {}

data class RecognizedCell(
    val day: Int,
    /** 칸에 보이는 그대로. **빈 칸은 빈 문자열이다** — null로 두면 스키마가 strict를 못 건다. */
    val value: String,
)

data class RecognizedSlice(
    val hasNameColumn: Boolean,
    val rowIndex: Int,
    val rowCount: Int,
    val year: Int,
    val month: Int,
    /** 이 조각에서 보이는 날짜 헤더. **집계 컬럼을 날짜로 셌는지 검증**하는 데 쓴다. */
    val visibleDays: List<Int>,
    val cells: List<RecognizedCell>,
)

/**
 * 배차표 조각 하나를 읽는다. 실패는 null로 돌려주되 **한 번은 재시도한다** —
 * 실측에서 5회 중 1회 JSON 파싱에 실패했고, 사용자가 사진을 다시 올려야 하는
 * 대면 흐름이라 그 한 번은 서버가 삼킨다. 조각당 $0.017이다.
 */
class DispatchVisionClient(
    private val properties: DispatchVisionProperties,
    private val webClient: WebClient,
) {
    fun read(
        slice: ImageSlice,
        targetName: String?,
        knownRowIndex: Int?,
    ): RecognizedSlice? {
        val body = visionBody(slice, targetName, knownRowIndex)
        repeat(MAX_ATTEMPTS) { attempt ->
            val content = post(body)
            if (content != null) {
                try {
                    return parse(content)
                } catch (e: Exception) {
                    log.warn(e) { "배차표 인식 응답 파싱 실패 (${attempt + 1}/$MAX_ATTEMPTS): $content" }
                }
            }
        }
        return null
    }

    internal fun visionBody(
        slice: ImageSlice,
        targetName: String?,
        knownRowIndex: Int?,
    ): Map<String, Any> =
        mapOf(
            "model" to properties.visionModel,
            "messages" to
                listOf(
                    mapOf(
                        "role" to "user",
                        "content" to
                            listOf(
                                mapOf("type" to "text", "text" to prompt(targetName, knownRowIndex)),
                                mapOf(
                                    "type" to "image_url",
                                    "image_url" to mapOf("url" to "data:image/png;base64,${slice.base64}"),
                                ),
                            ),
                    ),
                ),
            "response_format" to RESPONSE_FORMAT,
            // 안 보내면 잔액이 남았는데도 402가 난다(`OpenRouterProperties` 주석과 같은 함정).
            "max_tokens" to properties.visionMaxTokens,
        )

    private fun post(body: Map<String, Any>): String? =
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
                log.error { "배차표 인식이 max_tokens에 걸려 잘렸다 — 한도를 올려야 한다" }
            }
            choice
                ?.path("message")
                ?.path("content")
                ?.asString()
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            log.error(e) { "배차표 인식 호출 실패" }
            null
        }

    private fun parse(content: String): RecognizedSlice {
        val root = JsonMapper.builder().build().readTree(content)
        val visible: Iterable<JsonNode> = root.path("visibleDays")
        val cells: Iterable<JsonNode> = root.path("cells")
        return RecognizedSlice(
            hasNameColumn = root.path("hasNameColumn").asBoolean(),
            rowIndex = root.path("rowIndex").asInt(),
            rowCount = root.path("rowCount").asInt(),
            year = root.path("year").asInt(),
            month = root.path("month").asInt(),
            visibleDays = visible.map { it.asInt() },
            cells = cells.map { RecognizedCell(it.path("day").asInt(), it.path("value").asString()) },
        )
    }

    private fun prompt(
        targetName: String?,
        knownRowIndex: Int?,
    ): String {
        val rowInstruction =
            if (targetName != null) {
                """
                이 사진에는 성명 컬럼이 보인다. hasNameColumn을 true로 하라.
                '$targetName' 기사의 행을 찾아 그 행을 읽어라.
                rowIndex에는 그 행이 데이터 행 중 위에서 몇 번째인지 넣어라(맨 위 데이터 행이 0).
                """.trimIndent()
            } else {
                """
                이 사진은 표의 오른쪽 일부만 잘라낸 것이라 성명 컬럼이 보이지 않는다.
                hasNameColumn을 false로, rowIndex에는 ${knownRowIndex ?: 0}을 넣어라.
                데이터 행 중 위에서 ${(knownRowIndex ?: 0) + 1}번째 행을 읽어라.
                """.trimIndent()
            }

        return """
            이 사진은 월간 버스 배차표(엑셀)를 확대한 것이다.

            표 구조:
            - 위에서부터 [날짜 헤더 행] → [요일 행] → 기사별 데이터 행들.
            - 날짜 컬럼 사이에 '계'(주간 합계), '2주 합계', '합계', '총근무' 같은 집계 컬럼이
              끼어 있다. **이건 날짜가 아니다.** 이 컬럼들을 날짜로 세면 그 뒤가 전부 밀린다.

            $rowInstruction

            rowCount에는 이 표의 전체 데이터 행 수(기사 수)를 넣어라.
            year와 month에는 표 제목에서 읽은 연도와 월을 넣어라.

            visibleDays — 이 사진에서 보이는 날짜 헤더 숫자를 왼쪽부터 순서대로 나열하라.
            집계 컬럼은 빼라.

            cells — 지정한 행의 각 날짜 칸에 보이는 것을 그대로 value에 넣어라.

            **가장 중요한 규칙: 빈 칸은 빈 칸이다.**
            이 표는 근무일보다 빈 칸(휴무)이 더 많다. 한 행의 날짜 칸 중 절반 이상이 비어 있는
            것이 정상이다. 비어 있으면 value를 빈 문자열("")로 두어라. 숫자를 지어내지 마라.

            칸에 색(주황/초록/노랑)이 칠해져 있어도 글자가 없으면 빈 칸이다. 색칠 자체는 값이 아니다.

            값이 보이면 그 칸에서 수직으로 위로 올라가 날짜 헤더를 확인하라. 왼쪽부터 순서대로
            세다가 한 칸이라도 어긋나면 그 뒤가 전부 밀린다. 집계 컬럼은 폭이 좁고 배경이
            베이지색이니 셀 때 건너뛰어라.

            사진에서 잘려 보이지 않는 날짜는 cells에 넣지 마라. 추측해서 채우지 마라.
            """.trimIndent()
    }

    companion object {
        private const val MAX_ATTEMPTS = 2

        private val RESPONSE_FORMAT =
            mapOf(
                "type" to "json_schema",
                "json_schema" to
                    mapOf(
                        "name" to "dispatch_row",
                        "strict" to true,
                        "schema" to
                            mapOf(
                                "type" to "object",
                                "properties" to
                                    mapOf(
                                        "hasNameColumn" to mapOf("type" to "boolean"),
                                        "rowIndex" to mapOf("type" to "integer"),
                                        "rowCount" to mapOf("type" to "integer"),
                                        "year" to mapOf("type" to "integer"),
                                        "month" to mapOf("type" to "integer"),
                                        "visibleDays" to
                                            mapOf(
                                                "type" to "array",
                                                "items" to mapOf("type" to "integer"),
                                            ),
                                        "cells" to
                                            mapOf(
                                                "type" to "array",
                                                "items" to
                                                    mapOf(
                                                        "type" to "object",
                                                        "properties" to
                                                            mapOf(
                                                                "day" to mapOf("type" to "integer"),
                                                                "value" to mapOf("type" to "string"),
                                                            ),
                                                        "required" to listOf("day", "value"),
                                                        "additionalProperties" to false,
                                                    ),
                                            ),
                                    ),
                                // strict: true라 properties에 있는 키가 required에 없으면 호출 자체가 거부된다.
                                "required" to
                                    listOf(
                                        "hasNameColumn", "rowIndex", "rowCount",
                                        "year", "month", "visibleDays", "cells",
                                    ),
                                "additionalProperties" to false,
                            ),
                    ),
            )
    }
}
```

- [ ] **Step 5: 설정 추가**

`apps/daily-record/src/main/resources/application.yml`의 `openrouter:` 블록 **아래**에 추가한다. 기존 `openrouter.*`는 손대지 않는다.

```yaml
dispatch:
  api-key: ${OPENROUTER_API_KEY:}
  base-url: https://openrouter.ai/api/v1
  # 표 판독은 식단 인식과 요구가 다르다. gemini-2.5-flash는 같은 사진도 호출마다
  # 답이 달라져(실측 5회에 5/9~9/9) 쓸 수 없다. 3.6-flash만 6회 연속 정답과 일치했다.
  vision-model: ${DISPATCH_VISION_MODEL:google/gemini-3.6-flash}
  # reasoning 토큰이 이 한도에 함께 잡힌다(조각당 1,300~3,000). 식단용 4,000으로 두면
  # content가 빈 채로 온다.
  vision-max-tokens: ${DISPATCH_VISION_MAX_TOKENS:30000}
  timeout-seconds: 120
  # 전체본에서 행을 찾을 때만 쓴다. DB에도 API 응답에도 저장하지 않는다.
  father-name: ${DISPATCH_FATHER_NAME:}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.dispatch.llm.DispatchVisionClientTest"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/llm \
        apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/llm \
        apps/daily-record/src/main/resources/application.yml
git commit -m "feat: 배차표 조각을 gemini-3.6-flash로 읽는다

식단용 openrouter 설정과 분리한다. 표 판독에서 gemini-2.5-flash는 같은 사진도
호출마다 답이 달라지고(실측 5회에 5/9~9/9) 2.5-pro도 흔들렸다. 3.6-flash만
6회 연속 정답과 일치했다.

max_tokens를 30000으로 잡는다. 3.6-flash는 reasoning 토큰을 조각당 1,300~3,000
쓰는데 그 값이 이 한도에 함께 잡혀, 식단용 4,000으로 두면 content가 빈 채로 온다.

파싱 실패 시 한 번 재시도한다. 실측에서 5회 중 1회 실패했고 사용자가 사진을
다시 올려야 하는 대면 흐름이다."
```

---

### Task 6: 인식 서비스 — 조각 병합과 행 매칭

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchRecognitionService.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchDtos.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchRecognitionServiceTest.kt`

**Interfaces:**
- Consumes: `DispatchImageSlicer`(Task 4, 빈으로 주입), `DispatchVisionClient`·`RecognizedSlice`(Task 5), `DispatchRosterRepository`(Task 1)
- Produces:
  - `enum class MatchedBy { NAME, ROW_INDEX }`
  - `data class RecognitionDay(day: Int, working: Boolean, slot: Int?, note: String?, conflict: Boolean)`
  - `data class RecognitionResponse(yearMonth: String, hasNameColumn: Boolean, matchedBy: MatchedBy, rowIndex: Int, rowCount: Int, warnings: List<String>, days: List<RecognitionDay>)`
  - `DispatchRecognitionService.recognize(bytes: ByteArray): RecognitionResponse`

- [ ] **Step 1: DTO 추가**

`DispatchDtos.kt` 끝에 추가한다.

```kotlin
enum class MatchedBy { NAME, ROW_INDEX }

data class RecognitionDay(
    val day: Int,
    val working: Boolean,
    val slot: Int?,
    val note: String?,
    /** 겹친 구간에서 두 조각의 답이 갈렸다. 검수 화면이 강조한다. */
    val conflict: Boolean,
)

/** **실명·차량번호를 싣지 않는다.** 모델이 읽더라도 버린다 — 앱 로그·캐시에 남는다. */
data class RecognitionResponse(
    val yearMonth: String,
    val hasNameColumn: Boolean,
    val matchedBy: MatchedBy,
    val rowIndex: Int,
    val rowCount: Int,
    val warnings: List<String>,
    val days: List<RecognitionDay>,
)
```

- [ ] **Step 2: 실패하는 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchRecognitionServiceTest.kt`

```kotlin
package com.toy.backend.dispatch

import com.toy.backend.common.exception.CustomException
import com.toy.backend.dispatch.image.DispatchImageSlicer
import com.toy.backend.dispatch.image.ImageSlice
import com.toy.backend.dispatch.llm.DispatchVisionClient
import com.toy.backend.dispatch.llm.DispatchVisionProperties
import com.toy.backend.dispatch.llm.RecognizedCell
import com.toy.backend.dispatch.llm.RecognizedSlice
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

/**
 * 조각 둘을 하나로 합친다. **겹친 구간이 공짜 교차검증**이 된다 — 두 조각의 답이
 * 갈리면 어느 쪽도 고르지 않고 `conflict`를 달아 검수하는 사람에게 넘긴다.
 *
 * 행 매칭이 **조용히 어긋나는 것**이 이 기능의 가장 큰 위험이다. 인원이 바뀌어 행 순서가
 * 밀리면 엉뚱한 기사의 근무가 아빠 달력에 들어가고 아무도 눈치채지 못한다.
 */
class DispatchRecognitionServiceTest :
    BehaviorSpec({
        val rosterRepository = mockk<DispatchRosterRepository>(relaxed = true)
        val visionClient = mockk<DispatchVisionClient>()

        val slicer = mockk<DispatchImageSlicer>()
        every { slicer.slice(any()) } returns
            listOf(ImageSlice(0, "A", 0, 100), ImageSlice(1, "B", 90, 200))

        fun serviceWith(name: String = "홍길동") =
            DispatchRecognitionService(
                rosterRepository,
                visionClient,
                DispatchVisionProperties(apiKey = "sk-test", fatherName = name),
                slicer,
            )

        fun sliceResult(
            hasName: Boolean,
            cells: List<Pair<Int, String>>,
            rowCount: Int = 13,
            visibleDays: List<Int> = cells.map { it.first },
        ) = RecognizedSlice(
            hasNameColumn = hasName,
            rowIndex = 2,
            rowCount = rowCount,
            year = 2026,
            month = 8,
            visibleDays = visibleDays,
            cells = cells.map { RecognizedCell(it.first, it.second) },
        )

        Given("성명 컬럼이 보이는 사진") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1", 2 to "", 3 to "*97"))
            every { visionClient.read(match { it.index == 1 }, "홍길동", null) } returns
                sliceResult(true, listOf(4 to "2", 5 to ""))

            val result = serviceWith().recognize(ByteArray(1))

            Then("이름으로 매칭했다고 알린다") {
                result.matchedBy shouldBe MatchedBy.NAME
                result.hasNameColumn shouldBe true
            }

            Then("숫자는 근무 순번이 된다") {
                result.days.first { it.day == 1 }.working shouldBe true
                result.days.first { it.day == 1 }.slot shouldBe 1
            }

            Then("빈 칸은 휴무다") {
                result.days.first { it.day == 2 }.working shouldBe false
                result.days.first { it.day == 2 }.slot shouldBe null
            }

            Then("글자는 휴무이고 원문이 note로 남는다") {
                val day3 = result.days.first { it.day == 3 }
                day3.working shouldBe false
                day3.note shouldBe "*97"
            }

            Then("두 조각이 합쳐진다") {
                result.days.map { it.day } shouldBe listOf(1, 2, 3, 4, 5)
            }

            Then("행 위치를 기억한다") {
                io.mockk.verify { rosterRepository.save(any()) }
            }
        }

        Given("성명 컬럼이 없는 사진과 저장된 기준") {
            every { rosterRepository.findByYearMonth("2026-08") } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            every { visionClient.read(any(), null, 2) } returns
                sliceResult(false, listOf(10 to "2", 11 to ""))

            val result = serviceWith().recognize(ByteArray(1))

            Then("행 위치로 매칭했다고 알린다") {
                result.matchedBy shouldBe MatchedBy.ROW_INDEX
            }
        }

        Given("성명 컬럼이 없는데 저장된 기준도 없는 경우") {
            every { rosterRepository.findByYearMonth(any()) } returns null
            every { visionClient.read(any(), "홍길동", null) } returns
                sliceResult(false, listOf(10 to "2"))

            Then("거부한다 — 추측해서 저장하지 않는다") {
                val exception = shouldThrow<CustomException> { serviceWith().recognize(ByteArray(1)) }
                exception.errorCode shouldBe DispatchErrorCode.ROSTER_NOT_FOUND
            }
        }

        Given("표 인원이 바뀐 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            every { visionClient.read(any(), null, 2) } returns
                sliceResult(false, listOf(10 to "2"), rowCount = 14)

            val result = serviceWith().recognize(ByteArray(1))

            Then("경고를 단다 — 행 매칭이 조용히 어긋나는 것을 막는다") {
                result.warnings shouldBe listOf("ROW_COUNT_CHANGED")
            }
        }

        Given("겹친 구간에서 두 조각의 답이 갈린 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(5 to "1", 6 to "2"))
            every { visionClient.read(match { it.index == 1 }, "홍길동", null) } returns
                sliceResult(true, listOf(6 to "", 7 to "3"))

            val result = serviceWith().recognize(ByteArray(1))

            Then("왼쪽 조각 값을 쓰되 conflict를 단다") {
                val day6 = result.days.first { it.day == 6 }
                day6.slot shouldBe 2
                day6.conflict shouldBe true
            }

            Then("갈리지 않은 날은 conflict가 아니다") {
                result.days.first { it.day == 5 }.conflict shouldBe false
            }
        }

        Given("visibleDays와 cells가 어긋난 조각") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                // 보이는 날짜는 1~3인데 99일 칸을 냈다 — 집계 컬럼을 날짜로 센 흔적이다.
                sliceResult(true, listOf(1 to "1", 99 to "4"), visibleDays = listOf(1, 2, 3))
            every { visionClient.read(match { it.index == 1 }, "홍길동", null) } returns
                sliceResult(true, listOf(4 to "2"))

            val result = serviceWith().recognize(ByteArray(1))

            Then("범위 밖 칸은 버린다") {
                result.days.none { it.day == 99 } shouldBe true
            }
        }

        Given("모든 조각이 인식에 실패한 경우") {
            every { rosterRepository.findByYearMonth(any()) } returns null
            every { visionClient.read(any(), any(), any()) } returns null

            Then("인식 실패를 알린다") {
                val exception = shouldThrow<CustomException> { serviceWith().recognize(ByteArray(1)) }
                exception.errorCode shouldBe DispatchErrorCode.VISION_UNAVAILABLE
            }
        }

        Given("대상 이름이 설정되지 않은 경우") {
            Then("거부한다 — 이름 없이 부르면 아무 행이나 읽어 온다") {
                val exception = shouldThrow<CustomException> { serviceWith(name = "").recognize(ByteArray(1)) }
                exception.errorCode shouldBe DispatchErrorCode.TARGET_NAME_NOT_CONFIGURED
            }
        }
    })
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.dispatch.DispatchRecognitionServiceTest"`
Expected: 컴파일 실패 — `Unresolved reference: DispatchRecognitionService`

- [ ] **Step 4: 구현**

`DispatchRecognitionService.kt`

```kotlin
package com.toy.backend.dispatch

import com.toy.backend.common.exception.CustomException
import com.toy.backend.dispatch.image.DispatchImageSlicer
import com.toy.backend.dispatch.image.DispatchImageSlicer
import com.toy.backend.dispatch.image.ImageSlice
import com.toy.backend.dispatch.llm.DispatchVisionClient
import com.toy.backend.dispatch.llm.DispatchVisionProperties
import com.toy.backend.dispatch.llm.RecognizedSlice
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth

/**
 * 사진 한 장을 조각으로 나눠 읽고 하나로 합친다. **아무것도 저장하지 않는다** —
 * `DispatchRoster`(행 위치)만 갱신하고, 근무 값은 검수를 거쳐 별도 API로 저장된다.
 * 인식 결과를 바로 저장하면 틀린 값이 조용히 박히고 달력은 틀렸다는 사실조차 알려주지 않는다.
 *
 * `DispatchImageSlicer`를 주입받는다 — 테스트에서 실제 이미지 처리를 건너뛰기 위해서다.
 * 람다에 기본값을 주는 형태로 두면 Spring이 함수 타입 빈을 찾다 기동에 실패한다.
 */
@Service
class DispatchRecognitionService(
    private val rosterRepository: DispatchRosterRepository,
    private val visionClient: DispatchVisionClient,
    private val properties: DispatchVisionProperties,
    private val slicer: DispatchImageSlicer,
) {
    @Transactional
    fun recognize(bytes: ByteArray): RecognitionResponse {
        val targetName =
            properties.fatherName.takeIf { it.isNotBlank() }
                // 이름 없이 부른 프롬프트는 아무 행이나 읽어 온다.
                ?: throw CustomException(DispatchErrorCode.TARGET_NAME_NOT_CONFIGURED)

        val slices = slicer.slice(bytes)

        // 첫 조각으로 「성명 컬럼이 보이는가」와 연·월을 정한다. 왼쪽 조각에 성명 컬럼이 있다.
        val first =
            visionClient.read(slices.first(), targetName, null)
                ?: throw CustomException(DispatchErrorCode.VISION_UNAVAILABLE)

        val yearMonth = YearMonth.of(first.year, first.month)
        val roster = rosterRepository.findByYearMonth(yearMonth.toString())

        val matchedBy = if (first.hasNameColumn) MatchedBy.NAME else MatchedBy.ROW_INDEX
        val rowIndex =
            when {
                first.hasNameColumn -> first.rowIndex
                roster != null -> roster.rowIndex
                // 성명 컬럼도 없고 기준도 없으면 어느 줄이 대상인지 알 방법이 없다.
                else -> throw CustomException(DispatchErrorCode.ROSTER_NOT_FOUND, yearMonth.toString())
            }

        val rest =
            slices.drop(1).mapNotNull { slice ->
                if (first.hasNameColumn) {
                    visionClient.read(slice, targetName, null)
                } else {
                    visionClient.read(slice, null, rowIndex)
                }
            }

        val results = listOf(first) + rest
        val warnings = mutableListOf<String>()
        // 인원이 바뀌면 행 순서가 밀려 엉뚱한 기사의 근무가 들어온다.
        if (roster != null && roster.rowCount != first.rowCount) {
            warnings += "ROW_COUNT_CHANGED"
        }

        upsertRoster(yearMonth.toString(), rowIndex, first.rowCount, roster)

        return RecognitionResponse(
            yearMonth = yearMonth.toString(),
            hasNameColumn = first.hasNameColumn,
            matchedBy = matchedBy,
            rowIndex = rowIndex,
            rowCount = first.rowCount,
            warnings = warnings,
            days = merge(results, yearMonth),
        )
    }

    private fun upsertRoster(
        yearMonth: String,
        rowIndex: Int,
        rowCount: Int,
        existing: DispatchRoster?,
    ) {
        if (existing == null) {
            rosterRepository.save(DispatchRoster(yearMonth, rowIndex, rowCount))
        } else {
            existing.rowIndex = rowIndex
            existing.rowCount = rowCount
        }
    }

    /**
     * 조각들을 날짜 기준으로 합친다. **겹친 구간이 공짜 교차검증이다** — 값이 갈리면
     * 어느 쪽도 고르지 않고 왼쪽 조각 값을 쓰되 `conflict`를 달아 사람에게 넘긴다.
     */
    private fun merge(
        results: List<RecognizedSlice>,
        yearMonth: YearMonth,
    ): List<RecognitionDay> {
        val merged = LinkedHashMap<Int, RecognitionDay>()

        results.forEach { slice ->
            val visible = slice.visibleDays.toSet()
            slice.cells.forEach { cell ->
                // 집계 컬럼을 날짜로 셌으면 visibleDays 밖의 날짜가 나온다. 그 칸은 버린다.
                if (cell.day !in visible) return@forEach
                if (cell.day !in 1..yearMonth.lengthOfMonth()) return@forEach

                val parsed = toDay(cell.day, cell.value)
                val existing = merged[cell.day]
                merged[cell.day] =
                    when {
                        existing == null -> parsed
                        existing.working == parsed.working &&
                            existing.slot == parsed.slot &&
                            existing.note == parsed.note -> existing
                        // 왼쪽 조각 값을 유지하고 갈렸다는 사실만 남긴다.
                        else -> existing.copy(conflict = true)
                    }
            }
        }

        return merged.values.sortedBy { it.day }
    }

    /**
     * 칸 하나를 해석한다. 숫자면 근무 순번, 글자면 휴무 + 원문, 빈 칸이면 휴무다.
     * `휴`·`간담회`·`예비군`이 근무가 아니라는 것은 합의된 규칙이다.
     */
    private fun toDay(
        day: Int,
        raw: String,
    ): RecognitionDay {
        val value = raw.trim()
        val slot = value.toIntOrNull()
        return RecognitionDay(
            day = day,
            working = slot != null,
            slot = slot,
            note = value.takeIf { it.isNotEmpty() && slot == null },
            conflict = false,
        )
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.dispatch.DispatchRecognitionServiceTest"`
Expected: PASS (8개 시나리오)

- [ ] **Step 6: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/dispatch \
        apps/daily-record/src/test/kotlin/com/toy/backend/dispatch
git commit -m "feat: 배차표 조각을 합쳐 인식 결과를 만든다

아무것도 저장하지 않는다. 행 위치만 갱신하고 근무 값은 검수를 거쳐 별도 API로
저장된다. 인식 결과를 바로 저장하면 틀린 값이 조용히 박히고, 달력은 틀렸다는
사실조차 알려주지 않는다.

겹친 구간을 교차검증에 쓴다. 두 조각의 답이 갈리면 어느 쪽도 고르지 않고
왼쪽 값을 쓰되 conflict를 달아 검수하는 사람에게 넘긴다.

성명 컬럼도 없고 저장된 기준도 없으면 거부한다. 어느 줄이 대상인지 알 방법이
없는데 추측해서 저장하면 엉뚱한 기사의 근무가 조용히 들어온다."
```

---

### Task 7: 컨트롤러와 확정 저장

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchCommandService.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchController.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchCommandServiceTest.kt`

**Interfaces:**
- Consumes: Task 1의 저장소, Task 3의 DTO, Task 6의 `DispatchRecognitionService`
- Produces:
  - `DispatchCommandService.saveShifts(request: ShiftSaveRequest)`
  - `DispatchCommandService.savePattern(role: DispatchRole, request: PatternSaveRequest): PatternResponse`

- [ ] **Step 1: 실패하는 테스트 작성**

`apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchCommandServiceTest.kt`

```kotlin
package com.toy.backend.dispatch

import com.toy.backend.common.exception.CustomException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate

/**
 * **보낸 날짜만 갱신한다.** 월 전체를 지우고 다시 넣으면 이전에 확정한 날짜가 사라진다 —
 * 잘린 변경분 사진은 그 달의 일부만 담고 있다.
 */
class DispatchCommandServiceTest :
    BehaviorSpec({
        val shiftRepository = mockk<DispatchShiftRepository>(relaxed = true)
        val patternRepository = mockk<DispatchPatternRepository>(relaxed = true)
        val service = DispatchCommandService(shiftRepository, patternRepository)

        Given("새 날짜를 저장할 때") {
            every { shiftRepository.findByRoleAndWorkDate(any(), any()) } returns null

            service.saveShifts(
                ShiftSaveRequest(
                    role = DispatchRole.FATHER,
                    days = listOf(ShiftSaveDay(LocalDate.of(2026, 8, 1), working = true, slot = 1, note = null)),
                ),
            )

            Then("새 행이 생긴다") {
                val saved = slot<DispatchShift>()
                verify { shiftRepository.save(capture(saved)) }
                saved.captured.workDate shouldBe LocalDate.of(2026, 8, 1)
                saved.captured.working shouldBe true
                saved.captured.slot shouldBe 1
            }
        }

        Given("이미 저장된 날짜를 다시 보낼 때") {
            val existing =
                DispatchShift(DispatchRole.FATHER, LocalDate.of(2026, 8, 1), working = false, slot = null, note = "휴")
            every {
                shiftRepository.findByRoleAndWorkDate(DispatchRole.FATHER, LocalDate.of(2026, 8, 1))
            } returns existing

            service.saveShifts(
                ShiftSaveRequest(
                    role = DispatchRole.FATHER,
                    days = listOf(ShiftSaveDay(LocalDate.of(2026, 8, 1), working = true, slot = 2, note = null)),
                ),
            )

            Then("기존 행이 갱신된다") {
                existing.working shouldBe true
                existing.slot shouldBe 2
                existing.note shouldBe null
            }

            Then("새로 만들지 않는다") {
                verify(exactly = 0) { shiftRepository.save(any()) }
            }
        }

        Given("패턴을 저장할 때") {
            every { patternRepository.findByRole(DispatchRole.MOTHER) } returns null

            val response =
                service.savePattern(
                    DispatchRole.MOTHER,
                    PatternSaveRequest(cycleDays = 3, workingOffsets = listOf(1, 2), anchorDate = LocalDate.of(2026, 8, 8)),
                )

            Then("오프셋이 문자열로 저장되고 목록으로 돌아온다") {
                response.workingOffsets shouldBe listOf(1, 2)
                response.cycleDays shouldBe 3
            }
        }

        Given("주기를 벗어난 오프셋을 보낼 때") {
            every { patternRepository.findByRole(DispatchRole.MOTHER) } returns null

            Then("거부한다 — 영원히 도달하지 않는 오프셋은 조용히 무시된다") {
                val exception =
                    shouldThrow<CustomException> {
                        service.savePattern(
                            DispatchRole.MOTHER,
                            PatternSaveRequest(cycleDays = 3, workingOffsets = listOf(1, 5), anchorDate = LocalDate.of(2026, 8, 8)),
                        )
                    }
                exception.errorCode shouldBe DispatchErrorCode.INVALID_PATTERN
            }
        }
    })
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :daily-record:test --tests "com.toy.backend.dispatch.DispatchCommandServiceTest"`
Expected: 컴파일 실패 — `Unresolved reference: DispatchCommandService`

- [ ] **Step 3: 구현**

`DispatchCommandService.kt`

```kotlin
package com.toy.backend.dispatch

import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DispatchCommandService(
    private val shiftRepository: DispatchShiftRepository,
    private val patternRepository: DispatchPatternRepository,
) {
    /**
     * **보낸 날짜만 갱신한다.** 월 전체를 지우고 다시 넣으면 이전에 확정한 날짜가 사라진다 —
     * 잘린 변경분 사진은 그 달의 일부만 담는다.
     */
    fun saveShifts(request: ShiftSaveRequest) {
        request.days.forEach { day ->
            val existing = shiftRepository.findByRoleAndWorkDate(request.role, day.date)
            if (existing == null) {
                shiftRepository.save(
                    DispatchShift(
                        role = request.role,
                        workDate = day.date,
                        working = day.working,
                        slot = day.slot,
                        note = day.note,
                    ),
                )
            } else {
                existing.working = day.working
                existing.slot = day.slot
                existing.note = day.note
            }
        }
    }

    fun savePattern(
        role: DispatchRole,
        request: PatternSaveRequest,
    ): PatternResponse {
        // 주기 밖 오프셋은 영원히 도달하지 않아 조용히 무시된다. 저장 시점에 막는다.
        val invalid = request.workingOffsets.filterNot { it in 0 until request.cycleDays }
        if (invalid.isNotEmpty()) {
            throw CustomException(DispatchErrorCode.INVALID_PATTERN, "주기(${request.cycleDays}) 밖 오프셋 $invalid")
        }

        val offsets = request.workingOffsets.sorted().joinToString(",")
        val pattern = patternRepository.findByRole(role)
        val saved =
            if (pattern == null) {
                patternRepository.save(
                    DispatchPattern(
                        role = role,
                        cycleDays = request.cycleDays,
                        workingOffsets = offsets,
                        anchorDate = request.anchorDate,
                    ),
                )
            } else {
                pattern.cycleDays = request.cycleDays
                pattern.workingOffsets = offsets
                pattern.anchorDate = request.anchorDate
                pattern
            }

        return PatternResponse(
            role = saved.role,
            cycleDays = saved.cycleDays,
            workingOffsets = saved.workingOffsetList,
            anchorDate = saved.anchorDate,
        )
    }
}
```

- [ ] **Step 4: 컨트롤러 작성**

`DispatchController.kt`

```kotlin
package com.toy.backend.dispatch

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate

@Tag(name = "근무 달력", description = "배차표 사진 인식 → 검수 → 확정 저장, 그리고 무인증 조회")
@RestController
@RequestMapping("/dispatch")
class DispatchController(
    private val queryService: DispatchQueryService,
    private val commandService: DispatchCommandService,
    private val recognitionService: DispatchRecognitionService,
) {
    /** **무인증으로 열린 단 하나의 엔드포인트다.** 응답에 실명·차량번호가 없다. */
    @GetMapping("/shifts")
    @Operation(summary = "기간 근무 조회 — 아빠(확정분)와 엄마(패턴)를 합쳐 반환")
    fun findShifts(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<DataResponseBody<ShiftRangeResponse>> =
        ResponseEntity.ok(DataResponseBody(queryService.findRange(from, to)))

    @PostMapping("/recognitions")
    @Operation(summary = "배차표 사진 인식 — 저장하지 않고 결과만 준다(검수용)")
    fun recognize(
        @RequestPart("file") file: MultipartFile,
    ): ResponseEntity<DataResponseBody<RecognitionResponse>> =
        ResponseEntity.ok(DataResponseBody(recognitionService.recognize(file.bytes)))

    @PostMapping("/shifts")
    @Operation(summary = "검수 확정분 저장 — 보낸 날짜만 갱신한다")
    fun saveShifts(
        @Valid @RequestBody request: ShiftSaveRequest,
    ): ResponseEntity<Void> {
        commandService.saveShifts(request)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/patterns/{role}")
    @Operation(summary = "반복 근무 패턴 등록·수정")
    fun savePattern(
        @PathVariable role: DispatchRole,
        @Valid @RequestBody request: PatternSaveRequest,
    ): ResponseEntity<DataResponseBody<PatternResponse>> =
        ResponseEntity.ok(DataResponseBody(commandService.savePattern(role, request)))
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :daily-record:test`
Expected: PASS — 이 태스크의 테스트와 앞선 모든 테스트

- [ ] **Step 6: 전체 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — ktlint 포맷 검사 포함

- [ ] **Step 7: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/dispatch \
        apps/daily-record/src/test/kotlin/com/toy/backend/dispatch
git commit -m "feat: 근무 달력 엔드포인트를 연다

확정 저장은 보낸 날짜만 갱신한다. 월 전체를 지우고 다시 넣으면 이전에 확정한
날짜가 사라진다 — 잘린 변경분 사진은 그 달의 일부만 담는다.

패턴 저장 시 주기 밖 오프셋을 막는다. 영원히 도달하지 않아 조용히 무시된다."
```

---

## 수동 검증

자동 테스트로는 실제 인식 품질을 알 수 없다. 배포 전에 한 번 돌린다.

- [ ] `DISPATCH_FATHER_NAME`과 `OPENROUTER_API_KEY`를 설정하고 앱을 띄운다.
- [ ] 실제 배차표 사진(성명 컬럼이 보이는 확대 캡처)으로 `POST /dispatch/recognitions`를 호출한다.
- [ ] 응답의 `matchedBy`가 `NAME`인지, `rowIndex`·`rowCount`가 사진과 맞는지 확인한다.
- [ ] `days`를 사진과 대조한다. **빈 칸이 빈 칸으로 나오는지**가 핵심이다.
- [ ] 응답 어디에도 실명·차량번호가 없는지 확인한다.
- [ ] 잘린 변경분 사진으로 다시 호출해 `matchedBy`가 `ROW_INDEX`인지 확인한다.
- [ ] `PUT /dispatch/patterns/MOTHER`로 `cycleDays=3, workingOffsets=[1,2], anchorDate=2026-08-08`을 저장한다.
- [ ] 로그아웃 상태(토큰 없이)로 `GET /dispatch/shifts?from=2026-08-01&to=2026-08-31`이 200을 주는지 확인한다.
- [ ] 그 응답에서 엄마 휴무가 `2, 5, 8, 11, 14, 17, 20, 23, 26, 29`인지 확인한다.
- [ ] 토큰 없이 `POST /dispatch/shifts`가 401인지 확인한다.

---

## 다음 계획

이 계획은 서버만 다룬다. 서버가 배포된 뒤 아래 두 계획을 이어서 쓴다.

- **웹 조회 달력** (`toy-repo/apps/daily-record`) — `/shift` 무인증 라우트, 역할별 색 뱃지
- **iOS 업로드·검수** (`woori-haru`) — 사진 업로드, 사진과 나란히 놓고 수정, 확정 저장
