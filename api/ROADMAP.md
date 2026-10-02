# OBD-C API — roadmap

What is missing, what to build next, and in what order. Companion to
`README.md`, which documents what already **exists**; this file is about what
does not.

Last updated: 2026-10-02. Schema is at `V15__device_tokens.sql`. 496 tests,
104 smoke checks.

---

## Where things stand

| Area | Schema | Endpoints |
|---|---|---|
| Auth / users | `users`, `refresh_tokens`, `email_verification_tokens`, `password_reset_tokens` | register, login, refresh, logout, `GET`/`PUT /users/me`, `POST /users/me/password`, `POST /users/me/verify-email`, `POST /auth/verify-email`, `POST /auth/resend-verification`, `POST /auth/forgot-password`, `POST /auth/reset-password` |
| Cars | `cars` (with `group_id`, `snapshot_at`), `models` | `POST /cars`, `GET /cars`, `GET /cars/{id}`, `PUT`/`DELETE /cars/{id}/group`, `GET /groups/{id}/cars`, `GET /models`, `POST /models` (admin) |
| Groups | `groups`, `group_members` | `POST /groups`, `GET /groups`, `GET /groups/{id}/members` |
| Invitations | `invitations` | `POST /invitations/invite/{groupId}`, `GET /invitations/pending`, `POST /invitations/{id}/accept` |
| QR invites | `group_invite_codes` | `POST`/`GET`/`DELETE /groups/{id}/invite-code`, `GET /invite-codes/{code}` (public), `POST /invite-codes/{code}/join` |
| Trips | `trips` | start (idempotent via `clientTripId`), finish, cancel, `GET /trips`, `GET /cars/{id}/trips`, `GET /cars/{id}/trips/active`, `GET /trips/{id}/route` |
| Telemetry | `telemetry` | `POST /telemetry` (batch ingest, by `carId` or by dongle `serial`), `GET /telemetry` (cursor sync) |
| Devices | `devices` | `PUT`/`GET`/`DELETE /cars/{id}/device`, `GET /devices/{serial}` |
| Device tokens | `device_tokens` | `POST`/`GET /cars/{id}/device-tokens`, `DELETE /device-tokens/{id}`. The background service's credential: one car, no rotation, `ROLE_DEVICE` closed by default |

Access is one predicate — `CarRepository.READABLE`, owner **or** member of the
car's group — behind `CarAccess`, and every car/trip/telemetry endpoint goes
through it. **Sharing is live**: `PUT /cars/{id}/group` sets the column, and
nothing in trips or telemetry changed to honour it. `GroupAccess` is the
counterpart for groups (`memberOf` / `requireMember` / `requireAdmin`).

Every endpoint in the table has a slice test, a smoke check and a README
section; the status-code inconsistencies that used to be listed here
(`202` on `GET /cars/{id}`, `200` on `POST /models` and `DELETE /trips/{id}`,
`409` for an unknown trip) are fixed. Refusals are uniform: **404** for
"does not exist *or* not yours" (car, trip, group, device, invitation), **403**
only where the caller already knows the thing exists (not an admin, not the
account role), **409** for a state conflict, **400** for a malformed id or
body.

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
of the open-trip row. Needs **push** tokens (FCM registration ids, one per
installation) — not the `device_tokens` table, which is a credential for
calling this API and has nothing to do with delivering a notification.

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
- **A new endpoint is closed by default.** `anyRequest().hasAuthority(EMAIL_VERIFIED)`
  means it is refused to unconfirmed accounts and to device tokens without
  anybody adding a rule for it. Opening one to a device is a deliberate line in
  `SecurityConfig`, and `DeviceTokenScopeIT` / `EmailVerificationGateIT` fail
  until it is written.
- **Every `403` from the chain carries a `reason`** (`email_not_verified`,
  `forbidden`), as the `401`s do. A client's retry policy turns on it.

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
2b. If the endpoint changes who may call what, a chain test
   (`@SpringBootTest`, `*IT`) — the slice loads a permissive chain, so nothing
   else can prove a rule in `SecurityConfig`.
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

### ~~Phase 2 — The remaining reads~~ — done

