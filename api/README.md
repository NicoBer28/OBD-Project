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

### Telemetry

`telemetry` is the time series of everything a car has reported, and by the same
token the log of where it has been — nothing else records car movement.

Its shape follows from how a reading actually arrives. The ESP32 speaks BLE to
the phone and never HTTP to this API, so **the phone is the relay**: it buffers
readings while offline and uploads them in batches, out of order, retrying
whatever it is unsure about. Hence:

| Decision | Because |
|---|---|
| `recorded_at` **and** `received_at` | A reading taken in a tunnel belongs in history when it was taken; only `received_at` explains why it arrived an hour late. |
| `unique (car_id, recorded_at)` | A retry is rejected instead of doubling a car's history — ingestion is idempotent. Also serves as the index for a car's history and its latest reading. |
| `trip_id`, stamped at ingestion | A trip's route becomes an indexed read rather than a time-range join with fiddly boundaries. Null when the car reported outside any trip. |
| `raw_frame jsonb` | The decoder is unfinished (the firmware currently emits raw mode-01 frames). A column added in a later migration can be backfilled from these; a value discarded at ingestion is gone for good. |
| `bigint` primary key | The only table that is not `uuid`. This one grows without bound, and a reading is addressed as "this car, at this instant", never by id. |
| `cars.snapshot_at` | Says which reading the cached snapshot on `cars` came from, so a late upload cannot overwrite fresher values with stale ones. |

Note `telemetry.speed` is singular. `cars` originally carried `max_speed` and
`avg_speed`; `V6__drop_car_speed_aggregates.sql` removes them. The rest of the
snapshot is a *copy of one reading* — `snapshot_at` names which — so it can be
stale but never ambiguous. A max or an average is a copy of nothing: it is an
aggregate over many rows, and storing it would mean recomputing on every
reading or letting it drift silently, the same trap as storing fuel consumption
next to the two readings it comes from. They are `max(speed)`/`avg(speed)` over
`telemetry`, filtered by car or by `trip_id`, and belong in the stats endpoint.

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

---

### `POST /api/v1/cars`

Registers a car owned by the caller. Requires
`Authorization: Bearer <accessToken>`.

**Body** (`application/json`):

```json
{
  "name": "Ada's Gol",
  "licensePlate": "AB123CD",
  "modelId": "00000000-0000-4000-8000-000000000001",
  "mileage": 120000
}
```

| Field | Required | Notes |
|---|---|---|
| `name` | yes | non-blank, ≤ 60 chars; the label shown in car lists |
| `modelId` | yes | must exist in the model catalog |
| `licensePlate` | no | ≤ 16 chars; trimmed and upper-cased before storing |
| `mileage` | no | odometer reading at registration, ≥ 0 |

The owner is always taken from the access token, never from the body — a
client cannot create a car in someone else's name.

**Response** `201 Created`, with a `Location` header pointing at the new car:

```json
{
  "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "name": "Ada's Gol",
  "licensePlate": "AB123CD",
  "model": {
    "id": "00000000-0000-4000-8000-000000000001",
    "brand": "Volkswagen",
    "model": "Gol",
    "protocol": "ISO 15765-4 (CAN)"
  },
  "mileage": 120000,
  "fuelLevel": null,
  "batteryLevel": null,
  "latitude": null,
  "longitude": null
}
```

The telemetry fields are a cached snapshot of the last reading reported by the
device, so they are all `null` on a car that has never reported. They are never
accepted from the client.

**Errors:** `404 Not Found` if `modelId` is unknown, `409 Conflict` if the
caller already has a car with that plate, `400 Bad Request` with a per-field
error map if validation fails, `401 Unauthorized` without a valid access token.

Plates are unique **per owner**, not globally: making them globally unique
would let one account block another from registering a plate, and would leak
whether a plate is already known to the system.

The model catalog is seeded by `V2__cars_and_models.sql` with eight common
models, so this endpoint is usable before a catalog admin UI exists.

---

