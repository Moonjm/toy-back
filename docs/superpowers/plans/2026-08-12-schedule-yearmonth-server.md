# 배차 인식 연월 자동 판별 — 서버 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배차표 사진에서 읽은 연월을 기준으로 삼아, 앱이 연월을 보내지 않아도 인식이 되게 한다.

**Architecture:** 지금은 `연월(요청) → 저장된 줄 위치 조회 → 사진 읽기` 순서라 사진을 읽기 전에 연월이 필요하다. 첫 조각을 읽는 probe가 이미 `year`·`month`를 주므로 `사진 첫 조각 읽기 → 연월 확정 → 줄 위치 조회` 순서로 바꾸면 순환이 풀린다. 요청 `yearMonth`는 선택이 되고, 사진에서도 못 읽으면 「미상」으로 응답해 앱의 검수 화면이 채운다.

**Tech Stack:** Kotlin, Spring Boot, Kotest `BehaviorSpec`, mockk

## Global Constraints

- 설계 문서: `woori-haru/docs/superpowers/specs/2026-08-12-schedule-calendar-design.md`의 「연월」 절
- 커밋 전 `./gradlew spotlessApply` 필수 (ktlint)
- 테스트는 Kotest `BehaviorSpec` + mockk. 이 저장소에는 `@SpringBootTest`·`@DataJpaTest`가 **하나도 없다** — 파생 쿼리는 테스트로 검증되지 않으므로 컴파일과 이름 규칙으로만 지킨다
- 커밋 메시지는 **한국어**
- 필드를 더했으면 `grep -rln <이름> --include='*.kt'`로 **응답으로 나가는 타입(`*Dtos.kt`)이 목록에 있는지** 확인한다. 서비스에만 있으면 앱까지 가지 않는다
- 테스트 실행: `./gradlew :daily-record:test --tests "*DispatchRecognitionServiceTest*"`
- 전체 실행: `./gradlew :daily-record:test`

---

## 파일 구조

| 파일 | 책임 | 이 계획에서 |
|---|---|---|
| `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchRecognitionService.kt` | 조각을 읽고 합치고, 기준 연월과 줄 위치를 정한다 | 순서 재배치, 연월 선택화, 미상 처리 |
| `.../dispatch/DispatchController.kt` | HTTP 경계 | `yearMonth`를 선택 파라미터로 |
| `.../dispatch/DispatchDtos.kt` | 응답 모양 | `RecognitionResponse.yearMonth`를 nullable로 |
| `.../dispatch/DispatchRosterRepository.kt` | 줄 위치 조회 | 최근 기준 조회 추가 |
| `apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchRecognitionServiceTest.kt` | 위 동작 검증 | 태스크마다 추가 |

---

### Task 1: 저장된 줄 위치를 사진을 읽은 뒤에 조회한다

지금은 `rosterRepository.findByYearMonth(yearMonth)`가 `visionClient.read(...)`보다 **앞**에 있다. 연월을 사진에서 얻으려면 이 순서가 뒤집혀야 한다. 이 태스크는 **동작을 바꾸지 않고 순서만** 옮긴다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchRecognitionService.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchRecognitionServiceTest.kt`

**Interfaces:**
- Consumes: 없음
- Produces: `recognize`의 본문 순서가 `probe → roster 조회 → 나머지 조각`이 된다. Task 2가 이 사이에 연월 확정을 끼워 넣는다.

- [ ] **Step 1: 순서를 고정하는 테스트를 쓴다**

`DispatchRecognitionServiceTest.kt`의 마지막 `Given` 블록 뒤에 추가한다.

```kotlin
        Given("사진을 읽기 전에는 기준을 조회할 수 없는 구조") {
            // 연월을 사진에서 읽으려면 **읽은 뒤에** 그 달의 기준을 찾아야 한다.
            // 순서가 뒤집혀 있으면 「연월을 알아야 기준을 찾고, 기준을 찾아야 읽는다」는
            // 순환에 다시 빠진다.
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"))
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(4 to "2"))

            serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

            Then("첫 조각을 읽은 뒤에 기준을 조회한다") {
                verifyOrder {
                    visionClient.read(match { it.index == 0 }, "홍길동", null)
                    rosterRepository.findByYearMonth("2026-08")
                }
            }
        }
```

import에 `io.mockk.verifyOrder`를 더한다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "*DispatchRecognitionServiceTest*"`
Expected: FAIL — `Verification failed: calls are not in order`

