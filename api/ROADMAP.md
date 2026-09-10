# OBD-C API — roadmap

What is missing, what to build next, and in what order. Companion to
`README.md`, which documents what already **exists**; this file is about what
does not.

Last updated: 2026-09-10. Schema is at `V7__cars_group_id.sql`.

---

## Where things stand

| Area | Schema | Endpoints |
|---|---|---|
| Auth / users | `users`, refresh tokens | register, login, refresh, logout |
| Cars | `cars` (now with `group_id`), `models` | `POST /cars`, `GET /cars` |
| Groups | `groups`, `group_members` | `POST /groups` only |
| Trips | `trips` | `POST /trips` (start) only |
| Telemetry | `telemetry`, `cars.snapshot_at` | `POST /telemetry` (batch ingest), `GET /telemetry` (sync) |

Five `POST`s and two `GET`s. Every `Location` header currently returned points at
a route that does not exist. That is the shape of the work below.

`CarAccess` exists and already answers "owner **or** group member", from one
predicate (`CarRepository.READABLE`); `CarService`, `TripService` and
`TelemetryService` all go through it. Nothing can set `cars.group_id` yet.

---

## Part 1 — Open design decisions

Ten things that are missing or undecided. Roughly in the order they hurt.

### 1. `cars.group_id` — the product premise is not in the schema

Every access check in the codebase is "am I the owner". Family sharing, the
entire point of the app, has nowhere to live. A nullable `group_id` on `cars`,
FK to `groups`, `ON DELETE SET NULL` (deleting a group must un-share the cars,
not delete them). One car belongs to at most one group at a time — that was the
rule from the start, and a plain column enforces it for free where a join table
would not.

*Decide before:* any read endpoint that filters cars.

### 2. One access rule, not eight

The moment sharing lands, `TripService`, telemetry ingestion, and every car
read each need "owner **or** member of the car's group". Write it once:

```java
// CarAccess.java
Optional<Car> readableBy(UUID userId, UUID carId);   // owner or group member
Optional<Car> ownedBy(UUID userId, UUID carId);      // owner only: share, delete, rename
```

Eight hand-rolled copies of that predicate is exactly how one endpoint ends up
quietly wrong.

**Done.** `CarRepository.READABLE` is the predicate; `findAllReadableBy` and
`findReadableBy` share it verbatim, and `CarAccess` is the only caller. It
already includes group membership, so Phase 4 needs no change here — proven by
`TripServiceTest.aGroupMemberMayStartATripOnASharedCar`, which passes without
`TripService` knowing groups exist.

### 3. A `devices` table — the dongle is unmodelled

The ESP32 has an identity (BLE address, serial) and none of it exists in the
database. Without it you cannot move a dongle between cars, cannot say which
device produced a reading, and cannot revoke one that was sold with the car.

```sql
devices(device_id, car_id, ble_address unique, paired_at, last_seen_at)
```

### 4. Group invitations

Only group *creation* exists. Adding a member today would require knowing their
`user_id`, and the alternative — a user-search endpoint — leaks your user table
to anyone with an account. An invite code (or an emailed link) sidesteps that
entirely: the invitee brings the token, so nobody has to look anyone up.

⚠ `GroupMemberRepository.save()` is an **upsert, not an insert** — the composite
id is assigned by us, so Spring Data cannot tell a new row from an existing one
and merges. Adding someone already in the group silently rewrites their role.
The add-member path **must check membership first**. Pinned by
`GroupRepositoryTest.savingAnExistingMembershipSilentlyChangesTheRole`.

### 5. `?since=` on history reads

This is the offline-cache primitive already wanted for the phone. The client
stores its last-seen `recorded_at` and asks only for the delta. Combined with
the idempotent ingest that `ux_telemetry_car_recorded_at` already gives you,
sync becomes trivial in both directions. Pair it with a **batch** ingest
endpoint, since the relay uploads in batches by nature.

### 6. Settle whether fuel is a percentage or litres

`V2` and `V5` both dodge this with `>= 0` floors instead of `0..100`. Every day
of real data makes the tightening migration harder, and `fuelUsed` cannot be
rendered with a unit until it is decided. Same question for `battery_level`.

