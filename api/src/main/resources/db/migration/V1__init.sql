-- Initial schema: users and refresh tokens.
--
-- Migrations are immutable once they have run anywhere. To change the schema,
-- add V2__*.sql - never edit this file.

create table users (
    user_id      uuid         primary key,
    name         varchar(255) not null,
    lastname     varchar(255) not null,
    email        varchar(255) not null unique,
    password     varchar(255) not null,
    phone_number varchar(255),
    role         varchar(20)  not null,
    enabled      boolean      not null
);

create table refresh_tokens (
    id         uuid        primary key,
    user_id    uuid        not null references users (user_id) on delete cascade,
    family_id  uuid        not null,
    token_hash varchar(64) not null unique,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null
);

-- rotate() looks tokens up by hash (covered by the unique constraint above).
-- These cover the family/user revocation sweeps and the expiry purge.
create index ix_refresh_tokens_family_id  on refresh_tokens (family_id);
create index ix_refresh_tokens_user_id    on refresh_tokens (user_id);
create index ix_refresh_tokens_expires_at on refresh_tokens (expires_at);
