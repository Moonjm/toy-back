# 가계부(ledger) 앱 설계

- 작성일: 2026-07-19
- 상태: 확정

## 개요

지출 내역을 정리하는 개인 가계부 백엔드. 두 사용자가 각자 자신의 내역만 관리한다.
카드 승인/취소 문자와 카카오페이 알림톡을 iOS 단축어로 전송하면 서버가 파싱해 자동 저장하고,
월 반복 지출은 스케줄러가 자동 생성한다. 기존 가계부 앱에서 내보낸 엑셀 2개 파일을 일회성
스크립트로 이관한다. 이번 범위는 백엔드 API까지이며 프론트엔드는 추후 별도 진행한다.

## 확정된 결정 사항

| 항목 | 결정 |
|---|---|
| 저장 위치 | toy-back `apps/ledger` 앱 모듈 (common-core, common-auth 재사용) |
| 데이터 공유 | 각자 개인 가계부 — `user_id`로 완전 분리 |
| 분류(카테고리) | 없음 — 날짜·금액·구매처·내용만 관리 |
| 단축어 인증 | 사용자별 고정 API 키 (`X-API-Key` 헤더) |
| 취소 문자 | 자동 매칭 삭제, 매칭 실패 시 음수 건으로 저장 |
| 외화 | 환산 없이 통화·금액 그대로 저장 |
| 금액 정밀도 | `DECIMAL(19,4)` |
| 카카오페이 입력 | 알림톡 캡쳐 이미지를 단축어 OCR로 텍스트화 후 전송 (텍스트 복붙도 지원) |
| 엑셀 이관 | 일회성 스크립트 (앱에 업로드 기능 없음) |
| 프론트엔드 | 이번 범위 아님 (API만) |

## 아키텍처

daily-record와 동일한 패턴의 Spring Boot 앱 모듈:

- Spring Boot MVC + JPA + PostgreSQL, 전용 DB `ledger`, `ddl-auto: update`
- `settings.gradle.kts`에 `:ledger` 모듈 추가 (`apps/ledger`)
- 의존성: `common-core`(공통 응답/예외/BaseEntity/TokenHasher), `common-auth`(JWT 로그인·사용자 관리)
- 사용자 관리/로그인/토큰 갱신 API는 common-auth 것을 그대로 사용 — 신규 구현 없음
- 패키지: `com.toy.backend.ledger` 하위에 기능별 패키지(`entries`, `inbound`, `recurring`, `apikeys`)

## 데이터 모델

모든 엔티티는 `BaseEntity`(common-core) 상속.

### ledger_entry — 지출/수입 내역 한 건

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | bigint PK | |
| `user_id` | bigint | 소유자. 모든 조회/수정에 필수 조건 |
| `entry_at` | timestamp | 거래 일시 |
| `amount` | DECIMAL(19,4) | 금액 (통화 무관 넉넉한 정밀도) |
| `currency` | varchar(3) | ISO 4217 (`KRW`, `JPY`, `USD` …), 기본 `KRW` |
| `type` | varchar | `EXPENSE` / `INCOME` — 기존 데이터의 수입 행 이관 손실 방지용 |
| `merchant` | varchar | 구매처 |
| `description` | varchar | 내용 |
| `source` | varchar | `MANUAL` / `SMS` / `KAKAO_PAY` / `RECURRING` / `IMPORT` |

인덱스: `(user_id, entry_at)` — 월별 조회용.

### recurring_rule — 월 반복 규칙

| 컬럼 | 설명 |
|---|---|
| `user_id` | 소유자 |
| `day_of_month` | 1~31. 해당 월에 없는 날짜면 말일로 보정 |
| `amount`, `currency`, `merchant`, `description` | 생성될 entry 내용 |
| `active` | 활성 여부 |
| `last_generated_month` | `YYYY-MM` — 중복 생성 방지 |

### api_key — 단축어용 API 키

| 컬럼 | 설명 |
|---|---|
| `user_id` | 소유자 |
| `key_hash` | 키 해시 (common-core `TokenHasher` 재사용, 원본 미저장) |
| `name` | 용도 표시용 이름 (예: "아이폰 단축어") |

원본 키는 발급 응답에서 1회만 노출한다.

### inbound_message — 수신 원문 로그

