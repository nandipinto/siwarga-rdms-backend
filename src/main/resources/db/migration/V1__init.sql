-- RDMS initial schema (spec §3). Money is BIGINT IDR; ids are UUID.

CREATE TABLE rt (
    id          UUID PRIMARY KEY,
    rt_code     VARCHAR(10)  NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE house (
    id           UUID PRIMARY KEY,
    rt_id        UUID         NOT NULL REFERENCES rt (id),
    block_code   VARCHAR(10)  NOT NULL,
    house_number VARCHAR(10)  NOT NULL,
    owner_name   VARCHAR(200) NOT NULL,
    email        VARCHAR(255) NOT NULL,
    phone        VARCHAR(30)  NOT NULL,
    active_date  DATE         NOT NULL DEFAULT DATE '2024-01-01',
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_house_rt_block_number UNIQUE (rt_id, block_code, house_number)
);

CREATE INDEX idx_house_rt ON house (rt_id);

CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    password_hash TEXT         NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE payment (
    id           UUID PRIMARY KEY,
    house_id     UUID        NOT NULL REFERENCES house (id),
    payment_date DATE        NOT NULL,
    gross_amount BIGINT      NOT NULL CHECK (gross_amount > 0),
    note         TEXT,
    created_by   UUID        NOT NULL REFERENCES app_user (id),
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_payment_house ON payment (house_id);
CREATE INDEX idx_payment_date ON payment (payment_date);

CREATE TABLE payment_allocation (
    id               UUID PRIMARY KEY,
    payment_id       UUID        NOT NULL REFERENCES payment (id) ON DELETE CASCADE,
    allocation_type  VARCHAR(20) NOT NULL,
    period_year      SMALLINT,
    period_month     SMALLINT,
    amount           BIGINT      NOT NULL,
    discount_applied BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_alloc_payment ON payment_allocation (payment_id);
