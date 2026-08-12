# 근무 하루 편집 설계

**한 줄 요약.** 날짜 하나의 아빠·엄마 근무를 한 요청으로 고치고, 엄마 순번(`A`·`B`·`C`)을 담는다.

## 배경

근무를 바꾸는 길은 `POST /dispatch/shifts` 하나뿐이고, 이것은 **배차표 사진 검수의 확정 저장**이다. 본문이 `{role, days[]}`라 한 요청에 역할이 하나이고, 실제로는 앱 검수 화면이 아빠 한 달치를 통째로 보낸다.

하루가 틀렸을 때 고칠 방법이 배차표 사진을 다시 올리는 것밖에 없다. 엄마는 아예 저장 경로가 없다 — `DispatchQueryService.findMonth`는 이미 **예외 레코드가 있으면 패턴을 이기도록** 읽고 있고 `DispatchShift`의 문서도 "엄마는 패턴과 다른 날(예외)만 들어온다"고 적어 두었지만, **그 레코드를 만드는 길이 어디에도 없다.**

여기에 엄마 순번이 더해진다. 엄마도 순번 구분이 있는데 `slot`은 아빠용 정수라 `A`·`B`·`C`를 담지 못한다.

## 목표

- 날짜 하나의 두 역할을 한 요청·한 트랜잭션으로 upsert한다.
- 엄마 순번을 `slotCode`로 담고 조회 응답에 실어 보낸다.
- 성공하면 `204`다. 호출자가 달 전체를 다시 조회하지 않게 한다.

## 비목표

- **삭제·되돌리기.** upsert만 둔다. 잘못 고친 날은 다시 보내 바로잡는다. 엄마를 「패턴 계산값으로 복귀」시키는 수단도 두지 않는다.
- **엄마 배차표 사진 인식.** `slotCode`를 채우는 것은 이번에는 하루 편집뿐이다. 인식이 붙으면 `RecognitionDay`와 `ShiftSaveDay`에 같은 필드를 더한다.
- **`note` 편집.** 무인증 조회로 그대로 나가는 자유 입력이라 새 입력 경로를 열지 않는다.
- **기존 `POST /dispatch/shifts` 변경.** 사진 검수 경로는 그대로 둔다.

---

## API

### `PUT /dispatch/shifts/{date}`

```
PUT /dispatch/shifts/2026-08-15

{
  "father": { "working": true, "slot": 3 },
  "mother": { "working": true, "slotCode": "A" }
}
```

**기존 `POST`를 확장하지 않고 새로 낸다.** `POST`는 역할이 하나라 두 사람을 고치려면 두 번 호출해야 하고, 그 사이에 실패하면 아빠만 저장된 상태가 남아 뒷수습이 호출자로 넘어간다. 또 하나의 문으로 「한 달 통째 확정」과 「하루 손수정」이 같이 들어오면 검증 규칙이 섞인다 — 전자는 `days`가 비면 안 되고, 후자는 역할이 하나만 와도 된다.

`{date}`는 `LocalDate`로 받아 **Spring이 변환하게 둔다.** 본문에서 `LocalDate.parse`를 부르면 오타 하나가 `DateTimeParseException` → 공통 핸들러의 500으로 떨어져 서버 결함처럼 보인다(`DispatchController.recognize`의 `YearMonth`와 같은 이유다).

### 역할 항목은 둘 다 선택이다

```kotlin
data class DayEditRequest(
    @field:Valid val father: RoleEditRequest?,
    @field:Valid val mother: RoleEditRequest?,
)
```

**손대지 않은 역할은 아예 오지 않고, 서버도 건드리지 않는다.**

아빠 배차표를 아직 안 올린 달이 있다. 그 달에 엄마만 고치려는데 요청이 두 역할을 모두 요구하면, 호출자가 아빠 값을 지어내야 하고 그 달 아빠의 첫 레코드가 사람이 고른 적 없는 값으로 생긴다. 「저장된 적 없음」과 「휴무」는 조회에서 이미 구분되는 상태다(`DispatchQueryService`: "아빠는 확정 저장된 날짜만 나간다"). 그 구분을 저장 쪽에서 무너뜨리지 않는다.

둘 다 없으면 400이다. 아무것도 안 하는 요청은 성공도 실패도 아니어서, 조용히 204를 내면 호출자가 값이 안 들어간 것을 모른다.

### 순번은 역할마다 다른 필드다

