# 보관함 관리 기능 제거 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `daily-record` 앱에서 보관함(storage) 기능을 코드·테스트·에러코드·DB 테이블까지 완전히 제거한다.

**Architecture:** 보관함은 `com.toy.backend.storages` 패키지에 완전히 격리되어 있고 외부 참조가 없다. 패키지 디렉터리 2개(main/test)를 통째로 지우고, 보관함 전용 에러코드 한 줄을 제거한다. 운영 DB 테이블은 `ddl-auto: update`가 드롭하지 않으므로 changelog에 DROP 스크립트를 남겨 배포 후 수동 실행한다.

**Tech Stack:** Kotlin, Spring Boot, Gradle, kotest + mockk, PostgreSQL

## Global Constraints

- 커밋 전 `./gradlew spotlessApply` 필수 (ktlint) — AGENTS.md 관례
- 이 작업은 **삭제만** 한다. 남은 코드의 리팩터링·개선을 곁들이지 않는다
- `com.toy.backend.pair` 패키지는 건드리지 않는다 — 보관함과 독립적이다
- DB DROP은 이 계획의 코드 변경에 포함되지 않는다. 배포 후 수동 실행 대상이며 changelog에만 기록한다
- 설계 문서: `docs/superpowers/specs/2026-07-25-storage-removal-design.md`

---

### Task 1: 보관함 코드·테스트·에러코드 제거

**Files:**
- Delete: `apps/daily-record/src/main/kotlin/com/toy/backend/storages/` (디렉터리 전체 — `Storage.kt`, `StorageSection.kt`, `StorageItem.kt`, `StorageRepository.kt`, `StorageSectionRepository.kt`, `StorageItemRepository.kt`, `StorageService.kt`, `StorageController.kt`, `StorageDto.kt`)
- Delete: `apps/daily-record/src/test/kotlin/com/toy/backend/storages/` (디렉터리 전체 — `StorageServiceTest.kt`, `dto/DummyDTOs.kt`, `entity/DummyEntities.kt`)
- Modify: `common/core/src/main/kotlin/com/toy/backend/common/constant/ErrorCode.kt:23`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: 없음. 이 태스크는 공개 인터페이스를 제거만 하며 후속 태스크가 참조할 심볼을 만들지 않는다

**이 태스크에 TDD를 적용하지 않는 이유:** 삭제 작업이라 새 동작을 명세할 테스트가 없다. 검증은 "잔여 참조가 없는가"이고, Kotlin 컴파일러와 기존 전체 테스트 스위트가 그 역할을 한다. Step 4의 빌드가 이 태스크의 테스트 사이클이다.

- [ ] **Step 1: 삭제 전 외부 참조가 없음을 재확인**

```bash
cd /Users/youngminmoon/Documents/moonjm/toy-back
grep -rn "com.toy.backend.storages\|StorageService\|StorageRepository\|StorageController" \
  --include="*.kt" apps/ common/ \
  | grep -v "/com/toy/backend/storages/"
```

Expected: 출력 없음 (exit code 1). 만약 결과가 나오면 설계 전제가 깨진 것이므로 **삭제를 멈추고** 해당 참조를 먼저 보고할 것.

- [ ] **Step 2: 두 디렉터리 삭제**

```bash
cd /Users/youngminmoon/Documents/moonjm/toy-back
git rm -r -q apps/daily-record/src/main/kotlin/com/toy/backend/storages
git rm -r -q apps/daily-record/src/test/kotlin/com/toy/backend/storages
```

- [ ] **Step 3: `ErrorCode`에서 `STORAGE_ACCESS_DENIED` 제거**

`common/core/src/main/kotlin/com/toy/backend/common/constant/ErrorCode.kt`에서 아래 한 줄을 삭제한다.

```kotlin
    STORAGE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "보관함에 대한 접근 권한이 없습니다."),
```

삭제 후 그 자리는 `INVALID_DATE_RANGE` 다음에 바로 `ACCOUNT_LOCKED`가 오는 형태가 된다.

```kotlin
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "생년월일이 사망일보다 이후일 수 없습니다."),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "계정이 잠겨 있습니다: %s"),
```

- [ ] **Step 4: 포맷 검사 후 전체 빌드**

