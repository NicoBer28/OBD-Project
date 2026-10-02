-- What a trip needs to survive being recorded offline and uploaded later.
--
-- The phone is the only thing present when a trip happens: it reads the
-- dongle over BLE, buffers to local storage, and uploads when there is
-- signal - which may be hours later, in several batches, with retries. Three
-- consequences, one per change below.
--
-- Migrations are immutable once they have run anywhere. To change the schema,
-- add V15__*.sql - never edit this file.

-- 1. When the server first heard about the trip, as opposed to when the trip
--    happened. The exact counterpart of telemetry's received_at next to
--    recorded_at (V5): without it there is no way to tell a trip that really
--    started at 08:00 from one uploaded by a phone whose clock is wrong.
--    Nullable because rows written before this migration have no honest value
--    to put here - backfilling them with now() would be a lie about when they
--    were created.
alter table trips add column created_at timestamptz;

comment on column trips.created_at is
    'Server clock when the trip row was first written. started_at is the client''s claim about when driving began; this is when we heard about it.';

-- 2. The client's own id for the trip, so uploading it twice is harmless.
--    Without this, a lost response is unrecoverable: the retry gets 409
--    "car already on a trip" and the phone cannot tell "that open trip is the
--    one I just created" from "another driver took the car".
alter table trips add column client_trip_id uuid;

comment on column trips.client_trip_id is
    'Idempotency key minted by the client. A repeat start with the same value returns the existing trip instead of creating a second one.';

-- Scoped to the driver, not global: a client-generated UUID is unguessable in
-- practice, but keying only on the value would mean a caller who supplied
-- somebody else''s id would be handed somebody else''s trip.
create unique index ux_trips_client_trip_id
    on trips (driver_id, client_trip_id)
    where client_trip_id is not null;

-- 3. Distance with a unit and a fraction.
--    `integer` with no documented unit was a defect waiting to happen: the
--    native client sends decimals, so every value was either silently
--    truncated or rejected. numeric(8,2) in kilometres - up to 999999.99 km,
--    centimetre-free but precise enough for a 300 m trip, and exact rather
--    than binary floating point, which has no business near a number a user
--    reads.
--    The existing ck_trips_distance (>= 0) and ck_trips_open_has_no_result
--    survive the type change; the values already stored are whole kilometres
--    and convert exactly.
alter table trips rename column distance to distance_km;
alter table trips alter column distance_km type numeric(8, 2);

comment on column trips.distance_km is
    'Kilometres, two decimal places. The unit is in the column name on purpose: it was undocumented before and clients disagreed about it.';