```kotlin
data class RoleEditRequest(
    val working: Boolean,
    /** 아빠 배차 순번. **엄마에게 보내면 400이다.** */
    @field:PositiveOrZero val slot: Int?,
    /** 엄마 근무조. **아빠에게 보내면 400이다.** */
    @field:Pattern(regexp = "^[A-C]$") val slotCode: String?,
)
```

한 필드에 정수와 문자를 겹쳐 담지 않는다. `slot`을 문자열로 바꾸면 사진 인식·저장 경로와 이미 저장된 데이터까지 전부 딸려 오고, `A`를 `1`로 접어 저장하면 같은 숫자의 뜻이 역할마다 갈려 나중에 엄마 순번이 `D`로 늘어나는 순간 무너진다.

한 요청 안에서 역할과 필드가 어긋나면 400이다. 조용히 무시하면 호출자는 값이 들어간 줄 안다.

| 규칙 | 어겼을 때 |
|---|---|
| `father`·`mother` 둘 다 없음 | 400 |
| `mother.slot`이 옴 | 400 |
| `father.slotCode`가 옴 | 400 |
| `slotCode`가 `^[A-C]$`가 아님 | 400 |
| `slot`이 음수 | 400 |
| `{date}`가 `LocalDate`로 안 읽힘 | 400 (Spring 변환) |
| `working: false`인데 순번이 옴 | **오류가 아니다. 무시하고 `null`로 저장한다** |

마지막 줄만 400이 아닌 이유가 있다. 화면에서 근무↔휴무를 오가면 직전에 고른 순번이 폼에 남아 함께 실려 오기 쉽다. 그것을 거절하면 사용자가 스스로 순번을 지워야 하는데, 어차피 저장될 수 없는 값이다. **휴무면 순번이 없다**는 규칙을 서버가 지키면 되고, 화면 한쪽에서만 막으면 다른 호출자가 모순된 레코드를 만든다.

### `note`는 요청에 없고, 기존 값도 건드리지 않는다

새 입력 경로를 열지 않기로 했다. 그렇다고 `null`로 덮으면 사진에서 읽어 저장한 원문(`휴`·`간담회`·`*97`)이 하루 편집 한 번에 사라진다. **`note`는 이 경로에서 읽지도 쓰지도 않는다.**

기존 `saveShifts`는 `existing.note = day.note`로 덮는다. 그쪽은 사진이 그 날짜의 원본이므로 맞는 동작이고, 하루 편집과 규칙이 다른 것이 정상이다.

### 응답은 `204 No Content`다

**호출자는 자기가 보낸 값을 이미 알고 있다.** 안 보낸 역할은 서버도 건드리지 않으므로 그 칸은 화면에 그려진 그대로다. 그러니 응답 바디를 실어 보내도 방금 보낸 값과 안 바뀐 값뿐이고, 저장소 관례(`AGENTS.md`: "수정·삭제는 204 No Content")와도 이쪽이 맞는다. 성공하면 앱이 보낸 값으로 그 칸을 바로 갈아 끼운다 — 달 전체 재조회도, 하루치 재조회도 없다.

서버가 값을 **변형**하는 자리는 하나뿐이다: `working: false`인데 순번이 오면 `null`로 눕힌다. 그때만 「보낸 값 = 저장된 값」이 깨지는데, 그 칸은 이미 휴무로 그려져 순번이 보이지 않으므로 화면과 어긋나지 않는다. 앱은 휴무를 고르면 순번을 비워 보내면 된다.

### 인증

`DispatchPublicEndpoints`는 `GET /dispatch/shifts`만 연다. **`PUT`을 그 목록에 넣지 않는다** — 조회를 공개한 근거는 응답에 실명·차량번호가 없다는 것이지 아무나 고쳐도 된다는 뜻이 아니다.

---

## 저장 모델

### `slot_code` 컬럼

```kotlin
@field:Column(name = "slot_code", length = SLOT_CODE_MAX_LENGTH)
var slotCode: String? = null
```

`(role, work_date)` 유일 제약과 `findByRoleAndWorkDate`는 이미 있다. upsert의 근거가 갖춰져 있어 더할 것은 컬럼 하나다.

`ddl-auto: update`라 **nullable 컬럼 추가는 자동으로 반영된다.** CHECK 제약이나 enum과 달리 이쪽은 걸리는 것이 없다.

길이는 1로 못 박지 않고 짧게(예: 8) 둔다. 지금 값은 한 글자지만 나중에 `A조` 같은 표기가 오면 컬럼부터 막힌다. 실제 허용 범위는 요청 검증(`^[A-C]$`)이 정하므로, 컬럼을 조금 넉넉히 두는 것으로 잃는 것이 없다.

### 하루 upsert

