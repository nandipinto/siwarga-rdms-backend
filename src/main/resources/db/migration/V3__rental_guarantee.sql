-- Rental guarantee: occupancy status, tenant fields, guarantee collection & refunds (spec §3.2, §3.7, §3.8).

ALTER TABLE house
    ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'OWNED',
    ADD COLUMN tenant_name VARCHAR(200),
    ADD COLUMN tenant_email VARCHAR(255),
    ADD COLUMN tenant_phone VARCHAR(30),
    ADD COLUMN lease_duration_months SMALLINT,
    ADD COLUMN rental_guarantee_amount_idr BIGINT,
    ADD COLUMN rental_guarantee_obligation_id UUID;

ALTER TABLE house
    ADD CONSTRAINT chk_house_status CHECK (status IN ('OWNED', 'RENTED')),
    ADD CONSTRAINT chk_house_owned_fields CHECK (
        (status = 'OWNED'
            AND tenant_name IS NULL
            AND tenant_email IS NULL
            AND tenant_phone IS NULL
            AND lease_duration_months IS NULL
            AND rental_guarantee_amount_idr IS NULL
            AND rental_guarantee_obligation_id IS NULL)
        OR (status = 'RENTED'
            AND tenant_name IS NOT NULL
            AND tenant_email IS NOT NULL
            AND tenant_phone IS NOT NULL
            AND lease_duration_months IS NOT NULL
            AND lease_duration_months > 0)
    ),
    ADD CONSTRAINT chk_house_guarantee_obligation CHECK (
        (rental_guarantee_amount_idr IS NULL AND rental_guarantee_obligation_id IS NULL)
        OR (rental_guarantee_amount_idr IS NOT NULL AND rental_guarantee_obligation_id IS NOT NULL)
    );

CREATE TABLE rental_guarantee_payment (
    id              UUID PRIMARY KEY,
    house_id        UUID         NOT NULL REFERENCES house (id),
    obligation_id   UUID         NOT NULL UNIQUE,
    receipt_number  VARCHAR(30)  NOT NULL UNIQUE,
    payment_date    DATE         NOT NULL,
    amount_idr      BIGINT       NOT NULL CHECK (amount_idr > 0),
    paid_by_name    VARCHAR(200) NOT NULL,
    paid_by_email   VARCHAR(255) NOT NULL,
    paid_by_phone   VARCHAR(30)  NOT NULL,
    note            TEXT,
    created_by      UUID         NOT NULL REFERENCES app_user (id),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_rg_payment_house ON rental_guarantee_payment (house_id);
CREATE INDEX idx_rg_payment_date ON rental_guarantee_payment (payment_date);

CREATE TABLE rental_guarantee_refund (
    id                 UUID PRIMARY KEY,
    payment_id         UUID         NOT NULL UNIQUE REFERENCES rental_guarantee_payment (id),
    house_id           UUID         NOT NULL REFERENCES house (id),
    obligation_id      UUID         NOT NULL,
    refund_number      VARCHAR(30)  UNIQUE,
    status             VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'COMPLETED')),
    amount_idr         BIGINT       NOT NULL CHECK (amount_idr > 0),
    refunded_to_name   VARCHAR(200) NOT NULL,
    refunded_to_email  VARCHAR(255) NOT NULL,
    refunded_to_phone  VARCHAR(30)  NOT NULL,
    refund_date        DATE,
    note               TEXT,
    created_by         UUID         NOT NULL REFERENCES app_user (id),
    completed_by       UUID         REFERENCES app_user (id),
    created_at         TIMESTAMPTZ  NOT NULL,
    completed_at       TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_rg_refund_completed CHECK (
        (status = 'PENDING' AND refund_number IS NULL AND refund_date IS NULL AND completed_by IS NULL AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND refund_number IS NOT NULL AND refund_date IS NOT NULL AND completed_by IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE INDEX idx_rg_refund_house ON rental_guarantee_refund (house_id);
CREATE INDEX idx_rg_refund_status ON rental_guarantee_refund (status);

CREATE TABLE document_sequence (
    doc_type VARCHAR(10) NOT NULL,
    year     SMALLINT    NOT NULL,
    last_seq BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (doc_type, year)
);
