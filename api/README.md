# OBD API

Spring Boot backend for OBD. Authentication is JWT-based: a short-lived access
token returned in the response body, and a long-lived refresh token delivered
as an `httpOnly` cookie.

## Database

Postgres is required — the app will not start without it. Create the database
once; the tables are created for you by Flyway on first startup:

```bash
createdb obd
```

Or with Docker:

```bash
docker run -d --name obd-pg -e POSTGRES_DB=obd \
  -e POSTGRES_USER=obd -e POSTGRES_PASSWORD=obd \
  -p 5432:5432 postgres:16-alpine
```

The schema lives in `src/main/resources/db/migration`. Flyway applies any
pending migrations at startup and Hibernate then validates the entities
against the result (`ddl-auto=validate`) — Hibernate never creates or alters a
table itself, in any environment. To change the schema, add a new
`V2__description.sql`; never edit a migration that has already run.

## Running

Copy `.env.example` to `.env` and fill in real values, then load it into your
shell before running (this app does **not** auto-load `.env` — see the note
in `.env.example` for why):

```bash
set -a; source .env; set +a
./mvnw spring-boot:run
```

Or build and run the jar directly:

```bash
set -a; source .env; set +a
./mvnw -DskipTests package
java -jar target/api-0.0.1-SNAPSHOT.jar
```

In IntelliJ: open the Run/Debug configuration for `ApiApplication`, go to
**Modify options → Environment variables**, and set the values there (or use
an EnvFile-capable plugin) instead of relying on `.env`.

Required environment variables (see `.env.example` / `application.properties`):

| Variable | Purpose |
|---|---|
| `DB_USER` / `DB_PASSWORD` | Postgres credentials |
| `DB_URL` | JDBC URL; optional, defaults to `jdbc:postgresql://localhost:5432/obd` |
| `JWT_SECRET` | Base64-encoded HMAC signing key for access tokens |
| `CORS_ORIGINS` | Comma-separated frontend origin(s) allowed via CORS (defaults to `http://localhost:5173`) |

## Endpoints

Base path: `/api/v1/auth`

### `POST /api/v1/auth/register`

Creates a new user, then logs them in (issues tokens).

**Body** (`application/json`):

```json
{
  "userName": "Ada",
  "userLastName": "Lovelace",
  "userEMail": "ada@example.com",
  "userPassword": "supersecret123",
  "userPhone": "+39 320 1234567"
}
```

| Field | Required | Notes |
|---|---|---|
| `userName` | yes | non-blank |
| `userLastName` | yes | non-blank |
| `userEMail` | yes | must be a valid email |
| `userPassword` | yes | 8–72 characters |
| `userPhone` | no | must match a phone-number pattern if present |

**Response** `200 OK`:

```json
{
  "accessToken": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "expireInSeconds": 900,
  "userId": "8f14e...-...",
  "email": "ada@example.com"
}
```

Also sets a `refreshToken` cookie (`httpOnly`, `SameSite=Strict`, path
`/api/v1/auth`).

**Errors:** `409 Conflict` if the email is already registered, `400 Bad
Request` with a per-field error map if validation fails.

---

### `POST /api/v1/auth/login`

**Body** (`application/json`):

```json
{
  "userMail": "ada@example.com",
  "userPassword": "supersecret123"
}
```

| Field | Required | Notes |
|---|---|---|
| `userMail` | yes | must be a valid email |
| `userPassword` | yes | 8–72 characters |

**Response:** same shape as `register` — `AuthResponseDTO` body + `refreshToken` cookie.

**Errors:** `401 Unauthorized` on bad credentials.

---

### `POST /api/v1/auth/refresh`

Rotates the refresh token and issues a new access token. No request body —
the refresh token is read from the `refreshToken` cookie automatically sent
by the browser.

**Response:** same `AuthResponseDTO` body as `login`, plus a new `refreshToken`
cookie (the old one is invalidated — refresh tokens are single-use).

**Errors:** `401 Unauthorized` if the cookie is missing, expired, or already
used (reuse of a rotated-out token revokes the entire token family, forcing
re-login).

---

### `POST /api/v1/auth/logout`

Requires a valid `Authorization: Bearer <accessToken>` header. Revokes all
refresh tokens for the current user and clears the `refreshToken` cookie.

**Response:** `204 No Content`.

## Auth flow at a glance

1. `register`/`login` → access token (body) + refresh token (cookie), same
   "family" id for the refresh token.
2. Protected endpoints are called with `Authorization: Bearer <accessToken>`.
3. When the access token expires, call `refresh` (cookie sent automatically)
   to get a new access token and a rotated refresh token.
4. `logout` revokes the whole refresh-token family server-side.

## Known limitations

- The `car` package (`Car`, `Model`, `CarRepository`, `CarService`,
  `CarController`) is still empty scaffolding — no entity mapping and no
  migration yet.
- `ApiApplicationTests.contextLoads` is a full `@SpringBootTest`, so it needs a
  reachable Postgres. Use `./mvnw -DskipTests package` without one.
