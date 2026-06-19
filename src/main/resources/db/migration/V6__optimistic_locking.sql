-- Optimistic locking: a JPA @Version column on every mutable entity so concurrent
-- read-modify-write updates to the same row are detected instead of silently last-writer-wins.
-- DEFAULT 0 backfills existing rows; Hibernate manages the value on insert/update thereafter.
-- payment_allocation and document_sequence are excluded: allocations are deleted and
-- recreated wholesale per recompute (never updated in place), and the sequence row is
-- maintained by an atomic upsert.
ALTER TABLE rw ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rt ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE house ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rental_guarantee_payment ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rental_guarantee_refund ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