*Decide before:* the first device reports for real.

### 7. OpenAPI (springdoc)

The Flutter side needs a contract, and it generates client models from what is
already built. On a three-subsystem project this is unusually high leverage for
one dependency and zero code.

### 8. Compute expenses in SQL

"Fuel expense per user" is `sum(initial_fuel - final_fuel) group by driver_id`
with a date range — a single query, not trips loaded into Java and summed in a
loop. Decide that before the endpoint exists, or it will be written the slow
way and stay that way.

### 9. Notify when a trip starts

"Someone just took the car" is the feature that makes a family car-sharing app
worth installing, and it falls straight out of the open-trip row that already
exists. Needs a `device_tokens` table (push tokens per user per phone).

### 10. Retention for telemetry

Nothing prunes it and it grows without bound. Not worth building before a real
device is reporting, but decide the window (or monthly partitioning) *before*
the table is large — both are painful to retrofit.

### Still open: an audit log

Deliberately not built. `telemetry` logs where a car went; nothing logs who
removed a member, un-shared a car, or deleted a trip. If that is wanted, it is
a separate table plus a choice: Postgres triggers (catch every change, including
manual SQL, actor passed via a session variable) or application code (easier to
read, blind to anything that bypasses the API).

---

## Part 2 — Conventions every endpoint follows

These are already true of the four endpoints that exist. Keeping them true is
most of what makes the API coherent.

**Identity and authorization**
- The actor always comes from `@AuthenticationPrincipal`, **never** from the
  body. A stray `ownerId`/`driverId` in the payload is ignored, not rejected —
  it simply is not part of the API.
- A resource the caller may not see returns **404, not 403**. A 403 confirms the
  id is real. `CarNotFoundException` documents this.
- Route it through the access rule from §2, never an inline `equals` check.

**Derived data is never stored**
- Fuel expense = `initial_fuel - final_fuel`, computed on read.
- `memberCount` is counted from `group_members`.
- A car's current trip/driver is the row with `ended_at is null`.
- Max/avg speed are aggregates over `telemetry`, not readings — which is why
  they are no longer columns on `cars` (`V6`). A snapshot column is a copy of
  one reading; an aggregate is a copy of nothing.

**Schema**
- Migrations are immutable once run. Change = new `V{n}__*.sql`.
- `ddl-auto=validate`: Flyway owns the schema, Hibernate only checks the
  entities match. Entity/migration drift fails in the repository tests.
- Invariants belong in the database, not only the service. The service check
  exists for a clean 409; the constraint is what survives a race.
- Floors (`>= 0`) over ranges while units are unsettled.

**Shape**
- Errors are RFC-9457 `ProblemDetail` via `GlobalExceptionHandler`.
- Every list is paged. There is no such thing as "all the telemetry".
- `open-in-view=false`: map to a DTO **inside** the service transaction, or a
  lazy association throws on the way out.

**Definition of done, per endpoint**
1. Controller slice test (`@WebMvcTest` + `SliceSecurityConfig`) — status codes,
   validation, error mapping, principal-not-body.
2. Repository test (`@RepositoryTest`, real Postgres) for any new query or
   constraint.
3. A check added to `scripts/test-endpoints.sh`.
4. A `README.md` section, including the reasoning behind anything non-obvious.
5. Anything you decided *not* to do goes in README "Known limitations".

---

## Part 3 — Endpoint roadmap

Ordered by the cost of *not* having it yet — which is not the same as how
urgent the feature is. Ingestion comes first because delay loses data
permanently; everything after it only loses time.

### Phase 1 — Close the hardware loop. Everything else can wait; data cannot.

Reads can be built at any time and lose nothing by being late. **Ingestion is
different: every reading the device produces before this endpoint exists is
gone for good.** That asymmetry is what puts it first — not that it is the most
urgent feature, but that it is the only one with an unrecoverable cost for
being late.

Two more reasons it belongs here rather than late:

- The whole snapshot on `cars` (`fuel_level`, `battery_level`, `mileage`,
  position, `snapshot_at`) stays `null` forever until something writes it. Car
  reads built before ingestion return a car with nothing in it, so the two are
  worth building together.
