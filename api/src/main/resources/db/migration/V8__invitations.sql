-- Invitations: an admin asks an email address to join a group. Nothing is
-- written to group_members until the invitee accepts - joining is consent,
-- not something done to you.
--
-- Keyed by email rather than user id on purpose: the common case in a family
-- app is inviting someone who has not installed it yet. The row waits; when
-- that email registers and logs in, its pending invitations are simply there.
-- It also means creating an invitation never has to look a user up, which is
-- what keeps the endpoint from becoming an "is this email registered?" oracle.
--
-- Migrations are immutable once they have run anywhere. To change the schema,
-- add V9__*.sql - never edit this file.

create table invitations (
    invitation_id uuid         primary key,
    group_id      uuid         not null references groups (group_id) on delete cascade,
    -- Stored lowercased, as users.email is, so the two compare with plain
    -- equality and the indexes below serve those queries. Enforced here rather
    -- than trusted to every caller.
    email         varchar(255) not null,
    -- Who sent it. Set null rather than cascade: an invitation must survive
    -- the inviter deleting their account, since the invitee may still accept.
    invited_by    uuid                  references users (user_id) on delete set null,

    created_at    timestamptz  not null,
    expires_at    timestamptz  not null,
    -- Null while pending. The invitation's status is derived from these three
    -- timestamps - accepted, expired, or pending - and is never a column.
    accepted_at   timestamptz,

    constraint ck_invitations_email_lowercase
        check (email = lower(email)),
    constraint ck_invitations_expires_after_created
        check (expires_at > created_at),
    constraint ck_invitations_accepted_after_created
        check (accepted_at is null or accepted_at >= created_at)
);

-- One pending invitation per (group, email). Partial on accepted_at is null,
-- so the same person may be re-invited after leaving, and the history of
-- accepted ones is kept.
create unique index ux_invitations_pending
    on invitations (group_id, email)
    where accepted_at is null;

-- The invitee's read: "pending invitations for my email".
create index ix_invitations_email_pending
    on invitations (email)
    where accepted_at is null;

-- The admin's read: "who has this group invited".
create index ix_invitations_group_id
    on invitations (group_id, created_at desc);
