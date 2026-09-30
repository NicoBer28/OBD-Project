-- Invite codes: a group's QR code. Whoever scans it may join.
--
-- A sibling of `invitations`, not a column on it. An invitation is targeted -
-- it names one email, and accepting is only valid for the account holding
-- that address, which is the single condition that makes accepting safe. An
-- invite code is a bearer capability: the server does not know who will scan
-- it. Making `invitations.email` nullable to fit both would have turned that
-- condition into an optional one.
--
-- The code itself is never stored. Only its SHA-256, exactly as
-- refresh_tokens does, so a database leak yields no joinable codes - and so
-- the code cannot be re-displayed later: showing the QR again means minting a
-- new one, which is also what kills the old poster.
--
-- Migrations are immutable once they have run anywhere. To change the schema,
-- add V12__*.sql - never edit this file.

create table group_invite_codes (
    invite_code_id uuid        primary key,
    group_id       uuid        not null references groups (group_id) on delete cascade,
    -- SHA-256 of the code, hex. 32 random bytes go into the QR; guessing is
    -- not a threat model, so no rate limiting is needed anywhere.
    code_hash      varchar(64) not null,
    -- Who minted it. Set null rather than cascade, as invitations.invited_by:
    -- the code outlives the admin deleting their account.
    created_by     uuid                 references users (user_id) on delete set null,

    created_at     timestamptz not null,
    expires_at     timestamptz not null,
    -- Null while live. Revoking never deletes the row: it is the record that
    -- a code existed and how often it was used.
    revoked_at     timestamptz,

    -- Null = unlimited until it expires or is revoked.
    max_uses       integer,
    -- Incremented by joining, and by nothing else. Previewing a code must not
    -- consume it - someone who scans and backs out has taken nothing.
    uses           integer     not null default 0,

    constraint ux_group_invite_codes_hash unique (code_hash),
    constraint ck_gic_expires_after_created check (expires_at > created_at),
    constraint ck_gic_revoked_after_created check (revoked_at is null or revoked_at >= created_at),
    constraint ck_gic_uses_non_negative    check (uses >= 0),
    constraint ck_gic_max_uses_positive    check (max_uses is null or max_uses > 0)
);

-- One live code per group. Minting revokes the previous one in the same
-- transaction, so an old QR stops working the moment a new one is shown.
-- Partial on revoked_at alone, not on expiry: an expired row is still "the
-- group's last code" until something replaces it, and `now()` cannot appear
-- in an index predicate anyway.
create unique index ux_gic_one_live_per_group
    on group_invite_codes (group_id)
    where revoked_at is null;

-- Every future migration that creates a table must enable RLS on it too - see
-- V10__enable_rls.sql for why.
alter table group_invite_codes enable row level security;
