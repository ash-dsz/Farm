# Farm – backend

Spring Boot API for the Farmora app: farmer accounts (mobile number + password),
offline-first synchronisation, wage ledger, crop prices, and the platform owner's admin API with
the global kill switch and farmer blocking.

- Java 21+ (tested with 25), Spring Boot 4.1, Spring Security, PostgreSQL, Flyway
- Packages: `auth` (accounts, tokens), `sync` (push/pull of farm data), `ledger` (wage maths),
  `access` (kill switch), `admin` (dashboard API, audit log, CSV exports), `prices` (data.gov.in mandi prices)

## Run locally

```bash
./gradlew bootTestRun     # embedded PostgreSQL (data in .devdb/), dev profile, http://localhost:8080
./gradlew test            # integration + unit tests on an embedded PostgreSQL (no Docker needed)
```

## Production

The public health check is available at `GET /api/health` and returns `{"status":"UP"}` when
the application and its health contributors are healthy; otherwise it returns HTTP 503 with the
reported status.

```bash
./gradlew bootJar
SPRING_PROFILES_ACTIVE=prod \
DATABASE_URL='jdbc:postgresql://<host>:5432/<db>?sslmode=require' \
DATABASE_USERNAME=... DATABASE_PASSWORD=... \
JWT_SECRET=<long random secret> \
java -jar build/libs/farmer-accounts-backend-1.0.0.jar
```

The schema is created and migrated automatically by Flyway on start-up.

| Variable | Required | Purpose |
|---|---|---|
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | yes | PostgreSQL connection |
| `JWT_SECRET` | yes | signs farmer logins (≥ 32 bytes, keep it stable) |
| `DATABASE_POOL_SIZE` | no (10) | connection pool size (5 on Supabase free tier) |
| `DATA_GOV_IN_API_KEY` | no | enables the crop-price endpoint |
| `ADMIN_BOOTSTRAP_USERNAME`, `ADMIN_BOOTSTRAP_PASSWORD` | first start only | creates the first owner admin |
| `REQUIRE_HTTPS`, `ADMIN_COOKIE_SECURE` | no (true) | serve behind HTTPS |
| `OTP_ENABLED` + `OTP_*` | no (off) | optional SMS OTP login |

Never commit real secrets; pass them as environment variables.
