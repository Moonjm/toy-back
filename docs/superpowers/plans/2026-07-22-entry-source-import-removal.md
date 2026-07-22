# EntrySource IMPORT Documentation Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 제거된 `EntrySource.IMPORT`를 다시 사용하도록 안내하는 문서를 현재 `MANUAL` 출처 정책과 일치시킨다.

**Architecture:** 애플리케이션 코드와 배포 흐름은 유지하고, 현재 설계 문서와 과거 구현 계획의 실행 가능한 예시만 수정한다. 운영 DB에 `IMPORT` 행이 없음을 changelog에 기록해 별도 데이터 변환이 필요 없다는 근거를 남긴다.

**Tech Stack:** Markdown, Git, Gradle Spotless

## Global Constraints

- 운영 `daily-record` DB에는 `source = 'IMPORT'` 행이 없다.
- 애플리케이션 코드, DB 데이터, 배포 스크립트는 변경하지 않는다.
- 새 엑셀 이관 데이터는 `source = 'MANUAL'`을 사용한다.
- 스키마 마이그레이션 도구를 추가하지 않는다.

---

### Task 1: 가계부 문서와 변경 이력 정합성 수정

**Files:**
- Modify: `docs/superpowers/specs/2026-07-19-ledger-design.md`
- Modify: `docs/superpowers/plans/2026-07-19-ledger.md`
- Create: `docs/changelog/2026-07-22-ledger-entry-source-import-removal.md`

**Interfaces:**
- Consumes: 현재 코드의 `EntrySource { MANUAL, SMS, KAKAO_PAY, RECURRING }`
- Produces: `IMPORT`를 다시 저장하지 않는 설계·이관 지침과 운영 확인 기록

- [x] **Step 1: 현재 문서 불일치 확인**

Run:

```bash
rg -n "EntrySource.*IMPORT|source = IMPORT|'IMPORT'" docs/superpowers
```

Expected: 가계부 설계·계획 문서에서 `IMPORT` enum 또는 INSERT 지침이 검색된다.

- [x] **Step 2: 현재 설계와 구현 계획 수정**

`docs/superpowers/specs/2026-07-19-ledger-design.md`의 출처 목록을 다음과 같이 바꾼다.

```markdown
| `source` | varchar | `MANUAL` / `SMS` / `KAKAO_PAY` / `RECURRING` |
```

같은 문서의 엑셀 이관 지침은 다음과 같이 바꾼다.

```markdown
- DB 직접 INSERT, `source = MANUAL`
```

`docs/superpowers/plans/2026-07-19-ledger.md`의 `EntrySource` 선언 두 곳에서 `IMPORT`를 제거하고, INSERT 예시의 `'IMPORT'`를 `'MANUAL'`로 바꾼다.

- [x] **Step 3: 변경 이력 작성**

`docs/changelog/2026-07-22-ledger-entry-source-import-removal.md`를 다음 내용으로 작성한다.

```markdown
# 가계부 IMPORT 출처 제거

## 변경

- 엑셀 이관을 포함한 수동 입력 출처를 `MANUAL`로 통일했다.
- 더 이상 사용하지 않는 `EntrySource.IMPORT`를 제거했다.
- 설계·계획 문서의 enum 목록과 직접 INSERT 예시를 현재 모델에 맞췄다.

## 배포

운영 `daily-record` DB를 확인한 결과 `ledger_entries.source = 'IMPORT'` 행은 없다.
따라서 이번 배포에 선행 데이터 변환은 필요하지 않다. 향후 직접 이관할 때는 `source = 'MANUAL'`을 사용한다.
```

- [x] **Step 4: 제거된 값이 실행 지침에 남지 않았는지 확인**

Run:

```bash
rg -n "EntrySource.*IMPORT|source = IMPORT|'IMPORT'" \
  docs/superpowers/specs/2026-07-19-ledger-design.md \
  docs/superpowers/plans/2026-07-19-ledger.md
```

Expected: 검색 결과가 없다.

- [x] **Step 5: 포맷과 회귀 테스트 검증**

Run:

```bash
git diff --check
./gradlew :daily-record:test :daily-record:spotlessCheck --rerun-tasks
```

Expected: 두 명령 모두 exit code 0.

- [x] **Step 6: 변경 커밋**

```bash
git add docs/superpowers/specs/2026-07-19-ledger-design.md \
  docs/superpowers/plans/2026-07-19-ledger.md \
  docs/changelog/2026-07-22-ledger-entry-source-import-removal.md \
  docs/superpowers/plans/2026-07-22-entry-source-import-removal.md
git commit -m "docs: IMPORT 제거 후 가계부 문서 정리"
```
