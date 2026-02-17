# Family Tree 모듈 설계

가계도 웹 서비스를 위한 백엔드 모듈. 가계도를 생성하고, 생성자가 OWNER 권한을 갖고, 다른 사용자에게 조회/수정 권한을 부여할 수 있다.

## 모듈 구조

```
family-tree/
├── build.gradle.kts
├── src/main/kotlin/com/example/backend/
│   ├── FamilyTreeApplication.kt
│   └── familytree/
│       ├── tree/                              # 가계도 + 멤버
│       │   ├── FamilyTree.kt                  # 가계도 엔티티
│       │   ├── FamilyTreeMember.kt            # 멤버(권한) 엔티티
│       │   ├── FamilyTreeRole.kt              # enum: OWNER, EDITOR, VIEWER (priority 기반)
│       │   ├── FamilyTreeRepository.kt
│       │   ├── FamilyTreeMemberRepository.kt
│       │   ├── FamilyTreeDto.kt               # 가계도 + 멤버 DTO, TreeWithMember
│       │   ├── FamilyTreePermissionService.kt # 권한 체크
│       │   ├── FamilyTreeService.kt           # 가계도/멤버 CRUD
│       │   └── FamilyTreeController.kt        # 가계도/멤버 API
│       ├── person/                            # 인물
│       │   ├── Person.kt                      # 인물 엔티티
│       │   ├── PersonRepository.kt
│       │   ├── PersonDto.kt                   # 인물 DTO
│       │   ├── PersonService.kt               # 인물 CRUD, 프로필 이미지 관리
│       │   └── PersonController.kt            # 인물 API
│       └── relationship/                      # 관계
│           ├── Spouse.kt                      # 배우자 엔티티 (person_a_id, person_b_id 정규화)
│           ├── ParentChild.kt                 # 부모-자식 엔티티
│           ├── SpouseRepository.kt
│           ├── ParentChildRepository.kt
│           ├── RelationshipDto.kt             # Spouse/ParentChild DTO
│           ├── RelationshipService.kt         # 관계 CRUD, 순환 방지 BFS
│           └── RelationshipController.kt      # 관계 API
└── src/main/resources/
    └── application.yml
```

### 서비스/컨트롤러 구조

| 레이어        | 클래스                           | 담당                                                                                 |
|------------|-------------------------------|------------------------------------------------------------------------------------|
| Controller | `FamilyTreeController`        | 가계도 CRUD, 멤버 CRUD                                                                   |
| Controller | `PersonController`            | `POST/PUT/DELETE /{id}/persons/**`                                                 |
| Controller | `RelationshipController`      | `POST/DELETE /{id}/relationships/spouse`, `POST/DELETE /{id}/relationships/parent` |
| Service    | `FamilyTreeService`           | 가계도/멤버 CRUD, `getDetail` 조회, `buildDetailResponse`                                  |
| Service    | `PersonService`               | 인물 추가/수정/삭제, 프로필 이미지 연결, `findAllByFamilyTree`                                      |
| Service    | `RelationshipService`         | 배우자/부모-자식 추가/삭제, 순환 방지 BFS, 일괄 삭제                                                  |
| Service    | `FamilyTreePermissionService` | 권한 체크 (`TreeWithMember` 반환)                                                        |

3개 컨트롤러 모두 `@Tag(name = "가계도")`, `@RequestMapping("/family-trees")` 공유 → Swagger UI에서 하나의 태그로 묶임.

---

## common 모듈 변경사항

### 파일 업로드/조회 (S3 호환 - MinIO)

| 파일                       | 설명                                                                                           |
|--------------------------|----------------------------------------------------------------------------------------------|
| `file/S3Properties.kt`   | endpoint, publicEndpoint, region, accessKey, secretKey, bucket ConfigurationProperties       |
| `file/S3Config.kt`       | S3Client(내부용) + S3Presigner(publicEndpoint용) 빈 등록 (forcePathStyle로 MinIO 호환)                 |
| `file/FileEntity.kt`     | originalName, storedName(UUID), contentType, fileSize, bucketName, **status(TEMP/ATTACHED)** |
| `file/FileStatus.kt`     | enum: TEMP, ATTACHED                                                                         |
| `file/FileRepository.kt` | JpaRepository                                                                                |
| `file/FileService.kt`    | S3 업로드/삭제 + presigned URL 생성 + 상태 관리                                                         |
| `file/FileController.kt` | `POST /files` (업로드), `GET /files/{id}/url` (presigned URL 조회)                                |

