# Operations and delivery

## Runtime configuration

The application reads its database, retry, reconciliation and bootstrap identity settings from
environment variables.

### Database and reliability

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/aurum` | PostgreSQL JDBC URL |
| `DB_USER` | `aurum` | Database username |
| `DB_PASSWORD` | `aurum` | Database password |
| `DB_POOL_SIZE` | `10` | Maximum Hikari connection-pool size |
| `POSTGRES_RETRY_MAX_ATTEMPTS` | `3` | Total attempts for a retryable transfer |
| `POSTGRES_RETRY_BASE_DELAY_MILLIS` | `10` | Base delay used to calculate retry jitter |
| `RECONCILIATION_SCHEDULE_ENABLED` | `true` | Enables scheduled reconciliation |
| `RECONCILIATION_SCHEDULE_CRON` | `0 0 * * * *` | Spring six-field reconciliation cron |
| `RECONCILIATION_SCHEDULE_ZONE` | `UTC` | Time zone for the reconciliation cron |

### Bootstrap identities

| Variable | Default |
|---|---|
| `AURUM_CUSTOMER_USERNAME` | `customer` |
| `AURUM_CUSTOMER_PASSWORD` | `customer-local` |
| `AURUM_OPERATOR_USERNAME` | `operator` |
| `AURUM_OPERATOR_PASSWORD` | `operator-local` |
| `AURUM_AUDITOR_USERNAME` | `auditor` |
| `AURUM_AUDITOR_PASSWORD` | `auditor-local` |
| `AURUM_ADMIN_USERNAME` | `admin` |
| `AURUM_ADMIN_PASSWORD` | `admin-local` |

Replace the database and identity credentials before running Aurum in a shared environment.

## Local application

The Compose file starts a health-checked PostgreSQL 16 service with a named data volume:

```bash
docker compose up -d postgres
mvn spring-boot:run
```

Flyway applies all pending migrations at startup. Stop PostgreSQL while preserving its volume:

```bash
docker compose down
```

## Executable JAR

Build and run the application directly:

```bash
mvn verify
java -jar target/aurum-0.1.0-SNAPSHOT.jar
```

The JAR expects the configured PostgreSQL database to be reachable. `mvn verify` compiles the
application, runs the standard test suite and packages the Spring Boot executable JAR.

## Container image

Build the JAR before building the image:

```bash
mvn verify
docker build -t aurum:local .
```

The image uses the Eclipse Temurin Java 21 Alpine JRE, copies the packaged JAR, exposes port 8080
and runs the Java process as numeric user `10001`. PostgreSQL remains a separate service. Pass
database credentials and any reconciliation or identity overrides as container environment
variables.

## Continuous integration

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs for pushes and pull requests. The job
uses Temurin Java 21, restores the Maven dependency cache and executes:

```bash
mvn --batch-mode verify
```

This workflow compiles the code, runs JUnit, MockMvc, Testcontainers and jqwik verification, and
packages the JAR. The SQL and HTTP benchmark classes run through their documented commands rather
than the standard CI suite.

## GHCR publishing

[`.github/workflows/publish-container.yml`](../.github/workflows/publish-container.yml) verifies the
project, builds the image and publishes it to GitHub Container Registry. It runs when a `v*` tag is
pushed and can also be started manually from GitHub Actions.

Published image names use lowercase repository coordinates:

```text
ghcr.io/<owner>/<repository>:<version>
ghcr.io/<owner>/<repository>:<major>.<minor>
ghcr.io/<owner>/<repository>:sha-<commit>
```

The workflow authenticates with the repository-scoped `GITHUB_TOKEN`, requests `contents: read` and
`packages: write`, and attaches build provenance and an SBOM. GitHub Packages controls the image's
visibility.

## Storage and cleanup

`mvn clean` removes classes, test reports, benchmark reports and JAR files under `target/`.

The Compose-managed PostgreSQL volume preserves local data across container restarts. Delete it only
when the database contents are no longer needed:

```bash
docker compose down --volumes
```

Testcontainers removes its temporary PostgreSQL containers and writable layers after each test
process. The shared `postgres:16-alpine` image remains available for later runs.