### `GET /api/v1/cars`

Every car the caller may use: their own, plus any shared with a group they
belong to. Requires `Authorization: Bearer <accessToken>`.

**Response** `200 OK` — an array of the same objects `POST /cars` returns,
ordered by name. Empty for a new user, never an error. The telemetry fields
are the cached snapshot maintained by `POST /telemetry`.

**One definition of "may use".** The list comes from `CarRepository.READABLE`,
a JPQL predicate that `CarAccess` also uses for the per-car check every write
goes through (`POST /trips`, `POST /telemetry`). Sharing the clause verbatim is
what guarantees the two cannot disagree: a car that appears in this list is,
by construction, one the same user can start a trip on. A two-query merge -
own cars plus cars-from-my-groups - would have listed a car shared with your
own family twice, and would have been a second copy of the rule to keep in
step. See `ROADMAP.md` §2.

**Errors:** `401 Unauthorized` without a token.

---

### `POST /api/v1/groups`

Creates a group (a "family") and enrols the caller as its first member with the
`ADMIN` role. Requires `Authorization: Bearer <accessToken>`.

**Body** (`application/json`):

```json
{ "name": "Familia Lazzari" }
```

| Field | Required | Notes |
|---|---|---|
| `name` | yes | non-blank, ≤ 60 chars |

Group names are **not** unique — two unrelated families may both be "Los
Lopez". The name is a label, not an identifier.

The creator is always taken from the access token, never from the body.

**Response** `201 Created`, with a `Location` header:

```json
{
  "id": "e6c055de-e501-4e00-baf5-9cbc2d6722d2",
  "name": "Familia Lazzari",
  "createdAt": "2026-09-08T16:45:39.848482Z",
  "memberCount": 1,
  "callerRole": "ADMIN"
}
```

`memberCount` is counted from `group_members` on every read, never stored — the
ER diagram marks `cant_integrantes` as derivable. `callerRole` is the calling
user's role in this group, so a client can decide what to show without a second
request.

Creating the group and enrolling the creator happen in one transaction: a group
with no members would be unreachable — nobody could list, join or delete it —
so it must never be observable, not even after a crash between the two inserts.

**Errors:** `400 Bad Request` if the name is blank or too long, `401
Unauthorized` without a valid access token.

---

### `POST /api/v1/trips`

Starts a trip ("viaje"): records that the caller is now using a car, and leaves
the trip open until it is finished. Requires
`Authorization: Bearer <accessToken>`.

**Body** (`application/json`):

```json
{
  "carId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "initialFuel": 70
}
```

| Field | Required | Notes |
|---|---|---|
| `carId` | yes | must be a car the caller owns |
| `initialFuel` | no | fuel reading at the start, ≥ 0; defaults to the car's last reported level |

The driver is always taken from the access token and the start time from the
server clock, so a trip cannot be logged in someone else's name or backdated.

**Response** `201 Created`, with a `Location` header:

```json
{
  "id": "facb7ae2-cf8e-45f9-8fe4-c9d1b747354d",
  "carId": "a6eefd2a-bf17-4c5d-9597-a4029db33f4b",
  "driverId": "63173dc5-520b-4d84-8bbb-8a8367741ba7",
  "startedAt": "2026-09-09T02:45:43.663568Z",
  "endedAt": null,
  "initialFuel": 70,
  "finalFuel": null,
  "fuelUsed": null,
  "distance": null,
  "active": true
}
```

`fuelUsed` is `initialFuel - finalFuel`, computed on every read and never
stored: a stored total would be a second source of truth that can drift from
the two readings it comes from. It stays `null` until the trip is finished, and
afterwards if either reading is missing.

`active` is simply `endedAt == null`. **A car's current trip, and therefore its
current driver, is that open row** — the car holds no pointer back to it, so the
two can never disagree.

At most one trip may be open per car. That is enforced by a partial unique
index (`ux_trips_one_active_per_car`), not only by a check in the service, so
simultaneous requests cannot both win: six parallel starts on one car yield one
`201` and five `409`.

