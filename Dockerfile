# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# shared libraries from the host's local Maven repository (compose: additional_contexts.m2)
COPY --from=m2 . /root/.m2/repository/ua/kpi/iot/
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
 && cp build/libs/*-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
# 5683 CoAP over TCP, 5684 CoAP over TLS, 8080 HTTP (actuator)
EXPOSE 5683 5684 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