- [ ] **Step 3: 조회를 아래로 옮긴다**

`recognize` 안에서 이 줄을 지운다(현재 probe 위에 있다).

```kotlin
        val roster = rosterRepository.findByYearMonth(yearMonth.toString())
```

그리고 `probe`의 두 검증(`hasNameColumn && !targetFound` → `TARGET_NOT_FOUND`, `hasNameColumn && !isRowWithinTable(...)` → `VISION_UNAVAILABLE`) **아래**, `val matchedBy = ...` **위**에 넣는다.

```kotlin
        // **기준 조회는 사진을 읽은 뒤다.** 연월을 사진에서 읽으려면 이 순서여야 한다
        // — 반대로 두면 「연월을 알아야 기준을 찾고, 기준을 찾아야 읽는다」가 된다.
        val roster = rosterRepository.findByYearMonth(yearMonth.toString())
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests "*DispatchRecognitionServiceTest*"`
Expected: PASS — 기존 테스트도 전부 통과해야 한다. 하나라도 깨지면 순서 이동이 동작을 바꾼 것이므로 되돌리고 원인을 찾는다.

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchRecognitionService.kt \
        apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchRecognitionServiceTest.kt
git commit -m "refactor: 저장된 줄 위치를 사진을 읽은 뒤에 조회한다"
```

---

### Task 2: 연월을 선택으로 받고 사진에서 읽은 값을 기준으로 쓴다

요청 `yearMonth`를 선택으로 바꾸고, 없으면 사진에서 읽은 연월을 기준으로 삼는다. 둘 다 없으면 **미상**으로 두고 응답 `yearMonth`를 `null`로 내보낸다.

미상일 때의 규칙 세 가지가 이 태스크의 핵심이다.

1. 날짜 범위 검사는 `1..31`로 둔다 — 그 달의 마지막 날을 알 수 없다
2. 줄 위치를 **갱신하지 않는다** — 어느 달의 기준인지 적을 수 없다
3. 에러 메시지의 `%s`에는 `"연월 미상"`을 넣는다

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchRecognitionService.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchController.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchDtos.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchRecognitionServiceTest.kt`

**Interfaces:**
- Consumes: Task 1이 만든 `probe → roster 조회` 순서
- Produces:
  - `DispatchRecognitionService.recognize(bytes: ByteArray, yearMonth: YearMonth?): RecognitionResponse`
  - `RecognitionResponse.yearMonth: String?`
  - 컨트롤러 `POST /dispatch/recognitions`의 `yearMonth`가 선택 파라미터

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```kotlin
        Given("연월 없이 올린 사진") {
            every { rosterRepository.findByYearMonth("2026-09") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), year = 2026, month = 9)
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(4 to "2"), year = 2026, month = 9)

            val result = serviceWith().recognize(ByteArray(1), null)

            Then("사진에서 읽은 연월이 기준이 된다") {
                result.yearMonth shouldBe "2026-09"
            }

            Then("그 달의 기준을 조회한다") {
                verify { rosterRepository.findByYearMonth("2026-09") }
            }

            Then("연월이 어긋났다는 경고는 붙지 않는다") {
                // 비교할 요청값이 없다. 사진값이 곧 기준이다.
                result.warnings shouldBe emptyList()
            }
        }

        Given("요청 연월과 사진 연월이 둘 다 있고 서로 다른 경우") {
            every { rosterRepository.findByYearMonth("2026-08") } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), year = 2026, month = 9)
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(4 to "2"), year = 2026, month = 9)

            val result = serviceWith().recognize(ByteArray(1), YearMonth.of(2026, 8))

            Then("요청값이 기준이다") {
                // 앱이 명시했으면 그 뜻을 따른다. 사진 제목은 교차 확인용이다.
                result.yearMonth shouldBe "2026-08"
            }

            Then("어긋났다고 경고한다") {
                result.warnings shouldBe listOf("YEAR_MONTH_MISMATCH")
            }
        }

        Given("연월도 없고 사진에서도 못 읽은 경우") {
            every { rosterRepository.findByYearMonth(any()) } returns null
            // 제목이 잘린 사진. 모델이 0을 돌려준다.
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1", 31 to "2"), year = 0, month = 0)
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(4 to "2"), year = 0, month = 0)

            val result = serviceWith().recognize(ByteArray(1), null)

            Then("응답 연월이 비어 있다") {
                // 앱의 검수 화면이 채운다. 채우기 전에는 저장이 잠긴다.
                result.yearMonth shouldBe null
            }

            Then("31일도 살아남는다") {
                // 그 달의 마지막 날을 알 수 없으므로 1..31로 둔다. 좁게 잡으면
                // 31일이 있는 달의 마지막 날이 조용히 사라진다.
                result.days.map { it.day } shouldBe listOf(1, 31, 4)
            }

            Then("줄 위치를 갱신하지 않는다") {
                // 어느 달의 기준인지 적을 수 없다. 미상인 채로 저장하면 이후 사진이
                // 전부 그 값을 되쓴다.
                verify(exactly = 0) { rosterUpdater.upsert(any(), any(), any()) }
            }
        }
```