**Errors:** `404 Not Found` if the car does not exist **or belongs to someone
else** — the two are deliberately indistinguishable, since a `403` would confirm
that an id is real; `409 Conflict` if the car is already on a trip; `400 Bad
Request` with a per-field error map if validation fails; `401 Unauthorized`
without a valid access token.

---

### `POST /api/v1/telemetry`

Stores a batch of readings for one car and brings the car's cached snapshot up
to date. Requires `Authorization: Bearer <accessToken>`.

The phone is the relay (the ESP32 speaks only BLE), so it uploads whatever the
dongle sent since the last upload — batched, possibly late, possibly a repeat of
something it was unsure about. The endpoint is shaped for that: **retrying a
batch is always safe.**

**Body** (`application/json`):

```json
{
  "carId": "2d14dd55-c2c8-4b5a-aae8-b1c93a632cfc",
  "readings": [
    {
      "recordedAt": "2026-09-10T14:56:13Z",
      "latitude": -34.6037, "longitude": -58.3816,
      "speed": 40, "fuelLevel": 70, "batteryLevel": 85, "mileage": 120000,
      "raw": {"pids": {"04": "5020"}}
    },
    {"recordedAt": "2026-09-10T14:56:18Z", "speed": 55, "fuelLevel": 69, "raw": "01045020"}
  ]
}
```

| Field | Required | Notes |
|---|---|---|
| `carId` | yes | one car per batch |
| `readings` | yes | 1–500 entries |
| `readings[].recordedAt` | yes | when the reading was **taken** (device time), not uploaded; at most 5 minutes in the future |
| `readings[].latitude` / `longitude` | no | both or neither; valid WGS-84 ranges |
| `readings[].speed`, `fuelLevel`, `batteryLevel`, `mileage` | no | ≥ 0 each |
| `readings[].raw` | no | any JSON — the frame as the device sent it, kept verbatim |

The uploader is taken from the access token. The car must be one the caller
may read (its owner, until sharing exists).

**Response** `200 OK`:

```json
{
  "carId": "2d14dd55-c2c8-4b5a-aae8-b1c93a632cfc",
  "stored": 3,
  "duplicates": 0,
  "tripId": null,
  "snapshotUpdated": true,
  "latestRecordedAt": "2026-09-10T14:56:24Z"
}
```

| Field | Meaning |
|---|---|
| `stored` / `duplicates` | How many were new versus already in the database. Both are success — a retry that finds its readings present has done its job. Use them to trim the buffer. |
| `tripId` | The car's open trip, or `null` if the car is reporting with no trip open — the cue to prompt the driver to start one. |
| `snapshotUpdated` | Whether the car's cached state moved. `false` means the whole batch was older than what the car already knew: nothing to redraw. |
| `latestRecordedAt` | The newest `recordedAt` in the batch — the client's sync cursor. |

`200` rather than `201`: a batch that turns out to be all duplicates creates
nothing, and there is no single resource for a `Location` header to point at.
The readings are not echoed back; the client already has them.

**What happens to each reading.** It is inserted with
`on conflict (car_id, recorded_at) do nothing` — a duplicate is skipped, not an
error. (Saving each and catching the duplicate-key exception would not work:
Postgres aborts the transaction on the first violation and every statement
after it fails, so a batch would lose everything past its first duplicate.) If
the car has an open trip, readings taken **after the trip started** are stamped
with it; a late-flushed reading from before the driver pressed "start" is
history from when the car was parked, not part of the trip.

**What happens to the car.** The batch is folded into one effective reading —
each field from the newest reading that carries it — and the snapshot on
`cars` is refreshed with a single conditional `UPDATE ... where snapshot_at is
null or snapshot_at < :recordedAt`. The comparison is in the `WHERE` clause on
purpose: read-compare-write in Java would be a lost update between two
concurrent batches. Fields the batch did not carry keep their previous value
(`coalesce`), so a fuel-only frame does not blank the last known position. See
`ROADMAP.md` Part 4 for the full reasoning.

