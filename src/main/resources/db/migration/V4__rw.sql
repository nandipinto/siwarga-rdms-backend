CREATE TABLE rw (
    id          UUID PRIMARY KEY,
    rw_code     VARCHAR(10)  NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

INSERT INTO rw (id, rw_code, description, created_at, updated_at)
VALUES (gen_random_uuid(), 'RW01', 'Default RW', NOW(), NOW());

ALTER TABLE rt ADD COLUMN rw_id UUID;

UPDATE rt
SET rw_id = (SELECT id FROM rw WHERE rw_code = 'RW01' LIMIT 1);

ALTER TABLE rt ALTER COLUMN rw_id SET NOT NULL;
ALTER TABLE rt ADD CONSTRAINT fk_rt_rw FOREIGN KEY (rw_id) REFERENCES rw (id);

CREATE INDEX idx_rt_rw ON rt (rw_id);