의존성: `software.amazon.awssdk:s3:2.31.1`

**조건부 로딩**: `s3.endpoint` 프로퍼티가 없으면 파일 관련 빈(S3Properties, S3Config, FileService, FileController)이 전부 로드되지 않음. daily-record 등 파일 기능을 쓰지 않는 모듈은 S3 설정 없이 기동 가능.

### 파일 업로드/조회 흐름

```
1. 업로드 (백엔드 프록시)
   클라이언트 → POST /files (multipart, JWT 인증)
              → S3(MinIO)에 temp/{uuid}_{filename} 으로 저장
              → FileEntity 생성 (status=TEMP)
              ← fileId 응답

2. 프로필 이미지 등록 (Person 생성/수정 시)
   클라이언트 → POST /family-trees/{id}/persons { profileImageId: 42, ... }
              → FileEntity 조회, status=TEMP 확인 (TEMP가 아니면 거부)
              → S3에서 temp/ → profile/{treeId}/ 로 이동 (copy + delete)
              → FileEntity status를 ATTACHED로 변경
              → Person.profileImage에 연결
              ← 201

3. 이미지 조회 (Presigned URL)
   클라이언트 → GET /files/{id}/url (JWT 인증, fileId로 요청)
              → FileEntity에서 storedName 조회
              → S3Presigner.presignGetObject(storedName) ← 로컬 HMAC 서명 연산 (네트워크 호출 아님)
              ← presigned URL 응답 (10분 유효)

   클라이언트 → <img src="presignedUrl"> (S3/MinIO 직접 접근)
```

- S3 버킷은 **private** 유지 → presigned URL 없이는 접근 불가
- Presigned URL 생성은 로컬 서명 연산이므로 인물 100명이어도 성능 이슈 없음
- 가계도 상세 조회 시 `getPresignedUrls(ids)`로 배치 조회 후 Person별 profileImageUrl을 presigned URL로 내려줌
- **S3 endpoint 분리**: `endpoint`(백엔드→MinIO 내부 통신)와 `publicEndpoint`(presigned URL에 포함될 클라이언트용 주소)를 분리. Docker Compose 환경에서 nginx를 통해 MinIO를 프록시할 때 유용

```
# 예시: nginx 프록시 구성
# presigned URL → https://domain.com/file/family-tree/profile/1/abc_photo.jpg?X-Amz-Signature=...
# nginx가 /files/ 경로를 MinIO로 프록시
location /files/ {
    proxy_pass http://minio:9000/;
}
```

### ErrorCode 추가

```
FAMILY_TREE_ACCESS_DENIED   (403) 가계도에 대한 접근 권한이 없습니다.
FAMILY_TREE_OWNER_REQUIRED  (403) 가계도 소유자만 수행할 수 있는 작업입니다.
FAMILY_TREE_LAST_OWNER      (400) 마지막 소유자는 제거할 수 없습니다.
ALREADY_HAS_SPOUSE          (400) 이미 배우자가 있습니다.
MAX_PARENTS_EXCEEDED        (400) 부모는 최대 2명까지만 가능합니다.
CIRCULAR_RELATIONSHIP       (400) 순환 관계가 감지되었습니다.
SELF_RELATIONSHIP           (400) 자기 자신과의 관계는 설정할 수 없습니다.
INVALID_DATE_RANGE          (400) 생년월일이 사망일보다 이후일 수 없습니다.
```

---

## 엔티티 설계

### FileEntity (common)

| 컬럼           | 타입                                             | 제약                           |
|--------------|------------------------------------------------|------------------------------|
| (BaseEntity) | id, createdAt, updatedAt, createdBy, updatedBy |                              |
| originalName | VARCHAR(255)                                   | NOT NULL                     |
| storedName   | VARCHAR(255)                                   | NOT NULL (UUID prefix + 원본명) |
| contentType  | VARCHAR(100)                                   | NOT NULL                     |
| fileSize     | Long                                           | NOT NULL                     |
| bucketName   | VARCHAR(100)                                   | NOT NULL                     |
| status       | FileStatus (ENUM)                              | NOT NULL, default TEMP       |

### FamilyTree

| 컬럼           | 타입                                             | 제약       |
|--------------|------------------------------------------------|----------|
| (BaseEntity) | id, createdAt, updatedAt, createdBy, updatedBy |          |
| name         | VARCHAR(100)                                   | NOT NULL |
| description  | VARCHAR(500)                                   | nullable |

