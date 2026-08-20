# 통계 응답(`/tesla/insights`·`/tesla/battery-window`) 신설을 기록한다

## 변경

- **`/tesla/insights` 신설.** `/tesla/drive-insights`의 여덟 필드(`months`·`efficiencyKwhPerKm`·
  `temperatureBuckets`·`driveTimes`·`distanceBuckets`·`places`·`maxSpeedKmh`·`totalDistanceKm`
  계열)를 이름까지 그대로 흡수하고, 월별 요약·요일 집계·속도/충전 버킷·지역 집계를 더해 통계
  탭 하나가 한 번에 그리도록 냈다. `/tesla/drive-insights`는 지우지 않는다 — 앱이 넘어간 뒤
  별건으로 지운다. 그때까지는 옛 계약을 쓰는 앱 버전이 살아 있다.
- **`positions`를 읽는 계열이 `/tesla/battery-window` 하나로 남았다.** 범위(`hours`, 1~168)를
  받고, 표본을 5분 슬롯으로 솎는다.
- **`TeslaVehicleRepository`의 메서드 몇 개가 `months: Int` 대신 UTC 범위(`startUtc`,
  `endUtcExclusive`)를 받는다.** `months=0`(전체 기간)을 SQL로 표현할 수 없어서다 — 서비스가
  `months`를 범위로 먼저 바꾼 뒤 리포지토리에 넘긴다. `/tesla/drive-insights`가 보는 동작은
  그대로다.
- **버킷 라벨이 `TeslaBuckets` 한곳으로 모였다.** 온도·거리·속도·SoC 버킷의 경계·라벨이
  여러 파일에 흩어져 있으면 SQL의 `CASE`와 어긋날 자리가 늘어난다.

## `/tesla/battery-window` 표본을 5분 슬롯으로 솎는다

스펙 초안은 「48시간이면 수백 개라 솎지 않는다」였는데, 실측(2026-08-20)이 48시간 12,517행
(약 750KB)이라 뒤집혔다. 솎으면 48시간 93개·168시간 442개다.

TeslaMate는 차가 깨어 있을 때만 `positions`에 행을 쌓는다 — 표본이 주행·충전한 몇 시간에
몰려 있고 주차 중에는 애초에 행이 없다. 그래서 솎아서 잃는 것은 주행 중의 초 단위 해상도뿐이고,
48시간을 한 화면에 그리는 차트에서 그 해상도는 픽셀로도 안 보인다.

## 검증 방식 — 앱을 띄우지 않고 SQL을 실 DB에 직접 돌렸다

이 저장소는 사용자의 운영 PostgreSQL(라즈베리파이의 `daily-record`·`teslamate`)에 붙는다.
`/tesla/*`는 인증이 필요해 앱 없이는 토큰을 받을 수 없고, 어느 DB를 쓸지도 배포 시점에 정할
일이라 이번 태스크는 `./gradlew :daily-record:bootRun`으로 앱을 띄우지 않았다.

대신 커밋된 SQL 열한 개를 전부 파이의 실제 TeslaMate DB에 파라미터만 치환해 `psql`로 직접
돌렸다. 전부 정상 실행됐고 값이 계획 단계 실측과 일치했다 — 팬텀 드레인 표본 80·80·71,
SoC 버킷이 1~10만 나오고 11번은 없음, 배터리 표본 48시간 93개, 기록 셋(`records`) 3619·3619·
3342 등. 컬럼 이름·별칭·SQL 논리는 이 실행으로 검증됐다. 엔드포인트 실호출(HTTP 왕복, 토큰
발급, 전체 응답 크기·응답 시간)은 앱이 실제로 배포된 뒤로 남는다.

## 이번에 하지 않은 것

- **엔드포인트 실호출.** 위 이유로 이번 태스크 범위 밖이다.
- **`/tesla/drive-insights` 제거.** 앱이 넘어간 뒤 별건으로 지운다.
- **캐시.** 느리면 쿼리를 고친다.
