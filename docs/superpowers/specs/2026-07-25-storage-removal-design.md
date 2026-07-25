# 보관함 관리 기능 제거 설계

## 배경

`daily-record` 앱의 보관함(storage) 기능은 냉장고·창고 등의 칸(section)과 물품(item)을 등록해
수량과 유통기한을 관리하는 기능이었다. 현재 이 기능을 호출하는 클라이언트가 없고 앞으로도 쓰지 않는다.

보관함 코드는 `com.toy.backend.storages` 패키지 안에 완전히 격리되어 있다.
`ledger`·`pair` 등 다른 패키지에서 `storages`를 참조하는 코드는 없고,
`Storage.pairId`는 FK 없는 단순 `Long` 컬럼이라 `pair` 쪽에도 영향이 없다.
따라서 패키지 삭제만으로 기능 전체가 사라진다.

## 변경 범위

### 삭제

- `apps/daily-record/src/main/kotlin/com/toy/backend/storages/` 디렉터리 전체 (9개 파일)
  - `Storage`, `StorageSection`, `StorageItem` 엔티티
  - `StorageRepository`, `StorageSectionRepository`, `StorageItemRepository`
  - `StorageService`, `StorageController`, `StorageDto`
- `apps/daily-record/src/test/kotlin/com/toy/backend/storages/` 디렉터리 전체 (3개 파일)
  - `StorageServiceTest`, `dto/DummyDTOs`, `entity/DummyEntities`
- `common/core`의 `ErrorCode.STORAGE_ACCESS_DENIED`
  — 보관함 서비스에서만 쓰던 값이라 함께 제거한다.
  `getCodeName()`이 `name` 기반이고 ordinal에 의존하는 코드가 없으므로 다른 enum 값에 영향이 없다.

이로써 `/storages` 이하 REST 엔드포인트가 모두 사라진다.

### 유지

- `com.toy.backend.pair` 패키지 — 보관함과 독립적이며 페어 이벤트 등 다른 용도로 계속 쓰인다.
- 배포 스크립트, 설정 파일 — 보관함 전용 설정이 없다.

### DB

`ddl-auto: update`는 테이블을 드롭하지 않으므로 운영 DB에서 수동으로 정리한다.
FK 참조 방향에 따라 자식 테이블부터 드롭한다.

```sql
DROP TABLE IF EXISTS storage_items;
DROP TABLE IF EXISTS storage_sections;
DROP TABLE IF EXISTS storages;
```

배포 순서는 **코드 배포 → DROP 실행**이다. 반대로 하면 배포 직전까지 살아 있는 구버전이
없는 테이블을 조회해 오류를 낸다.

## 검증

- `./gradlew spotlessApply` 후 `./gradlew build` 실행.
  삭제 작업의 유일한 실패 모드는 "어딘가 남은 참조"이고, 컴파일러와 전체 테스트가 이를 잡는다.
- 저장소 전체에서 `storages`·`Storage`·`STORAGE_ACCESS_DENIED` 검색 결과가
  본 설계 문서와 changelog 외에 남지 않는지 확인한다.

## 기록

`docs/changelog/2026-07-25-storage-removal.md`에 제거 이유, DROP 스크립트, 배포 순서를 남긴다.