### FamilyTreeMember

| 컬럼           | 타입                     | 제약       |
|--------------|------------------------|----------|
| (BaseEntity) |                        |          |
| familyTree   | ManyToOne → FamilyTree | NOT NULL |
| user         | ManyToOne → User       | NOT NULL |
| role         | FamilyTreeRole (ENUM)  | NOT NULL |

- Unique: `(family_tree_id, user_id)`

### Person

| 컬럼             | 타입                     | 제약                                    |
|----------------|------------------------|---------------------------------------|
| (BaseEntity)   |                        |                                       |
| familyTree     | ManyToOne → FamilyTree | NOT NULL                              |
| name           | VARCHAR(50)            | NOT NULL                              |
| birthDate      | LocalDate              | nullable                              |
| deathDate      | LocalDate              | nullable                              |
| gender         | Gender (common 재사용)    | nullable                              |
| profileImageId | Long                   | nullable (FileEntity ID, JPA 연관관계 없음) |
| memo           | VARCHAR(500)           | nullable                              |

### Spouse (배우자)

| 컬럼           | 타입                     | 제약                        |
|--------------|------------------------|---------------------------|
| (BaseEntity) |                        |                           |
| familyTree   | ManyToOne → FamilyTree | NOT NULL                  |
| personAId    | Long                   | NOT NULL, UNIQUE (항상 min) |
| personBId    | Long                   | NOT NULL, UNIQUE (항상 max) |

- `person_a_id = minOf(personA, personB)`, `person_b_id = maxOf(personA, personB)`로 정규화 저장
- `person_a_id`, `person_b_id` 각각 UNIQUE → 한 사람은 하나의 배우자만 가능

### ParentChild (부모-자식)

| 컬럼           | 타입                     | 제약       |
|--------------|------------------------|----------|
| (BaseEntity) |                        |          |
| familyTree   | ManyToOne → FamilyTree | NOT NULL |
| parentId     | Long                   | NOT NULL |
| childId      | Long                   | NOT NULL |

- Unique: `(parent_id, child_id)`
- 비즈니스 규칙: 부모 최대 2명, 순환 관계 방지 (BFS 검증)

### ER 다이어그램

```
FamilyTree 1──* FamilyTreeMember *──1 User
FamilyTree 1──* Person
FamilyTree 1──* Spouse
FamilyTree 1──* ParentChild
Person ──── FileEntity (profileImageId로 참조, JPA 연관관계 없음)
```

---

## 권한 모델

| Role   | 가계도 수정 | 인물/관계 CRUD | 멤버 관리 | 삭제 |
|--------|:------:|:----------:|:-----:|:--:|
| OWNER  |   O    |     O      |   O   | O  |
| EDITOR |   X    |     O      |   X   | X  |
| VIEWER |   X    |     X      |   X   | X  |

`FamilyTreePermissionService.findTreeAndCheckPermission(treeId, user, requiredRole)` 메서드로 권한 체크 통일.

Role에 priority 기반 비교: `OWNER(100) > EDITOR(50) > VIEWER(10)` — `role.hasAtLeast(requiredRole)`로 권한 체크. enum 순서에 의존하지 않아 안전.

---

## API 엔드포인트

### 가계도

| Method | Path                      | 응답  | 권한      | 설명                                       |
|--------|---------------------------|-----|---------|------------------------------------------|
| POST   | `/family-trees`           | 201 | 인증      | 가계도 생성 (자동 OWNER)                        |
| GET    | `/family-trees`           | 200 | 인증      | 내 가계도 목록                                 |
| GET    | `/family-trees/{id}`      | 200 | VIEWER+ | 상세 (인물 + 배우자 + 부모자식 + presigned URL 포함)  |
| PUT    | `/family-trees/{id}`      | 204 | OWNER   | 이름/설명 수정                                 |
| DELETE | `/family-trees/{id}`      | 204 | OWNER   | 삭제                                       |

### 멤버

| Method | Path                                    | 응답  | 권한      | 설명    |
|--------|-----------------------------------------|-----|---------|-------|
| POST   | `/family-trees/{id}/members`            | 201 | OWNER   | 멤버 추가 |
| GET    | `/family-trees/{id}/members`            | 200 | VIEWER+ | 멤버 목록 |
| PUT    | `/family-trees/{id}/members/{memberId}` | 204 | OWNER   | 역할 변경 |
| DELETE | `/family-trees/{id}/members/{memberId}` | 204 | OWNER   | 멤버 제거 |

