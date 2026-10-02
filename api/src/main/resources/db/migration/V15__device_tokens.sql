-- A credential for the phone's background service, as opposed to for a person.
--
-- Why a second kind of credential exists at all: the app and the native
-- service are separate processes talking to the same API, and the JWT session
-- cannot be shared between them. The refresh token is single-use with
-- family-wide theft detection (RefreshTokenService.rotate), so the moment both
-- processes refresh, one presents a rotated token, the server reads it as theft
-- and revokes the whole family - the user is logged out having done nothing.
-- A second login per process would avoid that, but it would mean storing the
-- password on the device, would grant every permission the user has, and would
-- still die on logout, which revokes every family for the account.
--
-- So: a token that never rotates, is scoped to exactly one car, and can be
-- revoked on its own without touching the user's session.
--
-- Migrations are immutable once they have run anywhere. To change the schema,
-- add V16__*.sql - never edit this file.

create table device_tokens (
    device_token_id uuid         primary key,
    -- Who the token acts as. Everything it does is attributed to this account,
    -- so a trip it starts is that person's trip.
    user_id         uuid         not null references users (user_id) on delete cascade,
    -- The one car it may touch. Checked on every request against the car in
    -- the body, the path, or behind the dongle serial.
    car_id          uuid         not null references cars (car_id) on delete cascade,

    -- SHA-256 of the token, hex. The token itself is never stored, as with
    -- refresh_tokens, group_invite_codes and the two V13 tables. Plain SHA-256
    -- rather than BCrypt because the input is 32 random bytes: nothing to brute
    -- force, and no reason to pay for a slow hash on every telemetry upload.
    token_hash      varchar(64)  not null,

    -- Shown to the user so they can tell which phone to revoke.
    label           varchar(60)  not null,

    created_at      timestamptz  not null,
    -- Server clock, updated at most once a minute by the auth filter. Two jobs:
    -- it answers "this phone has not reported since Tuesday", and it anchors
    -- the idle expiry below.
    last_used_at    timestamptz,
    -- Null while live. Stamped rather than deleted, so a revoked token can be
    -- told apart from one that never existed, and so the history of which
    -- phones had access survives.
    revoked_at      timestamptz,

    constraint ux_device_tokens_hash unique (token_hash),
    constraint ck_device_tokens_label_not_blank check (btrim(label) <> ''),
    constraint ck_device_tokens_revoked_after_created
        check (revoked_at is null or revoked_at >= created_at),
    constraint ck_device_tokens_used_after_created
        check (last_used_at is null or last_used_at >= created_at)
);

-- The authentication path: hash in, row out. Covered by ux_device_tokens_hash.

-- The user's own list for one car, which is what the app shows: "these phones
-- can upload for this car". Partial, because revoked rows are history and are
-- never listed.
create index ix_device_tokens_user_car
    on device_tokens (user_id, car_id)
    where revoked_at is null;

-- Deliberately NOT indexed or denormalised: whether the user still has access
-- to the car. That is derived on every request by the same CarAccess predicate
-- everything else uses, so losing access through un-sharing, leaving a group,
-- the group being deleted, or any permission rule invented later kills the
-- token with no bookkeeping to keep in step - and nothing that can drift.

-- Every future migration that creates a table must enable RLS on it too - see
-- V10__enable_rls.sql for why.
alter table device_tokens enable row level security;
