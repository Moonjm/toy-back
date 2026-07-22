# 가계부 IMPORT 출처 제거

## 변경

- 엑셀 이관을 포함한 수동 입력 출처를 `MANUAL`로 통일했다.
- 더 이상 사용하지 않는 `EntrySource.IMPORT`를 제거했다.
- 설계·계획 문서의 enum 목록과 직접 INSERT 예시를 현재 모델에 맞췄다.

## 배포

운영 `daily-record` DB를 확인한 결과 `ledger_entries.source = 'IMPORT'` 행은 없다.
따라서 이번 배포에 선행 데이터 변환은 필요하지 않다. 향후 직접 이관할 때는 `source = 'MANUAL'`을 사용한다.
