ARG MODULE

FROM eclipse-temurin:25-jdk AS build
ARG MODULE
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY common/core/build.gradle.kts ./common/core/
COPY common/auth/build.gradle.kts ./common/auth/
COPY common/file/build.gradle.kts ./common/file/
COPY apps/daily-record/build.gradle.kts ./apps/daily-record/
COPY apps/family-tree/build.gradle.kts ./apps/family-tree/
RUN chmod +x gradlew && ./gradlew :${MODULE}:dependencies --no-daemon
COPY common ./common
COPY apps/${MODULE} ./apps/${MODULE}
RUN ./gradlew :${MODULE}:bootJar --no-daemon

FROM eclipse-temurin:25-jre
ARG MODULE
WORKDIR /app
COPY --from=build /app/apps/${MODULE}/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
