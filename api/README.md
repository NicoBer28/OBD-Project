# OBD API

Spring Boot backend for OBIDI. Authentication is JWT-based: a short-lived
access token returned in the response body, and a long-lived refresh token
delivered as an `httpOnly` cookie.

**Building the client?** Read *[Integrating with this API](#integrating-with-this-api)*
first — base URLs, the auth contract, the error shape and the conventions that
hold for every endpoint. Then the endpoint you need under
*[Endpoints](#endpoints)*. The *Database*, *Running* and *Testing* sections are
for whoever works on the backend.

No credentials, keys or hostnames of real databases appear in this file. Those
are shared out of band.

---

## Integrating with this API

### Base URL

| Environment | Base URL |
|---|---|
| Production | `https://api.obidi.com.ar` |
| Local backend | `http://localhost:8080` |

Every path below starts with `/api/v1`, so a full call looks like
`https://api.obidi.com.ar/api/v1/users/me`. An Android emulator reaches a
backend on the host machine at `http://10.0.2.2:8080`, not `localhost`.

### Two credentials

| | For | Header |
|---|---|---|
| **Access token** (JWT) | a person using the app | `Authorization: Bearer <accessToken>` |
| **Device token** | the phone's background service, with the app closed | `Authorization: Device <token>` |

Most of what follows is about the first. The second exists because the two
cannot be shared: see [Device tokens](#device-tokens-for-the-background-service).

### Authentication

`register` and `login` return this envelope:

```json
{
  "accessToken": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "expireInSeconds": 900,
  "userId": "8f14e...",
  "email": "ada@example.com"
}
```

Send it on every other call:

```
Authorization: Bearer <accessToken>
```

The access token lasts **15 minutes**. The refresh token is **not** in the
body — it is set as a cookie named `refreshToken`
(`httpOnly`, `SameSite=Strict`, `Secure` in production, **path
`/api/v1/auth`**) and lasts 14 days.

> **The one thing that catches mobile clients out.** The refresh token travels
> only as a cookie, and a plain Dart `http` client does not keep cookies. If
> nothing stores it, `POST /auth/refresh` will always answer `401` and users
> get logged out every 15 minutes. Use a client with a cookie jar — `dio` plus
> `cookie_jar`/`PersistCookieJar` — backed by persistent storage so the session
> survives the app restarting. The cookie's path means it is only ever sent to
> `/api/v1/auth/*`, which is intended.

### Keeping a session alive

1. `POST /auth/login` → store `accessToken` in memory; the cookie jar keeps the
   refresh token. A `403` with `reason: email_not_verified` here means the
   address was never confirmed — see
   [Email verification is a hard gate](#email-verification-is-a-hard-gate).
2. Call endpoints with the bearer token.
3. On a `401` whose `reason` is `token_expired`, call `POST /auth/refresh`
   (no body) and retry the original request once with the new token.
4. On a `401` whose `reason` is anything else, send the user to the login
   screen — the session is gone and refreshing will not help.
5. `POST /auth/logout` revokes every refresh token for that account.

Refresh tokens are **single-use**: each `refresh` returns a new one and
invalidates the old. Replaying an old refresh token revokes the whole family
and forces a fresh login — so never run two refreshes concurrently. Serialise
them behind one mutex, or a race will log the user out.

### Error responses

Every error is RFC 9457 `application/problem+json`. Three shapes, and the
field that tells them apart is `title`:

**Domain errors** — `detail` is a sentence safe to show a user:

```json
{ "status": 404, "title": "Not Found", "detail": "No such car",
  "instance": "/api/v1/cars/aaaa..." }
```

**Validation errors** — `title` is always `"Validation failed"`, there is no
`detail`, and `errors` maps each rejected field to its reason. Use it to mark
the offending inputs:

```json
{ "status": 400, "title": "Validation failed",
  "instance": "/api/v1/auth/register",
  "errors": { "userEmail": "must be a well-formed email address",
              "userPassword": "must not be blank" } }
```

**Authentication errors** — carry an extra `reason`, which is what step 3
above switches on:

```json
{ "status": 401, "title": "Unauthorized", "detail": "Access token expire",
  "reason": "token_expired" }
```

| `reason` | Meaning | What the app should do |
|---|---|---|
| `token_expired` | the 15 minutes are up | call `refresh`, retry once |
| `token_invalid` | signature, format, or the account is gone | log out |
| `missing_token` | no `Authorization` header | log out, or send the user to log in |

A `token_invalid` can also mean the password was changed on another device:
that invalidates every access token issued earlier, everywhere, immediately.

A `403` carries a `reason` too:

| `reason` | Meaning |
|---|---|
| `email_not_verified` | the account never confirmed its address — see [the gate](#email-verification-is-a-hard-gate) |
| `forbidden` | this credential may not call this endpoint. What a **device token** gets outside [its four](#device-tokens-for-the-background-service) |
| `device_out_of_scope` | a device token aimed at a car that is not its own |
| `car_not_accessible` | a device token whose owner lost access to that car |

A `403` with no `reason` at all comes from the endpoint rather than the
security chain, and means an ordinary permission refusal: not an admin of that
group, or not an admin account.

### Conventions that hold everywhere

| | |
|---|---|
| **Timestamps** | ISO-8601 instants in UTC, e.g. `2026-10-01T18:52:04.062Z`. Parse as UTC and render in local time; never send a local time. |
| **Ids** | UUIDs as strings. A malformed one is `400`, not `404`. |
| **`404` vs `403`** | A resource that exists but is not yours answers **`404`**, exactly like one that does not exist — so an id is never confirmed to a stranger. `403` appears only where the caller already knows the thing exists and simply lacks the right (not an admin of a group, not an admin account, email not verified). |
| **`204`** | A successful write with nothing to return, and also "the normal empty answer" — e.g. no active trip on a car. It is **not** an error; `404` there would mean "no such car". |
| **`409`** | A state conflict: already on a trip, already a member, a link already used or expired. Retrying without changing something will not help. |
| **`429`** | Throttled. Only `POST /users/me/verify-email` answers it (one link per 60s); the public resend stays silent at `202`. |
| **`403` with `reason: email_not_verified`** | The account has not confirmed its email address. Every endpoint answers this except `/auth/*` and the three listed under [Email verification is a hard gate](#email-verification-is-a-hard-gate). |
| **Empty lists** | `[]` and `200`, never `404`. |
| **The caller's identity** | Always taken from the token, never from the body. Sending `userId`, `ownerId` or `driverId` in a request body has no effect. |
| **Unknown JSON fields** | Ignored. |
| **A body that will not parse** | `400`, not `500` - malformed JSON, a 35-character "UUID", a string where a number belongs. The parser's own message is not forwarded, since it quotes the payload back. |
| **Distances** | Kilometres, two decimals (`numeric(8,2)`). A third decimal is a `400` rather than a silent rounding. The field is `distanceKm`, named for its unit on purpose. |

### Email verification is a hard gate

**An account can do nothing until it has confirmed its email address.** Not a
reduced set of things — nothing. That is deliberate: it means an account
created with somebody else's address is inert rather than merely limited, and
there is no window in which it can register cars, create groups, start trips,
or become visible to anybody.

Three things work before confirming, and they exist only to get the user
through the wall:

| | |
|---|---|
| `GET /users/me` | so the app can read `emailVerified` and show the right screen |
| `POST /users/me/verify-email` | ask for another link, using the token registration handed over |
| `POST /auth/logout` | |

**Everything else answers `403`** with `reason: "email_not_verified"` — the
one 403 an app can act on, so it is worth switching on:

```json
{ "status": 403, "title": "Forbidden", "reason": "email_not_verified",
  "detail": "Confirm your email address before using the application" }
```

**`POST /auth/login` also answers `403`** with that reason, and issues no
token. The password is checked **first**, so a wrong password is still a `401`
whatever state the account is in — otherwise the `403` would tell anybody who
asked that an address is registered.

#### The flow the client has to implement

```
POST /auth/register          → 200 + tokens, emailVerified: false
                               (registration still logs the user in)
   └─ show "confirmá tu correo", offer resend with the token it just got

user opens the mail, taps the link
   └─ the page POSTs /auth/verify-email  → 204

GET /users/me                → emailVerified: true
   └─ the same token now works everywhere; nothing needs re-issuing
```

And for somebody who comes back later, after the token is gone:

```
POST /auth/login             → 403 email_not_verified
POST /auth/resend-verification  {userEmail}  → 202, always
```

That last endpoint is **public**, because an account that cannot log in has no
authenticated way to ask for anything. It answers `202` whether the address is
unknown, already confirmed, or throttled — identical in every case, so it
cannot be used to ask who has an account here. A malformed address is the only
thing it will admit to, with a `400`.

**`POST /auth/refresh` keeps working while the account is walled**, on
purpose. The cookie came from registration, so the wall screen survives the
access token expiring under it — otherwise a user who left the app open for
twenty minutes would be thrown out of the one screen that can get them
through. The token it returns is still walled, and whether the gate has lifted
is read from `GET /users/me`, not from having a fresh token.

**`POST /auth/reset-password` also lifts the gate.** Reaching the mailbox is
the same proof either way, so somebody who goes through *forgot password*
comes out confirmed and does not have to then find the verification mail too.

#### What this replaced

An earlier version let an unconfirmed account use most of the app and blocked
only the three actions that touched other people. It was dropped because
blocking actions one at a time leaves the account itself usable, and the list
of actions that expose you to somebody else is not a list you can be sure you
finished. Two consequences of the change:

- **Joining by QR is no longer an exception.** It used to be allowed
  unconfirmed, on the grounds that a code is handed over in person and claims
  no address. Now nothing is allowed unconfirmed, so invitations and codes are
  treated the same.
- **Previewing a code (`GET /invite-codes/{code}`) is still public**, and is
  now the only endpoint outside `auth/*` that needs no credential at all —
  it is how somebody with no account sees whose group they were handed before
  deciding to sign up.

#### A note on roles

`EMAIL_VERIFIED` is an **authority of its own, not a role**. The role says
what kind of account this is (`USER`, `ADMIN`); whether its address is
confirmed is orthogonal — an admin can be unconfirmed and a plain user
confirmed. Folding the two together is what briefly locked every `ADMIN`
account out of the whole API, since an admin's only role authority is
`ROLE_ADMIN`.

### Device tokens, for the background service

The app has to record trips **with the app closed**, and the access token
cannot be used for that: it lasts 15 minutes, renewing it needs a cookie jar
and a live session, and handing a credential that can read and change the
whole profile to a background service is far more than that service needs.

A device token is the narrow alternative:

| | |
|---|---|
| Header | `Authorization: Device obdd_…` |
| Lives | until revoked, or **90 days without being used** |
| Scoped to | **one car** — the one it was minted for |
| May call | `POST /telemetry`, `POST /trips`, `POST /trips/{id}/finish`, `GET /cars/{carId}/trips/active`. Nothing else, `GET /users/me` included |
| Acts as | the person who minted it, so a trip it starts is that person's trip |

**Minting.** `POST /cars/{carId}/device-tokens` with a person's bearer token
and a `label` (the phone's name, so the user knows what they are revoking).
The response is the **only** time the token is returned — only its SHA-256 is
stored. Hand it to the native side and keep it in the Keystore/Keychain, not
in `SharedPreferences`. Any member of the car's group may mint one, several
per phone are fine, and each is revocable on its own with
`DELETE /device-tokens/{id}`.

**What the client must handle.** The token can stop working without anybody
revoking it, because it carries no copy of the permission — it is re-checked
against the car on every request. The `reason` says which case it is, and they
need different handling:

| Status | `reason` | Meaning | What to do |
|---|---|---|---|
| `401` | `token_invalid` | not one of ours, or no such token | drop it, ask the user to pair again |
| `401` | `token_revoked` | revoked, or 90 days idle | drop it, ask the user to pair again |
| `403` | `car_not_accessible` | the owner lost access to that car (un-shared, left the group) | **stop**; re-minting will fail too |
| `403` | `device_out_of_scope` | the car is not the one this token is scoped to | a bug in the caller: it sent the wrong `carId` |

Keep uploading at least once every 90 days of use, or let the token lapse on
purpose when the user stops driving that car — lapsing is the safety net for a
phone that was lost or wiped without anybody revoking anything.

### Recording trips offline

The phone is the source of truth for *when* things happened; the server is the
source of truth for *who* they happened to. Three features make a sync that
runs hours later come out right.

**1. `clientTripId` makes starting idempotent.** Mint a UUID per trip on the
phone and send it with `POST /trips`. A repeat of the same start returns the
same trip with **`200`** instead of creating a second one or failing with
`409`. Without it a lost response is unrecoverable: the retry gets
`409 "car already on a trip"` and the client cannot tell its own trip from one
another driver started. Keys are scoped per driver, so the same UUID from
another account is a different trip.

**2. `startedAt` and `endedAt` are yours to send.** Omit them and the server
clock is used, which is right online and wrong for a trip that ended in a
tunnel and uploaded two hours later. Bounds, on both: at most **5 minutes**
into the future (clock skew) and at most **30 days** old; `endedAt` may not
predate the trip's own `startedAt`. Outside those, `400` with an `errors` map
naming the field.

**3. Readings carry their own `recordedAt`.** `POST /telemetry` takes up to
500 readings per batch, in any order, and is idempotent on
`(carId, recordedAt)` — re-uploading a batch whose response was lost changes
nothing. Each reading keeps the phone's `recordedAt` and the server stamps its
own `receivedAt`, so a queue flushed late is not mistaken for a car that drove
at 3 a.m.

A sync therefore looks like:

```
POST /trips              {carId, clientTripId, startedAt}   → 201 (or 200 on retry)
POST /telemetry          {carId, readings:[…]}              → 200, repeatable
POST /trips/{id}/finish  {distanceKm, endedAt}              → 200
```

**Order does not matter, and telemetry may arrive before its trip exists.**
A reading is stamped with `trip_id` only if a trip was open when it was
ingested, so readings uploaded first have `trip_id: null` forever — and
`GET /trips/{tripId}/route` still returns them, because it resolves the route
by time window on `(car_id, recorded_at)` rather than by that column. Do not
build anything on `trip_id`: it is never wrong, only sometimes absent.

**Distance is whatever the client reports** — `distanceKm`, kilometres, two
decimals. The server does not compute it from the route.

### Endpoint index

| Method | Path | Auth | Notes |
|---|---|---|---|
| `POST` | `/auth/register` | — | creates the account, logs it in, mails a verification link |
| `POST` | `/auth/login` | — | `403 email_not_verified` until the address is confirmed |
| `POST` | `/auth/refresh` | cookie | no body |
| `POST` | `/auth/logout` | bearer | `204` |
| `POST` | `/auth/verify-email` | — | body `{token}`; the link's last path segment |
| `POST` | `/auth/resend-verification` | — | body `{userEmail}`; **always** `202`. The way back in for an account that cannot log in |
| `POST` | `/auth/forgot-password` | — | body `{userEmail}`; **always** `202` |
| `POST` | `/auth/reset-password` | — | body `{token, newPassword}`; `204`, kills every session |
| `GET` | `/users/me` | bearer | includes `emailVerified` |
| `PUT` | `/users/me` | bearer | name, lastname, phone. A replace, not a merge |
| `POST` | `/users/me/password` | bearer | current + new; returns a fresh token pair |
| `POST` | `/users/me/verify-email` | bearer | resend the link. `202`, throttled |
| `POST` | `/cars` | bearer | `201` + `Location` |
| `GET` | `/cars` | bearer | owned **and** shared with your groups |
| `GET` | `/cars/{carId}` | bearer | |
| `PUT` `DELETE` | `/cars/{carId}/group` | bearer | share / un-share. Owner only |
| `GET` | `/groups/{groupId}/cars` | bearer | members only |
| `PUT` `GET` `DELETE` | `/cars/{carId}/device` | bearer | pair / read / unpair a dongle. Owner only |
| `GET` | `/devices/{serial}` | bearer | "which car is this dongle?" |
| `GET` | `/models` | bearer | the car catalog, ordered by brand |
| `POST` | `/models` | bearer, **admin** | |
| `POST` | `/groups` | bearer | `201` + `Location`; creator becomes `ADMIN` |
| `GET` | `/groups` | bearer | your groups, with your role in each |
| `GET` | `/groups/{groupId}/members` | bearer | members only |
| `POST` | `/invitations/invite/{groupId}` | bearer, **admin** | by email |
| `GET` | `/invitations/pending` | bearer | your own pending invitations |
| `POST` | `/invitations/{id}/accept` | bearer | |
| `POST` `GET` `DELETE` | `/groups/{groupId}/invite-code` | bearer, **admin** | mint / inspect / revoke the QR code |
| `GET` | `/invite-codes/{code}` | **none** | preview a scanned code before joining |
| `POST` | `/invite-codes/{code}/join` | bearer | `200` and nothing spent if already a member |
| `POST` | `/trips` | bearer **or device** | start. `201`, or `200` for a repeated `clientTripId` |
| `GET` | `/trips` | bearer | trips you drove |
| `GET` | `/cars/{carId}/trips` | bearer | the car's whole history |
| `GET` | `/cars/{carId}/trips/active` | bearer **or device** | `200` or `204` when idle |
| `POST` | `/trips/{tripId}/finish` | bearer **or device** | driver only |
| `DELETE` | `/trips/{tripId}` | bearer | cancel an open trip. `204` |
| `POST` | `/cars/{carId}/device-tokens` | bearer | mint a background credential. `201`, token shown once |
| `GET` | `/cars/{carId}/device-tokens` | bearer | your own live tokens for that car |
| `DELETE` | `/device-tokens/{id}` | bearer | revoke one. `204` |
| `GET` | `/trips/{tripId}/route` | bearer | the positions recorded during the trip |
| `POST` | `/telemetry` | bearer **or device** | batch ingest, by `serial` or `carId` |
| `GET` | `/telemetry` | bearer | cursor sync |

Two things the `Auth` column does not repeat for every row:

- **`bearer` also means a confirmed email address.** Everything above except
  `/auth/*`, `GET /users/me` and `POST /users/me/verify-email` answers `403`
  with `reason: email_not_verified` until the account confirms — see
  [Email verification is a hard gate](#email-verification-is-a-hard-gate).
- **`bearer **or device**` marks the four endpoints a dongle credential can
  reach.** Everywhere else a device token gets `403`, `GET /users/me`
  included.

`GET /invite-codes/{code}` is the only endpoint outside `/auth` that needs no
credential at all: it lets someone who scanned a QR see the group's name
before they have an account.

### Things that changed recently

If you integrated before October 2026, note the gate first — it changes what
every endpoint answers for a new account:

- **An unconfirmed account now gets `403 email_not_verified` from everything**
  except `GET /users/me`, `POST /users/me/verify-email` and `POST /auth/logout`,
  and **`POST /auth/login` refuses it too**. Registration still returns tokens,
  so the sign-up flow is unchanged - but the app has to read `emailVerified`
  and show a wall until the user confirms.
- **`POST /auth/resend-verification`** is new and public, for an account that
  can no longer log in.

Two wire changes besides:

- **`distance` is now `distanceKm`**, in both the finish request (was
  `tripDistance`) and every trip response, and it is a **decimal in
  kilometres** rather than an integer with no documented unit. Sending the old
  field name now leaves the distance null.
- **Trip responses gained `createdAt` and `clientTripId`.** Additive.

### Things the API does not do yet

Worth knowing before you design around them: there is no endpoint to change an
email address, to delete an account, to remove or re-role a group member, or to
update or delete a car. Lists are unpaged. See *Known limitations* at the end.

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

### Devices

`devices` maps an OBD dongle to the car it is plugged into, so a phone that
connects to one can learn which car it is talking to **without asking the
user** — and get the same answer on every phone in the family.

The problem it solves is in the firmware: every dongle advertises the same BLE
name (`NimBLEDevice::init("OBD-C")`). A phone that sees "OBD-C" cannot tell two
family cars apart, and a mapping stored locally on one phone is unknown to the
others and goes stale the moment a dongle is moved.

| Decision | Because |
|---|---|
| keyed on a **serial the firmware exposes**, not the BLE MAC | iOS hides the real address and gives each app a per-phone random UUID for the same peripheral, so a MAC-keyed mapping would not actually be shared across phones. |
| `serial` stored trimmed + upper-cased, enforced by a CHECK | However the phone's BLE stack reports it, `a4:cf:12` and `A4:CF:12` are one device. |
| one dongle per car **and** one car per dongle (two unique constraints) | Re-pairing a car to a new dongle replaces the row; moving a dongle to another car is an explicit unpair-then-pair, so readings are never silently re-attributed. |
| `last_seen_at`, server clock | "The dongle hasn't reported since Tuesday" cannot be produced any other way. Set only by batches that name the serial. |
| `ON DELETE CASCADE` from `cars` | A dongle without its car is nothing to us. |

How the pieces fit end to end — pair, connect, upload by serial — is walked
through under Endpoints › *From dongle to database*.

**The firmware does not expose a serial yet.** `main.cpp` advertises a name and
a raw frame, nothing that identifies the unit. The intended change is small:
add the standard Device Information Service (`0x180A`) with a Serial Number
String characteristic (`0x2A25`), derived from the ESP32's factory MAC
(`esp_efuse_mac_get_default`). Until then the serial is whatever the pairing
person types — the API works either way, but the "no user prompt" benefit
needs the firmware half.

### Invite codes

`group_invite_codes` is the QR half of joining a group, next to `invitations`
(the email half). A bearer capability: whoever scans it may join.

| Decision | Because |
|---|---|
| a separate table, not a nullable `invitations.email` | An invitation is only acceptable by the account holding that address — the one condition that makes accepting safe. A bearer code has no email to check, so sharing the table would have made that condition optional. |
| only the SHA-256 is stored, as `refresh_tokens` does | A database leak yields nothing joinable. The cost is that a QR cannot be re-displayed: showing it again means minting a new one — which is also what kills the printed poster. |
| 32 random bytes | Guessing is not a threat model, so nothing here needs rate limiting or lockout. |
| one live code per group (partial unique on `revoked_at is null`) | Minting revokes the previous row in the same transaction, so there is never a second QR in circulation. An **expired** row still holds the slot until it is revoked — `now()` cannot appear in an index predicate, and "the group's last code" is still a fact worth keeping. |
| `uses` counter, incremented only by joining | Previewing must not consume a code: someone who scans, reads the group's name and backs out has taken nothing. Who actually joined is already in `group_members`. |
| revoking sets `revoked_at`, never deletes | The row is the record that a code existed and how often it was used. |
| `ON DELETE CASCADE` from `groups` | A code for a group that is gone is nothing. |

### Device tokens

`device_tokens` holds the credential the phone's background service uses.
Reasoning for its existence is in
[Device tokens, for the background service](#device-tokens-for-the-background-service);
the schema decisions are these:

| Decision | Because |
|---|---|
| a separate credential, not a second JWT session | The refresh token is single-use with family-wide theft detection, so two processes refreshing log the user out. A second login would need the password stored on the device and would grant every permission the user has. |
| `(user_id, car_id)` on the row, and **no copy of the permission** | Access is re-derived on every request by the same `CarAccess` predicate everything else uses, so un-sharing the car, leaving the group, the group being deleted, or any rule invented later kills the token with nothing to keep in step. The alternative - revoking eagerly from every one of those places - is bookkeeping that drifts. |
| only the SHA-256 is stored, with an `obdd_` prefix on the value | A leaked dump yields nothing usable; the prefix means a secret scanner recognises the value if it ever lands in a log, and the server can refuse obvious noise without a query. |
| no expiry date, but dead after 90 days unused | It serves a process that must keep working for months without a person present. `last_used_at` anchors the deadline and renews it on use, so the only tokens that die of old age are the ones on phones nobody uses any more. |
| `last_used_at` written at most once a minute | Telemetry arrives in batches; a write per request would be pure cost. The UPDATE repeats the staleness test in its WHERE clause, so two concurrent uploads cannot both write. |
| revoked rows kept | The record of which phones had access, and it lets "already revoked" answer differently from "never existed" internally. |
| `ON DELETE CASCADE` from both `users` and `cars` | A credential for an account or a car that is gone is nothing. |

### Email verification and password reset

`V13` adds `users.email_verified_at` and two token tables. The mechanism is
the one already used twice: a random secret, hashed, redeemed once.

| Decision | Because |
|---|---|
| `email_verified_at`, not `enabled` | `enabled` already means "not disabled by an admin". Merging them would lose the difference between a brand-new account and a banned one. |
| **two token tables**, though their columns are identical | A verification token is mailed automatically on every registration and lives for a day; a reset token changes a password. In one table, the only thing stopping the first being redeemed as the second is remembering `and purpose = ?` in every query — one forgotten clause from account takeover. Separate tables make that unrepresentable, the same reasoning that kept `group_invite_codes` out of `invitations`. |
| only the SHA-256 is stored | A leaked dump contains nothing redeemable. Plain SHA-256, not BCrypt: the input is 32 random bytes, so there is nothing to brute force and no reason to pay for a slow hash on every click. |
| `consumed_at` stamped, never deleted | A second click can be answered *"already used"* instead of *"invalid link"*. |
| one live token per user, per table (partial unique index) | Issuing a new link retires the old one, so an older email stops working — and it is what the throttle reads. |
| mail is sent **after** the transaction commits | A provider call inside a transaction holds a database connection open across the network (five of them, on the dev pooler), and if that transaction then rolls back the mail has already gone out for something that did not happen. `MailRequest` is published inside, `AccountMailListener` sends after. |
| a failed send never fails the request | The token is already stored, so a lost message costs a resend. Failing registration because SMTP timed out would leave an account that exists behind an error screen. |

**The hole this closes.** Invitations are keyed by email (`V8`), and
registration never proved ownership of an address. Until `V13` anyone could
register as `victim@example.com`, read their pending invitations and join the
family group meant for them.

**The hard gate.** An account that has not confirmed its address can do
nothing: three endpoints work (`GET /users/me`, `POST /users/me/verify-email`,
`POST /auth/logout`) and everything else answers `403` with
`reason: email_not_verified`. `POST /auth/login` refuses it too, which is why
`POST /auth/resend-verification` is public — an account that cannot log in has
no authenticated way to ask for another link.

The rule is **one line in the filter chain**, not a check in each service:

```java
.anyRequest().hasAuthority(UserPrincipal.EMAIL_VERIFIED)
```

That is the whole reason to spend the authority rather than call
`requireVerifiedEmail` where it matters. An endpoint added next month is
closed until somebody opens it deliberately, instead of open until somebody
remembers. `DeviceTokenScopeIT.itMayNotCallAnythingElse` and
`EmailVerificationGateIT.everythingElseIsForbidden…` are the assertions that
keep it true.

`UserAccess.requireVerifiedEmail` still guards inviting, accepting and
minting a code inside the services. The chain refuses those requests first, so
the guard is unreachable over HTTP and kept deliberately: those three actions
are the ones that put this account in front of somebody else, and they should
not depend on one line of configuration elsewhere staying the way it is.

| Decision | Because |
|---|---|
| `EMAIL_VERIFIED` is an authority, **not** a role | The role says what kind of account this is; confirmation is orthogonal. Folding them together — `anyRequest().hasRole("USER")` — locked every `ADMIN` out of the entire API, since an admin's only role authority is `ROLE_ADMIN`. Pinned by `anAdminAccountIsNotLockedOut`. |
| login refuses, **after** checking the password | A `403` before the password check would confirm that an address is registered to anybody who asked. Wrong password is `401` whatever state the account is in. |
| registration still returns tokens | The app needs a credential to show the wall screen and offer a resend. The token is simply useless everywhere else until the address is confirmed. |
| the three allowed endpoints are `hasAnyRole("USER","ADMIN")`, not `authenticated()` | A device token authenticates. `authenticated()` let a dongle's credential read `GET /users/me`, which `DeviceTokenScopeIT` caught. |

**This replaced a soft gate** that blocked only accepting an invitation, on
the grounds that it was the one action reaching other people. It was dropped
because an account that can create groups, cars and trips under a name that is
not its own is already a problem, and the list of actions that expose you to
somebody else is not a list you can be sure you finished. Joining by QR used
to be a deliberate exception — a code is handed over in person and claims no
address — and is now gated like everything else.

### Mail

`Mailer` is an interface with two implementations, chosen by
**`app.mail.provider`** at startup:

| `app.mail.provider` | Implementation | Behaviour |
|---|---|---|
| unset or `log` | `LoggingMailer` | writes the message — **including the link** — to the application log and sends nothing. What development, the test suite and the smoke script run on: no account, no API key, no network. |
| `resend` | `ResendMailer` | sends through Resend's API (`resend-java`, pinned). Needs `RESEND_API_KEY`. |

The two are mutually exclusive, so exactly one `Mailer` bean exists in either
configuration — pinned by `MailProviderSelectionTest`, because getting it
wrong fails at startup rather than in a test.

**`log` refuses to run in production**, and that is enforced rather than
documented: `LoggingMailer`'s constructor throws unless the `dev` or `test`
profile is active, the same signal `app.cookie.secure=false` uses to mean
"this run is not serving real traffic".

Two reasons it is fatal rather than untidy. It prints a live token to the log,
which discards the point of storing only its hash. And since verification
became a [hard gate](#email-verification-is-a-hard-gate), mail is load-bearing:
an account can do nothing until it confirms its address, nobody can confirm an
address whose mail was never sent, so every new user would be locked out with
no symptom except a `403` that never goes away.

The trade-off is deliberate: a container with a misconfigured `.env`
crashloops instead of serving. The deploy workflow's health check catches that
and prints the reason, which names the variables to set. A deployment that
looks healthy while locking out everyone who registers is the worse outcome.

`ResendMailer` details worth knowing:

- **It refuses to start** if the provider is `resend` with no API key. A
  deployment configured for mail that cannot send is broken, and the only good
  moment to discover that is before it serves traffic — not on the first
  password reset somebody needs.
- `from` is assembled as `OBIDI <no-reply@mail.obidi.com.ar>` from
  `app.mail.from-name` + `app.mail.from`. Resend rejects any address whose
  domain is not verified in the account, so a wrong `from` shows up as a `403`
  on the first send.
- Every send carries an **`Idempotency-Key`** derived from the message itself,
  so two attempts at an identical mail collapse into one delivery rather than
  two copies in somebody's inbox.
- Timeouts are bounded (`app.mail.resend.timeout-seconds`, 10s). The send
  happens after the transaction commits, so a hanging provider cannot hold a
  database connection, but it can still tie up the request thread.
- Failures throw `MailSendFailedException`, which carries the purpose and the
  recipient and **not** the provider's response body — that can echo the
  request, and the request contains the link. `AccountMailListener` catches
  and logs it; the request that triggered it still succeeds.

Emails are the only user-facing text in the project, and they are in
**Spanish**. Code, comments, logs and these docs stay English.

Two things the verification mail says on purpose, both consequences of the
[hard gate](#email-verification-is-a-hard-gate): that the account cannot be
used until the link is clicked, so nobody waits for an app that will only
answer `403`; and that somebody who did *not* create the account should **not**
click it — the account is inert until somebody does, and clicking would
activate one whose password a stranger chose. It also points at the login
screen, not the profile, for a fresh link: a walled account cannot log in to
ask from inside the app.

**The pages the links open** live in `deploy/web/` and are served by the same
Caddy that fronts the API — `deploy/web/verify-email/index.html` and
`deploy/web/reset-password/index.html`, plus a shared `style.css`. No build
step, no framework, no external requests: a password reset has to work on a
bad connection, and nothing on the page should be able to leak the token in
its URL to a third party (hence `referrer: no-referrer` and `robots:
noindex`).

Caddy also proxies `/api/*` on that hostname to the API container, so the
pages call **relative** URLs. That means no API hostname baked into the HTML
and no cross-origin request at all, which is why `CORS_ORIGINS` needs nothing
added for them. See `deploy/README.md` → *Turning email on*.

The verification page posts the token as soon as it loads; the reset page
waits for a person to type a password and submit. The difference matters: a
mail scanner fetching either URL runs no JavaScript, so neither GET can spend
a token — which is the whole reason the API endpoints are POSTs.

**Before sending real mail** you need a domain and three DNS records, or the
mail lands in spam (Gmail and Yahoo have required them from bulk senders since
2024):

- **SPF** — a TXT record listing who may send for your domain. The receiver
  checks the connecting IP against it. Breaks on forwarding, by design.
- **DKIM** — the provider signs each message with a private key; you publish
  the public key in DNS. Survives forwarding, and proves the message was not
  altered.
- **DMARC** — ties the two together by requiring the authenticated domain to
  match the visible `From:`, says what to do when they fail
  (`none`/`quarantine`/`reject`), and collects reports. This is the part that
  actually stops impersonation. Start at `p=none`, read the reports, then
  tighten.

Send from a **subdomain** (`mail.tudominio.com`): reputation is tracked per
domain, so this keeps the app's mail from dragging down — or being dragged
down by — anything else on the domain.

## Running

The app reads all of its configuration from environment variables and does
**not** auto-load `.env` files (see the note in `.env.example` for why), so you
load the right file into your shell first, then start the app.

Keep **two env files, one per environment**, both git-ignored:

| File | Used for | Points at |
|---|---|---|
| `.env.dev` | local development (`dev` profile) | the **development** Supabase project (or a local Postgres) |
| `.env.prod` | production / running the prod build locally | the **production** Supabase project |

Copy `.env.example` to each and fill in the values for that environment. They
must never point at the same database: Flyway runs the migrations against
whatever `DB_URL` it finds on first start, and migrations are immutable once
applied, so a dev experiment against the prod DB cannot be undone.

Variables (same set in both files, different values):

| Variable | Purpose |
|---|---|
| `DB_URL` | JDBC URL. For Supabase use the **session pooler** host, e.g. `jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require` (the direct host is IPv6-only). Defaults to `jdbc:postgresql://localhost:5432/obd` |
| `DB_USER` / `DB_PASSWORD` | Postgres credentials. Supabase pooler user is `postgres.<project-ref>` |
| `JWT_SECRET` | Base64-encoded HMAC signing key for access tokens (`openssl rand -base64 32`). Use a different key per environment |
| `CORS_ORIGINS` | Comma-separated frontend origin(s) allowed via CORS. Dev: `http://localhost:5173`; prod: the deployed frontend URL |
| `PORT` | Optional. Port to listen on, defaults to `8080`. Hosting platforms set this themselves |

### Profiles

Compilation does not depend on the environment: `./mvnw package` builds one
jar containing every `application-*.properties`. The environment is chosen
**at run time** by the active Spring profile:

- **`dev`** (`application-dev.properties`): refresh cookie sent over plain
  HTTP, small Hikari pool sized for the Supabase pooler. Activated by
  `SPRING_PROFILES_ACTIVE=dev`, which lives in `.env.dev` so the file itself
  selects the profile.
- **no profile** (`application.properties` only): production settings —
  `Secure` cookies, no error details in responses. This is what runs in
  production; never activate `dev` there.

The startup log tells you which one took effect: `The following 1 profile is
active: "dev"` vs `No active profile set`.

### Development

```bash
set -a; source .env.dev; set +a
./mvnw spring-boot:run
```

Or build once and run the jar with the profile:

```bash
./mvnw -DskipTests package
set -a; source .env.dev; set +a
java -jar target/api-0.0.1-SNAPSHOT.jar
```

Or run the production container image against the dev database:

```bash
docker build -f Dockerfile.vercel -t obd-api .
docker run --rm -p 8080:8080 --env-file .env.dev obd-api
```

In IntelliJ: open the Run/Debug configuration for `ApiApplication` and under
**Modify options → Environment variables** paste the values from `.env.dev`,
including `SPRING_PROFILES_ACTIVE=dev` (or use an EnvFile-capable plugin).

### Production

Locally, to run exactly what production runs (no profile, prod database):

```bash
./mvnw -DskipTests package
set -a; source .env.prod; set +a
java -jar target/api-0.0.1-SNAPSHOT.jar
```

On the hosting platform there is no `.env.prod` file at all: enter the same
variables in the platform's environment-variable settings (e.g. Vercel →
Project → Settings → Environment Variables, marking `DB_PASSWORD` and
`JWT_SECRET` as sensitive) and do **not** set `SPRING_PROFILES_ACTIVE`. The
container is built from `Dockerfile.vercel`; `PORT` must match the port the
platform routes to.

If you also want a hosted **dev** deployment (e.g. Vercel's Preview
environment), give that environment the values from `.env.dev` - including
`SPRING_PROFILES_ACTIVE=dev` - scoped to Preview only, so production keeps
the default profile and the production database.

## Endpoints

Base path: `/api/v1`. Everything except `auth/*` and `GET /invite-codes/{code}`
requires `Authorization: Bearer <accessToken>`.

### From dongle to database — how a reading finds its car

The ESP32 speaks only BLE and every unit advertises the same name, `OBD-C`.
The phone is the relay, and the API is what tells it which car a dongle
belongs to. The whole path, in order:

1. **Pair, once, by the owner** — `PUT /cars/{carId}/device` with the
   dongle's serial. This is the only step where a person names the car.
   Group members do not pair; they inherit the mapping.
2. **The phone connects to `OBD-C`** and reads the unit's serial (the
   firmware half — Device Information Service `0x2A25`, see Database ›
   Devices). It does **not** need to know the car.
3. *(optional)* **`GET /devices/{serial}`** — "which car is this?" — to show
   the driver the car's name and to prompt for a trip. Not required before
   uploading.
4. **`POST /telemetry` with `serial` + the buffered readings.** The server
   resolves the serial to its paired car, checks that the uploader may read
   that car (owner or group member), stores the readings, stamps them with
   the car's open trip, refreshes the car's snapshot and marks the dongle as
   seen. The phone never says which car — it cannot get that wrong, and
   moving a dongle to another car (unpair, pair) re-routes every phone in the
   family at once.
5. **`GET /telemetry?carId=&since=`** — any member reads the history back.

`carId` on upload exists for the cases with no dongle in the loop: a car
without one, manual entry, tests. When the phone has a serial it should send
the serial.

---

### `POST /api/v1/auth/register`

Creates a new user, then logs them in (issues tokens).

**Body** (`application/json`):

```json
{
  "userName": "Ada",
  "userLastName": "Lovelace",
  "userEmail": "ada@example.com",
  "userPassword": "supersecret123",
  "userPhone": "+39 320 1234567"
}
```

| Field | Required | Notes |
|---|---|---|
| `userName` | yes | non-blank |
| `userLastName` | yes | non-blank |
| `userEmail` | yes | must be a valid email |
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

**The tokens work on three endpoints until the address is confirmed.** A
verification link is mailed here, and until it is clicked this account is
walled — see [Email verification is a hard gate](#email-verification-is-a-hard-gate).
The app should send the user to a *"confirmá tu correo"* screen rather than
into the application.

**Errors:** `409 Conflict` if the email is already registered, `400 Bad
Request` with a per-field error map if validation fails.

---

### `POST /api/v1/auth/login`

**Body** (`application/json`):

```json
{
  "userEmail": "ada@example.com",
  "userPassword": "supersecret123"
}
```

| Field | Required | Notes |
|---|---|---|
| `userEmail` | yes | must be a valid email |
| `userPassword` | yes | 8–72 characters |

**Response:** same shape as `register` — `AuthResponseDTO` body + `refreshToken` cookie.

**Errors:** `401 Unauthorized` on bad credentials. **`403 Forbidden` with
`reason: "email_not_verified"`** if the account never confirmed its address —
no token is issued, and the way forward is
`POST /auth/resend-verification`. The password is checked first, so a wrong
password is `401` either way.

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

### `POST /api/v1/auth/verify-email`

Redeems the link mailed at registration and marks the address verified.

**Public — no access token.** The mail is often opened on a laptop while only
the phone is logged in, and the secret in the link is itself the proof.

**Body**: `{ "token": "..." }` — the last path segment of the mailed URL.

A `POST`, even though it arrives from a link, because corporate mail filters
**fetch every URL they see**. A `GET` that consumed the token would be spent
before the human ever clicked it, so the link opens a screen and that screen
posts.

**Response** `204 No Content`.

**Errors:** `404 Not Found` for an unknown token, `409 Conflict` for one
already used or expired — told apart because the app says different things
("ese enlace no es válido" against "pedí uno nuevo"). `400` for an empty
token.

---

### `POST /api/v1/auth/resend-verification`

Mails another verification link to an address that owns an unconfirmed
account. **Public — no access token**, and that is the point: an unconfirmed
account cannot log in, so without this endpoint somebody who closed the app
before clicking the link would have no way back in at all.

**Body**: `{ "userEmail": "ada@example.com" }`

**Response** `202 Accepted` — **always**. Unknown address, already confirmed,
or throttled: the same answer, because this endpoint is public and anything
else would turn it into a way to ask *"does this person use the app?"*. Same
rule as `forgot-password`.

The throttle is therefore silent here (`app.mail.resend-throttle-seconds`,
60s), unlike `POST /users/me/verify-email`, which can answer `429` because
that caller is already identified.

**Errors:** `400 Bad Request` for a malformed address. The only thing it will
admit to.

---

### `POST /api/v1/auth/forgot-password`

Starts a password reset. **Public.**

**Body**: `{ "userEmail": "ada@example.com" }`

**Response** `202 Accepted` — **always**, whether or not that address has an
account, and whether or not it was throttled. Anything else would make this a
way to ask *"does this person use the app?"*, the same rule already applied to
`POST /invitations/invite/{groupId}`. The throttle is therefore silent here,
unlike the resend endpoint, which can answer `429` because the caller is
already identified.

**Errors:** `400 Bad Request` for a malformed address. That is the only thing
this endpoint will tell you.

---

### `POST /api/v1/auth/reset-password`

Sets a new password against the mailed link. **Public.**

**Body**: `{ "token": "...", "newPassword": "..." }` (8–72 characters)

No current password: not knowing it is the whole reason for this flow. The
link is the proof instead.

**Response** `204 No Content`, and the refresh cookie is cleared.

**Every session is revoked, with no exception for the device doing the
reset** — whoever is here may be recovering an account someone else had
access to. It also stamps `password_changed_at`, so access tokens already
issued die on their next request rather than lingering for up to
`app.jwt.access-ttl-minutes` (see `JwtAuthFilter`). The caller is deliberately
**not** logged in: logging in with the new password confirms to them that it
took.

Reaching the mailbox proves ownership of the address just as well as the
verification flow does, so **a reset also marks the address verified** — and
therefore lifts the [gate](#email-verification-is-a-hard-gate). It is the
second way through the wall, and the one the real owner of a squatted address
uses: *forgot password* takes the account over, revokes every session the
squatter had, and leaves it confirmed.

**Errors:** `404` unknown token, `409` already used or expired, `400` if the
new password is too short.

---

### `GET /api/v1/users/me`

The caller's own profile. Requires `Authorization: Bearer <accessToken>`.
The user is the token holder; there is no `GET /users/{id}`.

**Response** `200 OK`:

```json
{
  "id": "8f14e...-...",
  "userName": "Ada",
  "userLastName": "Lovelace",
  "userEmail": "ada@example.com",
  "userPhone": "+39 320 1234567",
  "emailVerified": true
}
```

`userName` is the first name (the same field `register` takes), not a
username. No password hash, no role.

**`emailVerified` is the flag the app routes on.** This is one of the three
endpoints an unconfirmed account may call, precisely so the client can read
this field and decide between the application and the *"confirmá tu correo"*
screen. While it is `false`, everything else answers `403`.

**Errors:** `404 Not Found` if the account behind a still-valid token no
longer exists; `401 Unauthorized` without a token. Note a device token gets
`403` here: this endpoint is for people, not dongles.

---

### `PUT /api/v1/users/me`

Edits the caller's own profile. Requires `Authorization: Bearer <accessToken>`.

**Body** (`application/json`):

```json
{ "userName": "Augusta", "userLastName": "Byron", "userPhone": "+39 06 999999" }
```

| Field | Required | Notes |
|---|---|---|
| `userName` | yes | non-blank; the first name, not a username |
| `userLastName` | yes | non-blank |
| `userPhone` | no | same pattern as registration |

**`PUT`, not `PATCH` - this replaces the editable profile.** A field left out
is *cleared*, not kept: sending no `userPhone` sets it to `null`. The client
has the whole object from `GET /users/me`, so a replace costs it nothing, and
"absent" and "explicitly null" stay the same thing rather than becoming two
cases to handle.

**Only three fields are editable here.** The email is the login identifier and
what `invitations` are keyed by; the password needs the current one and takes
every other session down with it. Neither belongs in a form that otherwise
cannot fail, so the password gets its own endpoint below (and changing the
email is not implemented yet - see Known limitations).

**Response** `200 OK` - the updated profile, same shape as `GET /users/me`.

**Errors:** `400 Bad Request` with a per-field map, `404 Not Found` if the
account behind a still-valid token is gone, `401` without a token.

---

### `POST /api/v1/users/me/verify-email`

Sends the caller a fresh verification link — the *"no me llegó el mail"*
button. Requires `Authorization: Bearer <accessToken>`.

No body, and **no address**: the caller's own is the only one it can target,
so it cannot be pointed at a stranger's inbox. Issuing a new link retires the
previous one, so an older email stops working.

**Response** `202 Accepted`.

**Errors:** `429 Too Many Requests` within the throttle window
(`app.mail.resend-throttle-seconds`, 60s) — answerable here, unlike
`forgot-password`, because the caller is already identified so there is
nothing to leak. `409 Conflict` if the address is already verified. `401`
without a token.

---

### `POST /api/v1/users/me/password`

Changes the caller's password and signs every **other** device out.

**Body** (`application/json`):

```json
{ "currentPassword": "supersecret123", "newPassword": "evenbetter456" }
```

| Field | Required | Notes |
|---|---|---|
| `currentPassword` | yes | the password in force right now |
| `newPassword` | yes | 8-72 characters, and different from the current one |

**The current password is required even though the caller already holds a
valid access token.** A token proves the session, not the person - a borrowed
unlocked phone carries one.

**Response** `200 OK` - the same envelope `login` returns, plus a rotated
`refreshToken` cookie:

```json
{
  "accessToken": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "expireInSeconds": 900,
  "userId": "8f14e...",
  "userEmail": "ada@example.com"
}
```

The change revokes **every** refresh family, including the caller's own, and
then issues a fresh one - in that order, or the new family would be revoked
along with the rest. That is why a new token pair comes back: without it the
user would be signed out of the very device they just changed the password on.

**Other devices stop working immediately, not eventually.** Revoking refresh
tokens cannot reach the access tokens already issued - they are self-contained
and valid until they expire, so another phone would otherwise keep working for
up to `app.jwt.access-ttl-minutes`. The change stamps
`users.password_changed_at`, and `JwtAuthFilter` rejects any token minted
before it. This costs nothing: the filter already loads the user from the
database on every request.

Both sides of that comparison are at **second precision** - a JWT's `iat` is
epoch seconds, so the stamp is truncated to match. Without that, the token
minted by the change itself would look older than the change and be rejected
on its first request. Pinned by
`JwtAuthFilterTest.theTokenMintedByTheChangeItselfSurvives`.

**Errors:** `401 Unauthorized` if `currentPassword` is wrong (a credential
failure, not a validation one - and nothing is changed, no session ends),
`400 Bad Request` if the new password is too short or equal to the current one
(refused rather than accepted as a no-op: it would sign every other device out
for nothing), `401` without a token.

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
  "longitude": null,
  "snapshotAt": null,
  "group": null
}
```

The telemetry fields are a cached snapshot of the last reading reported by the
device, so they are all `null` on a car that has never reported. `snapshotAt`
is the `recordedAt` of the reading they came from - "last seen" for the UI -
and is maintained by `POST /telemetry`. None of them are accepted from the
client.

**Errors:** `404 Not Found` if `modelId` is unknown, `409 Conflict` if the
caller already has a car with that plate, `400 Bad Request` with a per-field
error map if validation fails, `401 Unauthorized` without a valid access token.

Plates are unique **per owner**, not globally: making them globally unique
would let one account block another from registering a plate, and would leak
whether a plate is already known to the system.

Model ids come from `GET /api/v1/models`. The catalog is seeded by
`V2__cars_and_models.sql` with eight common models, so this endpoint is usable
before an admin has added any.

---

### `GET /api/v1/cars`

Every car the caller may use: their own, plus any shared with a group they
belong to. Requires `Authorization: Bearer <accessToken>`.

**Response** `200 OK` — an array of the same objects `POST /cars` returns,
ordered by name. Empty for a new user, never an error. The telemetry fields
are the cached snapshot maintained by `POST /telemetry`; `group` names the
group a car is shared with, or is `null`.

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

### `GET /api/v1/cars/{carId}`

One car, by id. Requires `Authorization: Bearer <accessToken>`.

**Response** `200 OK` — the same object `GET /cars` lists, so a client that
already has the list gains nothing by calling this; it is for deep links and
for refreshing one car's snapshot after `POST /telemetry`.

Goes through the same `CarAccess.readableBy` as every other per-car endpoint:
the owner and every member of the group the car is shared with may read it.

**Errors:** `404 Not Found` — the same answer whether the car does not exist or
belongs to someone the caller has no group in common with, so an id is never
confirmed to a stranger (`CarNotFoundException`). `400 Bad Request` when
`{carId}` is not a UUID. `401 Unauthorized` without a token.

---

### `PUT /api/v1/cars/{carId}/group`

Shares a car the caller **owns** with a group the caller **belongs to**. From
then on every member of that group sees it in `GET /cars`, can start a trip on
it and can upload telemetry for it. Requires
`Authorization: Bearer <accessToken>`.

**Body** (`application/json`):

```json
{ "groupId": "937bcb73-203e-44c8-8f6d-8adc2f4a2994" }
```

`PUT`, not `POST`: "the group this car is shared with" is a single slot the
owner sets, replaces or clears — a car is in **at most one group at a time**,
so sharing with a second group moves it. Sharing with the group it is already
in is a no-op.

**Response** `200 OK` — the car, now with a `group`:

```json
{
  "id": "25d7971f-0aee-4bb0-887d-a607800a4b75",
  "name": "Telemetry car",
  "...": "...",
  "snapshotAt": "2026-09-16T18:35:22Z",
  "group": { "id": "937bcb73-203e-44c8-8f6d-8adc2f4a2994", "name": "Familia Garcia" }
}
```

`group` is present on every car read (`GET /cars`, `GET /cars/{id}`), `null`
for an unshared car.

**Nothing else had to change for sharing to work.** Access was already one
predicate — `CarRepository.READABLE`, owner *or* member of the car's group —
behind `CarAccess`, and `TripService` and `TelemetryService` already went
through it. This endpoint is only the write that makes the predicate true.
Smoke check 59 is a group member uploading telemetry for a car they do not
own, against telemetry code that never learned groups exist.

**An open trip is left alone.** If a member is driving when the owner
un-shares (or moves) the car, the trip stays open and stays theirs — a trip is
a record of what happened, not a permission. They just cannot start the *next*
one. Pinned by `CarServiceTest.sharingLeavesAnOpenTripAlone`.

**Errors:** `404 Not Found` if the car is not the caller's — a member who may
drive it gets the same answer as a stranger, since which group a car is in is
the owner's decision — **or** if the caller is not a member of the target
group, so the endpoint cannot be used to probe group ids; `400 Bad Request`
without a `groupId`; `401 Unauthorized` without a token.

---

### `DELETE /api/v1/cars/{carId}/group`

Un-shares the car. Owner-only, idempotent.

**Response** `204 No Content`. Members lose access at once (their next
`GET /cars` no longer lists it, their next upload is a `404`); an open trip is
unaffected, as above.

**Errors:** `404 Not Found` if the car is not the caller's; `401 Unauthorized`
without a token.

---

### `GET /api/v1/groups/{groupId}/cars`

The cars shared with a group, ordered by name, for any member of it.

**Response** `200 OK` — an array of car objects, each with `group` set to this
group. `[]` for a group nobody has shared a car with yet.

**Errors:** `404 Not Found` if the caller is not a member (the group's
existence is not confirmed to outsiders); `401 Unauthorized` without a token.

---

### `PUT /api/v1/cars/{carId}/device`

Pairs a dongle to a car the caller **owns**. Requires
`Authorization: Bearer <accessToken>`.

**Body** (`application/json`):

```json
{ "serial": "a4:cf:12:8b:3c:7e" }
```

| Field | Required | Notes |
|---|---|---|
| `serial` | yes | ≤ 64 chars; letters, digits, `:`, `_`, `-`; trimmed and upper-cased before storing |

`PUT` semantics: pairing the car to a new serial **replaces** its current
dongle (and clears `lastSeenAt` — the new one has not reported yet); pairing
the same serial again is a no-op that returns the existing row. Owner-only —
group members may drive the car and upload for it, but which physical device
speaks for a car is the owner's decision.

**Response** `200 OK`:

```json
{
  "id": "776e510b-5367-4986-8bf5-2345903fa238",
  "serial": "A4:CF:12:8B:3C:7E",
  "carId": "2333c028-9224-4096-9c06-da2fd83e8bb3",
  "carName": "Telemetry car",
  "pairedAt": "2026-09-15T15:08:14.296645Z",
  "lastSeenAt": null
}
```

No `Location` header: a device is addressed as "this car's dongle" or "this
serial", never by its own id.

**Errors:** `404 Not Found` if the car does not exist or the caller does not
own it (a member gets the same answer as a stranger); `409 Conflict` if the
serial is paired to a **different** car — unpair it there first, so a move is
always deliberate; `400 Bad Request` for a blank or malformed serial; `401
Unauthorized` without a token.

---

### `GET /api/v1/cars/{carId}/device`

The dongle paired to a car the caller may read (owner or group member).

**Response** `200 OK` — same shape as above, `lastSeenAt` set once the dongle
has delivered readings.

**Errors:** `404 Not Found` with `"No such car"` if the car is not readable,
or `"No device is paired to this car"` if it is readable but has no dongle.
`401 Unauthorized` without a token.

---

### `DELETE /api/v1/cars/{carId}/device`

Unpairs the car's dongle. Owner-only. Idempotent — a car with no dongle
answers the same.

**Response** `204 No Content`. The serial is free to pair elsewhere afterwards.

**Errors:** `404 Not Found` if the car is not the caller's; `401 Unauthorized`
without a token.

---

### `GET /api/v1/devices/{serial}`

**"Which car is this dongle?"** — what a phone asks the moment it connects,
to show the driver the car's name and offer to start a trip. Not a
prerequisite for uploading: `POST /telemetry` accepts the serial directly and
does this lookup itself. The serial is matched after normalisation, so the
phone sends it however its BLE stack reports it.

**Response** `200 OK` — the same device object, whose `carId` and `carName`
are what the phone needs to start uploading and to tell the user what it
found.

Answered only if the caller may read that car. Otherwise — and for a serial
that does not exist — `404 Not Found` with `"No such device"`, identically:
a phone outside the family cannot confirm that a dongle exists. `401
Unauthorized` without a token.

---

### `GET /api/v1/models`

The car model catalog — what the create-car form lists, and where the
`modelId` that `POST /cars` requires comes from. Requires
`Authorization: Bearer <accessToken>`; any signed-in user may read it.

**Response** `200 OK`, ordered by brand then model — the order a picker
shows it in:

```json
[
  {
    "modelId": "00000000-0000-4000-8000-000000000003",
    "modelBrand": "Chevrolet",
    "modelName": "Onix",
    "modelProtocol": "ISO 15765-4 (CAN)"
  },
  {
    "modelId": "00000000-0000-4000-8000-000000000001",
    "modelBrand": "Volkswagen",
    "modelName": "Gol",
    "modelProtocol": "ISO 15765-4 (CAN)"
  }
]
```

Unpaged on purpose: the catalog is small and curated, not user-generated. The
eight seeded models from `V2__cars_and_models.sql` are always present, with
fixed ids, so the app is usable before anyone has added a model.

**Errors:** `401 Unauthorized` without a token.

---

### `POST /api/v1/models`

Adds a model to the catalog. **Admin-only**: requires an access token for an
account whose system role is `ADMIN` (`@PreAuthorize("hasRole('ADMIN')")`).
This is the account-level `Role`, unrelated to the `ADMIN` role inside a group.

The catalog is curated rather than open because `POST /cars` refuses free-text
brand/model precisely so the table cannot fill up with "VW" / "vw" /
"Volkswagen" variants of the same car.

**Body** (`application/json`):

```json
{
  "modelBrand": "Renault",
  "modelName": "Clio",
  "modelProtocol": "ISO 15765-4 (CAN)"
}
```

| Field | Required | Notes |
|---|---|---|
| `modelBrand` | yes | ≤ 60 chars |
| `modelName` | yes | ≤ 60 chars |
| `modelProtocol` | yes | ≤ 40 chars |

`(brand, model)` is unique (`ux_models_brand_model`). No `Location` header is
returned: a model has no canonical URL a client would fetch — the catalog is
only ever read as a list — so there is nothing for one to point at.

**Response** `201 Created` — the created model, same shape as one entry of
`GET /models`.

**Errors:** `409 Conflict` if that brand/model is already in the catalog,
`403 Forbidden` for a non-admin account, `400 Bad Request` for a missing or
overlong field, `401 Unauthorized` without a token.

**Creating the first admin.** `register` always assigns `USER`, and no endpoint
promotes an account, so the first admin is made by hand:

```sql
update users set role = 'ADMIN' where email = 'you@example.com';
```

The change applies on the next login (the role is read into the token then).

---

### `POST /api/v1/groups`

Creates a group (a "family") and enrols the caller as its first member with the
`ADMIN` role. Requires `Authorization: Bearer <accessToken>`.

**Body** (`application/json`):

```json
{ "name": "Familia Garcia" }
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
  "name": "Familia Garcia",
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

### `GET /api/v1/groups`

Every group the caller belongs to. Requires
`Authorization: Bearer <accessToken>`.

**Response** `200 OK` — an array of the same objects `POST /groups` returns,
ordered by name:

```json
[
  {
    "id": "e6c055de-e501-4e00-baf5-9cbc2d6722d2",
    "name": "Familia Garcia",
    "createdAt": "2026-09-08T16:45:39.848482Z",
    "memberCount": 3,
    "callerRole": "ADMIN"
  }
]
```

`callerRole` is *this* caller's role in *that* group — the same person can be
`ADMIN` of one group and `MEMBER` of another. `memberCount` is counted from
`group_members` on every read, never stored.

**One query.** The list is a single JPQL statement
(`GroupMemberRepository.findSummariesForUser`) that joins the caller's
memberships to their groups and builds the response objects directly, with the
member count as a correlated subquery — so a user in N groups costs one
round trip, not 1 + N. Empty for a user in no groups; never an error.

There is deliberately no `GET /users/{id}/groups`: "my groups, from the token"
is the same principal-not-body rule as everything else, and nothing yet needs
to read someone else's list.

**Errors:** `401 Unauthorized` without a valid access token.

---

### `GET /api/v1/groups/{groupId}/members`

Who is in a group, with their role. Requires
`Authorization: Bearer <accessToken>`; any member may read it, whatever their
role (`GroupAccess.requireMember`).

**Response** `200 OK` — admins first, then by name:

```json
[
  { "userId": "3f9a…", "name": "Carl", "email": "carl@example.com", "role": "ADMIN" },
  { "userId": "b81c…", "name": "Grace", "email": "grace@example.com", "role": "MEMBER" }
]
```

**One query.** `GroupMemberRepository.findMembersOf` joins `group_members` to
`users` and builds `GroupDTO.Member` directly, so a members screen can be
drawn from this list alone — there is no `GET /users/{id}` to resolve ids
with, and one request per member would be the N+1 this avoids. `name` is the
first name, as in `GET /users/me`.

**Errors:** `404 Not Found` for a non-member **and** for a group that does not
exist — the same answer, so a group id is never confirmed to an outsider.
`400 Bad Request` when `{groupId}` is not a UUID. `401 Unauthorized` without a
token.

---

### `POST /api/v1/invitations/invite/{groupId}`

An admin of `{groupId}` invites an email address to join it. Requires
`Authorization: Bearer <accessToken>`.

**Body** (`application/json`):

```json
{ "email": "Grace@Example.com" }
```

| Field | Required | Notes |
|---|---|---|
| `email` | yes | valid email; trimmed and lowercased before storing |

Invitations are keyed by **email, not user id**, because the common case in a
family app is inviting someone who has not installed it yet. The row waits;
when that email registers and logs in, the invitation is simply there.

Nothing is written to `group_members` here. Joining is consent — the invitee
accepts (below); an admin cannot enrol someone by typing their address.

**Response** `200 OK`:

```json
{
  "invitationId": "dc839824-2a6a-4a6e-905b-098a90c9cca4",
  "groupId": "fa3b6130-bee8-4523-a450-75147ef7fb19",
  "invitationEmail": "grace@example.com",
  "invitationBy": "00927ef1-2499-4487-b26b-601305cf1c69",
  "invitationCreatedAt": "2026-09-15T13:04:36.832938Z",
  "invitationExpiresAt": "2026-09-22T13:04:36.832938Z",
  "invitationStatus": "PENDING"
}
```

`invitationStatus` is derived from the timestamps — `ACCEPTED` if
`accepted_at` is set, `EXPIRED` if past `expires_at`, else `PENDING` — and is
never stored. Invitations expire 7 days after creation.

**The response is identical whether or not the email has an account.** The
only thing that changes the outcome is existing *membership* of this group,
which implies an account anyway. Anything else would make this endpoint a free
"is this email registered?" oracle for anyone who has created a group.

**Expired invitations are reclaimed here.** An invitation that lapsed
unaccepted still occupies `ux_invitations_pending`; inviting the same email
again deletes it first, then issues a fresh one. That is the only cleanup
expired invitations get, and it is enough: an expired row is harmless until
someone re-invites that address, and then it is gone. No scheduled job,
nothing to forget. Accepted rows are never removed — they are the record of
how someone joined — and the partial unique index lets a re-invite coexist
with them.

**Errors:** `404 Not Found` if the caller is not a member of the group (a
`403` would confirm the group exists); `403 Forbidden` if the caller is a
member but not `ADMIN` — or, with `reason: email_not_verified`, if their own
address is unconfirmed, which the filter chain refuses before the request
reaches the group at all; `409 Conflict` if that email already belongs to a
member; `400 Bad Request` for a malformed email; `401 Unauthorized` without a
token. A second *live* pending invitation for the same email is rejected by
`ux_invitations_pending` — see Known limitations for how that currently
surfaces.

---

### `GET /api/v1/invitations/pending`

The caller's pending invitations — the way an invitee discovers the id they
need to accept. Requires `Authorization: Bearer <accessToken>`.

The email is always the caller's own, from the token. There is no way to ask
for anyone else's list, which is what stops one person listing — and then
accepting — another's invitations.

**Response** `200 OK`, newest first. Accepted and expired invitations are
filtered out, so every id returned is one the caller can accept right now:

```json
[
  {
    "id": "dc839824-2a6a-4a6e-905b-098a90c9cca4",
    "groupId": "fa3b6130-bee8-4523-a450-75147ef7fb19",
    "groupName": "Familia Garcia",
    "invitationCreatedAt": "2026-09-15T13:04:36.832938Z",
    "invitationExpiresAt": "2026-09-22T13:04:36.832938Z"
  }
]
```

A different shape from the admin's view on purpose: the invitee sees the
group's *name*, not the inviter's user id. Empty for most users most of the
time — `[]`, never `404`.

**Errors:** `401 Unauthorized` without a token.

---

### `POST /api/v1/invitations/{id}/accept`

The invitee accepts an invitation addressed to their email **and joins the
group as `MEMBER`**, in one transaction. Requires
`Authorization: Bearer <accessToken>`; no body.

Acceptance is a single conditional `UPDATE`
(`InvitationRepository.accept`): the row is marked accepted only if it is this
invitation, addressed to the caller's email, still pending, and not yet
expired — all four in the `WHERE` clause, so the check and the write are one
atomic statement. The caller physically cannot accept an invitation that is
not theirs, or twice, whatever happens around the call.

**Response** `200 OK` — the invitation, now `"invitationStatus": "ACCEPTED"`.
The caller appears in `GET /groups` immediately, with `callerRole: MEMBER`.
The membership is written under the id of the account that accepted, which
is the only account whose email could have matched.

**Errors:** the `UPDATE` affecting zero rows means one of four things, and the
service reads the row back to say which:

| Cause | Status |
|---|---|
| No such invitation, **or** addressed to someone else — deliberately indistinguishable | `404 Not Found` |
| Already accepted (a double tap, or a retry after a lost response) | `409 Conflict` |
| Expired | `409 Conflict` |

`401 Unauthorized` without a token.

---

---

### QR invites — the five endpoints, and what each is for

Two ways into a group, for two different situations:

| | `invitations` (by email) | `group_invite_codes` (by QR) |
|---|---|---|
| Names | one email address, before they have an account | nobody — whoever scans |
| Reaches them | waiting in `GET /invitations/pending` when they register | in person, on a screen or a printed poster |
| Acceptable by | only the account holding that address | anyone holding the code |
| Lives | 7 days | 24 hours by default |

They are separate tables on purpose. An invitation's safety is the condition
`and i.invitationEmail = :email` in `InvitationRepository.accept`; a bearer
code has no email to check, so making that column nullable would have turned
the one condition that matters into an optional one.

**The code is a secret, and only its SHA-256 is stored** — the same
construction as `refresh_tokens.token_hash`. Two consequences worth knowing
before you build a screen on it: a database leak yields no joinable codes, and
**the server cannot show a QR twice**. Re-opening the admin screen shows
metadata, not the code; showing the QR again means minting a new one, which is
also what kills the printed poster.

The API returns the *payload*, never a PNG. Rendering is the client's job
(`qr_flutter`), which keeps image sizes, error-correction levels and logos out
of the backend entirely.

---

### `POST /api/v1/groups/{groupId}/invite-code`

Mints the group's QR code, replacing whatever it had. **Admin-only** — a
member may drive the cars, but deciding who else gets in is the admin's call,
the same split as pairing a dongle.

**Body** (`application/json`), or none at all for the defaults:

```json
{ "ttlHours": 24, "maxUses": null }
```

| Field | Required | Notes |
|---|---|---|
| `ttlHours` | no | 1–168. Defaults to `app.invite.code-ttl-hours` (24) |
| `maxUses` | no | ≥ 1, or omitted for unlimited until it expires or is revoked |

**Response** `201 Created` — the one and only time the code leaves the server:

```json
{
  "code": "kJ8xQv7sT2nR4mW9pL1yB6zC3dF5gH0jK8nM2qS4tV6",
  "joinUrl": "https://obd-c.app/join/kJ8xQv7sT2nR4mW9pL1yB6zC3dF5gH0jK8nM2qS4tV6",
  "expiresAt": "2026-09-25T18:00:00Z",
  "maxUses": null,
  "replacedPrevious": true
}
```

Put `joinUrl` in the QR; its base is `app.invite.join-url-base`
(`INVITE_JOIN_URL_BASE`), which in production should be a universal link that
opens the app — and offers the store to someone who has not installed it.

`replacedPrevious` says a previous code was killed to make room for this one:
the cue for *"the QR you printed no longer works"*. No `Location` header —
the `GET` below returns metadata, never the code, so no URL serves this
resource again.

**One live code per group**, enforced by `ux_gic_one_live_per_group` (partial
on `revoked_at is null`). Minting revokes the old row first, in the same
transaction. The revoke is deliberately *not* filtered on expiry: an expired
row is still unrevoked, so it still occupies the index.

**Errors:** `404 Not Found` if the caller is not a member (the group is not
confirmed to exist), `403 Forbidden` if they are a member but not `ADMIN`,
`400 Bad Request` for an out-of-range `ttlHours`/`maxUses`, `401` without a
token.

Like every other endpoint, this one also answers `403` with
`reason: email_not_verified` before any of that if the caller never confirmed
their address. The two are told apart by `reason`, which is absent on the
not-an-admin refusal.

---

### `GET /api/v1/groups/{groupId}/invite-code`

The admin screen re-opened later: is a code live, and how is it doing.
Admin-only.

**Response** `200 OK`, or **`204 No Content`** when the group has no usable
code — the normal state, so not an error, and `404` here keeps its one meaning
of "no such group" (the same reasoning as `GET /cars/{id}/trips/active`). A
code that has expired or run out of uses reads as `204` too.

```json
{
  "id": "…",
  "createdAt": "2026-09-24T18:00:00Z",
  "expiresAt": "2026-09-25T18:00:00Z",
  "uses": 3,
  "maxUses": 5,
  "remainingUses": 2
}
```

No `code`, ever. `remainingUses` is `null` when `maxUses` is.

**Errors:** `404` non-member, `403` non-admin, `401` without a token.

---

### `DELETE /api/v1/groups/{groupId}/invite-code`

Kills the group's code — the "someone photographed the poster" button.
Admin-only, idempotent.

**Response** `204 No Content`, whether or not there was anything to revoke.

The row is not deleted: it stays as the record that a code existed and how
often it was used, and revoking frees the unique index for the next one.
**Revoking never removes anyone.** A code is how someone got in, not what
keeps them in.

**Errors:** `404` non-member, `403` non-admin, `401` without a token.

---

### `GET /api/v1/invite-codes/{code}`

**"What am I about to join?"** — what the phone asks the moment it scans, to
show *"Join Familia Garcia · 3 members?"* before the user commits.

**No token required.** Someone scanning a QR before they have an account sees
the group's name rather than a bare login wall; the code is the capability,
and holding it is the authorisation. It is the only endpoint outside
`auth/*` that needs no credential at all — joining needs a token, and a
confirmed address.

**Response** `200 OK`:

```json
{
  "groupId": "fa3b6130-bee8-4523-a450-75147ef7fb19",
  "groupName": "Familia Garcia",
  "memberCount": 3,
  "expiresAt": "2026-09-25T18:00:00Z"
}
```

`groupId` is included so the app can spot a group the user is already in
without a second request; on its own it grants nothing, since every group
endpoint checks membership.

**Read-only — previewing never spends a use.** Someone who scans, reads the
name and taps Cancel has consumed nothing.

**Errors:** `404 Not Found` (`"No such invite code"`) for an unknown code;
`409 Conflict` for one that is revoked, expired or out of uses. The two are
told apart on purpose — only someone holding the real code gets that far, and
the app says different things ("that link is wrong" against "ask them for a
new QR").

---

### `POST /api/v1/invite-codes/{code}/join`

Joins the caller to the group the code belongs to, as `MEMBER`. No body — the
code is in the path, the user is in the token.

**Response** `200 OK` — the group as the caller now sees it, so the app can
navigate straight in:

```json
{
  "id": "fa3b6130-bee8-4523-a450-75147ef7fb19",
  "name": "Familia Garcia",
  "createdAt": "2026-09-08T16:45:39.848482Z",
  "memberCount": 4,
  "callerRole": "MEMBER"
}
```

**Already a member? `200`, and nothing happens** — no use spent, no write.
Scanning the same poster twice is ordinary behaviour, not a conflict (this is
a deliberate divergence from the email flow's `409 AlreadyAMember`). The
membership check runs *before* the code is claimed for a second reason as
well: `GroupMemberRepository.save` is an upsert on an assigned composite key,
so writing `MEMBER` over the row of an admin testing their own QR would
silently demote them — and a group whose last admin was demoted cannot be
administered by anyone. Pinned by
`InviteCodeServiceTest.anAdminScanningTheirOwnCodeIsNotDemoted` and smoke 77.

**One conditional `UPDATE`** (`InviteCodeRepository.claim`): the use is spent
only if the code is unrevoked, unexpired and under its limit — all in the
`WHERE`, so two people scanning a single-use QR at the same moment cannot both
get in. Spending the use and inserting the membership are one transaction: a
crash between them would burn a use without letting anybody in.

**A code never confers `ADMIN`**, and never changes an existing member's role.

**A confirmed address is required**, like everywhere else: an account that has
not verified gets `403` with `reason: email_not_verified` and the code keeps
all its uses. This used to be an exception — a code is handed over in person,
so nobody is impersonated — and stopped being one when the gate became
absolute. A guest at the dinner table confirms their mail first; previewing
the code with `GET /invite-codes/{code}` works meanwhile.

**Errors:** `404` unknown code, `409` revoked/expired/exhausted, `401` without
a token, `403` unconfirmed address.

---

### `POST /api/v1/trips`

Starts a trip ("viaje"): records that the caller is now using a car, and leaves
the trip open until it is finished. Takes **either** credential —
`Authorization: Bearer <accessToken>` for a person, or
`Authorization: Device <token>` for the phone's background service, which may
only start trips on the car its token is scoped to.

**Body** (`application/json`):

```json
{
  "carId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "initialFuel": 70
}
```

| Field | Required | Notes |
|---|---|---|
| `carId` | yes | a car the caller may use: their own, or one shared with a group they belong to |
| `initialFuel` | no | fuel reading at the start, ≥ 0; defaults to the car's cached snapshot (`fuelLevel`), which is what the dongle last reported |
| `startedAt` | no | ISO-8601 UTC. When driving actually began. Defaults to the server clock; at most 5 minutes ahead and 30 days old |
| `clientTripId` | no | any UUID the client mints. Makes starting idempotent - see below |

**`201` created, `200` already created.** With a `clientTripId`, a repeat of
the same start returns the same trip and `200` instead of creating a second
one. That distinction is the point: without it, a lost response is
unrecoverable, because the retry gets `409 "car already on a trip"` and the
client cannot tell its own trip from one another driver started. The key is
scoped per driver, so the same UUID from another account is a different trip -
keying on the value alone would hand somebody else's trip to whoever guessed
it.

Note what the key does **not** do: it makes a retry safe, not a second driver
legal. A genuinely new start on a car that is already on a trip is still
`409`.


**The driver always comes from the credential, never from the body**, so a
trip cannot be logged in somebody else's name. The *time* is the client's to
claim: `startedAt` is what it reports and `createdAt` is when the server heard
it, and the two differ exactly as much as the phone was offline.

**Response** `201 Created`. No `Location` header: there is no
`GET /trips/{id}` for it to point at.

```json
{
  "id": "facb7ae2-cf8e-45f9-8fe4-c9d1b747354d",
  "carId": "a6eefd2a-bf17-4c5d-9597-a4029db33f4b",
  "driverId": "63173dc5-520b-4d84-8bbb-8a8367741ba7",
  "startedAt": "2026-09-09T02:45:43.663568Z",
  "endedAt": null,
  "createdAt": "2026-09-09T02:45:43.663568Z",
  "clientTripId": "9a0e1f4c-1d2b-4a3c-9f8e-7d6c5b4a3210",
  "initialFuel": 70,
  "finalFuel": null,
  "fuelUsed": null,
  "distanceKm": null,
  "active": true
}
```

`createdAt` is the server's clock and `startedAt` is the client's claim. For a
trip started online they are the same instant; for one uploaded later,
`startedAt` is older. `clientTripId` is echoed back, or `null` if none was
sent.

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

**Errors:** `404 Not Found` if the car does not exist **or the caller may not
use it** (neither owner nor a member of its group) — the two are deliberately
indistinguishable, since a `403` would confirm that an id is real; `409
Conflict` if the car is already on a trip; `400 Bad Request` with a per-field
error map if validation fails; `401 Unauthorized` without a valid access token.

---

### `GET /api/v1/trips`

The caller's trips — every trip they drove, in any car. Requires
`Authorization: Bearer <accessToken>`. The driver is always the token holder;
there is no way to ask for someone else's history.

**Response** `200 OK` — an array of the trip objects `POST /trips` returns,
open and finished alike (`active` tells them apart), newest first. `[]` for
someone who has never driven; never an error. Unpaged for now.

---

### `GET /api/v1/cars/{carId}/trips`

**The car's history** — every trip on it, by every driver, newest first — for
anyone who may read the car (owner or group member). This is what a shared
car's history is for: who used it, and who used the fuel.

**Response** `200 OK`, `[]` for a car nobody has driven yet.

**Errors:** `404 Not Found` if the car does not exist or the caller may not
read it; `401 Unauthorized` without a token.

---

### `GET /api/v1/cars/{carId}/trips/active`

**"Who has the car right now?"** — the car's open trip, for anyone who may read
the car (owner or group member). Derived from the one row with
`ended_at is null`; the car itself stores no pointer, so this can never
disagree with the trip table.

**Response** `200 OK` with the open trip, or **`204 No Content` when the car is
idle** — which is its usual state, so that is a normal answer, not an error.
`204` rather than `404` because `404` here means "no such car".

**Errors:** `404 Not Found` if the car does not exist or the caller may not
read it; `401 Unauthorized` without a token.

---

### `POST /api/v1/trips/{tripId}/finish`

The driver ends their trip. Requires `Authorization: Bearer <accessToken>`.

**Body** (`application/json`):

```json
{ "tripFinalFuel": 50, "tripDistance": 140 }
```

| Field | Required | Notes |
|---|---|---|
| `tripFinalFuel` | no | fuel reading at the end, ≥ 0. Absent means the expense is unknown (`fuelUsed: null`), not zero. |
| `distanceKm` | no | > 0, **kilometres**, two decimals. Renamed from `tripDistance`, which carried no unit |
| `endedAt` | no | ISO-8601 UTC. When driving actually ended. Defaults to the server clock |

**`endedAt` is accepted and kept**, bounded the same way `startedAt` is. The
phone is the only thing present when a trip ends, so a trip driven through a
tunnel and uploaded two hours later must not be recorded as having ended two
hours late. An `endedAt` that predates the trip's own start is a `400`: the
comparison lives in the `WHERE` clause of the closing `UPDATE`, so it happens
against the row being written rather than against a value that could change in
between.

`tripFinalFuel` may exceed `initialFuel`: the driver refuelled, and `fuelUsed`
comes out negative rather than being "validated" away.

**Response** `200 OK` — the trip, now with `endedAt`, `active: false`, and
`fuelUsed` computed as `initialFuel - tripFinalFuel`:

```json
{
  "id": "4d405fed-973a-4f20-bfa5-d486fd221a74",
  "carId": "060200ce-b92f-4917-a0bb-c53afdf50bd3",
  "driverId": "b7a60789-bdb2-4e86-a617-3fae2266e746",
  "startedAt": "2026-09-16T20:45:59.940386Z",
  "endedAt": "2026-09-16T20:46:04.746519Z",
  "createdAt": "2026-09-16T20:45:59.940386Z",
  "clientTripId": null,
  "initialFuel": 68,
  "finalFuel": 50,
  "fuelUsed": 18,
  "distanceKm": 12.75,
  "active": false
}
```

**One conditional `UPDATE`** (`TripRepository.finish`): the row is closed only
if it is this trip, driven by the caller, and still open — all in the `WHERE`,
so the check and the write are one atomic statement. That gives three things
for free: only the driver can finish their trip (the owner cannot close a
member's trip out from under them); a double tap or a retry cannot overwrite
the first result with a second fuel reading; and `ended_at`, `final_fuel` and
`distance_km` land together, which is what `ck_trips_open_has_no_result`
requires. The car is free the instant the statement runs — a new trip can
start immediately (smoke 66).

**Errors:** `404 Not Found` if the trip does not exist **or is someone
else's** — indistinguishable on purpose; `409 Conflict` if it has already
ended; `400 Bad Request` for negative fuel or non-positive distance; `401
Unauthorized` without a token.

---

### `DELETE /api/v1/trips/{tripId}`

Cancels a trip the caller started by mistake, as if it never happened. Only an
**open** trip of the caller's own can be cancelled: a finished trip is history,
carries an expense, and stays.

**Response** `204 No Content`.

One conditional `DELETE … where trip_id = ? and driver_id = ? and ended_at is
null`; when it removes nothing the service looks the trip up to say why, the
same way `finish` does:

**Errors:** `404 Not Found` for an unknown trip **and** for someone else's —
the same answer, so a trip id is never confirmed to a non-driver (and the
car's owner cannot cancel a trip out from under the driver). `409 Conflict`
for a trip that has already ended. `401 Unauthorized` without a token.

---

### `GET /api/v1/trips/{tripId}/route`

Where the car went during a trip, oldest first. Readable by anyone who may
read the car, like its trip history.

**Response** `200 OK` — only the readings that carry a position; a fuel-only
frame draws nothing:

```json
[
  { "recordedAt": "2026-10-01T20:21:34Z", "latitude": -34.6037, "longitude": -58.3816, "speed": 0 },
  { "recordedAt": "2026-10-01T20:22:34Z", "latitude": -34.6040, "longitude": -58.3820, "speed": 42 }
]
```

**Resolved by time window, not by `telemetry.trip_id`** — and this is the part
worth knowing, because it is what makes an offline-synced trip have a route at
all.

`trip_id` is stamped at ingest, once, and only when a trip was open at that
moment. Two ordinary situations leave it null forever, since nothing re-stamps
an inserted row:

- the phone uploads a tunnel's worth of readings **before** it uploads the
  trip, so no trip was open;
- it uploads them **after** the trip was already finished, in a later batch.

So the route is read as "this car's readings between the trip's start and its
end", which is correct regardless of arrival order. The window is unambiguous
because `ux_trips_one_active_per_car` forbids two overlapping trips on one
car, and it is already indexed - `ux_telemetry_car_recorded_at` is
`(car_id, recorded_at)`. An open trip's window runs to now.

`trip_id` is kept and still stamped: it is never *wrong*, only sometimes
*absent* - a cache that can be cold rather than a second source of truth. But
nothing that has to be complete may rely on it.

**Errors:** `404 Not Found` if the trip does not exist or its car is not
readable by the caller; `401` without a credential.

---

### `POST /api/v1/cars/{carId}/device-tokens`

Mints the credential the phone's background service uses. Requires a person:
`Authorization: Bearer <accessToken>`, with access to the car.

**Body**: `{ "label": "Pixel de Juan" }` — non-blank, ≤ 60 chars. Shown back
to the user so they know which phone they are revoking.

**Response** `201 Created` — the only response that ever carries the token:

```json
{
  "id": "eb10c7ce-fcb0-4eb1-b98e-7e9360899710",
  "carId": "ee9e87e6-46d4-4a06-9998-8545daf00057",
  "label": "Pixel de Juan",
  "token": "obdd_QSvj1JBW3pl2qG9w7kQlER7HlCW69Owa1mFKgQu0bGs",
  "createdAt": "2026-10-01T22:35:10.735274Z",
  "idleExpiresAt": "2026-12-30T22:35:10.735274Z"
}
```

Only the SHA-256 is stored, so this cannot be produced again — hand it to the
native side and store it encrypted there. No `Location` header: a token is
addressed for deletion by its id, and there is no endpoint that returns one by
id, deliberately.

**Any member may mint one for a shared car**, not only the owner. The point is
that whoever's phone is in the car can upload for it, and a member who may
start trips by hand may as well have them recorded automatically — the token
can do no more than its owner already could. Several per person per car are
allowed: one per phone, each revocable on its own.

**Errors:** `404 Not Found` if the car does not exist or the caller may not use
it, `400` for a blank or overlong label, `403` if the caller is a device token
(a stolen token must not be able to issue itself a replacement), `401` without
a credential.

---

### `GET /api/v1/cars/{carId}/device-tokens`

The caller's **own** live tokens for that car — what the app lists so a user
can see which phones have access and withdraw one.

**Response** `200 OK`, newest first, and never the tokens themselves:

```json
[
  {
    "id": "eb10c7ce-…",
    "carId": "ee9e87e6-…",
    "label": "Pixel de Juan",
    "createdAt": "2026-10-01T22:35:10.735274Z",
    "lastUsedAt": "2026-10-01T23:02:11.004121Z",
    "idleExpiresAt": "2026-12-30T23:02:11.004121Z"
  }
]
```

Only their own: another member's phone is that member's business, and listing
it would leak which family members have the app installed. `lastUsedAt` is
`null` until the first upload and is written at most once a minute, so it is
accurate to the minute rather than the request.

**Errors:** `404` if the car is not readable by the caller, `401` without a
credential.

---

### `DELETE /api/v1/device-tokens/{id}`

Withdraws a token. The phone stops working on its next upload — `401` with
`reason: token_revoked`.

**Response** `204 No Content`.

Only the token's own owner may revoke it, **even the car's owner cannot revoke
a member's**. The row is kept rather than deleted, as the record of which
phone had access.

**Errors:** `404 Not Found` for a token that is unknown, already revoked, or
somebody else's — one answer for all three, so a token id is never confirmed
to a stranger. `401` without a credential.

**The user's own session is untouched**, which is the whole reason this
credential is separate from it.

---

### `POST /api/v1/telemetry`

Stores a batch of readings for one car and brings the car's cached snapshot up
to date. Requires `Authorization: Bearer <accessToken>`.

The phone is the relay (the ESP32 speaks only BLE), so it uploads whatever the
dongle sent since the last upload — batched, possibly late, possibly a repeat of
something it was unsure about. The endpoint is shaped for that: **retrying a
batch is always safe.**

**Which car?** The batch names its target in one of two ways, and exactly one:

- **`serial`** — the dongle's serial, as read over BLE. This is the normal
  path. The server looks the serial up in `devices`, takes the car it is
  paired to, and uses that; the phone does not know, and is not asked, which
  car it is in. Also marks the dongle's `lastSeenAt`.
- **`carId`** — the car directly. For a car with no dongle, manual entry, or
  tests. Says nothing about hardware, so `lastSeenAt` is untouched.

Either way the uploader comes from the token and must be able to read the car
— its owner or a member of the group it is shared with. A serial paired to a
car the caller cannot see answers exactly like an unknown serial.

**Body** (`application/json`) — by serial, the phone's case:

```json
{
  "serial": "a4:cf:12:8b:3c:7e",
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

Or by car id, same `readings`:

```json
{ "carId": "2d14dd55-c2c8-4b5a-aae8-b1c93a632cfc", "readings": [ … ] }
```

| Field | Required | Notes |
|---|---|---|
| `serial` | one of | the paired dongle's serial; matched after trim + upper-case, so send it as the BLE stack reports it. ≤ 64 chars |
| `carId` | one of | the car, by id. Exactly one of `serial`/`carId` |
| `readings` | yes | 1–500 entries |
| `readings[].recordedAt` | yes | when the reading was **taken** (device time), not uploaded; at most 5 minutes in the future |
| `readings[].latitude` / `longitude` | no | both or neither; valid WGS-84 ranges |
| `readings[].speed`, `fuelLevel`, `batteryLevel`, `mileage` | no | ≥ 0 each |
| `readings[].raw` | no | any JSON — the frame as the device sent it, kept verbatim |

**Response** `200 OK`. `carId` is the car the readings landed on — when
uploading by serial this is how the phone learns it:

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
would leave its buffer state unknowable; also `400` (`exactlyOneTarget`) if
both or neither of `serial`/`carId` are given. `404 Not Found`: by car id,
`"No such car"` if it does not exist or the caller may not read it; by serial,
`"No such device"` if the serial is not paired to any car **or** is paired to
a car the caller may not see — the same answer, so a phone outside the family
cannot confirm a dongle exists. `401 Unauthorized` without a token. There is
deliberately no `409`: unlike a duplicate trip start, a duplicate reading is
the normal retry path.

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

## Auth flow — how it works server-side

What a client has to *do* is under
[Keeping a session alive](#keeping-a-session-alive). This is the mechanism
underneath it.

1. `register`/`login` issue an access token (body) and a refresh token
   (cookie). Each login starts a **family**: the chain of refresh tokens
   descended from it.
2. Protected endpoints are called with `Authorization: Bearer <accessToken>`.
3. `refresh` rotates: it revokes the token presented and issues its successor
   in the same family. Presenting an already-rotated token is treated as theft
   — the **whole family** is revoked, so both the attacker and the legitimate
   holder are logged out and the user has to log in again. That is why a client
   must never run two refreshes at once.
4. `logout` revokes every family for that account.
5. `POST /users/me/password` revokes every family, issues a fresh one for the
   caller, and stamps `password_changed_at`. Access tokens cannot be revoked on
   their own, so `JwtAuthFilter` rejects any token minted before that stamp —
   which is what makes "signed out everywhere" immediate rather than up to 15
   minutes later. A reset via `POST /auth/reset-password` does the same, with no
   exception for the device doing the reset.

## Testing

```bash
./mvnw test
```

Docker must be running; nothing else is required (no local Postgres, no
environment variables). That one command runs **every** lane below, `*IT`
classes included — Surefire's default patterns stop at `*Test`, so `pom.xml`
lists `**/*IT.java` explicitly. Three lanes:

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

**Chain tests** - `@SpringBootTest` + `@AutoConfigureMockMvc`, for the rules
that live in `SecurityConfig` and therefore in no slice.
`EmailVerificationGateIT` asserts what an unconfirmed account may and may not
do (and that an `ADMIN` is not locked out); `DeviceTokenScopeIT` asserts that
a dongle's credential reaches four endpoints and nothing else. Both are
written as blanket refusals, so an endpoint added later fails them until
somebody opens it deliberately.

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

- `GET /trips` and `GET /cars/{carId}/trips` are unpaged.
- Deleting a car deletes its trips (`on delete cascade`), unlike deleting a user,
  which orphans them. There is no delete-car endpoint yet, so this is still free
  to change if trip history should outlive the car.
- Whether `fuel_level` and `battery_level` are percentages or absolute units is
  undecided, so V2 constrains them to `>= 0` rather than `0..100`. Tighten in a
  later migration once the firmware settles what it reports.
- No admin account is seeded, so the smoke script only covers the `403` side
  of `POST /models`; the `201` path is pinned by `ModelControllerTest`.
- Updating and deleting a car are not implemented.
- A device token is not rate-limited beyond what the endpoints themselves do.
  Ingest is idempotent and capped at 500 readings per batch, so the damage a
  misbehaving client can do is bounded, but a per-token limit is the obvious
  next guard.
- Trip distance is whatever the client reports; the server does not compute it
  from the route. With positions now resolvable by time window that is
  possible, but raw GPS summed point to point overstates distance badly, so it
  needs filtering to be worth doing.
- **Registering with somebody else's address is still possible, but the
  account is inert.** It can do nothing until the link in that person's inbox
  is clicked, so nothing is created under a name that is not the owner's. What
  remains is a nuisance: the real owner cannot register (`409`), and if they
  click the link they confirm an account whose password somebody else chose -
  recoverable with *forgot password*, which revokes every session and device
  token, but confusing. The fix is to let a new registration reclaim an
  address that was never verified, plus an expiry for unverified accounts. The
  verification email says what to do in the meantime.
- `telemetry.trip_id` is a hint, not a source of truth: it is set at ingest
  only when a trip was open then. Anything that must be complete reads by time
  window instead - see `GET /trips/{tripId}/route`.
- **No mail is sent yet in practice.** `ResendMailer` exists and is tested, but
  `app.mail.provider` is still `log` everywhere, so links appear in the
  application log. Turning it on needs only a verified sending domain and an
  API key — no code change.
- **Changing the email address** is not implemented. It needs the same mail
  infrastructure (the new address must be proven) and has knock-on effects
  worth thinking through first: the email is the login identifier, it is a
  claim inside the access token, and `invitations` are keyed by it, so a
  pending invitation sent to the old address would not follow the user.
- Delivery is best-effort: a failed send is logged and the request still
  succeeds. There is no outbox and no retry — the resend endpoint is the
  retry. Good enough while a human can always ask for another link.
- The smoke script **requires** `MAIL_LOG` pointed at the application log and
  refuses to start without it. Every registration it makes has to read a link
  out of the log and confirm the address before the account can do anything,
  so there is no longer a useful subset that runs without mail.
- **CI does not run this suite.** `.github/workflows/ci.yaml` runs `make test`,
  which is Flutter only; the API's 496 tests are run locally. Adding them is a
  few lines (`ubuntu-latest` has Docker, so Testcontainers works unchanged) and
  is worth doing before more than one person pushes to `development`.
- `password_changed_at` gives revocation a one-second granularity, because a
  JWT's `iat` is expressed in whole seconds. A token minted in the same second
  as a password change survives it. Harmless in practice, and the alternative
  (a per-token blacklist) costs far more than it is worth here.