import에 `io.mockk.verify`를 더한다.

**주의:** `rosterUpdater`는 `relaxed = true` mock이고 다른 `Given` 블록에서도 호출된다. `verify(exactly = 0)`는 그 호출까지 세어 실패할 수 있다. 이 `Given` 블록 첫 줄에 `clearMocks(rosterUpdater, answers = false)`를 넣어 호출 기록만 지운다. import는 `io.mockk.clearMocks`.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "*DispatchRecognitionServiceTest*"`
Expected: 컴파일 실패 — `recognize`가 `YearMonth?`를 받지 않는다

- [ ] **Step 3: 응답 DTO를 nullable로 바꾼다**

`DispatchDtos.kt`의 `RecognitionResponse`:

```kotlin
/** **실명·차량번호를 싣지 않는다.** 모델이 읽더라도 버린다 — 앱 로그·캐시에 남는다. */
data class RecognitionResponse(
    /** 사진에서도 읽지 못하고 요청에도 없으면 `null`이다. 앱의 검수 화면이 채운다. */
    val yearMonth: String?,
    val hasNameColumn: Boolean,
    val matchedBy: MatchedBy,
    val rowIndex: Int,
    val rowCount: Int,
    val warnings: List<String>,
    val days: List<RecognitionDay>,
)
```

- [ ] **Step 4: 컨트롤러의 파라미터를 선택으로 바꾼다**

`DispatchController.kt`의 `recognize`:

```kotlin
    /**
     * **`yearMonth`는 선택이다.** 배차표 사진 위에 연월이 적혀 있고 모델이 그것을 읽으므로,
     * 앱은 보내지 않는다. 자리를 남겨 두는 것은 웹처럼 어느 달인지 이미 아는 호출자를 위해서다.
     * 보내면 그 값이 기준이 되고 사진 제목은 교차 확인용으로만 쓰인다.
     *
     * **`YearMonth`로 받아 Spring이 변환하게 둔다.** 본문에서 `YearMonth.parse`를 부르면
     * 오타 하나에 `DateTimeParseException`이 공통 핸들러의 500으로 떨어져 서버 결함처럼 보인다.
     */
    @PostMapping("/recognitions")
    @Operation(summary = "배차표 사진 인식 — 저장하지 않고 결과만 준다(검수용)")
    fun recognize(
        @RequestPart("file") file: MultipartFile,
        @RequestParam(required = false) yearMonth: YearMonth?,
    ): ResponseEntity<DataResponseBody<RecognitionResponse>> =
        ResponseEntity.ok(DataResponseBody(recognitionService.recognize(file.bytes, yearMonth)))
