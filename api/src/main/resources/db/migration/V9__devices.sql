-- Devices: the OBD dongle plugged into a car, so a phone that connects to one
-- can learn which car it is talking to without asking the user.
--
-- Every dongle advertises the same BLE name ("OBD-C"), so the phone needs an
-- identity from the device itself and a shared mapping from that identity to a
-- car. Keyed on a serial the firmware exposes over GATT, NOT on the BLE MAC:
-- iOS hides the real address and hands each app a per-phone random UUID for
-- the same peripheral, so a MAC-keyed mapping would not be shared across
-- phones after all.
--
-- One dongle per car and one car per dongle - both unique. Re-pairing a car to
-- a new dongle replaces the row; moving a dongle to another car is an explicit
-- unpair then pair, so nothing is ever silently re-attributed.
--
-- Migrations are immutable once they have run anywhere. To change the schema,
-- add V10__*.sql - never edit this file.

create table devices (
    device_id    uuid        primary key,
    -- A dongle without its car is nothing to us; it goes with the car.
    car_id       uuid        not null references cars (car_id) on delete cascade,
    -- Stored trimmed and upper-cased, so "a4:cf:12" and "A4:CF:12" are one
    -- device. Enforced here so a caller that forgets fails loudly.
    serial       varchar(64) not null,
    paired_at    timestamptz not null,
    -- Server clock, set whenever readings arrive attributed to this device.
    -- Null until it has reported once. "Offline since Tuesday" comes from here.
    last_seen_at timestamptz,

    constraint ux_devices_serial unique (serial),
    constraint ux_devices_car_id unique (car_id),
    constraint ck_devices_serial_normalised check (serial = upper(trim(serial)) and serial <> '')
);
