# AGENTS.md

AI 에이전트(코드 작성·리뷰)가 이 저장소에서 작업할 때 알아야 할 전제와 관례.

## 프로젝트 성격

개인용 토이 프로젝트 모노레포다. **사용자는 본인과 배우자 2명**이고, 라즈베리파이 한 대에
**단일 인스턴스**로 배포한다(`./deploy.sh <모듈명>`). 트래픽은 하루 수십 건 수준이다.

이 규모를 리뷰·설계 판단의 기준으로 삼아라. 대규모 서비스 기준의 방어를 요구하지 말 것.

## 구조

```
common/core    공통 응답·예외·BaseEntity·설정 (Code, CustomException, ResponseCreated 등)
common/auth    JWT 인증·사용자 관리 (User, SecurityConfig, AdditionalAuthFilter)
common/file    파일 업로드
apps/daily-record, apps/family-tree   각 앱 (전용 DB 사용)
```

- 앱마다 자체 PostgreSQL DB를 쓴다 (`daily-record`, `family-tree`)
- 가계부(ledger) 기능은 `daily-record` 모듈 안 `com.toy.backend.ledger.*` 패키지에 있다(전용 앱에서 통합됨)
- 앱 모듈은 `common-core`·`common-auth`를 의존하고, 앱끼리는 의존하지 않는다
- 앱 전용 에러 코드는 앱 모듈에 `Code` 구현 enum으로 둔다(예: `LedgerErrorCode`).
  공통 인프라 성격만 `common-core`의 `ErrorCode`에 추가한다

## 의도적으로 하지 않는 것

리뷰에서 반복 지적되지만 **의식적으로 선택한 것들**이다. 지적하기 전에 이 항목을 확인하라.

- **동시성 방어 없음** — 락·행 선점·낙관적 버저닝을 두지 않는다. 단일 인스턴스이고
  사용자 2명이 순차적으로 쓰며, 스케줄러도 하루 1회 돈다. 얻는 것보다 복잡도가 크다
- **스키마 마이그레이션 도구 없음** — Flyway/Liquibase 대신 `ddl-auto: update`를 쓴다.
  단, enum 컬럼은 `columnDefinition`을 명시해 CHECK 제약이 생기지 않게 한다
  (ddl-auto가 제약을 갱신하지 못해 enum 값 추가 시 기존 DB에서 INSERT가 깨진다)
- **크래시 중간 상태 복구 없음** — "커밋 A 직후 프로세스가 죽으면 B가 유실된다" 류의
  좁은 창은 감수한다. 상태를 늘려 방어하면 그 상태 자체가 새 문제를 만든다

반대로 **데이터 정합성·보안 문제는 규모와 무관하게 엄격히** 다룬다:
사용자 데이터 격리, 잘못된 레코드 삭제, 금액·통화 오류, 자격 증명 노출 등.

## 알려진 미해결 — 리뷰에서 다시 지적하지 말 것

**의도적 선택이 아니라 아직 안 고친 것들이다.** 위 목록과 성격이 다르므로 섞지 않는다.
새로 발견한 같은 계열 문제는 여전히 지적하라 — 여기 적힌 것만 제외한다.

- **`FileEntity`에 소유자가 없다** — `common/file`이 파일을 사용자에 묶지 않아
  `FileService`의 `getPresignedUrl`·`getPresignedUrls`·`download`·`attachFile`이 전부 id만
  받는다. 그 결과 `GET /files/{id}/url`이 아무 파일이나 presigned URL로 내주고,
  `MealAnalysisService.create`가 남의 `fileId`를 받아 OpenRouter로 넘길 수 있으며,
  `GET /diet/analyses/{id}`가 그 URL을 그대로 돌려준다. `family-tree`의 `attachFile`도 같다.

  `common/file`·`common-auth`·두 앱을 함께 고쳐야 하는 별건이라 식단 브랜치 범위 밖으로 뒀다.
  **배포 전에 별도 브랜치에서 처리한다.** 그때까지 이 계열 지적은 이 항목을 가리키면 된다.

