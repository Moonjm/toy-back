# 코틀린 널 불가 필드를 스웨거 필수로 낸다

작성일: 2026-08-23

## 무엇을 바꿨나

`KotlinRequiredModelConverter`(common-core)를 넣었다. 스웨거가 스키마를 만들 때 코틀린
생성자를 함께 읽어 **필수 필드를 `required`에 올린다**. 183개 스키마 중 166개가 바뀌었다.

```
BillSaveRequest  before: ["items"]
                 after : ["chargedAmount", "items", "yearMonth"]
```

## 왜

`required`가 `@NotBlank`·`@NotEmpty` 같은 검증 애너테이션에서만 채워지고 있었다.
널 불가 `val`에서 `@field:NotNull`을 걷어내자 `yearMonth`·`chargedAmount`가 문서에서
선택값으로 보였다 — 실제로는 빠뜨리면 역직렬화가 실패하는 필수값이다.

원인은 버전 조합이다. springdoc 3.0.3이 쓰는 swagger-core 2.2.47은 **잭슨 2** 매퍼로
타입을 읽는데(본체는 잭슨 3다) 그 매퍼에는 코틀린 모듈이 없다. 잭슨 2용 코틀린 모듈을
문서 때문에 하나 더 붙이는 대신, 이미 있는 kotlin-reflect로 직접 판정한다.

## 판정 규칙

| 대상 | 필수 | 근거 |
| --- | --- | --- |
| 널 불가 · 기본값 없는 생성자 파라미터 | O | 빠뜨리면 역직렬화가 실패한다 |
| 기본값 있는 파라미터 | X | 빠뜨려도 되니 실제로 선택값이다 |
| 널 허용 파라미터 | X | |
| 상위 클래스 생성자의 널 불가 파라미터 | O | 응답 봉투의 `status`가 여기 있다 |
| 생성자에 없는 널 불가 `val` | O | JSON으로 못 채우니 서버가 늘 내보낸다 (`timestamp`) |
| 생성자에 없는 `var` | X | JSON으로 채울 수 있어 선택값일 수 있다 |

스웨거가 **이미 내보낸 이름만** 판정한다. `@JsonIgnore`로 빠졌거나 이름이 바뀐 것은
애초에 후보가 아니다. 검증 애너테이션으로 이미 올라간 이름은 다시 넣지 않는다 —
`required`에 중복으로 쌓인다.

## 남은 것

필드가 전부 널 허용인 7개 타입(`BillUsage`, `DayEditRequest`, `UserUpdateRequest`,
`TeslaStatusResponse`, `TpmsBar`, `InsightsRecords`, `StudyDailyGoalTodayResponse`)은
`required`가 없다. 규칙대로다.

`@Schema(requiredMode = ...)`를 명시한 곳은 저장소에 없다. 생기면 이 변환기가 덮으므로
그때 예외 처리를 더해야 한다.
