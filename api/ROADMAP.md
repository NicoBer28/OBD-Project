# OBD-C API — roadmap

What is missing, what to build next, and in what order. Companion to
`README.md`, which documents what already **exists**; this file is about what
does not.

Last updated: 2026-09-15. Schema is at `V9__devices.sql`. 197 tests, 56 smoke
checks.

---

## Where things stand

| Area | Schema | Endpoints |
|---|---|---|
| Auth / users | `users`, `refresh_tokens` | register, login, refresh, logout. **No profile endpoint** — `UserController` is still an empty class. |
| Cars | `cars` (with `group_id`, `snapshot_at`), `models` | `POST /cars`, `GET /cars`, `GET /cars/{id}`, `GET /models`, `POST /models` (admin) |
| Groups | `groups`, `group_members` | `POST /groups`, `GET /groups` |
| Invitations | `invitations` | `POST /invitations/invite/{groupId}`, `GET /invitations/pending`, `POST /invitations/{id}/accept` |
| Trips | `trips` | `POST /trips` (start) only — **nothing can finish a trip** |
| Telemetry | `telemetry` | `POST /telemetry` (batch ingest, by `carId` or by dongle `serial`), `GET /telemetry` (cursor sync) |
| Devices | `devices` | `PUT`/`GET`/`DELETE /cars/{id}/device`, `GET /devices/{serial}` |

Access is one predicate — `CarRepository.READABLE`, owner **or** member of the
car's group — behind `CarAccess`, and every car/trip/telemetry endpoint goes
through it. It already honours sharing; nothing can *set* `cars.group_id` yet.

Two things in the table are half-built and worth knowing before anything else:

- **Accepting an invitation does not enrol the invitee.** The row is marked
  accepted and vanishes from their pending list, but no `group_members` row is
  written. From the user's side it looks like it worked.
- **`GET /cars/{car_id}` answers `202 Accepted`** — a status that means "queued
  for later" — instead of `200`. It has no README section and no tests.

---

## Part 1 — Design decisions

### Resolved

- ~~**`cars.group_id`**~~ — `V7`, nullable, `ON DELETE SET NULL`, mapped as
  `Car.carGroup`. Only the write endpoints remain (Phase 4).
- ~~**One access rule, not eight**~~ — `CarRepository.READABLE`, shared verbatim
  by `findAllReadableBy` and `findReadableBy`; `CarAccess` is the only caller.
  Proven by `TripServiceTest.aGroupMemberMayStartATripOnASharedCar`, which
  passes without `TripService` knowing groups exist.
- ~~**`?since=` on history reads**~~ — `GET /telemetry?carId=&since=&limit=`,
  oldest-first from an exclusive cursor, `hasMore` + `nextSince`. Paired with
  batch ingest that treats duplicates as success.
- ~~**Invitations by email**~~ — `V8`; people without an account can be invited,
  and the invite response is identical whether or not the email is registered.
  Accept is an atomic conditional `UPDATE`. What is *not* done is listed under
  Phase 5.

### Still open

**1. The firmware exposes no serial.** The API side of devices is done (V9,
pair/read/unpair/resolve, ingest by serial), keyed on a serial rather than the
BLE MAC because iOS hides the MAC. But `main.cpp` advertises only a name, so
today the serial is whatever the pairing person types. The "phone learns the
car without asking" benefit needs the GATT Device Information Service
(`0x180A`, Serial Number `0x2A25`) derived from the ESP32's factory MAC — a
few lines of firmware.

**2. Settle whether fuel is a percentage or litres.** `V2` and `V5` both dodge
this with `>= 0` floors instead of `0..100`. Every day of real data makes the
tightening migration harder, and `fuelUsed` cannot be rendered with a unit until
it is decided. Same for `battery_level`. *Decide before the first device
reports for real.*

**3. OpenAPI (springdoc).** The Flutter side needs a contract, and it generates
client models from what is already built. Unusually high leverage for one
dependency and zero code.

**4. Compute expenses in SQL.** "Fuel expense per user" is
`sum(initial_fuel - final_fuel) group by driver_id` over a date range — one
query, not trips loaded into Java and summed. Decide before the endpoint
exists.

**5. Notify when a trip starts.** "Someone just took the car" is the feature
that makes a family car-sharing app worth installing, and it falls straight out
of the open-trip row. Needs a `device_tokens` table.

**6. Retention for telemetry.** Nothing prunes it and it grows without bound.
Not worth building before a real device is reporting, but decide the window (or
monthly partitioning) *before* the table is large.

