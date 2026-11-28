# syntax=docker/dockerfile:1

# ---- build stage: compile and package the jar (tests run in CI, not here) ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test \
    && java -Djarmode=tools -jar build/libs/*-SNAPSHOT.jar extract --layers --launcher --destination extracted

# ---- runtime stage: small JRE image, non-root user ----
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 dropcode
# Layers ordered from least to most frequently changing, for better Docker cache reuse
COPY --from=build /app/extracted/dependencies/ ./
COPY --from=build /app/extracted/spring-boot-loader/ ./
COPY --from=build /app/extracted/snapshot-dependencies/ ./
COPY --from=build /app/extracted/application/ ./
USER dropcode
# Railway Hobby limit is 512 MB: let the heap use 75% of the container memory
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 --enable-native-access=ALL-UNNAMED"
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
