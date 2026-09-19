-- Telemetry: the time series of everything a car has reported, and by the same
-- token the log of where it has been.
--
-- Shaped around how a reading actually reaches us. The ESP32 speaks BLE to the
-- phone, never HTTP to this API, so the phone is the relay: it buffers readings
-- while it has no connectivity and uploads them in batches, out of order,
-- retrying whatever it is unsure about. Every decision below follows from that.
--
-- Migrations are immutable once they have run anywhere. To change the schema,
-- add V6__*.sql - never edit this file.

create table telemetry (
    -- bigint, not uuid, and the only table here that differs. This one grows
    -- without bound (a reading every few seconds, per car), and a random uuid
    -- is twice as wide and lands in a random leaf of the index on every insert.
    -- A reading has no identity worth exposing anyway: it is addressed as
    -- "this car, at this instant", never by id.
    telemetry_id bigint generated always as identity primary key,

    car_id       uuid        not null references cars  (car_id)  on delete cascade,
    -- Stamped at ingestion from the car's open trip, so a trip's route is a
    -- plain indexed read rather than a time-range join whose boundaries are
    -- easy to get subtly wrong. Null when the car reported outside any trip -
    -- parked, or the driver forgot to start one. Set null rather than cascade:
    -- deleting a trip must not erase where the car actually was.
    trip_id      uuid                 references trips (trip_id) on delete set null,

    -- Two clocks on purpose. recorded_at is when the reading was taken, which
    -- is what every query means by "when"; received_at is when it reached us.
    -- For a phone that was offline in a tunnel for an hour these are an hour
    -- apart, and only received_at explains why the data showed up late.
    recorded_at  timestamptz not null,
    received_at  timestamptz not null default now(),

    -- Instantaneous values. Note "speed", singular: cars.max_speed and
    -- cars.avg_speed are aggregates over many readings, not measurements, and
    -- are derivable from this table.
    latitude      double precision,
    longitude     double precision,
    speed         integer,
    fuel_level    integer,
    battery_level integer,
    mileage       integer,

    -- Whatever the device actually sent, kept verbatim. The firmware currently
    -- emits raw mode-01 frames and the decoder is far from finished, so the
    -- columns above are a best-effort reading of a payload we will get better
    -- at parsing. Keeping the source means a new column added in V6 can be
    -- backfilled from history instead of starting empty - a reading thrown away
    -- at ingestion is gone for good.
    raw_frame     jsonb,

    -- Same floors-only reasoning as V2: the units are still the firmware's to
    -- settle, and a migration cannot be edited once it has run.
    constraint ck_telemetry_speed         check (speed         >= 0),
    constraint ck_telemetry_fuel_level    check (fuel_level    >= 0),
    constraint ck_telemetry_battery_level check (battery_level >= 0),
    constraint ck_telemetry_mileage       check (mileage       >= 0),
    constraint ck_telemetry_latitude      check (latitude  between  -90 and  90),
    constraint ck_telemetry_longitude     check (longitude between -180 and 180),

    -- The phone retries anything it is unsure about, so the same reading will
    -- be uploaded twice. This makes ingestion idempotent: a repeat is rejected
    -- by the database instead of quietly doubling a car's history. It also
    -- doubles as the index for "this car's readings, in order" and "its latest
    -- reading", so no separate (car_id, recorded_at) index is needed - Postgres
    -- reads a btree backwards perfectly well.
    --
    -- The cost: two genuinely distinct readings at the identical instant cannot
    -- both be stored, so the relay must timestamp at sub-second precision.
    constraint ux_telemetry_car_recorded_at unique (car_id, recorded_at)
);

-- Route replay for one trip. Partial, because readings taken outside a trip are
-- the majority for a parked car and would only bloat the index.
create index ix_telemetry_trip_id on telemetry (trip_id, recorded_at)
    where trip_id is not null;

-- When the cached snapshot on cars was taken. Without it, an out-of-order
-- upload - the phone flushing an hour-old buffer after a live reading - would
-- overwrite fresh values with stale ones, and nothing would ever reveal that it
-- had. Ingestion compares against this before touching the snapshot. Nullable:
-- a car that has never reported has no snapshot, and no time to record for it.
alter table cars add column snapshot_at timestamptz;

comment on column cars.snapshot_at is
    'recorded_at of the telemetry row the cached snapshot columns came from.';
