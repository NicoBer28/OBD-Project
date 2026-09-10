-- Removes cars.max_speed and cars.avg_speed.
--
-- They were the one part of the original diagram that does not survive having a
-- real time series. The other snapshot columns are a *copy of the newest
-- reading* - cars.snapshot_at says exactly which one, so they can be stale but
-- never ambiguous. These two are not a copy of anything: telemetry stores
-- `speed`, instantaneous, and there is no row a max or an average is taken from.
--
-- Keeping them would mean recomputing both on every ingested reading, or
-- letting them drift with nothing to reveal that they had - the same trap as
-- storing fuel consumption alongside the two readings it is derived from.
-- They are aggregates: max(speed) and avg(speed) over telemetry, filtered by
-- car or by trip_id, which is where the question is actually asked. They belong
-- in the stats endpoint, not in a column.
--
-- Dropped now because nothing has ever written to them - there is no ingestion
-- endpoint yet - so no data is lost. Postgres drops ck_cars_max_speed and
-- ck_cars_avg_speed along with their columns.
--
-- Migrations are immutable once they have run anywhere. To change the schema,
-- add V7__*.sql - never edit this file.

alter table cars drop column max_speed;
alter table cars drop column avg_speed;