- It is the first time the full path — ESP32 → BLE → phone → API → Postgres —
  runs end to end, across three subsystems and two people. Integration seams
  get more expensive to discover the longer they go unexercised.

Nothing blocks it. Ingestion needs `CarAccess` (introduced here) and the car's
open trip (`POST /trips` already exists). It does **not** need the `devices`
table: the phone is a logged-in client and relays with the user's own access
token, so there is no device-credential problem to solve yet.

**`CarAccess` is in place** (owner-only) — see `car/CarAccess.java` — so Phase 4
is a one-method change instead of a sweep through every endpoint.

| Endpoint | Notes |
|---|---|
| ~~`POST /telemetry`~~ **done** | Batch ingest; `carId` in the body rather than the path. Response is the summary in the README. |
| ~~`GET /telemetry?carId=&since=&limit=`~~ **done** | Oldest-first from an exclusive cursor, `hasMore` + `nextSince`. The sync primitive from §5. |
| ~~`GET /cars`~~ **done** | Through `CarAccess.allReadableBy`. Still to add to the DTO: `snapshotAt` and the group. |
| `GET /cars/{id}` | The route `POST /cars` already advertises in `Location`. |

Ingestion algorithm, in order:
1. Access check via `CarAccess`.
2. Look up the car's open trip once per batch; stamp `trip_id` on readings whose
   `recorded_at` falls inside it.
3. Insert. A duplicate `(car_id, recorded_at)` is **success, not an error** —
   that is what makes the relay's retries safe.
4. Refresh the car's snapshot **only if** the newest `recorded_at` in the batch
   is later than `cars.snapshot_at`, and set `snapshot_at` with it. Skipping
   this check lets a late buffer flush overwrite fresh values with stale ones.
5. Keep `raw_frame` verbatim, even for readings you could not decode.

Traps:
- Until sharing lands (Phase 4), `CarAccess` is owner-only, so **only the
  owner's phone can relay**. A group member driving the car cannot upload. That
  is acceptable temporarily, but note it in the README rather than discovering
  it in the field.
- A batch will routinely contain readings you already have. Decide the response
  shape now: how many were stored versus already known, so the relay can trim
  its buffer with confidence.

### Phase 2 — The remaining reads. Nothing is visible without them.

The app still cannot render a screen that is not about one car. Cheapest phase,
largest unblock.

| Endpoint | Notes |
|---|---|
| `GET /users/me` | `UserService`/`UserController` are empty stubs. Every app needs this on launch. |
| `GET /models` | The create-car form cannot populate its dropdown without the catalog. Static, cacheable. |
| `GET /cars/{id}/trips/active` | The "who has the car right now" lookup. Derived from `ended_at is null`. 204 or `null` when idle — pick one and document it. |
| `GET /groups` | Groups the caller belongs to, with `memberCount` and `callerRole`. |
| `GET /groups/{id}` | Members list included, or a separate `/members` route. |
| `GET /trips/{id}` | The route `POST /trips` advertises. |
| `GET /trips` | The caller's history, paged, newest first. |
| `GET /cars/{id}/trips` | One car's history, paged. |

### Phase 3 — Finish the trip lifecycle.

Until this exists `fuelUsed` is always `null` and the expense feature is dead.

| Endpoint | Notes |
|---|---|
| `POST /trips/{id}/finish` | Body: `finalFuel?`, `distance?`. Sets `ended_at` = server clock. |
| `DELETE /trips/{id}` | Cancel a trip started by mistake. Only while active. |
| `GET /trips/{id}/route` | Readings stamped with this trip, oldest first. Works on an active trip too. |

Traps:
- Only the **driver** may finish or cancel; the car's owner may not finish
  someone else's trip out from under them.
- `finalFuel > initialFuel` is legal — the driver refuelled. Do not "validate"
  it away.
- Finishing must **flush the `UPDATE` before any subsequent `INSERT`** on that
  car: Hibernate orders inserts ahead of updates within one flush, so a
  start-immediately-after-finish would collide with the trip it just closed.
  Pinned by `TripRepositoryTest.allowsANewTripOnceThePreviousOneIsFinished`.
- Finishing a trip is a good moment to bring the car's `mileage` up to date.
  Speed figures are **not** stored — they are computed from `telemetry` in the
  stats endpoint (Phase 6).

