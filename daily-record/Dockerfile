FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY common/build.gradle.kts ./common/
COPY daily-record/build.gradle.kts ./daily-record/
RUN mkdir -p family-tree
RUN chmod +x gradlew && ./gradlew :daily-record:dependencies --no-daemon
COPY common ./common
COPY daily-record ./daily-record
RUN ./gradlew :daily-record:bootJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/daily-record/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
