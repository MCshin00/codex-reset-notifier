FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew --no-daemon test fatJar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 notifier \
    && useradd --uid 10001 --gid notifier --no-create-home notifier \
    && mkdir -p /data && chown notifier:notifier /data
COPY --from=build /workspace/build/libs/codex-reset-notifier.jar /app/notifier.jar
COPY config /app/config
USER 10001:10001
ENV STATE_FILE=/data/state.json
ENTRYPOINT ["java", "-Xms16m", "-Xmx96m", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/notifier.jar"]
