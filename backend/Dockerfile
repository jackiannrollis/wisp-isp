# syntax=docker/dockerfile:1
# Corebalt ISP Platform API: built and run on Java 25 (LTS).

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q -DskipTests package \
    && cp target/isp-platform-*.jar /workspace/app.jar

FROM eclipse-temurin:25-jre
RUN groupadd --system isp && useradd --system --gid isp --no-create-home isp
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
USER isp
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# The JRE image has no curl or wget; bash's /dev/tcp is enough for a readiness probe.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q UP <&3"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