### 인물

| Method | Path                                    | 응답  | 권한      | 설명                                 |
|--------|-----------------------------------------|-----|---------|------------------------------------|
| POST   | `/family-trees/{id}/persons`            | 201 | EDITOR+ | 인물 추가 (profileImageId로 TEMP 파일 연결) |
| PUT    | `/family-trees/{id}/persons/{personId}` | 204 | EDITOR+ | 인물 수정 (profileImageId 변경 가능)       |
| DELETE | `/family-trees/{id}/persons/{personId}` | 204 | EDITOR+ | 인물 삭제                              |

### 배우자 관계

| Method | Path                                      | 응답  | 권한      | 설명              |
|--------|-------------------------------------------|-----|---------|-----------------|
| POST   | `/family-trees/{id}/relationships/spouse` | 201 | EDITOR+ | 배우자 추가 (1:1 제한) |
| DELETE | `/family-trees/{id}/relationships/spouse` | 204 | EDITOR+ | 배우자 삭제          |

### 부모-자식 관계

| Method | Path                                      | 응답  | 권한      | 설명                         |
|--------|-------------------------------------------|-----|---------|----------------------------|
| POST   | `/family-trees/{id}/relationships/parent` | 201 | EDITOR+ | 부모-자식 추가 (부모 최대 2명, 순환 방지) |
| DELETE | `/family-trees/{id}/relationships/parent` | 204 | EDITOR+ | 부모-자식 삭제                   |

### 파일 (common)

| Method | Path              | 응답  | 설명                                  |
|--------|-------------------|-----|-------------------------------------|
| POST   | `/files`          | 201 | 파일 업로드 → S3 temp/ 경로, status=TEMP   |
| GET    | `/files/{id}/url` | 200 | Presigned URL 조회 (10분 유효, 로컬 서명 연산) |

---

## DB 설정

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/family-tree
    username: ${DB_USERNAME:tree}
    password: ${DB_PASSWORD:tree00}
```

## 환경변수

```
# 기존 공통
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=dev
DB_HOST=localhost
DB_PORT=5432
DB_USERNAME=tree
DB_PASSWORD=tree00
JWT_SECRET=<base64-encoded>
JWT_EXPIRE_MINUTES=120
JWT_REFRESH_DAYS=30

