-- APDDMP-22: optional PostgreSQL schema setup, run against the development DB.
-- Hibernate ddl-auto=update already creates this table during normal local startup.
-- Do not run this script against production without the project's migration procedure.
CREATE TABLE IF NOT EXISTS addresses (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    label varchar(500) NOT NULL,
    recipient_name varchar(500) NOT NULL,
    phone varchar(16) NOT NULL,
    address_line varchar(500) NOT NULL,
    latitude double precision NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude double precision NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_addresses_owner_active_created
    ON addresses (user_id, deleted, created_at, id);
