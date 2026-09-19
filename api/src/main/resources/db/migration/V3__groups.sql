-- Groups (families) and their membership.
--
-- Migrations are immutable once they have run anywhere. To change the schema,
-- add V4__*.sql - never edit this file.

create table groups (
    group_id   uuid        primary key,
    name       varchar(60) not null,
    created_at timestamptz not null
);

-- The many-to-many between users and groups, carrying the member's role.
-- Composite primary key, so a user cannot appear twice in the same group and
-- the "is this user in this group" lookup is the key itself.
create table group_members (
    group_id uuid        not null references groups (group_id) on delete cascade,
    user_id  uuid        not null references users  (user_id)  on delete cascade,
    role     varchar(20) not null,
    primary key (group_id, user_id)
);

-- The PK already covers group-first lookups ("who is in this group"). This
-- covers the other direction, "which groups is this user in", which is how a
-- member's car list will be resolved.
create index ix_group_members_user_id on group_members (user_id);

-- Note: there is deliberately no member-count column. The ER diagram marks
-- cant_integrantes as derivable, so it is counted from group_members rather
-- than stored and kept in sync.
