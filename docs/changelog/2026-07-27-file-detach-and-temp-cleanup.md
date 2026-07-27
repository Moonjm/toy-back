# 파일 삭제를 detach + 정리 배치로 전환

`../../pluxity/safers-api`의 파일 관리 코드와 비교해 `common-file`에 반영할 것을 골라낸 결과다.
전략 패턴(Local/S3), ZIP 해제, 임의 s3Key 기반 presigned URL은 이 프로젝트 규모에 맞지 않아
가져오지 않았다.

## 변경

### 도메인 트랜잭션 안에서 S3 객체를 즉시 지우던 문제

`FileService.delete`/`deleteAll`을 `detachFile`/`detachFiles`로 바꿨다. 상태만 `ATTACHED → TEMP`로
되돌리고 물리 삭제는 정리 배치가 맡는다.

기존 코드는 `PersonService.updateProfileImage`에서 옛 이미지를 S3에서 먼저 지우고 새 이미지를
첨부했다. 첨부가 실패하면(이미 사용 중인 파일 id 등) 트랜잭션은 롤백되어 `profileImageId`는
살아나는데 **S3 객체는 이미 사라져 복구 수단이 없었다.** 상태 변경은 도메인 트랜잭션과 함께
원자적으로 롤백된다. 같은 이유로 `updateProfileImage`의 순서도 "새 파일 첨부 → 옛 파일 detach"로
뒤집었다.

### temp 파일 정리 배치 추가

`FileCleanupService` + `FileCleanupScheduler`(매일 04:00, TTL 24시간)를 추가했다.
업로드만 하고 첨부하지 않은 파일과 detach 잔재를 수거한다. 이전에는 이런 파일이 S3와 DB에
영구히 남았다.

- S3 삭제(외부 I/O)는 트랜잭션 밖에서 파일별로 수행하고, 성공한 건만 레코드를 지운다.
  실패 건은 레코드를 남겨 다음 주기에 재시도한다(S3 삭제는 멱등).
- 레코드 삭제에 `status = TEMP` 가드를 건다. 조회~삭제 사이에 뒤늦게 첨부된 파일이 함께
  지워지는 것을 막아, 최악의 경우를 "레코드 소실"에서 "S3 객체만 소실"로 낮춘다.
- 스케줄러를 위해 `FamilyTreeApplication`에 `@EnableScheduling`을 추가했다.

### temp 원본 삭제를 커밋 이후로

`attachFile`은 copy 직후 temp 원본을 지웠다. 도메인 트랜잭션이 그 뒤에 롤백되면 `storedName`은
temp 경로로 되돌아가는데 실제 객체는 새 경로에만 있어 링크가 깨졌다.
`TransactionSynchronization.afterCommit`으로 미뤄, 롤백 시에도 레코드와 객체가 계속 맞물린다.

detach된 파일은 경로가 이미 영구 경로라 재첨부를 거부한다(파일을 다시 업로드해야 한다).

### 저장 키에서 원본 파일명 제거

`temp/{uuid}_{원본파일명}` → `temp/{uuid}{확장자}`. 원본 파일명은 `original_name` 컬럼에만 남기고
경로를 제거해(`Paths.get(name).fileName`) 저장한다. 파일명의 경로 구분자가 키 구조를 바꾸거나
공백·한글이 URL 인코딩을 흔드는 문제가 사라진다. 기존 키 형식도 그대로 동작한다.

## 배포

스키마 변경 없음. 배포 후 첫 04:00 배치에서 그동안 쌓인 고아 temp 파일이 한 번에 수거된다.