| 컬럼 | 설명 |
|---|---|
| `user_id` | 수신자 |
| `raw_text` | 수신 원문 (문자/OCR 텍스트) |
| `status` | `SAVED` / `CANCEL_MATCHED` / `PARSE_FAILED` |
| `entry_id` | 생성된 entry 참조 (nullable) |

파싱 실패해도 원문이 보존되므로 파서 수정 후 재처리할 수 있다. 단축어 전송 건이
조용히 유실되는 것을 막는 핵심 장치.

## API

경로는 기존 앱 관례대로 루트 기준이다(별도 prefix 없음 — daily-record의 `/daily-records`와 동일한 방식).
응답 형식은 common-core의 `DataResponseBody`/`ErrorResponseBody` 관례를 따른다.

| 메서드/경로 | 인증 | 설명 |
|---|---|---|
| `GET /entries?from=&to=` | JWT | 기간 조회 (본인 것만), `entry_at` 내림차순 |
| `POST /entries` | JWT | 수동 입력 |
| `PUT /entries/{id}` | JWT | 수정 (본인 것만) |
| `DELETE /entries/{id}` | JWT | 삭제 (본인 것만) |
| `POST /inbound` | API 키 또는 JWT | `{ "text": "원문" }` — 파싱 후 저장/취소매칭 |
| `GET /api-keys` | JWT | 키 목록 (해시 제외, 이름·생성일만) |
| `POST /api-keys` | JWT | 키 발급 — 원본 키는 이 응답에서만 노출 |
| `DELETE /api-keys/{id}` | JWT | 키 폐기 |
| `GET /recurring-rules` | JWT | 규칙 목록 (관리 페이지용) |
| `POST /recurring-rules` | JWT | 규칙 등록 — `{ entryId, dayOfMonth? }`. 내역 상세의 "반복" 버튼에서 호출하며, 해당 entry의 금액·통화·구매처·내용을 복사한다. `dayOfMonth` 생략 시 entry 날짜의 일(日) 사용 |
| `PUT /recurring-rules/{id}` | JWT | 규칙 수정 (금액·구매처·내용·반복일·활성 여부) |
| `DELETE /recurring-rules/{id}` | JWT | 규칙 삭제 |

반복 규칙 생성 진입점은 내역 상세 화면의 "반복" 버튼 하나다(빈 폼으로 새로 만드는 UI 없음).
관리 페이지는 목록·수정·삭제만 담당한다. 규칙은 entry 값의 복사본이므로 이후 원본 entry를
수정·삭제해도 규칙에는 영향이 없다.

### API 키 인증

`/inbound`에 한해 `X-API-Key` 헤더를 허용하는 필터를 추가한다.
키 해시로 `api_key`를 조회해 `user_id`를 확정한다. 그 외 엔드포인트는 common-auth JWT 필터를 그대로 사용.

## 메시지 파싱

`MessageParser` 인터페이스 + 구현체 체인. `supports(text)`가 true인 첫 파서가 처리하고,
결과는 `ParsedMessage(kind: APPROVAL|CANCEL, amount, currency, merchant, occurredAt)`로 통일한다.
어느 파서도 처리하지 못하면 `inbound_message`에 `PARSE_FAILED`로 기록하고 200 응답(원문 보존됨을 명시).

### CardApprovalParser — 국내 카드 승인/취소

```
[Web발신]
대한항공카드 승인          ← "승인"/"취소" 로 kind 판별
문*민
18,920원 일시불            ← 금액
07/14 07:38               ← 연도 없음 → 수신 시점 기준 보정
제주특별자치도개발          ← 가맹점
누적438,919원
```

연도 보정: 수신일 기준으로 미래 날짜가 되면 전년도로 처리 (12월 말~1월 초 경계 대응).

### OverseasApprovalParser — 해외 승인

```
[Web발신]
[현대카드] 해외승인
문*민님
07/14 23:15
JPY 1,000.00              ← 통화 + 금액 (소수점 포함)
SUICAMOBILEPAYMENT        ← 가맹점
```

통화 코드와 금액을 그대로 저장 (환산 없음).

### KakaoPayParser — 카카오페이 알림톡

입력 경로 두 가지, 엔드포인트는 동일:

