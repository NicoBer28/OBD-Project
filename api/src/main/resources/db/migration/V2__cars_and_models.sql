-- Cars, and the model catalog they point at.
--
-- Migrations are immutable once they have run anywhere. To change the schema,
-- add V3__*.sql - never edit this file.

create table models (
    -- Prefixed like users.user_id rather than a bare "id", matching the
    -- convention V1 set for domain tables.
    model_id uuid        primary key,
    brand    varchar(60) not null,
    model    varchar(60) not null,
    protocol varchar(40),
    -- The catalog is curated, so the same brand/model must not appear twice.
    constraint ux_models_brand_model unique (brand, model)
);

create table cars (
    car_id        uuid        primary key,
    -- Matches the ER diagram: deleting a user does not delete their cars, it
    -- orphans them, so trip history and group access survive the account.
    owner_id      uuid        references users (user_id) on delete set null,
    model_id      uuid        not null references models (model_id),
    name          varchar(60) not null,
    license_plate varchar(16),

    -- Cached snapshot of the last telemetry seen for this car. All nullable:
    -- a car that has never reported has no snapshot yet. The authoritative
    -- time series lives in its own table (added with the telemetry endpoints);
    -- these columns exist so reading a car does not require aggregating it.
    mileage       integer,
    fuel_level    integer,
    battery_level integer,
    max_speed     integer,
    avg_speed     integer,
    latitude      double precision,
    longitude     double precision,

    created_at    timestamptz not null,

    -- Deliberately floors only, not ranges: whether fuel_level and
    -- battery_level are percentages or absolute units is still open, and a
    -- migration cannot be edited once it has run. Tighten in a later migration
    -- when the firmware settles what it reports.
    constraint ck_cars_mileage       check (mileage       >= 0),
    constraint ck_cars_fuel_level    check (fuel_level    >= 0),
    constraint ck_cars_battery_level check (battery_level >= 0),
    constraint ck_cars_max_speed     check (max_speed     >= 0),
    constraint ck_cars_avg_speed     check (avg_speed     >= 0),
    constraint ck_cars_latitude      check (latitude  between  -90 and  90),
    constraint ck_cars_longitude     check (longitude between -180 and 180)
);

create index ix_cars_owner_id on cars (owner_id);
create index ix_cars_model_id on cars (model_id);

-- Stops one owner adding the same physical car twice, without making plates
-- unique across the whole table: a global constraint would let one account
-- block another from registering a plate, and would leak whether a given plate
-- is already known to the system. Partial, so any number of cars may have no
-- plate recorded.
create unique index ux_cars_owner_license_plate
    on cars (owner_id, license_plate)
    where license_plate is not null;

-- Seed catalog. POST /api/v1/cars requires an existing model id, so without
-- these rows the endpoint is unusable until someone populates the table by
-- hand. Fixed uuids (not gen_random_uuid()) so every environment - and every
-- test - refers to the same model by the same id.
insert into models (model_id, brand, model, protocol) values
    ('00000000-0000-4000-8000-000000000001', 'Volkswagen', 'Gol',     'ISO 15765-4 (CAN)'),
    ('00000000-0000-4000-8000-000000000002', 'Volkswagen', 'Golf',    'ISO 15765-4 (CAN)'),
    ('00000000-0000-4000-8000-000000000003', 'Chevrolet',  'Onix',    'ISO 15765-4 (CAN)'),
    ('00000000-0000-4000-8000-000000000004', 'Renault',    'Sandero', 'ISO 15765-4 (CAN)'),
    ('00000000-0000-4000-8000-000000000005', 'Peugeot',    '208',     'ISO 15765-4 (CAN)'),
    ('00000000-0000-4000-8000-000000000006', 'Toyota',     'Corolla', 'ISO 15765-4 (CAN)'),
    ('00000000-0000-4000-8000-000000000007', 'Ford',       'Focus',   'ISO 15765-4 (CAN)'),
    ('00000000-0000-4000-8000-000000000008', 'Fiat',       'Punto',   'ISO 9141-2');