```kotlin
@Transactional
fun editDay(date: LocalDate, request: DayEditRequest)
```

`DispatchCommandService`에 넣는다. 이미 `@Transactional`이라 **두 역할이 한 트랜잭션에 든다** — 한쪽만 저장된 상태를 만들지 않는다.

역할 하나를 저장하는 흐름은 기존 `saveShifts`와 같다. `findByRoleAndWorkDate`로 찾아 있으면 필드를 갈고 없으면 새로 만든다. 다른 점은 셋이다.

- `note`를 읽지도 쓰지도 않는다.
- `working`이 false면 `slot`·`slotCode`를 `null`로 눕힌다.
- 역할에 맞지 않는 필드는 저장 전에 400으로 거른다.

**엄마 조회 쪽은 손대지 않는다.** `findMonth`가 이미 `storedByKey[MOTHER to date] ?: 패턴계산`으로 읽으므로, 레코드가 생기는 순간 그것이 이긴다. 한 번 손댄 날은 패턴이 나중에 바뀌어도 따라가지 않는데, 되돌리는 수단을 두지 않기로 한 결과다. 값 자체는 다시 보내 고칠 수 있다.

### 조회 응답 확장

`ShiftDayResponse`에 `slotCode: String?`를 더하고 `DispatchQueryService.toResponse`가 채운다. 패턴에서 만들어지는 엄마 기본값은 `null`이다.

아빠는 이 값을 쓰지 않으므로 늘 `null`로 나간다. 역할마다 필드를 갈라 응답을 두 모양으로 만들지 않는다 — 조회는 두 사람을 **같은 모양으로 합쳐** 내보내는 것이 원래 계약이다.

---

## 컴포넌트

| 파일 | 변경 |
|---|---|
| `DispatchShift.kt` | `slotCode` 컬럼, `SLOT_CODE_MAX_LENGTH` |
| `DispatchDtos.kt` | `ShiftDayResponse.slotCode`. `DayEditRequest`·`RoleEditRequest` 추가 |
| `DispatchController.kt` | `PUT /dispatch/shifts/{date}` |
| `DispatchCommandService.kt` | `editDay(date, request)` — 검증, 두 역할 upsert |
| `DispatchQueryService.kt` | `toResponse`에 `slotCode` |

`DispatchShiftRepository`, `DispatchPublicEndpoints`, 마이그레이션은 손대지 않는다.

---

## 오류 처리

검증 실패는 모두 400이다. 기존 공통 핸들러가 `@Valid` 위반과 Spring 변환 실패를 이미 400으로 옮긴다.

역할·필드 조합 검증(`mother.slot`, `father.slotCode`, 둘 다 없음)은 빈 검증 애너테이션으로 표현되지 않는다. **DTO 안의 검증 메서드나 서비스 진입부에서 명시적으로 던진다** — 저장 로직 속에 흩어 두면 어떤 조합이 막히는지 한자리에서 읽히지 않는다.

메시지는 사용자용 한국어로 낸다. 앱이 그대로 화면에 옮긴다.

---

## 테스트

`DispatchCommandServiceTest`에 더한다.

- `father`만 보내면 아빠만 upsert되고 엄마 레코드는 생기지 않는다
- `mother`만 보내면 엄마 예외 레코드가 생기고, 그 뒤 조회가 패턴 대신 그 값을 준다
- 같은 날짜를 두 번 보내면 레코드가 하나로 유지된다
- `working: false`면 `slot`·`slotCode`가 와도 `null`로 저장된다
- 기존 `note`가 하루 편집 뒤에도 남는다
- `mother.slot`이면 400, `father.slotCode`면 400, `slotCode`가 `D`면 400, 둘 다 없으면 400
- 검증에 걸리면 **아무것도 저장되지 않는다** — 아빠가 멀쩡해도 엄마가 틀리면 둘 다 안 들어간다

`DispatchQueryServiceTest`에 더한다.

- 저장된 `slotCode`가 조회 응답에 실린다
- 패턴에서 만들어진 엄마 기본값의 `slotCode`가 `null`이다

---

## 열린 항목

- **엄마 순번 체계.** 지금은 `A`·`B`·`C` 셋으로 못 박는다. 실제로 `D`나 조 이름이 쓰이면 검증 정규식을 넓힌다. 컬럼 길이는 미리 열어 둔다.
- **엄마 배차표 인식.** 붙게 되면 `RecognitionDay`·`ShiftSaveDay`에도 `slotCode`가 필요하고, `POST /dispatch/shifts`가 역할별로 어느 필드를 받을지 여기와 같은 규칙을 따라야 한다.