1. **이미지 공유 (주 경로)**: 알림톡 캡쳐 → iOS 단축어 "이미지에서 텍스트 추출"(OCR) → 텍스트 전송.
   OCR 결과에 `결제금액` 라벨과 금액이 포함된다.
2. **텍스트 복붙 (보조)**: 카톡 메시지 본문 복사 — 이 경우 결제금액 헤더가 없을 수 있다.

파싱 규칙 (라벨 앵커 방식 — OCR 노이즈에 강함):

- 금액: `결제금액` 라벨 다음의 `N원` 패턴. 없으면 `0`으로 저장하고 사용자가 수정
- 구매처: `- 구매처:` 라벨
- 내용: `- 상품명:` 라벨부터 다음 `- ` 라벨 전까지 이어붙임 (OCR이 넣는 어절 중간 공백은 무해)
- 일시: `- 결제일시: YYYY.MM.DD HH:mm`
- 상단(`알림톡 도착` 등)/하단(`이용내역 보기`, 시각 등) 노이즈는 라벨 앵커라 자연 무시

실제 OCR 출력 샘플을 유닛테스트 픽스처로 사용한다 (2026-07-19 수집분).

### 취소 매칭

취소 문자 수신 시: 같은 `user_id` + 같은 금액 + 같은 가맹점의 **최근 7일 내 승인 건** 중
가장 최근 건을 삭제하고 `inbound_message.status = CANCEL_MATCHED`.
매칭 실패 시 음수 금액의 entry로 저장해 합계를 보정한다.

## 반복입력 스케줄러

HolidayScheduler 패턴의 `@Scheduled(cron = "매일 새벽")` 잡:

1. `active = true`인 규칙 조회
2. `해당 월 실제 생성일 = min(day_of_month, 이번 달 말일)` 계산
3. `생성일 ≤ 오늘` 이고 `last_generated_month ≠ 이번 달`이면 entry 생성(`source = RECURRING`, `entry_at`은 생성일 00:00) 후 `last_generated_month` 갱신

`≤ 오늘` 조건으로 서버가 며칠 중단돼도 다음 실행 때 밀린 건이 생성된다(캐치업).

## 엑셀 이관 (일회성 스크립트)

앱 외부의 파이썬 스크립트. 실행 시 대상 사용자를 지정한다.

- `2026-07-19.xls`: 실제로는 HTML 테이블 → HTML 파싱.
  컬럼: `날짜, 계좌, 대분류, 내용, 금액, 수입/지출, 상세내역`
- `2026-07-19_new.xlsx`: openpyxl 파싱.
  컬럼: `기간, 자산, 분류, 소분류, 내용, KRW, 수입/지출, 추가입력, 금액, 화폐, 자산`
- 병합: `(일시, 금액, 내용)` 기준 중복 제거. 분류/계좌 값은 버린다
  (필요 시 `description`에 병기하는 옵션 제공)
- `수입/지출` → `type` 매핑, `화폐` → `currency` (구형 파일은 전부 `KRW`)
- DB 직접 INSERT, `source = IMPORT`

## 에러 처리

- `/inbound`: 파싱 실패도 200 + `PARSE_FAILED` 상태 반환 (원문 보존, 단축어에서 유실 방지).
  API 키 불일치만 401
- entries CRUD: 타인 소유 접근 시 404 (존재 자체를 숨김), 검증 실패 400 —
  common-core `CustomException`/`ErrorCode` 관례 사용

## 테스트

기존 관례(kotest + mockk, testFixtures) 준수:

- **파서 유닛테스트가 핵심**: 실제 샘플 문자 4종(승인/취소/해외승인/카카오페이 OCR·복붙) 픽스처
- 연도 보정 경계(12월→1월), 카카오페이 금액 누락 케이스
- 취소 매칭: 성공 / 7일 경과 실패 / 동일 금액 다중 건에서 최근 건 선택
- 스케줄러: 말일 보정(31일 규칙 + 2월), 캐치업, 중복 생성 방지
- API 키 필터: 유효/무효/타 사용자 키

## 범위 제외 (추후)

- 프론트엔드 화면
- 분류(카테고리) — 필요해지면 컬럼 추가
- 외화 원화 환산 / 환율 연동
- 엑셀 업로드 API
