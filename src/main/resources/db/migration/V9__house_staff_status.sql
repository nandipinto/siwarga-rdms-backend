CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE house_staff_status (
    id UUID PRIMARY KEY,
    house_id UUID NOT NULL REFERENCES house(id),
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    staff_house BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_house_staff_status_range
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

ALTER TABLE house_staff_status
ADD CONSTRAINT excl_house_staff_status_no_overlap
EXCLUDE USING gist (
    house_id WITH =,
    daterange(effective_from, effective_to, '[)') WITH &&
);

CREATE INDEX idx_house_staff_status_house_from
    ON house_staff_status (house_id, effective_from);

CREATE INDEX idx_house_staff_status_house_range
    ON house_staff_status (house_id, effective_from, effective_to);

INSERT INTO house_staff_status (
    id,
    house_id,
    effective_from,
    effective_to,
    staff_house,
    created_at,
    updated_at,
    version
)
SELECT
    gen_random_uuid(),
    h.id,
    DATE '2025-01-01',
    NULL,
    TRUE,
    now(),
    now(),
    0
FROM house h
WHERE (h.block_code, h.house_number) IN (
    ('A', '16'),
    ('B', '05'),
    ('B', '07'),
    ('B', '21'),
    ('D', '07'),
    ('D', '16'),
    ('E', '08')
);
