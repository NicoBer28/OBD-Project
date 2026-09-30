-- When the account last changed its password, so that changing it can sign
-- every other device out immediately rather than eventually.
--
-- Revoking refresh tokens is not enough on its own: the access tokens already
-- issued stay valid until they expire, so another device would keep working
-- for up to app.jwt.access-ttl-minutes after the change. JwtAuthFilter already
-- loads the user from this table on every request, so comparing the token's
-- `iat` against this column costs no extra query and closes that window.
--
-- Null means "never changed since registration", which is why the comparison
-- has to tolerate null rather than defaulting this to now() - backfilling
-- existing rows with the migration's clock would be a lie about when those
-- passwords were set, and every token in flight would be rejected on deploy.
--
-- Migrations are immutable once they have run anywhere. To change the schema,
-- add V13__*.sql - never edit this file.

alter table users add column password_changed_at timestamptz;
