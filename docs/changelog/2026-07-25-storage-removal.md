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