# S3 (MinIO 호환) — s3.endpoint가 없으면 파일 기능 비활성화
S3_ENDPOINT=http://minio:9000
S3_PUBLIC_ENDPOINT=https://domain.com/file   # presigned URL용 (없으면 endpoint 사용)
S3_REGION=ap-northeast-2
S3_ACCESS_KEY=minioadmin
S3_SECRET_KEY=minioadmin
S3_BUCKET=family-tree
```

## 빌드 & 실행

```bash
./gradlew :family-tree:bootRun          # 앱 실행
./gradlew :family-tree:bootJar          # JAR 빌드
./gradlew :family-tree:compileKotlin    # 컴파일 확인
./gradlew spotlessApply                  # 코드 포맷팅
```

---

## Changelog

### 2026-02-16: 관계 모델 리팩토링

**브랜치**: `feat/family-tree-relationship-refactor`

기존 `Relationship` 엔티티(PARENT_CHILD, SPOUSE 타입을 하나의 테이블로 관리)를 `Spouse`와 `ParentChild` 두 개의 별도 테이블로 분리. 비즈니스 규칙(순환 방지, 부모 최대 2명, 배우자 1:1)을 서비스 레이어에서 검증하도록 변경.

#### 삭제한 파일

- `relationship/Relationship.kt`
- `relationship/RelationshipType.kt`
- `relationship/RelationshipRepository.kt`
- `relationship/RelationshipDto.kt` (새 내용으로 재작성)

#### 새로 생성한 파일

| 파일                                       | 설명                                                      |
|------------------------------------------|---------------------------------------------------------|
| `relationship/Spouse.kt`                 | 배우자 엔티티. `person_a_id`/`person_b_id` 정규화 저장, 각각 UNIQUE  |
| `relationship/ParentChild.kt`            | 부모-자식 엔티티. `(parent_id, child_id)` 복합 UNIQUE            |
| `relationship/SpouseRepository.kt`       | `findByPersonAIdOrPersonBId`, `deleteByPersonAIdOrPersonBId`, `deleteAllByFamilyTree` |
| `relationship/ParentChildRepository.kt`  | `countByChildId`, `deleteAllByParentIdOrChildId`, `deleteAllByFamilyTree`              |
| `relationship/RelationshipDto.kt`        | `SpouseRequest/Response`, `ParentChildRequest/Response` |
| `relationship/RelationshipService.kt`    | 배우자/부모-자식 CRUD, 순환 방지 BFS, 일괄 삭제                        |
| `relationship/RelationshipController.kt` | 배우자/부모-자식 관계 REST API                                   |
| `person/PersonService.kt`                | 인물 CRUD, 프로필 이미지 연결/삭제, 일괄 삭제                           |
| `person/PersonController.kt`             | 인물 REST API                                             |
| `person/PersonDto.kt`                    | `PersonRequest/Response`, 확장 함수 `Person.toResponse()`   |

#### 수정한 파일

- **ErrorCode.kt**: `ALREADY_HAS_SPOUSE`, `MAX_PARENTS_EXCEEDED`, `CIRCULAR_RELATIONSHIP`, `SELF_RELATIONSHIP`, `INVALID_DATE_RANGE` 추가
- **Person.kt**: `positionX`, `positionY` 제거 (프론트 라이브러리가 레이아웃 담당), `familyTree`를 `val`로 변경
- **FamilyTreeRole.kt**: ordinal 비교 → `priority` 필드 기반 (OWNER=100, EDITOR=50, VIEWER=10), `hasAtLeast()` 메서드 추가
- **FamilyTreePermissionService.kt**: `Pair` → `data class TreeWithMember` 반환, `hasAtLeast()` 사용
- **FamilyTreeDto.kt**: `TreeWithMember` data class 추가, `FamilyTreeDetailResponse`에 `spouses` + `parentChild` 분리
- **FamilyTreeMemberRepository.kt**: `findAllByUser`에 `JOIN FETCH m.familyTree`, `findAllByFamilyTree`에 `JOIN FETCH m.user` 추가 (N+1 해결)
- **PersonRepository.kt**: `existsByIdAndFamilyTree()` 추가 (관계 생성 시 트리 소속 검증용)
- **RelationshipService.kt**: `addSpouse()`, `addParentChild()`에서 personId가 해당 트리 소속인지 검증 추가. 유니크 제약과 중복되는 `addSpouse` 배우자 존재 체크, `addParentChild` 중복 체크 쿼리 제거. `deleteAllByPersonId()`에서 find→delete 패턴을 `deleteByPersonAIdOrPersonBId`, `deleteAllByParentIdOrChildId` 단일 쿼리로 변경
- **PersonService.kt**: `add()`, `update()`에서 `birthDate > deathDate` 검증 추가. `deleteAllByFamilyTree()`에서 forEach 단건 삭제 → `fileService.deleteAll()` 배치 삭제로 변경
- **PersonController.kt / RelationshipController.kt**: `@ResponseCreated` 제거, `ResponseEntity.status(201).body(id)`로 변경 (단건 GET 없으므로 Location 헤더 불필요)
- **FamilyTreeService.kt**: Person/Relationship CRUD를 각 서비스로 위임, `buildDetailResponse()` 추출, `getPresignedUrls()` 배치 조회, `addMember()` 유니크 제약 중복 체크 쿼리 제거
- **FamilyTreeController.kt**: Person/Relationship 엔드포인트를 각 컨트롤러로 분리
- **S3Properties.kt**: `publicEndpoint` 필드 추가
- **S3Config.kt**: `S3Presigner`가 `publicEndpoint` 사용 (없으면 `endpoint` 폴백), `pathStyleAccessEnabled(true)` 추가 (MinIO presigned URL path style 수정)
- **FileService.kt**: `getPresignedUrls(ids): Map<Long, String>` 배치 메서드 추가, `deleteAll(ids)` S3 `deleteObjects` 배치 삭제 메서드 추가
- **S3Properties, S3Config, FileService**: `@ConditionalOnProperty(prefix = "s3", name = ["endpoint"])` 조건부 로딩
- **FileController**: `@ConditionalOnBean(FileService::class)` 조건부 로딩
- **application.yml**: `s3.public-endpoint` 추가 (기본값: `http://localhost:9000`)
