-- Trips ("viajes"): one row per use of a car, by one driver.
--
-- Migrations are immutable once they have run anywhere. To change the schema,
-- add V5__*.sql - never edit this file.

create table trips (
    trip_id      uuid        primary key,
    -- A trip without its car is meaningless, so it goes with the car. (There is
    -- no delete-car endpoint yet; if history has to outlive a deleted car, this
    -- becomes `on delete set null` plus a nullable column in a later migration.)
    car_id       uuid        not null references cars (car_id) on delete cascade,
    -- Nullable for the same reason cars.owner_id is: deleting an account must
    -- not erase the car's history, it orphans the trips that account drove.
    driver_id    uuid        references users (user_id) on delete set null,

    started_at   timestamptz not null,
    -- NULL here is what makes a trip "active". The car's current trip and
    -- current driver are read from this open row - the car carries no pointer
    -- back to it, so the two can never disagree.
    ended_at     timestamptz,

    -- Fuel readings at each end of the trip. The expense is
    -- initial_fuel - final_fuel, computed on read: storing the total as well
    -- would be a second source of truth that can drift from the two readings
    -- it is derived from.
    initial_fuel integer,
    final_fuel   integer,
    distance     integer,

    constraint ck_trips_ends_after_it_starts
        check (ended_at is null or ended_at >= started_at),

    -- Floors only, not ranges: as in V2, whether fuel is a percentage or an
    -- absolute quantity is still open and a migration cannot be edited once it
    -- has run.
    constraint ck_trips_initial_fuel check (initial_fuel >= 0),
    constraint ck_trips_final_fuel   check (final_fuel   >= 0),
    constraint ck_trips_distance     check (distance     >= 0),

    -- A trip that is still running cannot already have an end reading.
    constraint ck_trips_open_has_no_result
        check (ended_at is not null or (final_fuel is null and distance is null))
);

-- The core invariant, enforced by the database rather than only by a service
-- check: at most one open trip per car. Partial on `ended_at is null`, so a car
-- may have any number of finished trips, and two concurrent "start trip"
-- requests cannot both win.
create unique index ux_trips_one_active_per_car
    on trips (car_id) where ended_at is null;

-- Trip history per car and per driver are both first-class reads, newest first.
create index ix_trips_car_id    on trips (car_id,    started_at desc);
create index ix_trips_driver_id on trips (driver_id, started_at desc);
