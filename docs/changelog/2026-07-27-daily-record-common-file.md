# daily-record에 common-file 적용

diet(식단 사진) 도메인이 파일 업로드를 쓰려면 `daily-record`가 `common-file`을 의존해야 한다
(`docs/superpowers/specs/2026-07-27-diet-tracking-backend-design.md` 참고). 도메인 코드는 아직
없고 배선만 했다.

## 변경

- `apps/daily-record/build.gradle.kts`에 `implementation(project(":common-file"))` 추가
- `application.yml`에 `s3.*` 블록 추가. 버킷 기본값은 `daily`
- `application.yml`에 `spring.servlet.multipart` 제한(10MB) 추가 —
  Spring 기본값(`max-file-size` 1MB)은 사진 업로드에 부족하다

`@SpringBootApplication`이 `com.toy.backend`에 있어 `com.toy.backend.file`의 빈·엔티티·리포지토리는
자동으로 스캔된다. 코드 변경은 없다. `@EnableScheduling`도 이미 있어 temp 정리 배치가 그대로 돈다.

이로써 `POST /files`, `GET /files/{id}/url` 엔드포인트와 매일 04:00 temp 정리 배치가
`daily-record`에도 생긴다. `files` 테이블은 `ddl-auto: update`가 만든다.

## 배포

운영 `daily-record` 컨테이너에 **S3 환경변수를 새로 넣어야 한다**. 넣지 않으면
`application.yml`의 개발용 기본값(로컬 MinIO 주소·자격증명)으로 뜨고 업로드가 실패한다.

```
S3_ENDPOINT, S3_PUBLIC_ENDPOINT, S3_REGION, S3_ACCESS_KEY, S3_SECRET_KEY, S3_BUCKET
```

MinIO에 해당 버킷도 미리 만들어야 한다(없으면 업로드가 `NoSuchBucket`으로 500).
환경변수는 이 저장소가 아니라 라즈베리파이의 배포 스크립트에 있다.