**Errors:** `400 Bad Request` rejects the **whole batch**, with errors keyed by
index (`readings[2].positionComplete`, `readings[3].notFromTheFuture`) — a
malformed reading is a serialiser bug on the phone, and half-applying a batch
would leave its buffer state unknowable. `404 Not Found` if the car does not
exist or is not the caller's. `401 Unauthorized` without a token. There is
deliberately no `409`: unlike a duplicate trip start, a duplicate reading is the
normal retry path.

---

### `GET /api/v1/telemetry`

One page of a car's readings after a cursor, oldest first. Shaped for
incremental sync: the client stores `nextSince` and keeps calling while
`hasMore` is true. Requires `Authorization: Bearer <accessToken>`.

| Query param | Required | Notes |
|---|---|---|
| `carId` | yes | must be a car the caller may read |
| `since` | no | ISO-8601 instant; returns readings recorded **strictly after** it. Absent means from the beginning. |
| `limit` | no | 1–500, default 100 |

**Response** `200 OK`:

```json
{
  "carId": "40a8379c-75d0-46af-997f-01509cc23dd6",
  "readings": [
    {
      "tripId": null,
      "recordedAt": "2026-09-10T17:37:52Z",
      "receivedAt": "2026-09-10T17:38:26.388044Z",
      "latitude": -34.6037, "longitude": -58.3816,
      "speed": 40, "fuelLevel": 70, "batteryLevel": 85, "mileage": 120000,
      "raw": {"pids": {"04": "5020"}}
    }
  ],
  "nextSince": "2026-09-10T17:37:52Z",
  "hasMore": true
}
```

`since` is exclusive because the client passes back a value it was given —
`nextSince` from the last page, or `latestRecordedAt` from an ingest — and
already holds that row. `nextSince` is the last reading's `recordedAt`, or the
cursor you passed in when the page is empty, so it can always be stored
unconditionally. `hasMore` comes from fetching one row past the limit, which
spares both a final empty round trip and a count over the largest table.

**Oldest first, on purpose.** Newest-first with a limit is what a display
wants, but it cannot be paged forward correctly: whatever fell between the
newest N and the cursor is silently skipped. Ascending with an exclusive cursor
walks the whole series with no gaps and no repeats — the client has a local
cache, so it syncs everything and sorts locally.

The query is a single range scan on `ux_telemetry_car_recorded_at` from the
cursor forward, so it costs the same on a car with a million readings as on one
with ten. `raw` is emitted as the JSON that was stored, not as a string
containing JSON.

**Errors:** `400 Bad Request` for a missing `carId`, a `limit` outside 1–500,
or a `since` that does not parse — the parameters bind to a record so a type
error is a field error, not a `500`. `404 Not Found` if the car does not exist
or is not the caller's. `401 Unauthorized` without a token.

## Auth flow at a glance

1. `register`/`login` → access token (body) + refresh token (cookie), same
   "family" id for the refresh token.
2. Protected endpoints are called with `Authorization: Bearer <accessToken>`.
3. When the access token expires, call `refresh` (cookie sent automatically)
   to get a new access token and a rotated refresh token.
4. `logout` revokes the whole refresh-token family server-side.

## Testing

```bash
./mvnw test
```

Docker must be running; nothing else is required (no local Postgres, no
environment variables). There are two lanes:

**Controller slices** - `@WebMvcTest` with the service layer mocked. They cover
routing, request validation, response shape and error mapping. The production
security chain is excluded from the slice on purpose: `JwtAuthFilter` drags in
`JwtService` and `AppUserDetailsService`, and whether a route is public or
protected is a wiring question that belongs to a full-context test.

A slice whose endpoint needs the caller's identity imports
`support/SliceSecurityConfig`, a permissive chain, and injects the principal
with `.with(authentication(...))`. Note that
`@AutoConfigureMockMvc(addFilters = false)` cannot be used in that case:
without `SecurityContextHolderFilter` nothing loads the stored context and
`@AuthenticationPrincipal` silently arrives `null`.