| Endpoint | Notes |
|---|---|
| ~~`GET /users/me`~~ | The profile, from the token. No slice test or smoke check yet. |
| ~~`GET /cars/{id}`~~ | `200`, same shape as the list; `404` for unknown *and* not-readable. |
| ~~`GET /cars/{id}/trips/active`~~ | Through `CarAccess.readableBy`; `204` when idle. |
| ~~`GET /groups/{id}/members`~~ | Any member, `requireMember`. One join to `users` → `GroupDTO.Member(userId, name, email, role)`, admins first. There is no `GET /groups/{id}` on its own; `GET /groups` already carries name and count. |
| ~~`GET /trips`~~ | Newest first. Paging still to do. |
| ~~`GET /cars/{id}/trips`~~ | The car's full history, every driver, newest first, for anyone who may read it. |
| ~~`GET /models`~~ | Ordered by brand then model. Plus an admin-only `POST /models`, `201`. |
| ~~`GET /groups`~~ | One JPQL query building the DTO directly; `memberCount` as a correlated subquery. |

`GET /trips/{id}` was dropped: `POST /trips` sends no `Location`, and a trip
is always reached through its car or driver list.

### Phase 3 — Finish the trip lifecycle — mostly done

| Endpoint | Notes |
|---|---|
| ~~`POST /trips/{id}/finish`~~ | One conditional `UPDATE`: driver-only, once, server clock. `fuelUsed` is live. |
| ~~`DELETE /trips/{id}`~~ | Open trips of the caller's own, `204`. `404` unknown/foreign, `409` already ended — same split as `finish`. |
| ~~`GET /trips/{id}/route`~~ | Resolved by **time window**, not by `telemetry.trip_id`: that column is stamped at ingest only when a trip was open then, so readings uploaded before the trip exists or after it is finished - both normal offline - stay null forever. The window is unambiguous (one open trip per car) and already indexed. |

Traps:
- A client-supplied `startedAt` interacts with telemetry: `trip_id` is stamped
  at ingest and never re-stamped, so a backdated trip's readings keep whatever
  they got. Resolved by reading routes by time window instead of trusting that
  column - do the same for any per-trip aggregate.
- Only the **driver** may finish or cancel; the owner may not finish someone
  else's trip out from under them.
- `finalFuel > initialFuel` is legal — the driver refuelled. Do not "validate"
  it away.
- ~~Flush the closing `UPDATE` before the next `INSERT`~~ — moot: `finish` is a
  JPQL update, which runs immediately. `aFinishedCarCanStartAgainImmediately`.
- Finishing is a good moment to bring `cars.mileage` up to date. Speed figures
  are **not** stored — they are computed from `telemetry` (Phase 6).

### ~~Phase 4 — Sharing~~ — done

`PUT`/`DELETE /cars/{id}/group`, `GET /groups/{id}/cars`, `group` on every car
read, and `GroupAccess`. Every endpoint from Phases 1–3 honoured sharing the
moment the column was written — smoke 59 is a member uploading telemetry for a
car they do not own, with no change to telemetry code. Decided and pinned: an
open trip survives un-sharing (`sharingLeavesAnOpenTripAlone`).

### Phase 5 — Membership

