# syntax=docker/dockerfile:1
# The app as one portable image (FORMS-21). Host-specific settings are environment variables; see
# src/main/resources/application-prod.yml and docs/deployment.md.

# --- Build: compile and package with the Maven wrapper ---------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# A checkout made on Windows can carry CRLF line endings and lose the executable bit.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src/ src/
# Tests run in development (they need Docker for Testcontainers), not in the image build.
RUN ./mvnw -q -B -DskipTests package \
    && java -Djarmode=tools -jar target/form-structure-builder-*.jar extract --destination /app

# --- Run: a JRE, the extracted jar (starts faster than the fat jar), a non-root user -----------
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 forms
WORKDIR /app
COPY --from=build /app/ ./
USER forms

ENV SPRING_PROFILES_ACTIVE=prod
# Small instance: cap the heap well under the container's memory, keep the JIT and GC cheap so
# startup is quick on a fraction of a CPU.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:ReservedCodeCacheSize=48m"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "form-structure-builder-0.0.1-SNAPSHOT.jar"]
