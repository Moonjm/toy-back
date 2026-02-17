FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY common/build.gradle.kts ./common/
COPY family-tree/build.gradle.kts ./family-tree/
RUN mkdir -p daily-record
RUN chmod +x gradlew && ./gradlew :family-tree:dependencies --no-daemon
COPY common ./common
COPY family-tree ./family-tree
RUN ./gradlew :family-tree:bootJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/family-tree/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