## 코드 관례

- Kotlin + Spring Boot. 버전은 `gradle/libs.versions.toml`에서 관리
  (**Kotlin 버전을 올릴 때는 `gradle.properties`의 `kotlin.version`도 함께 올려야 한다** —
  Spring Boot BOM이 관리하는 Kotlin 버전이 컴파일러 클래스패스까지 덮어써서 빌드가 깨진다)
- 커밋 전 `./gradlew spotlessApply` 필수 (ktlint)
- 응답 규칙: 생성은 `@ResponseCreated`로 201 + Location, 수정·삭제는 204 No Content,
  조회는 `DataResponseBody`. 액션 결과는 바디가 아니라 상태 코드로 구분한다
- 타인 소유 리소스 접근은 404(`RESOURCE_NOT_FOUND`)로 존재 자체를 숨긴다
- 금액은 `BigDecimal`, 컬럼은 `precision = 19, scale = 4`
- 파라미터가 4개를 넘는 JPA 파생 쿼리는 `@Query`로 바꾼다 — 메서드명이 길어지는 것보다
  같은 타입 인자의 순서 뒤바뀜이 위험하다

### 커밋 전: 새 심볼이 **어느 파일에** 나오는지 센다

필드·플래그·가드를 더했으면 `grep -rln <이름> --include='*.kt'`로 **파일 목록**을 본다.
개수가 아니라 목록에 무엇이 빠졌는지가 기준이다.

- **필드·플래그를 더했으면** — 목록에 **응답으로 나가는 타입이 있는가**
  (`*Dtos.kt`·`*Response.kt` — 이 저장소는 두 이름을 섞어 쓴다).
  없으면 그 값은 앱까지 가지 않는다. 엔티티·파서·서비스에 다 있어도 소용없다.
- **가드를 더했으면** — 그 서비스의 다른 public 메서드를 훑어 **같은 가드가 필요한 것이
  있는지** 본다. `create`/`retry`처럼 짝을 이루는 자리가 흔하다.

**개수로는 안 잡힌다.** `servingSizeKnown`을 응답 DTO에 빠뜨렸을 때 이미 4개 파일에서
쓰이고 있었고, `isAvailable`도 2곳이었다. 「사용처가 하나뿐이면 의심」 같은 어림수는 이 두
경우 모두 발화하지 않는다 — 실제로 대 보고 확인했다.

이 패턴으로 실제 결함이 반복해서 났다. 주의 영양소 3필드를 엔티티에만 더하고 `AnalyzedItem`·
`FrequentItemResponse`에 안 실어 **사진으로 기록한 모든 끼니가 나트륨 0**으로 저장됐고(매일
거짓 「안전」 판정), `LLM_UNAVAILABLE` 가드를 `create`에만 넣어 재시도가 204를 돌려주고도
아무것도 못 했다.

**설계 문서에 적어 두는 것으로는 안 잡힌다.** 두 엔드포인트를 한 줄에 함께 적어 놓고도 한쪽만
구현된 적이 있다. 아는 것이 아니라 **돌리는 검사**여야 한다 — SQL 컬럼 수와 `setX` 인덱스가
어긋난 것도 눈이 아니라 스크립트로 대조해서 잡았다.

## 테스트

- kotest `BehaviorSpec` + mockk, 픽스처는 `testFixtures`의 `dummyUser()`·`withId()` 사용
- 격리 모드가 `InstancePerLeaf`라 `beforeTest`는 컨테이너 노드에서도 발화한다.
  리프에서만 초기화하려면 `beforeContainer`를 쓴다
- 단위 테스트는 리포지토리를 목으로 대체하므로 **트랜잭션 경계·LAZY 로딩·DB 제약 문제를
  잡지 못한다.** 이런 변경을 했다면 실제로 앱을 띄워 엔드포인트를 호출해 확인하라

## 문서

- 설계·계획 문서는 `docs/superpowers/{specs,plans}/`에 날짜 접두사로 둔다
- 의미 있는 리팩터링은 `docs/changelog/`에 기록한다