**7. An audit log.** Deliberately not built. `telemetry` logs where a car went;
nothing logs who removed a member, un-shared a car, or deleted a trip. A
separate table plus a choice: Postgres triggers (catch everything, actor via a
session variable) or application code (readable, blind to anything that
bypasses the API).

Two smaller ones that keep coming up:

- **`GroupMemberRepository.save()` is an upsert, not an insert** — the
  composite id is assigned by us, so Spring Data merges. Adding someone already
  in the group silently rewrites their role. Every path that writes a
  membership **must check first**. Pinned by
  `GroupRepositoryTest.savingAnExistingMembershipSilentlyChangesTheRole`.
- **`Location` only where a GET-by-id exists or is committed.** Cars, groups
  and trips send one; models and invitations do not. A `Location` that 404s is
  a broken promise, not a convention.

---

## Part 2 — Conventions every endpoint follows

These are true of the endpoints that exist. Keeping them true is most of what
makes the API coherent.

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

Ordered by the cost of *not* having it yet. Struck-through rows exist and are
tested; **bold** notes are what is left.

### ~~Phase 1 — Close the hardware loop~~ — done

`POST /telemetry` (batch, idempotent, trip-stamped, snapshot guard),
`GET /telemetry` (cursor sync), `GET /cars`, `GET /cars/{id}`, and `CarAccess`.
The full path ESP32 → phone → API → Postgres can run end to end.

Left over:
- **`GET /cars/{car_id}` returns `202`** instead of `200`; no README section,
  no slice test, no smoke check.
- `CarDTO.Read` has `snapshotAt` but **not the group** a car is shared with —
  a client cannot show "shared with Familia".

### Phase 2 — The remaining reads

| Endpoint | Notes |
|---|---|
| **`GET /users/me`** | `UserController`/`UserService` are still empty classes. Every app needs this on launch. |
| **`GET /cars/{id}/trips/active`** | "Who has the car right now." Derived from `ended_at is null`. 204 or `null` when idle — pick one and document it. |
| **`GET /groups/{id}`** | One group, with its members (or a separate `/members`). `GroupAccess.memberOf` for the check. |
| **`GET /trips/{id}`** | The route `POST /trips` already advertises in `Location`. |
| **`GET /trips`** | The caller's history, paged, newest first. |
| **`GET /cars/{id}/trips`** | One car's history, paged. |
| ~~`GET /models`~~ | Plus an admin-only `POST /models`. Answers `200` where other creates answer `201`; `findAll()` is unordered. |
| ~~`GET /groups`~~ | One JPQL query building the DTO directly; `memberCount` as a correlated subquery. No tests or smoke check yet. |

### Phase 3 — Finish the trip lifecycle

**Nothing here exists.** Until it does, `fuelUsed` is always `null` and the
expense feature is dead.

| Endpoint | Notes |
|---|---|
| **`POST /trips/{id}/finish`** | Body: `finalFuel?`, `distance?`. Sets `ended_at` = server clock. |
| **`DELETE /trips/{id}`** | Cancel a trip started by mistake. Only while active. |
| **`GET /trips/{id}/route`** | Readings stamped with this trip, oldest first. `findByTelemetryTripIdOrderByTelemetryRecordedAtAsc` already exists. |

Traps:
- Only the **driver** may finish or cancel; the owner may not finish someone
  else's trip out from under them.
- `finalFuel > initialFuel` is legal — the driver refuelled. Do not "validate"
  it away.
- Finishing must **flush the `UPDATE` before any subsequent `INSERT`** on that
  car: Hibernate orders inserts ahead of updates within one flush, so a
  start-immediately-after-finish collides with the trip it just closed. Pinned
  by `TripRepositoryTest.allowsANewTripOnceThePreviousOneIsFinished`.
- Finishing is a good moment to bring `cars.mileage` up to date. Speed figures
  are **not** stored — they are computed from `telemetry` (Phase 6).

### Phase 4 — Sharing. The product premise.

The column, the mapping and the access rule all exist. **All that is left is
writing `cars.group_id`** — the moment these land, every endpoint in Phases 1–3
honours sharing with no further change.

| Endpoint | Notes |
|---|---|
| **`PUT /cars/{id}/group`** | Share. `CarAccess.ownedBy` for the car **and** membership of the target group. |
| **`DELETE /cars/{id}/group`** | Un-share. Owner only. |
| **`GET /groups/{id}/cars`** | Cars available to this group. `ix_cars_group_id` already exists for it. |

Traps:
- Un-sharing a car that has an **active trip driven by a group member**: does
  the trip continue or get cut off? Letting it continue is the kinder answer
  and needs no extra code — but decide it deliberately.
- Owner-only for share/unshare, member-level for read. Two rules on the same
  resource; this is why `CarAccess` has two methods.

