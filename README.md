# Form Structure Builder

Spring Boot + PostgreSQL replacement for an internal Excel/VBA form-structure tool.

Form structures are stored as a tree of elements (page → section → subsection → field) with a
data-driven hierarchy and an EAV-style attribute model, so new element types, attributes, and
parent/child combinations are added as data rather than code changes.

## Stack

- Java 21, Maven (wrapper included)
- Spring Boot 3.5.16 — Web, Data JPA, Thymeleaf, Validation
- PostgreSQL 16, schema owned by Flyway (`ddl-auto=validate`)
- Testcontainers for integration tests

## Running locally

Start Postgres:

```bash
docker compose up -d
```

Then run the application:

```bash
./mvnw spring-boot:run
```

The web UI is served at `http://localhost:8080`.

## Building and testing

```bash
./mvnw verify
```

Unit tests (`*Test`) run under Surefire; integration tests (`*IT`) run under Failsafe and require
Docker, since they start a real PostgreSQL container.

## What's here

- `src/main/java/com/vprok/forms` — entities, repositories, services, REST controllers (`web`),
  server-rendered UI controllers (`web.ui`)
- `src/main/resources/db/migration` — Flyway migrations
- `src/main/resources/templates` — Thymeleaf templates for the structure editor
- `docs/json-export-schema.md` — the JSON export contract consumed by the frontend

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for branch naming, merge, and commit-message conventions.