### Phase 4 — Sharing. The product premise.

`cars.group_id` exists (`V7`), `Car.carGroup` maps it, and `CarAccess` already
grants group members read-level access. **All that is left is writing the
column** — the moment share/unshare land, every endpoint built in Phases 1–3
honours sharing with no further change. That is the payoff for building the
seam early.

| Endpoint | Notes |
|---|---|
| `PUT /cars/{id}/group` | Share. Caller must own the car **and** belong to the target group. |
| `DELETE /cars/{id}/group` | Un-share. |
| `GET /groups/{id}/cars` | Cars available to this group. |

Traps:
- Un-sharing a car that has an **active trip driven by a group member**: does
  the trip continue or get cut off? Decide deliberately; letting it continue is
  the kinder answer and needs no extra code.
- Owner-only for share/unshare, member-level for read. Two different rules on
  the same resource — this is why §2 exposes two methods.

### Phase 5 — Membership. Sharing with a one-person group is pointless.

Ships together with Phase 4 as one milestone.

| Endpoint | Notes |
|---|---|
| `POST /groups/{id}/invitations` | Admin-only. Returns a single-use code with an expiry. |
| `POST /invitations/{code}/accept` | The invitee joins as `MEMBER`. |
| `GET /groups/{id}/members` | |
| `PATCH /groups/{id}/members/{userId}` | Change role. Admin-only. |
| `DELETE /groups/{id}/members/{userId}` | Remove, or leave when it is yourself. |
| `DELETE /groups/{id}` | Admin-only. Cars fall back to un-shared, not deleted. |

Traps:
- **Never allow the last ADMIN to leave or be demoted** — the group becomes
  unadministrable and no endpoint can recover it.
- The upsert trap from §4: check membership before writing it.
- Removing a member who is mid-trip in a group car: same question as un-sharing.

### Phase 6 — Aggregates. The payoff.

| Endpoint | Notes |
|---|---|
| `GET /users/me/expenses?from=&to=` | `sum(initial_fuel - final_fuel)`, in SQL. |
| `GET /groups/{id}/expenses` | Per member, over a range. Who used how much. |
| `GET /cars/{id}/stats` | Total distance, max/avg speed, trip count — from `telemetry` and `trips`. |

### Phase 7 — Updates and deletes.

| Endpoint | Notes |
|---|---|
| `PATCH /cars/{id}` | Rename, plate, mileage correction. Owner-only. |
| `DELETE /cars/{id}` | **Cascades to trips and telemetry.** Consider a soft delete / archive flag first — a shared car's history belongs to the group, not only the owner. |
| `PATCH /users/me` | |
| `DELETE /users/me` | Cars orphan (`owner_id` → null), trips keep the car, memberships cascade. Verify that is the intent before shipping it. |
| `PATCH /groups/{id}` | Rename. Admin-only. |

### Phase 8 — Devices.

| Endpoint | Notes |
|---|---|
| `POST /cars/{id}/device` | Pair a dongle by BLE address. |
| `GET /cars/{id}/device` | Includes `last_seen_at`. |
| `DELETE /cars/{id}/device` | Unpair — needed when a car is sold. |

### Phase 9 — Polish.

OpenAPI, push notifications on trip start, rate limiting on ingest, telemetry
retention, and `server.forward-headers-strategy=framework` so `Location`
headers stay correct once a reverse proxy terminates TLS.

---

## Part 4 — Implementation note: the snapshot guard

Step 4 of the ingestion algorithm is the one part of Phase 1 that is easy to
write incorrectly in a way that tests written afterwards will not catch. Worth
settling before the endpoint is started.

### The comparison belongs in the `WHERE`, not in Java

The obvious version reads the car, compares, then writes:

```java
if (car.getCarSnapshotAt() == null || newest.isAfter(car.getCarSnapshotAt())) {
    car.setCarFuelLevel(...);
    car.setCarSnapshotAt(newest);
}
```

That is a lost update. Two batches arriving at once both read `snapshot_at =
T0`, both conclude they are newer, and whichever commits last wins — which may
be the older one. The snapshot goes backwards, which is precisely what the
guard exists to prevent.