| Endpoint | Notes |
|---|---|
| ~~`POST /invitations/invite/{groupId}`~~ | Admin-only, by email, 7-day expiry. Answers `200` not `201`; verb in the route. |
| ~~`GET /invitations/pending`~~ | The invitee's list — how they find the id to accept. |
| ~~`POST /invitations/{id}/accept`~~ | Atomic accept + `group_members` insert in one transaction. Pinned by `acceptingJoinsTheGroupAsAMember` and smoke 50. No membership guard before the insert — unreachable today (invite already refuses members), reachable once add-member exists. |
| **`FailedInvitationException` handler** | A duplicate pending invitation is a `500` today. One `@ExceptionHandler` → `409`. |
| ~~Reclaim expired rows on invite~~ | `deleteExpiredPending` before the insert; accepted rows untouched. |
| ~~QR invite codes~~ | `group_invite_codes`: mint (admin, one live code per group), preview (public — a scanner with no account still sees the group's name), join. Hashed like a refresh token, so a code is displayable once; re-showing means rotating, which kills the old poster. Only joining spends a use. |
| **`GET /groups/{id}/invitations`** | Admin's view: who was invited, status. |
| **`DELETE /groups/{id}/invitations/{invId}`** | Revoke. Today the only way out of a pending invitation is expiry. |
| **`PATCH /groups/{id}/members/{userId}`** | Change role. Admin-only. |
| **`DELETE /groups/{id}/members/{userId}`** | Remove, or leave when it is yourself. |
| **`DELETE /groups/{id}`** | Admin-only. Cars fall back to un-shared (`ON DELETE SET NULL`), invitations go with the group (`CASCADE`). |

Traps:
- **Never allow the last ADMIN to leave or be demoted** — the group becomes
  unadministrable and no endpoint can recover it. `POST /invite-codes/{code}/join`
  is the first endpoint that could have: `GroupMemberRepository.save` is an
  upsert, so an admin scanning their own QR would have been written back as
  `MEMBER`. It checks membership before writing; anything else that enrols a
  user must do the same.
- `GroupAccess` now exists (`requireMember` / `requireAdmin`). `InvitationService`
  still does the admin check inline; worth routing through it when next touched.
- Removing a member who is mid-trip in a group car: same question as
  un-sharing.

### Phase 6 — Aggregates. The payoff.

| Endpoint | Notes |
|---|---|
| **`GET /users/me/expenses?from=&to=`** | `sum(initial_fuel - final_fuel)`, in SQL. Unblocked: trips can end. |
| **`GET /groups/{id}/expenses`** | Per member, over a range. |
| **`GET /cars/{id}/stats`** | Total distance, `max(speed)`/`avg(speed)`, trip count — from `telemetry` and `trips`. |

### Phase 7 — Updates and deletes

| Endpoint | Notes |
|---|---|
| **`PATCH /cars/{id}`** | Rename, plate, mileage correction. `CarAccess.ownedBy`. |
| **`DELETE /cars/{id}`** | **Cascades to trips and telemetry.** Consider a soft delete first — a shared car's history belongs to the group, not only the owner. |
| **`PATCH /users/me`** | The `UserDTO.Update` record already exists, unused. |
| ~~`PUT /users/me`~~ | Name, lastname, phone. A replace, not a merge. |
| ~~`POST /users/me/password`~~ | Current password required; revokes every other session immediately, via `password_changed_at` + `JwtAuthFilter`. |
| ~~`POST /auth/forgot-password` + `/auth/reset-password`~~ | Always `202`, so it cannot be used to ask who has an account. The reset revokes every session and marks the address verified. |
| ~~Email verification~~ | Mailed on registration, redeemable once, resend throttled to 60s. |
| ~~**Hard gate**~~ | An unconfirmed account can do **nothing**: `GET /users/me`, `POST /users/me/verify-email` and `POST /auth/logout` work, everything else is `403 email_not_verified`, and `POST /auth/login` refuses it too (after checking the password, so it is not an existence oracle). One line in the chain — `anyRequest().hasAuthority(EMAIL_VERIFIED)` — so an endpoint added later is closed until somebody opens it. `POST /auth/resend-verification` is public, always `202`, because a walled account cannot log in to ask. Replaced a soft gate on the three *"reaches other people"* actions; `EMAIL_VERIFIED` is an authority and **not** a role, which is what keeps `ADMIN` accounts out of the blast radius. |
| **Reclaim an unverified address** | Registering with somebody else's email still squats it: the owner gets `409` and cannot sign up. Less severe now that the squatting account is inert, so this dropped below the aggregates in priority. The fix is to let a new registration replace an account that was never verified, plus an expiry for unverified ones. |
| ~~`Mailer` seam~~ | Interface plus `LoggingMailer` (dev and tests, sends nothing, **refuses to start without a dev/test profile**) and `ResendMailer` (real sending, refuses to start without an API key). Mutually exclusive, selected by `app.mail.provider`. Both guards exist because the hard gate makes mail load-bearing: no mail, no confirmations, no usable accounts. |
| **`PUT /users/me/email`** | Also blocked on mail. Watch the knock-ons: the email is the login identifier, a JWT claim, and the key `invitations` uses. |
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

1. **`GET /users/me/expenses`** (Phase 6). Trips can end now, so there is
   finally something to sum — one SQL aggregate.
2. **Turn mail on.** `ResendMailer` is written and tested; what is left is
   outside the code - verify `mail.obidi.com.ar` in Resend (SPF, DKIM, then
   DMARC), create the API key, and set `MAIL_PROVIDER=resend` plus the two
   `MAIL_*_URL_BASE` values and `CORS_ORIGINS` on the VM. Also needed: the two
   static pages at `obidi.com.ar/verify-email/:token` and `/reset-password/:token`
   that post the token, since the emailed link is a GET on a page and the
   endpoints are POSTs.
3. **A rate limit per device token**, and `lastUsedAt`-based "this phone has
   not reported since Tuesday" in the app. The credential is in place; what is
   missing is noticing when a dongle goes quiet.
4. **`FailedInvitationException` handler** (Phase 5). One `@ExceptionHandler`
   turns a duplicate live invitation from a `500` into a `409`.