**Repository tests** - `@RepositoryTest` (see `support/RepositoryTest.java`)
runs against a real Postgres 16 in a container. Flyway builds the schema and
Hibernate validates the entities against it, so an entity that drifts from its
migration fails here instead of at startup in production. The container is a
bean rather than a `@Container` static field, so Spring's test-context cache
keeps one container alive for the whole run instead of starting a fresh one per
test class.

`ApiApplicationTests` boots the whole application - every bean and the real
security filter chain - against the same container.

If you use **Colima** rather than Docker Desktop, the `colima` Maven profile
activates itself and points Testcontainers at the socket under your home
directory. Docker Desktop and CI need no setup. Running tests from an IDE with
Colima needs those two variables set on the run configuration, since the profile
only applies to Maven:

```
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Known limitations

What is missing, in what order to build it, and the endpoint roadmap live in
[`ROADMAP.md`](ROADMAP.md). This list is the short version.

- Cars cannot yet be shared with a group. `V7__cars_group_id.sql` adds the
  nullable `cars.group_id` column and `Car.carGroup` maps it, but nothing writes
  it: the share/unshare endpoints and the widening of `CarAccess.readableBy` to
  group members are still to do (ROADMAP Phase 4).
- Only group *creation* exists. Listing groups, adding/removing members and
  changing roles are not implemented.
- `GroupMemberRepository.save()` is an upsert, not an insert: the composite id is
  assigned by us, so Spring Data cannot tell a new row from an existing one and
  merges. Adding a member who is already in the group would silently rewrite
  their role. The add-member endpoint must check membership first - see
  `GroupRepositoryTest.savingAnExistingMembershipSilentlyChangesTheRole`.
- Telemetry history is only readable oldest-first from a cursor. A
  newest-first view for display (`order=desc`) is not implemented; the client is
  expected to sync into its local cache and sort there.
- Telemetry grows without bound and nothing prunes it. At real volume this wants
  a retention window or monthly partitioning; neither is worth building before
  there is a device actually reporting.
- Only *starting* a trip exists. Finishing one, the active-trip lookup, trip
  history per car and per user, and the aggregated fuel expense per user are not
  implemented.
- Access is already "owner **or** member of the group the car is shared with"
  (`CarRepository.READABLE`, via `CarAccess`), for the car list, starting a
  trip and uploading telemetry alike - but no endpoint can *set*
  `cars.group_id` yet, so in practice every car is still owner-only until
  share/unshare exist.
- Deleting a car deletes its trips (`on delete cascade`), unlike deleting a user,
  which orphans them. There is no delete-car endpoint yet, so this is still free
  to change if trip history should outlive the car.
- Finishing a trip will have to flush the closing `UPDATE` before inserting the
  next trip on that car: Hibernate orders inserts ahead of updates within one
  flush, so a start-immediately-after-finish would otherwise collide with the
  trip it just closed. Pinned by
  `TripRepositoryTest.allowsANewTripOnceThePreviousOneIsFinished`.
- Whether `fuel_level` and `battery_level` are percentages or absolute units is
  undecided, so V2 constrains them to `>= 0` rather than `0..100`. Tighten in a
  later migration once the firmware settles what it reports.
- `GET /api/v1/cars/{id}`, updating and deleting a car are not implemented.
  The `Location` header returned by create therefore points at a route that
  does not exist yet.
- `CarDTO.Read` does not yet expose `snapshotAt` or the group a car is shared
  with, so a client cannot show "last seen 3 hours ago" or "shared with
  Familia Lazzari".
- `AuthController.logout` is still not covered by the controller slice. It reads
  `@AuthenticationPrincipal`, so `SliceSecurityConfig` would now make this
  straightforward.
- Mockito logs a self-attach warning on JDK 25. Harmless today; it will need
  the Mockito agent wired into Surefire before a JDK that removes self-attach.