One atomic statement instead:

```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("""
        update Car c
           set c.carFuelLevel    = coalesce(:fuelLevel,    c.carFuelLevel),
               c.carBatteryLevel = coalesce(:batteryLevel, c.carBatteryLevel),
               c.carMileage      = coalesce(:mileage,      c.carMileage),
               c.carLocation.latitude  = coalesce(:latitude,  c.carLocation.latitude),
               c.carLocation.longitude = coalesce(:longitude, c.carLocation.longitude),
               c.carSnapshotAt   = :recordedAt
         where c.carId = :carId
           and (c.carSnapshotAt is null or c.carSnapshotAt < :recordedAt)
        """)
int refreshSnapshot(UUID carId, Instant recordedAt, Integer fuelLevel, ...);
```

This is the same compare-and-set already used in `RefreshTokenService` to
revoke a token family, so it is not a new idea in this codebase.

Why each piece:

- **`where snapshot_at < :recordedAt`** — a late batch updates 0 rows, and that
  is the correct outcome, not an error. The returned `int` distinguishes the
  two cases: useful for logging, and it is what the tests assert on.
- **`coalesce(:x, c.x)`** — a partial reading (fuel but no GPS fix) must not
  blank the last known position. The cost is that `snapshot_at` then means "as
  of, for the fields this reading carried"; the position may be older. The
  alternative — overwriting with nulls — makes `snapshot_at` exact for every
  column but makes the map pin blink every time a fuel-only frame arrives.
  Prefer `coalesce`, and say so in the README.
- **`@Modifying` with flush/clear** — a JPQL update bypasses the persistence
  context. Without those flags a `Car` loaded earlier in the same transaction
  shadows the update with stale state.

**Where it runs:** inside the same `@Transactional` as the batch inserts, after
them. One transaction, so a failed insert leaves the snapshot where it was.

**Which reading:** the one with the greatest `recorded_at` **in the batch, in
memory**. Never `select max(recorded_at) from telemetry where car_id = ...` —
that re-reads the largest table in the database to find something already in
hand.

**Refinement worth taking:** the newest reading does not necessarily carry every
field. If it has `fuel_level = null` but an older reading in the same batch has
one, `coalesce` keeps the old *database* value and discards the batch's. Fold
the batch first — build one effective reading, taking each field from the newest
reading that carries it, and pass that. Ten lines, and it stops you throwing
away data you were just handed.

**Optional:** `greatest(...)` on `mileage` so an odometer never goes backwards.
Deliberately not doing this is also defensible — a reversing odometer is a data
quality signal you may prefer to see rather than silently smooth over.

### Tests (repository lane, real Postgres)

| Case | Expected |
|---|---|
| First ever reading (`snapshot_at` null) | 1 row, values set |
| Newer reading | 1 row, values replaced, `snapshot_at` advances |
| **Older reading** | **0 rows, nothing changes** ← the one that matters |
| Identical timestamp (a retry) | 0 rows |
| Partial reading | fuel updated, previous position intact |

### Two alternatives, and why not

**`SELECT ... FOR UPDATE` then write.** Correct, but takes a row lock on `cars`
for every batch from every car, serialising ingestion behind a lock to achieve
what a conditional `UPDATE` does without one.

**A trigger on insert into `telemetry`.** Tempting, because it cannot be
forgotten. But it fires per row rather than per batch, and it hides a write to
`cars` inside an insert into another table — six months from now, wondering why
a car changed, the trigger is the last place anyone looks. Application code, in
one place (`TelemetryService`), is easier to reason about.

---

## Suggested next step

Phase 1 is done except `GET /cars/{id}` — a one-liner through
`CarAccess.readableBy`, and the route every `Location` header from `POST /cars`
already promises. Add `snapshotAt` and a `GroupRef(id, name)` to `CarDTO.Read`
at the same time; `carGroup` is lazy, so map it inside the transaction like the
model.

After that, Phase 4's write side — `PUT`/`DELETE /cars/{id}/group` — is unusually
cheap now: the access rule, the column and the tests for "a member can use a
shared car" all exist. Share/unshare only has to set `carGroup`, through
`CarAccess.ownedBy` plus a membership check on the target group.