```

- [ ] **Step 5: 서비스에서 기준 연월을 정한다**

`recognize`의 시그니처를 바꾸고 KDoc의 첫 문단을 교체한다.

```kotlin
    /**
     * **연월은 선택이다.** 사진 제목에서 읽은 값을 기준으로 쓴다 — probe가 이미 `year`·`month`를
     * 주므로 「사진을 읽기 전에는 어느 달 기준을 조회할지 모른다」는 순환은 읽는 순서를 바꿔
     * 푼다. 요청으로 받은 값이 있으면 그쪽이 기준이고 사진 제목은 교차 확인용이다.
     *
     * 둘 다 없으면 **미상**으로 둔다. 날짜 범위를 좁힐 수 없고 줄 위치도 갱신할 수 없으므로,
     * 응답 연월을 비워 검수 화면이 채우게 한다.
     *
     * **트랜잭션으로 감싸지 않는다.** 조각당 최대 2회, 최대 3조각을 `timeoutSeconds = 120`으로
     * 읽으므로 감싸면 최악의 경우 DB 커넥션을 12분 붙잡는다. DB를 건드리는 것은 기준 조회
     * 한 번과 `DispatchRosterUpdater` 호출 한 번뿐이고, 각자 자기 트랜잭션에서 돈다.
     */
    fun recognize(
        bytes: ByteArray,
        yearMonth: YearMonth?,
    ): RecognitionResponse {
```

probe의 두 검증 **아래**(Task 1에서 roster 조회를 넣은 자리 바로 위)에 사진 연월 계산을 옮겨 넣는다. 지금 `warnings` 근처에 있는 `photoYearMonth` 블록을 **잘라내어** 여기로 옮긴다.

```kotlin
        // **`YearMonth.of`에 넣기 전에 범위를 확인한다.** strict 스키마는 정수라는 것만 보장하므로
        // `month = 13` 같은 값이 오면 `DateTimeException`이 나는데, 이는 `DispatchVisionClient`의
        // 실패 처리 바깥이라 그대로 500이 된다. 사진 제목을 잘못 읽은 것뿐인데 서버 결함처럼 보인다.
        val photoYearMonth =
            if (probe.month in 1..12 && probe.year in PLAUSIBLE_YEARS) {
                YearMonth.of(probe.year, probe.month)
            } else {
                if (probe.year != 0 || probe.month != 0) {
                    log.warn { "배차표 제목의 연월을 해석할 수 없다: year=${probe.year}, month=${probe.month}" }
                }
                null
            }

        // 요청값이 있으면 그쪽이 기준이다. 없으면 사진에서 읽은 값을 쓴다. 둘 다 없으면 미상이다.
        val effectiveYearMonth = yearMonth ?: photoYearMonth

        // 에러 메시지의 `%s` 자리. 미상이어도 문장이 성립해야 한다.
        val yearMonthLabel = effectiveYearMonth?.toString() ?: "연월 미상"

        // **기준 조회는 사진을 읽은 뒤다.** 연월을 사진에서 읽으려면 이 순서여야 한다
        // — 반대로 두면 「연월을 알아야 기준을 찾고, 기준을 찾아야 읽는다」가 된다.
        val roster = effectiveYearMonth?.let { rosterRepository.findByYearMonth(it.toString()) }
```

`TARGET_NOT_FOUND`·`ROSTER_NOT_FOUND`의 인자를 바꾼다. **`TARGET_NOT_FOUND`는 `photoYearMonth` 계산보다 위에 있으므로 그 검증 블록을 `yearMonthLabel` 아래로 함께 옮긴다.**

```kotlin
        throw CustomException(DispatchErrorCode.TARGET_NOT_FOUND, yearMonthLabel)
```
```kotlin
                else -> throw CustomException(DispatchErrorCode.ROSTER_NOT_FOUND, yearMonthLabel)
```

`warnings` 블록에서 사진 연월 계산을 **지우고** 비교만 남긴다.

```kotlin
        // 사진의 달과 요청한 달이 다르면 엉뚱한 달에 저장된다. 요청값이 없으면 비교할 것이
        // 없다 — 사진값이 곧 기준이므로 어긋날 수가 없다.
        if (yearMonth != null && photoYearMonth != null && photoYearMonth != yearMonth) {
            warnings += "YEAR_MONTH_MISMATCH"
        }
```

줄 위치 갱신 조건에 미상 제외를 더한다.

```kotlin
        // **새로 배운 것이 있고 의심할 근거가 없을 때만 갱신한다.** 경고가 붙은 사진(9월 사진을
        // 8월로 올린 경우 등)에서 읽은 값으로 덮으면 검수 화면에서 취소해도 되돌아가지 않고,
        // 이후 잘린 사진이 전부 틀린 행을 읽는다. `ROW_INDEX` 모드는 기존 기준을 되쓴 것이라
        // 새로 배운 것이 없다 — 여기서 `rowCount`를 덮으면 경고가 한 번만 뜨고 사라진다.
        //
        // **연월이 미상이면 갱신하지 않는다.** 기준은 연월로 찾으므로 어느 달의 것인지
        // 적을 수 없다.
        if (matchedBy == MatchedBy.NAME && warnings.isEmpty() && effectiveYearMonth != null) {
            rosterUpdater.upsert(effectiveYearMonth.toString(), rowIndex, rowCount)
        }
```

응답과 `merge` 호출을 바꾼다.

```kotlin
        return RecognitionResponse(
            yearMonth = effectiveYearMonth?.toString(),
            hasNameColumn = probe.hasNameColumn,
            matchedBy = matchedBy,
            rowIndex = rowIndex,
            rowCount = rowCount,
            warnings = warnings,
            days = merge(results, effectiveYearMonth),
        )
```

`merge`의 시그니처와 날짜 범위를 바꾼다.

```kotlin
    private fun merge(
        results: List<RecognizedSlice>,
        yearMonth: YearMonth?,
    ): List<RecognitionDay> {
        val merged = LinkedHashMap<Int, RecognitionDay>()
        // 연월이 미상이면 그 달의 마지막 날을 알 수 없다. 좁게 잡으면 31일이 있는 달의
        // 마지막 날이 조용히 사라진다.
        val lastDay = yearMonth?.lengthOfMonth() ?: 31
```

그리고 본문의 범위 검사를 바꾼다.

```kotlin
                if (cell.day !in 1..lastDay) return@forEach
```

- [ ] **Step 6: 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests "*DispatchRecognitionServiceTest*"`
Expected: PASS — 기존 테스트도 전부 통과한다. 기존 테스트는 모두 연월을 넘기므로 동작이 바뀌지 않는다.

- [ ] **Step 7: 응답까지 갔는지 확인한다**

```bash
grep -rln "yearMonth" --include='*.kt' apps/daily-record/src/main/kotlin/com/toy/backend/dispatch
```

목록에 `DispatchDtos.kt`가 있어야 한다. 없으면 값이 앱까지 가지 않는다.

- [ ] **Step 8: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/ \
        apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchRecognitionServiceTest.kt
git commit -m "feat: 배차표 사진에서 읽은 연월을 기준으로 쓴다"
```

---

### Task 3: 연월이 미상이면 가장 최근 줄 위치를 쓴다

시간표만 잘라 찍은 사진에는 제목이 없고, **성명 컬럼도 함께 잘려 있다.** 즉 연월을 모르는 사진이 곧 줄 위치가 가장 필요한 사진이다. 연월로는 찾을 수 없으므로 가장 최근에 저장된 기준을 빌려 쓰고, 경고를 달아 검수 화면이 알린다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchRosterRepository.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/DispatchRecognitionService.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchRecognitionServiceTest.kt`

**Interfaces:**
- Consumes: Task 2의 `effectiveYearMonth`, `yearMonthLabel`
- Produces: 경고 코드 `ROSTER_FROM_OTHER_MONTH` — 앱의 검수 화면이 이 문자열로 분기한다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```kotlin
        Given("연월도 성명 컬럼도 없는 잘린 사진") {
            // 중간에 바뀐 부분만 잘라 온 사진. 제목도 성명 컬럼도 없다.
            clearMocks(rosterUpdater, answers = false)
            every { rosterRepository.findTopByOrderByYearMonthDesc() } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(false, listOf(20 to "1"), year = 0, month = 0)
            every { visionClient.read(match { it.index == 0 }, null, 2) } returns
                sliceResult(false, listOf(20 to "1"), year = 0, month = 0)
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(false, listOf(21 to "2"), year = 0, month = 0)

            val result = serviceWith().recognize(ByteArray(1), null)

            Then("최근 기준의 행 위치로 읽는다") {
                result.matchedBy shouldBe MatchedBy.ROW_INDEX
                result.rowIndex shouldBe 2
            }

            Then("다른 달 기준을 빌려 썼다고 경고한다") {
                // 인원이 그 사이 바뀌었으면 순번이 밀린다. 사람이 사진과 대조해야 한다.
                result.warnings shouldBe listOf("ROSTER_FROM_OTHER_MONTH")
            }

            Then("줄 위치를 갱신하지 않는다") {
                verify(exactly = 0) { rosterUpdater.upsert(any(), any(), any()) }
            }
        }

        Given("연월은 미상이지만 성명 컬럼이 보이는 사진") {
            clearMocks(rosterUpdater, answers = false)
            every { rosterRepository.findTopByOrderByYearMonthDesc() } returns
                DispatchRoster(yearMonth = "2026-08", rowIndex = 2, rowCount = 13)
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(true, listOf(1 to "1"), year = 0, month = 0)
            every { visionClient.read(match { it.index == 1 }, null, 2) } returns
                sliceResult(true, listOf(4 to "2"), year = 0, month = 0)

            val result = serviceWith().recognize(ByteArray(1), null)

            Then("빌려 썼다는 경고가 붙지 않는다") {
                // 이름으로 행을 찾았으므로 저장된 기준을 쓰지 않았다. 경고할 것이 없다.
                result.warnings shouldBe emptyList()
            }
        }

        Given("연월이 미상이고 저장된 기준도 없는 경우") {
            every { rosterRepository.findTopByOrderByYearMonthDesc() } returns null
            every { visionClient.read(match { it.index == 0 }, "홍길동", null) } returns
                sliceResult(false, listOf(20 to "1"), year = 0, month = 0)

            Then("거부한다") {
                // 어느 줄이 대상인지 알 방법이 없다. 추측해서 저장하느니 거부한다.
                shouldThrow<CustomException> {
                    serviceWith().recognize(ByteArray(1), null)
                }
            }
        }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests "*DispatchRecognitionServiceTest*"`
Expected: 컴파일 실패 — `findTopByOrderByYearMonthDesc`가 없다

- [ ] **Step 3: 리포지토리에 조회를 더한다**

```kotlin
package com.toy.backend.dispatch

import org.springframework.data.jpa.repository.JpaRepository

interface DispatchRosterRepository : JpaRepository<DispatchRoster, Long> {
    fun findByYearMonth(yearMonth: String): DispatchRoster?

    /**
     * 가장 최근 달의 기준. **연월을 모르는 사진에만 쓴다.**
     *
     * `yearMonth`는 `2026-08` 형식 문자열이라 사전순 내림차순이 곧 시간순 내림차순이다.
     * 파생 쿼리 이름이 엔티티 필드명 `yearMonth`와 맞아야 기동한다 — 이 저장소에는
     * 컨텍스트를 띄우는 테스트가 없어 오타는 실행 시점에야 드러난다.
     */
    fun findTopByOrderByYearMonthDesc(): DispatchRoster?
}
```

- [ ] **Step 4: 서비스에서 폴백과 경고를 더한다**

Task 2에서 만든 `roster` 줄을 바꾼다.

```kotlin
        // **연월을 모르는 사진이 곧 기준이 가장 필요한 사진이다** — 시간표만 잘라 찍으면
        // 제목과 성명 컬럼이 함께 잘린다. 연월로는 찾을 수 없으니 가장 최근 기준을 빌려 쓴다.
        // 잘린 변경분은 같은 달 배차표의 일부이므로 직전 기준이 맞다.
        //
        // 연월을 아는데 그 달 기준이 없는 경우는 **빌려 오지 않는다.** 그때는 성명 컬럼이
        // 보이는 사진을 먼저 올리라고 거부하는 편이 맞다(`ROSTER_NOT_FOUND`).
        val roster =
            if (effectiveYearMonth != null) {
                rosterRepository.findByYearMonth(effectiveYearMonth.toString())
            } else {
                rosterRepository.findTopByOrderByYearMonthDesc()
            }
```

`warnings` 블록에 경고를 더한다. `ROW_COUNT_CHANGED` 아래, `YEAR_MONTH_MISMATCH` 위에 넣는다.

```kotlin
        // **빌려 쓴 기준으로 읽었을 때만 경고한다.** 이름으로 행을 찾았으면 저장된 기준을
        // 쓰지 않았으므로 경고할 것이 없다.
        if (effectiveYearMonth == null && matchedBy == MatchedBy.ROW_INDEX) {
            warnings += "ROSTER_FROM_OTHER_MONTH"
        }
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :daily-record:test`
Expected: PASS — 전체 통과

- [ ] **Step 6: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/dispatch/ \
        apps/daily-record/src/test/kotlin/com/toy/backend/dispatch/DispatchRecognitionServiceTest.kt
git commit -m "feat: 연월을 모르는 사진은 최근 줄 위치로 읽는다"
```

---

## 수동 확인

배포 뒤 앱 없이 확인할 수 있는 것들이다. `<TOKEN>`은 로그인 후 얻은 JWT다.

```bash
# 연월을 안 보내도 인식된다 — 응답 yearMonth가 사진에서 읽은 값이어야 한다
curl -s -H "Authorization: Bearer <TOKEN>" \
  -F "file=@배차표.jpg" \
  https://daily.eunji.shop/api/dispatch/recognitions | jq '.data.yearMonth, .data.warnings'

# 연월을 보내면 그 값이 기준이다
curl -s -H "Authorization: Bearer <TOKEN>" \
  -F "file=@배차표.jpg" \
  "https://daily.eunji.shop/api/dispatch/recognitions?yearMonth=2026-08" | jq '.data.yearMonth'
```

제목이 잘린 사진으로 첫 번째 명령을 돌리면 `yearMonth`가 `null`이고 `warnings`에 `ROSTER_FROM_OTHER_MONTH`가 있어야 한다.
