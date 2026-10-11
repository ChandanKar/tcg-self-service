# VM Self-Service Platform

Spring Boot 3.5 API with a vanilla-JS single-page frontend for self-service control of AWS EC2 and EKS environments. See `CLAUDE.md` for architecture and build commands, and `docs/` for design notes.

## Running locally

`./gradlew bootRun` starts the app with the `dev` profile unless `SPRING_PROFILES_ACTIVE` is set. Setting `ENTRAID_ENABLED=false` swaps in the permit-all dev security (a dev user is chosen with the `X-User-Id` header), so the app refuses to start that way unless the `dev` or `test` profile is active.

The frontend is served as one bundle (`dist/app.js`, `dist/app.css`) that Gradle builds from the files listed in `src/main/resources/static/index.html`; `bootRun`, `bootJar` and the tests rebuild it automatically. Run `./gradlew bootRun -PskipBundle` to load the individual source files instead. The database is MySQL 8 in every environment; Flyway validates migrations on startup and never repairs them automatically (see `docs/runbook-flyway.md`).

With Entra ID on, every POST, PUT, PATCH and DELETE needs the `X-XSRF-TOKEN` header, matching the `XSRF-TOKEN` cookie the server sets. `ApiClient` and the login page send it automatically, so API scripts must send it too.

## Running tests

| Command | What runs | Needs |
| --- | --- | --- |
| `./gradlew unitTest` | Tests that need no database | Nothing |
| `./gradlew test` | Every test, including integration tests | A MySQL 8 database (see below) |
| `node --test "src/test/js/**/*.test.mjs"` | Front-end helper tests (`src/test/js`) | Node.js 20+ |

Integration tests (subclasses of `AbstractIntegrationTest`) need MySQL 8. Pick one:

- **Local MySQL, no Docker.** Add to `.env`:

  ```
  TEST_DB_URL=jdbc:mysql://localhost:3306/vmcontrol_test?createDatabaseIfNotExist=true
  TEST_DB_USERNAME=<user>
  TEST_DB_PASSWORD=<password>
  ```

  The tests delete data in that schema, so the build refuses any schema name that does not end in `_test`.
- **Docker.** Leave `TEST_DB_URL` unset; Testcontainers starts a MySQL 8.0 container. CI uses this.

In tests, scheduled jobs are off and every AWS and Microsoft Graph client is a mock. `SecuredWebTestBase` runs the real Entra ID security chain, so tests can check `@PreAuthorize` rules as admin, environment admin, user or viewer.
