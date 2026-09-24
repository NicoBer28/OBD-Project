-- Lock the schema down against the hosted Postgres's own HTTP API.
--
-- Migrations are immutable once they have run anywhere. To change the schema,
-- add a new V<n>__*.sql file instead of editing this one.
--
-- Supabase (and similar hosts) expose every table in `public` through an
-- auto-generated REST API to anyone holding the project's public "anon" key,
-- unless row level security is enabled. This API only ever talks to the
-- database over JDBC as the owning role, and table owners bypass RLS, so
-- enabling it with no policies costs the app nothing and leaves the REST
-- API with nothing to read - including users.password_hash.
--
-- Every future migration that creates a table must enable RLS on it too.

alter table users                 enable row level security;
alter table refresh_tokens        enable row level security;
alter table models                enable row level security;
alter table cars                  enable row level security;
alter table groups                enable row level security;
alter table group_members         enable row level security;
alter table trips                 enable row level security;
alter table telemetry             enable row level security;
alter table invitations           enable row level security;
alter table devices               enable row level security;

-- Not flyway_schema_history: Flyway's main connection holds a share lock on
-- it while a migration runs, so an ALTER from inside a migration blocks until
-- the statement timeout. The revoke below already hides it from the REST API.

-- Belt and braces on Supabase: also drop the API roles' grants, so the tables
-- are unreachable even if RLS were ever switched off from the dashboard.
-- Guarded, because the roles do not exist on a plain Postgres (local dev,
-- Testcontainers).
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        revoke all on all tables    in schema public from anon, authenticated;
        revoke all on all sequences in schema public from anon, authenticated;
        alter default privileges in schema public
            revoke all on tables    from anon, authenticated;
        alter default privileges in schema public
            revoke all on sequences from anon, authenticated;
    end if;
end
$$;