```bash
cd /Users/youngminmoon/Documents/moonjm/toy-back
./gradlew spotlessApply && ./gradlew build
```

Expected: `BUILD SUCCESSFUL`. 컴파일 에러가 나면 잔여 참조가 있다는 뜻이므로 해당 파일을 고친 뒤 다시 실행한다.

- [ ] **Step 5: 저장소 전체에 잔여 흔적이 없는지 확인**

```bash
cd /Users/youngminmoon/Documents/moonjm/toy-back
grep -rn "STORAGE_ACCESS_DENIED\|보관함" --include="*.kt" --include="*.yml" --include="*.yaml" apps/ common/
```

Expected: 출력 없음 (exit code 1).

- [ ] **Step 6: 커밋**

```bash
cd /Users/youngminmoon/Documents/moonjm/toy-back
git add -A apps/daily-record/src common/core/src
git commit -m "chore: 보관함 관리 기능 제거

storages 패키지(엔티티·리포지토리·서비스·컨트롤러·DTO)와 테스트를 삭제하고
보관함 전용 에러코드 STORAGE_ACCESS_DENIED를 제거했다.
호출하는 클라이언트가 없고 다른 패키지에서 참조하지 않는다.

Claude-Session: https://claude.ai/code/session_01JtcMqadTUcWWVPaoqBnZgq"
```

---

### Task 2: changelog 기록

**Files:**
- Create: `docs/changelog/2026-07-25-storage-removal.md`

**Interfaces:**
- Consumes: Task 1이 제거한 파일 목록 (문서 본문에 기술)
- Produces: 없음

**이 태스크에 TDD를 적용하지 않는 이유:** 문서 작성이라 실행 가능한 테스트 대상이 없다. 검증은 Step 2의 형식 확인이다.

- [ ] **Step 1: changelog 작성**

`docs/changelog/2026-07-25-storage-removal.md`를 아래 내용으로 만든다.

````markdown
# 보관함 관리 기능 제거

## 변경

- `daily-record`의 보관함 기능(`com.toy.backend.storages`)을 제거했다.
  엔티티(`Storage`, `StorageSection`, `StorageItem`), 리포지토리 3종,
  `StorageService`, `StorageController`, `StorageDto`와 관련 테스트가 모두 사라졌다.
- `/storages` 이하 REST 엔드포인트가 없어졌다. 호출하는 클라이언트는 없었다.
- 보관함 서비스에서만 쓰던 `ErrorCode.STORAGE_ACCESS_DENIED`를 함께 제거했다.
- `com.toy.backend.pair` 패키지는 유지한다. `Storage.pairId`는 FK 없는 단순 컬럼이었고
  페어 기능은 보관함과 독립적이다.

## 배포

`ddl-auto: update`는 테이블을 드롭하지 않으므로 운영 `daily-record` DB에서 수동으로 정리한다.
**코드 배포를 먼저 하고 그다음 DROP을 실행한다.** 순서를 뒤집으면 배포 직전까지 살아 있는
구버전이 없는 테이블을 조회해 오류를 낸다.

FK 참조 방향에 따라 자식 테이블부터 드롭한다.

```sql
DROP TABLE IF EXISTS storage_items;
DROP TABLE IF EXISTS storage_sections;
DROP TABLE IF EXISTS storages;
```
````

- [ ] **Step 2: 형식 확인**

```bash
cd /Users/youngminmoon/Documents/moonjm/toy-back
git add docs/changelog/2026-07-25-storage-removal.md
git diff --cached --check
grep -c '```' docs/changelog/2026-07-25-storage-removal.md
```

Expected: `git diff --check`는 출력 없음. `grep -c` 결과는 `2` (SQL 블록 여는 펜스와 닫는 펜스).

- [ ] **Step 3: 커밋**

```bash
cd /Users/youngminmoon/Documents/moonjm/toy-back
git commit -m "docs: 보관함 제거 changelog 추가

Claude-Session: https://claude.ai/code/session_01JtcMqadTUcWWVPaoqBnZgq"
```

---

## 배포 체크리스트 (구현 이후, 사람이 수행)

- [ ] `./deploy.sh daily-record`로 코드 배포
- [ ] 배포 완료 확인 후 운영 DB에서 DROP 3줄 실행
- [ ] `/storages` 호출이 404를 반환하는지 확인
