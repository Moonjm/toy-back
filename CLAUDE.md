# Project Overview

Kotlin + Spring Boot 멀티모듈 백엔드 API 서버. 일일 기록(Daily Record), 가계도(Family Tree) 등 앱 모듈 운영.

## Tech Stack

| Component         | Version  |
| ----------------- | -------- |
| Kotlin            | 2.3.0    |
| Java              | 25       |
| Spring Boot       | 4.0.2    |
| PostgreSQL        | latest   |
| JJWT              | 0.12.6   |
| Kotlin JDSL       | 3.5.5    |
| SpringDoc OpenAPI | 3.0.1    |
| Spotless (ktlint) | 8.2.1    |

## Module Structure

```
toy-back/
├── apps/
│   ├── daily-record/          # Spring Boot app (일일 기록, 카테고리, 페어)
│   └── family-tree/           # Spring Boot app (가계도)
├── common/
│   ├── core/                  # 기본 인프라 (entity, exception, response, config, aop, utils)
│   ├── auth/                  # 인증 + 유저 + 어드민
│   └── file/                  # 파일 업로드 + S3
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile
└── deploy.sh
```

### Gradle 모듈 의존성

```
:common-core  ← 독립 (web, jpa, validation, openapi, jdsl, logging)
:common-auth  ← :common-core + security, jwt
:common-file  ← :common-core + aws-sdk-s3

:daily-record ← :common-core, :common-auth         (파일 기능 불필요)
:family-tree  ← :common-core, :common-auth, :common-file
```

### common/core 모듈 (`:common-core`)

- `common/annotation/` - @ResponseCreated
- `common/aop/` - ResponseCreatedAspect
- `common/config/` - CorsConfig, WebClientConfig
- `common/constant/` - Code, ErrorCode, SuccessCode
- `common/entity/` - BaseEntity (id, createdAt, updatedAt, createdBy, updatedBy)
- `common/exception/` - CustomException, CustomExceptionHandler
- `common/response/` - ResponseBody, DataResponseBody, ErrorResponseBody
- `common/utils/` - TokenHasher(SHA256), KotlinJdslExtensions
- `logback-spring.xml` - 공통 로깅 설정 (앱 모듈에서 classpath로 자동 적용)

### common/auth 모듈 (`:common-auth`)

- `auth/` - 로그인/로그아웃/토큰갱신 (JWT + Refresh Token)
- `auth/security/` - SecurityConfig, JwtAuthFilter, JwtService, RefreshToken
- `user/` - User 엔티티, UserService, Authority(USER/ADMIN)
- `admin/` - AdminUserController/Service (유저 관리)
- `common/auditing/` - AuditorAwareImpl (Spring Security 의존)
- `common/config/` - CommonAuditingConfig (@EnableJpaAuditing)

### common/file 모듈 (`:common-file`)

- `file/` - FileEntity, FileController, FileService, FileRepository, S3Config, S3Properties

### daily-record 모듈 (`:daily-record`)

- `dailyrecords/` - DailyRecord, DailyOvereat (과식 기록)
- `categories/` - Category (emoji + name)
- `pair/` - PairConnection (유저 페어링), PairEvent

### family-tree 모듈 (`:family-tree`)

- `familytree/person/` - Person (인물 정보, 음력/양력 생년월일)
- `familytree/relationship/` - Spouse, ParentChild (배우자/부모-자녀 관계)
- `familytree/tree/` - FamilyTree, FamilyTreeMember (가계도 관리, 역할 기반 권한)

## Architecture & Patterns

- **인증**: JWT 기반 Stateless 인증. access_token/refresh_token은 HttpOnly 쿠키로 전달
- **BaseEntity**: 모든 엔티티가 상속. id(IDENTITY), createdAt, updatedAt, createdBy, updatedBy
- **응답 포맷**: DataResponseBody<T>로 래핑, ErrorResponseBody로 에러 표준화
- **예외 처리**: CustomException + @RestControllerAdvice 기반 글로벌 핸들러
- **쿼리**: Kotlin JDSL로 타입 안전한 JPQL 쿼리 작성
- **AOP**: @ResponseCreated 어노테이션으로 201 응답 + Location 헤더 자동 생성
- **파일(FileEntity)**: 다른 엔티티와 JPA 연관관계를 맺지 않고 ID(Long)만 저장 (common/file 모듈)
- **코드 포맷**: Spotless + ktlint 적용 (`./gradlew spotlessApply`)

## Key API Endpoints

- `POST /auth/login` - 로그인 (204 + 쿠키)
- `POST /auth/refresh` - 토큰 갱신 (204 + 쿠키)
- `POST /auth/logout` - 로그아웃 (204)
- `/daily-records` - 일일 기록 CRUD
- `/daily-overeat` - 과식 기록
- `/categories` - 카테고리 CRUD
- `/pair` - 페어 연결
- `/user` - 유저 정보
- `/admin/users` - 어드민 유저 관리
- Swagger UI: `/swagger-ui/`

## Database

- PostgreSQL
- DB명: `daily-record` (daily-record 모듈), `family-tree` (family-tree 모듈)
- DDL: `hibernate.ddl-auto=update`
- Batch fetch size: 100 (N+1 최적화)

## Environment Variables

```
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=dev
DB_HOST=localhost
DB_PORT=5432
DB_USERNAME=toy
DB_PASSWORD=toy00
JWT_SECRET=<base64-encoded>
JWT_EXPIRE_MINUTES=120
JWT_REFRESH_DAYS=30
```

## Build & Run

```bash
./gradlew :daily-record:bootRun          # daily-record 앱 실행
./gradlew :family-tree:bootRun           # family-tree 앱 실행
./gradlew spotlessApply                   # 코드 포맷팅
./gradlew :daily-record:bootJar           # daily-record JAR 빌드
./gradlew :family-tree:bootJar            # family-tree JAR 빌드
./gradlew clean build                     # 전체 빌드 + 테스트
```

## Deployment

라즈베리파이(arm64)에 Docker 이미지로 배포.

### 배포 구조

- **배포 스크립트**: `deploy.sh` - 모듈별 Docker 빌드 → 이미지 전송 → 원격 배포
- **Dockerfile**: 멀티스테이지 빌드 (JDK 빌드 → JRE 런타임), `MODULE` ARG로 모듈 선택
- **환경 설정**: `.deploy.env` (git 미추적) - 원격 서버 접속 정보

### 배포 명령어

```bash
./deploy.sh family-tree              # family-tree 모듈 배포
./deploy.sh daily-record             # daily-record 모듈 배포
./deploy.sh daily-record family-tree # 여러 모듈 동시 배포
```

### 배포 프로세스

1. Docker 이미지 빌드 (linux/arm64)
2. 이미지를 tar로 저장
3. scp로 라즈베리파이에 전송
4. 로컬 tar 파일 정리
5. 원격 서버에서 `deploy.sh` 실행

### 배포 환경

- **대상 서버**: 라즈베리파이 (`pi@192.168.0.10`)
- **원격 디렉토리**: `/home/pi/docker/<모듈명>/`
- **필수 조건**: Docker Desktop 실행 필요

## Conventions

- 커밋 메시지: `type(scope): 설명` (한글 사용)
- 패키지: `com.toy.backend.*`
- SecurityConfig에서 `/auth/**`, `/swagger-ui/**`, `/v3/api-docs/**`는 public
- CORS: localhost, 192.168.0.*, https://daily.eunji.shop 허용
