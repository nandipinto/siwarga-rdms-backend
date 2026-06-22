-- Supervisor ↔ RT confinement (spec §3.5, §4.3).
-- A supervisor is associated with exactly one RT; an RT has at most one (active) supervisor.
-- NULL for administrators; UNIQUE permits many NULLs (admins) but a single non-null per RT (strict 1:1).
ALTER TABLE app_user ADD COLUMN rt_id UUID;

ALTER TABLE app_user ADD CONSTRAINT fk_app_user_rt FOREIGN KEY (rt_id) REFERENCES rt (id);
ALTER TABLE app_user ADD CONSTRAINT uq_app_user_rt UNIQUE (rt_id);

CREATE INDEX idx_app_user_rt ON app_user (rt_id);
