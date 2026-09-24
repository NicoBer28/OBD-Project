-- Sharing: the group a car is currently shared with, if any.
--
-- A plain nullable column rather than a join table, because the rule from the
-- start was "one group at a time" - a column enforces that for free, a join
-- table would need a unique index to. Null is the common case: a car starts
-- unshared, and un-sharing sets it back.
--
-- on delete set null: deleting a group must un-share its cars, not delete them.
-- The car and its history belong to the owner, not to the group.
--
-- Migrations are immutable once they have run anywhere. To change the schema,
-- add V8__*.sql - never edit this file.

alter table cars
    add column group_id uuid references groups (group_id) on delete set null;

-- "Cars available to this group" is a first-class read (ROADMAP Phase 4).
create index ix_cars_group_id on cars (group_id) where group_id is not null;
