-- V1: orders and payments.
--
-- Money is stored in minor units (a long of kobo/cents), never as a
-- float, so no rounding can ever appear in a balance. Amounts must be
-- positive; a zero or negative amount is rejected by the CHECK as well
-- as by request validation.

CREATE TABLE IF NOT EXISTS orders (
    id UUID PRIMARY KEY,
    client_reference VARCHAR(64) NOT NULL UNIQUE,
    customer_name VARCHAR(200) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    total_amount_minor BIGINT NOT NULL CHECK (total_amount_minor > 0),
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders (status);
CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders (created_at);

-- One successful payment per order: the order is paid in full, so a
-- second payment row for the same order is a conflict, not a second
-- charge. The UNIQUE on order_id enforces that at the database level
-- as well as in the service.
CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE REFERENCES orders (id),
    reference VARCHAR(64) NOT NULL UNIQUE,
    method VARCHAR(20) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_payments_order_id ON payments (order_id);
