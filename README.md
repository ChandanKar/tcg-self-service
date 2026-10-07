# VM Self-Service Platform

Spring Boot 3.5 API with a vanilla-JS single-page frontend for self-service control of AWS EC2 and EKS environments. See `CLAUDE.md` for architecture and build commands, and `docs/` for design notes.

## Running tests

| Command | What runs | Needs |
| --- | --- | --- |
| `./gradlew unitTest` | Tests that need no database | Nothing |
| `./gradlew test` | Every test, including integration tests | A MySQL 8 database (see below) |

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
