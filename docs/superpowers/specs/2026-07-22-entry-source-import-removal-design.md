# EntrySource IMPORT 제거 후 문서 정합성 설계

## 배경

엑셀 이관 내역도 현재 `SMS` 또는 `MANUAL` 출처를 사용하므로 `EntrySource.IMPORT`를 제거했다.
운영 `daily-record` DB에는 `source = 'IMPORT'` 행이 없음을 확인했기 때문에 배포 전 데이터 변환은 필요하지 않다.

반면 기존 가계부 설계·계획 문서에는 `IMPORT` enum과 직접 INSERT 예시가 남아 있다. 이를 그대로 따르면
현재 애플리케이션이 읽을 수 없는 출처 값이 다시 저장될 수 있다.

## 변경 범위

- 현재 가계부 설계 문서의 출처 목록과 엑셀 이관 지침에서 `IMPORT`를 제거하고 `MANUAL`을 사용한다.
- 기존 구현 계획의 enum 및 INSERT 예시도 현재 모델과 일치하도록 `MANUAL`로 바꾼다.
- changelog에 enum 제거 이유, 운영 데이터 확인 결과, 향후 이관 시 사용할 출처를 기록한다.
- 애플리케이션 코드, DB 데이터, 배포 스크립트는 변경하지 않는다.

## 검증

- 저장소 문서와 코드에서 `IMPORT` 출처 안내가 남아 있지 않은지 검색한다.
- `git diff --check`로 문서 형식을 확인한다.
- `daily-record` 전체 테스트와 Spotless 검사를 실행한다.