### Phase 5 — Membership

| Endpoint | Notes |
|---|---|
| ~~`POST /invitations/invite/{groupId}`~~ | Admin-only, by email, 7-day expiry. Answers `200` not `201`; verb in the route. |
| ~~`GET /invitations/pending`~~ | The invitee's list — how they find the id to accept. |
| `POST /invitations/{id}/accept` — **half done** | Marks the row accepted (atomic, tested). **Does not insert the `group_members` row.** Fix: same transaction, after `findByIdGroupIdAndIdUserId` (the upsert trap), `save(GroupMember.of(inv.groupId, userId, MEMBER))` — which means `accept` needs the caller's user id as well as their email. |
| **`FailedInvitationException` handler** | A duplicate pending invitation is a `500` today. One `@ExceptionHandler` → `409`. |
| **Reclaim expired rows on invite** | An expired invitation still holds `ux_invitations_pending`, so that email can never be re-invited. Delete expired pending rows for `(group, email)` before inserting. |
| **`GET /groups/{id}/invitations`** | Admin's view: who was invited, status. |
| **`DELETE /groups/{id}/invitations/{invId}`** | Revoke. Today the only way out of a pending invitation is expiry. |
| **`GET /groups/{id}/members`** | |
| **`PATCH /groups/{id}/members/{userId}`** | Change role. Admin-only. |
| **`DELETE /groups/{id}/members/{userId}`** | Remove, or leave when it is yourself. |
| **`DELETE /groups/{id}`** | Admin-only. Cars fall back to un-shared (`ON DELETE SET NULL`), invitations go with the group (`CASCADE`). |

Traps:
- **Never allow the last ADMIN to leave or be demoted** — the group becomes
  unadministrable and no endpoint can recover it.
- A `GroupAccess` seam (`memberOf` / `adminOf`, one PK lookup answering both)
  is worth building with the first of these, for the same reason `CarAccess`
  was: five endpoints need the identical check.
- Removing a member who is mid-trip in a group car: same question as
  un-sharing.

### Phase 6 — Aggregates. The payoff.

| Endpoint | Notes |
|---|---|
| **`GET /users/me/expenses?from=&to=`** | `sum(initial_fuel - final_fuel)`, in SQL. Needs Phase 3 first — there are no finished trips to sum. |
| **`GET /groups/{id}/expenses`** | Per member, over a range. |
| **`GET /cars/{id}/stats`** | Total distance, `max(speed)`/`avg(speed)`, trip count — from `telemetry` and `trips`. |

### Phase 7 — Updates and deletes

| Endpoint | Notes |
|---|---|
| **`PATCH /cars/{id}`** | Rename, plate, mileage correction. `CarAccess.ownedBy`. |
| **`DELETE /cars/{id}`** | **Cascades to trips and telemetry.** Consider a soft delete first — a shared car's history belongs to the group, not only the owner. |
| **`PATCH /users/me`** | The `UserDTO.Update` record already exists, unused. |
| **`DELETE /users/me`** | Cars orphan (`owner_id` → null), trips keep the car, memberships cascade, invitations keep `invited_by` → null. Verify that is the intent before shipping it. |
| **`PATCH /groups/{id}`** | Rename. Admin-only. |

### ~~Phase 8 — Devices~~ — done

`V9`, `PUT`/`GET`/`DELETE /cars/{id}/device`, `GET /devices/{serial}`, and
`POST /telemetry` accepting `serial` instead of `carId`. Keyed on a
firmware serial, not the BLE MAC. Left over: **the firmware half** — see Part 1
§1.

### Phase 9 — Polish

OpenAPI, push notifications on trip start, rate limiting on ingest, telemetry
retention, and `server.forward-headers-strategy=framework` so `Location`
headers stay correct once a reverse proxy terminates TLS.

---

## Part 4 — Implementation note: the snapshot guard

**Implemented** as `CarRepository.refreshSnapshot` + `TelemetryService.fold`,
with the five cases below pinned in `CarRepositoryTest`. Kept here because it
is the reasoning, and the same shape is reused by `InvitationRepository.accept`.

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

In this order, each small:

1. **Enrol on accept** (Phase 5). The invitation feature is live and silently
   does nothing — the most misleading state a feature can be in. One
   membership check and one insert, in the transaction that already exists.
2. **`POST /trips/{id}/finish`** (Phase 3). Every trip ever started is still
   open; nothing downstream — expenses, stats, the car's mileage — can exist
   until trips can end.
3. **`GET /users/me`** (Phase 2). The empty `UserController` is the oldest
   stub in the codebase and the first call every app makes.

Then Phase 4's two writes, which are cheap now that everything they depend on
exists.
