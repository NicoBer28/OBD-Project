-- Proving that an account owns the email address it registered with, and
-- letting someone who forgot their password back in.
--
-- The two are one migration because they are the same mechanism pointed at
-- two problems: a random secret mailed to an address, which only whoever
-- reads that mailbox can present back.
--
-- Why this matters beyond the obvious: invitations are keyed by email
-- (V8), and registration has never proven ownership of the address. Until
-- now anyone could register as someone else's address, read their pending
-- invitations and join a family group meant for them. email_verified_at is
-- what closes that.
--
-- Migrations are immutable once they have run anywhere. To change the
-- schema, add V14__*.sql - never edit this file.

-- Null means unverified. Deliberately NOT `enabled`, which already means
-- "not disabled by an admin": merging the two would lose the difference
-- between a brand-new account and a banned one.
alter table users add column email_verified_at timestamptz;

-- Existing accounts are all test accounts and no production database exists
-- yet, so they are grandfathered in rather than locked out. Against a fresh
-- production database this table is empty and the statement does nothing.
update users set email_verified_at = now() where email_verified_at is null;

-- Two tables rather than one with a `purpose` column, though the columns are
-- identical. A verification token is mailed automatically on every
-- registration and lives for a day; a reset token changes a password. If
-- both lived in one table, the only thing stopping the first from being
-- redeemed as the second would be remembering `and purpose = :purpose` in
-- every query - one forgotten clause away from account takeover. Separate
-- tables make that confusion impossible to express, the same reasoning that
-- kept group_invite_codes out of `invitations`.

create table email_verification_tokens (
    token_id    uuid        primary key,
    user_id     uuid        not null references users (user_id) on delete cascade,
    -- SHA-256 of the token, hex. The token itself is never stored, so a
    -- database leak yields nothing redeemable - as refresh_tokens does.
    -- Plain SHA-256 rather than BCrypt on purpose: the input is 32 random
    -- bytes, so there is nothing to brute force and no reason to pay for a
    -- slow hash on every click.
    token_hash  varchar(64) not null,
    created_at  timestamptz not null,
    expires_at  timestamptz not null,
    -- Null while redeemable. Stamped rather than deleted, so a second click
    -- can be answered with "already used" instead of "invalid link".
    consumed_at timestamptz,

    constraint ux_evt_hash unique (token_hash),
    constraint ck_evt_expires_after_created check (expires_at > created_at),
    constraint ck_evt_consumed_after_created check (consumed_at is null or consumed_at >= created_at)
);

-- One live token per user: issuing a new one consumes the previous, so an
-- older link in an older email stops working. Also the anchor for the resend
-- throttle - "when was the live one created".
create unique index ux_evt_one_live_per_user
    on email_verification_tokens (user_id)
    where consumed_at is null;

create table password_reset_tokens (
    token_id    uuid        primary key,
    user_id     uuid        not null references users (user_id) on delete cascade,
    token_hash  varchar(64) not null,
    created_at  timestamptz not null,
    expires_at  timestamptz not null,
    consumed_at timestamptz,

    constraint ux_prt_hash unique (token_hash),
    constraint ck_prt_expires_after_created check (expires_at > created_at),
    constraint ck_prt_consumed_after_created check (consumed_at is null or consumed_at >= created_at)
);

create unique index ux_prt_one_live_per_user
    on password_reset_tokens (user_id)
    where consumed_at is null;

-- Every future migration that creates a table must enable RLS on it too -
-- see V10__enable_rls.sql for why.
alter table email_verification_tokens enable row level security;
alter table password_reset_tokens     enable row level security;
