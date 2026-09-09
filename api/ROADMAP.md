# OBD-C API — roadmap

What is missing, what to build next, and in what order. Companion to
`README.md`, which documents what already **exists**; this file is about what
does not.

Last updated: 2026-09-09. Schema is at `V5__telemetry.sql`.

---

## Where things stand

| Area | Schema | Endpoints |
|---|---|---|
| Auth / users | `users`, refresh tokens | register, login, refresh, logout |
| Cars | `cars`, `models` | `POST /cars` only |
| Groups | `groups`, `group_members` | `POST /groups` only |
| Trips | `trips` | `POST /trips` (start) only |
| Telemetry | `telemetry`, `cars.snapshot_at` | **none** — the table exists, nothing writes to it |

Four `POST`s and no `GET`s. Every `Location` header currently returned points at
a route that does not exist. That is the shape of the work below.

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
quietly wrong. **Build the seam in Phase 1**, implemented as "owner only" —
then Phase 3 changes one method instead of revisiting every endpoint.

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
- `cars.max_speed`/`avg_speed` are aggregates over `telemetry`, not readings.

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

Ordered by what breaks while it is missing, not by what is easiest.

### Phase 1 — Reads. Nothing is visible without them.

The app currently cannot render a single screen. Cheapest phase, largest
unblock. **Introduce `CarAccess` here** (owner-only for now) so Phase 3 is a
one-method change.

| Endpoint | Notes |
|---|---|
| `GET /users/me` | `UserService`/`UserController` are empty stubs. Every app needs this on launch. |
| `GET /models` | The create-car form cannot populate its dropdown without the catalog. Static, cacheable. |
| `GET /cars` | The caller's cars. Include the snapshot + `snapshot_at` so the client can show staleness. |
| `GET /cars/{id}` | The route `POST /cars` already advertises in `Location`. |
| `GET /cars/{id}/trips/active` | The "who has the car right now" lookup. Derived from `ended_at is null`. 204 or `null` when idle — pick one and document it. |
| `GET /groups` | Groups the caller belongs to, with `memberCount` and `callerRole`. |
| `GET /groups/{id}` | Members list included, or a separate `/members` route. |
| `GET /trips/{id}` | The route `POST /trips` advertises. |
| `GET /trips` | The caller's history, paged, newest first. |
| `GET /cars/{id}/trips` | One car's history, paged. |

### Phase 2 — Finish the trip lifecycle.

Until this exists `fuelUsed` is always `null` and the expense feature is dead.

| Endpoint | Notes |
|---|---|
| `POST /trips/{id}/finish` | Body: `finalFuel?`, `distance?`. Sets `ended_at` = server clock. |
| `DELETE /trips/{id}` | Cancel a trip started by mistake. Only while active. |

Traps:
- Only the **driver** may finish or cancel; the car's owner may not finish
  someone else's trip out from under them.
- `finalFuel > initialFuel` is legal — the driver refuelled. Do not "validate"
  it away.
- Finishing must **flush the `UPDATE` before any subsequent `INSERT`** on that
  car: Hibernate orders inserts ahead of updates within one flush, so a
  start-immediately-after-finish would collide with the trip it just closed.
  Pinned by `TripRepositoryTest.allowsANewTripOnceThePreviousOneIsFinished`.
- Finishing a trip is a good moment to recompute the car's `mileage` and
  `avg_speed`.

### Phase 3 — Sharing. The product premise.

Migration `V6`: `cars.group_id`. Then flip `CarAccess.readableBy` from
"owner" to "owner or member of the car's group" — and everything built in
Phases 1–2 gains sharing for free. That is the payoff for building the seam
early.

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

### Phase 4 — Membership. Sharing with a one-person group is pointless.

Ships together with Phase 3 as one milestone.

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

### Phase 5 — Telemetry. The hardware currently has nowhere to send data.

| Endpoint | Notes |
|---|---|
| `POST /cars/{id}/telemetry` | Accepts an **array**. The phone relays in batches. |
| `GET /cars/{id}/telemetry?since=&limit=` | History and incremental sync. |
| `GET /trips/{id}/route` | Readings stamped with this trip, oldest first. |

Ingestion algorithm, in order:
1. Access check via `CarAccess`.
2. Look up the car's open trip once per batch; stamp `trip_id` on readings whose
   `recorded_at` falls inside it.
3. Insert. A duplicate `(car_id, recorded_at)` is **success, not an error** —
   that is what makes retries safe.
4. Refresh the car's snapshot **only if** the newest `recorded_at` in the batch
   is later than `cars.snapshot_at`, and set `snapshot_at` with it. Skipping
   this check lets a late buffer flush overwrite fresh values with stale ones.
5. Keep `raw_frame` verbatim, even for readings you could not decode.

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

## Suggested next step

Phase 1, starting with `GET /users/me` and `GET /models` — the two smallest,
each unblocking a screen — then `GET /cars` with the `CarAccess` seam in place
from the first commit.
