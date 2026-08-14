# 차량 상태가 늘 「충전 중」이던 것을 고친다

## 증상

`GET /tesla/status`의 `state`가 **배포 이후 한 번도 맞은 적이 없었다.** 충전 중이 아닌데도 늘
`charging`이었고, `driving`도 `states`의 진짜 값(`online`·`asleep`·`offline`)도 나온 적이 없다.

## 원인

`charging`·`driving`은 TeslaMate가 저장하지 않아 열린 행에서 파생시킨다. 그 판정이 이랬다.

```sql
EXISTS (SELECT 1 FROM charging_processes WHERE end_date IS NULL)
```

**시간 제한이 없다.** TeslaMate가 충전·주행 중에 죽거나 차가 오프라인이 되면 세션이 마감되지
않고 `end_date IS NULL`인 채로 영원히 남는데, 그런 유령이 실제로 충전 6건(2021~2025년)·
주행 12건(2022~2024년) 쌓여 있었다. 2021년에 안 닫힌 행 하나가 2026년의 「지금 충전 중」으로
읽히고 있었다.

## 변경

열린 행에 최근성 조건을 건다.

```sql
AND start_date >= (now() AT TIME ZONE 'UTC') - interval '24 hours'
```

완속 오버나이트 충전이 10시간쯤이고 한 번에 24시간 연속 주행은 없으므로 진짜 세션은 이 창을
넘지 않는다. **새로 생긴 유령도 하루면 스스로 낫는다.**

TeslaMate DB의 유령 행은 건드리지 않는다 — 우리가 쓰는 것은 여전히 `charging_processes.cost`
하나뿐이다.

## 남은 것

리포지토리가 목이라 **단위 테스트가 이 SQL을 덮지 못한다.** 확인은 실호출이다 —
충전 중이 아닐 때 `/tesla/status`가 `asleep`이나 `online`을 내면 맞은 것이다.

더 정밀한 방법(`charges`·`positions`에 최근 샘플이 들어왔는지 확인)은 `positions.drive_id`의
인덱스 유무를 확인한 뒤에 검토한다.
