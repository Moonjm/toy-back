ARG MODULE

FROM eclipse-temurin:25-jdk AS build
ARG MODULE
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY common/build.gradle.kts ./common/
COPY daily-record/build.gradle.kts ./daily-record/
COPY family-tree/build.gradle.kts ./family-tree/
RUN chmod +x gradlew && ./gradlew :${MODULE}:dependencies --no-daemon
COPY common ./common
COPY ${MODULE} ./${MODULE}
RUN ./gradlew :${MODULE}:bootJar --no-daemon

FROM eclipse-temurin:25-jre
ARG MODULE
WORKDIR /app
COPY --from=build /app/${MODULE}/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
