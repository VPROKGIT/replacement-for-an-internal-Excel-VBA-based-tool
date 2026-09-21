# Form Structure Builder

Spring Boot + PostgreSQL replacement for an internal Excel/VBA form-structure tool. The old tool
used a fixed spreadsheet layout (page → sections → subsections → fields) with hardcoded columns
per attribute; this application stores the same kind of structure as a data-driven tree (an
EAV-style attribute model plus a seeded `element_type_rule` table) so new element types,
attributes, and parent/child combinations are added as data rather than code changes.

## Stack

- Java 21, Maven (wrapper included, no local Maven install required)
- Spring Boot 3.5.16 — Web, Data JPA, Thymeleaf, Validation
- PostgreSQL 16, schema owned by Flyway (`ddl-auto=validate`)
- Testcontainers for integration tests

## Prerequisites

- **Java 21** (JDK, not just a JRE)
- **Maven** — not strictly required; the repo includes the Maven Wrapper (`mvnw` / `mvnw.cmd`),
  which downloads the correct Maven version on first use
- **Docker Desktop** — needed both to run Postgres locally via `docker-compose.yml` and for the
  Testcontainers-backed integration tests (`*IT` classes)

If you don't have Java 21 installed system-wide, a portable JDK works fine and needs no
administrator rights: download a Temurin 21 archive, extract it anywhere (e.g. a `tools/`
directory outside the repo), and set `JAVA_HOME` to that extracted path for each shell session you
build in — for example, in PowerShell:

```powershell
$env:JAVA_HOME = 'C:\path\to\tools\jdk-21.x.x+y'
.\mvnw.cmd verify
```

`JAVA_HOME` set this way is per-shell, not persistent — set it again in any new terminal you use
for this project unless you add it to your permanent environment variables. The same approach
works for a portable Maven distribution if you'd rather not rely on the wrapper.

## Setup: fresh clone to a running app

1. **Clone and enter the repo.**

2. **Start PostgreSQL:**

   ```bash
   docker compose up -d
   ```

3. **Run the application:**

   ```bash
   ./mvnw spring-boot:run
   ```

   The web UI is served at `http://localhost:8080`. Flyway applies the schema (`V1__init_schema.sql`,
   `V2__seed_data.sql`) automatically on startup — no manual migration step.

### Windows-specific gotcha: loopback socket failure on startup

On at least one Windows 11 dev machine, this project's JDK (Temurin 21.0.11) fails to start **any**
real embedded servlet container — Tomcat, and presumably any other NIO-selector-based server —
with:

```
IOException: Unable to establish loopback connection
SocketException: Invalid argument: connect
```

thrown from `sun.nio.ch.UnixDomainSockets`. This is a JDK-on-Windows bug where the internal
Selector wakeup pipe tries to use an AF_UNIX socket that fails on that host. It blocks both
`TestRestTemplate` + `RANDOM_PORT` tests and running the actual jar (`java -jar ...`) — MockMvc-based
tests are unaffected, since they never open a real socket.

**Fix:** pass `-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp` on the java command line. Apply it
whenever running the app directly:

```bash
java -Djdk.net.unixdomain.tmpdir=C:\Windows\Temp -jar target/*.jar
```

or via `mvnw spring-boot:run`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp
```

If you hit the same `IOException`/`SocketException` pair on startup, this is almost certainly it.

## Running tests

```bash
./mvnw verify
```

Unit tests (`*Test`, plain JUnit 5 + Mockito, no Docker needed) run under Surefire during the
`test` phase. Integration tests (`*IT`, real PostgreSQL via Testcontainers) run under Failsafe
during `verify`, and are skipped automatically if Docker isn't available rather than failing the
build. This split is deliberate: `*Test` vs `*IT` is a strict naming convention here, not a
stylistic preference — a test named with the wrong suffix either runs somewhere unintended or gets
silently skipped.

## Project structure

- `src/main/java/com/vprok/forms` — entities (`entity`), repositories (`repository`), services
  (`service`), REST controllers (`web`), server-rendered UI controllers (`web.ui`)
- `src/main/resources/db/migration` — Flyway migrations (schema in `V1`, seed data — attribute
  catalog, applicability matrix, hierarchy rules — in `V2`)
- `src/main/resources/templates` — Thymeleaf templates for the structure editor
- `src/test/java/com/vprok/forms` — mirrors the main package layout; see "Running tests" above for
  the `*Test` / `*IT` split
- `docs/json-export-schema.md` — the JSON export contract consumed by the frontend, asserted
  byte-for-byte by `FormExportIT`; see that doc rather than this README for the export shape

## Implementation gotchas worth knowing

A few non-obvious things that cost real debugging time during development, recorded here so they
don't repeat:

- **Lazy-loading past the transaction boundary.** `spring.jpa.open-in-view=false` is set
  deliberately, so any repository method whose result crosses into a controller/DTO and needs a
  lazy `@ManyToOne` field's non-id properties (for example `AttributeDefinition.code` off an
  `ElementAttributeValue`) must join-fetch it in the query, or it throws
  `LazyInitializationException` once the transaction has closed. Accessing just `.getId()` on a
  lazy proxy is always safe without fetching.
- **Thymeleaf recursive fragments** (used for the page tree editor): (1) fragment-call arguments
  must be explicit `${...}` — a bare identifier like `~{frag :: name(child)}` is parsed as a
  literal token, not resolved as a variable, and silently passes the wrong value. (2) `th:replace`/
  `th:insert` (fragment inclusion) is processed **before** `th:each` (iteration) when both
  attributes sit on the same tag, so the loop variable is still unbound when the fragment call
  evaluates. For recursive tree/list rendering, put `th:each` on the outer element and
  `th:replace` on a nested child element — and give the fragment a `th:block` root, not a real
  tag, so it doesn't add an extra wrapping element inside the caller's item tag.
- **The JDK/Windows loopback socket issue** described above under Setup.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for branch naming, merge, and commit-message conventions.

Work is tracked in Jira under the `FORMS` project; commits reference their ticket by key using
smart-commit syntax (e.g. `FORMS-11 #comment ...`).

## Known limitations

- **Jira↔GitHub smart-commit integration does not work.** Commit messages use smart-commit syntax
  as a readability convention, but as verified in
  [FORMS-9](https://vprokbv.atlassian.net/browse/FORMS-9), it does not produce Jira comments or
  transitions automatically — installing the "GitHub for Jira" app did not fix this either. Ticket
  comments and status transitions are done directly against the Jira API instead.
- **No authentication.** `AuditorAware` returns empty, so `created_by`/`updated_by` are null on
  every row until a real principal exists.
