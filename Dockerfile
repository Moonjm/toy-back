ARG MODULE

FROM eclipse-temurin:25-jdk AS build
ARG MODULE
WORKDIR /app
# gradle.properties에 kotlin.version 오버라이드가 있어 함께 복사해야 한다(없으면 컴파일러 버전 충돌).
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
# settings.gradle.kts가 include하는 모든 프로젝트의 빌드 파일이 있어야 Gradle 설정 단계가 통과한다.
COPY common/core/build.gradle.kts ./common/core/
COPY common/auth/build.gradle.kts ./common/auth/
COPY common/file/build.gradle.kts ./common/file/
COPY apps/daily-record/build.gradle.kts ./apps/daily-record/
COPY apps/family-tree/build.gradle.kts ./apps/family-tree/
COPY apps/ledger/build.gradle.kts ./apps/ledger/
RUN chmod +x gradlew && ./gradlew :${MODULE}:dependencies --no-daemon
COPY common/core ./common/core
COPY common/auth ./common/auth
COPY common/file ./common/file
COPY apps/${MODULE} ./apps/${MODULE}
RUN ./gradlew :${MODULE}:bootJar --no-daemon

FROM eclipse-temurin:25-jre
ARG MODULE
WORKDIR /app
COPY --from=build /app/apps/${MODULE}/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
