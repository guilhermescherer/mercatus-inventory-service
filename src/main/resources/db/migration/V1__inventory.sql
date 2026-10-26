CREATE TABLE stock_item (
    sku_code    VARCHAR(64) PRIMARY KEY,
    on_hand     BIGINT NOT NULL,
    reserved    BIGINT NOT NULL DEFAULT 0,
    committed   BIGINT NOT NULL DEFAULT 0,
    version     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT stock_item_on_hand_non_negative CHECK (on_hand >= 0),
    CONSTRAINT stock_item_reserved_non_negative CHECK (reserved >= 0),
    CONSTRAINT stock_item_committed_non_negative CHECK (committed >= 0),
    CONSTRAINT stock_item_available_non_negative CHECK (on_hand - reserved - committed >= 0)
);

CREATE TABLE reservation (
    id          UUID PRIMARY KEY,
    order_id    UUID NOT NULL UNIQUE,
    status      VARCHAR(16) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ
);

CREATE TABLE reservation_line (
    reservation_id  UUID NOT NULL REFERENCES reservation (id),
    sku_code        VARCHAR(64) NOT NULL REFERENCES stock_item (sku_code),
    quantity        BIGINT NOT NULL,
    PRIMARY KEY (reservation_id, sku_code),
    CONSTRAINT reservation_line_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX idx_reservation_status_expires ON reservation (status, expires_at);

CREATE TABLE stock_movement (
    id UUID PRIMARY KEY,
    sku_code VARCHAR(64) NOT NULL REFERENCES stock_item (sku_code),
    type VARCHAR(16) NOT NULL,
    quantity BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    reason TEXT,
    reservation_id UUID REFERENCES reservation (id),
    CONSTRAINT stock_movement_type CHECK (type IN ('RECEIPT', 'ADJUSTMENT', 'SHIPMENT')),
    CONSTRAINT stock_movement_quantity CHECK (
        (type = 'RECEIPT' AND quantity > 0) OR
        (type = 'ADJUSTMENT' AND quantity <> 0) OR
        (type = 'SHIPMENT' AND quantity < 0)
    ),
    CONSTRAINT stock_movement_reason CHECK (
        type <> 'ADJUSTMENT' OR (reason IS NOT NULL AND length(btrim(reason)) > 0)
    ),
    CONSTRAINT stock_movement_reservation CHECK (
        (type = 'SHIPMENT' AND reservation_id IS NOT NULL) OR
        (type <> 'SHIPMENT' AND reservation_id IS NULL)
    )
);

CREATE INDEX idx_stock_movement_sku_time ON stock_movement (sku_code, occurred_at, id);
CREATE UNIQUE INDEX uq_stock_movement_shipment ON stock_movement (reservation_id, sku_code)
    WHERE type = 'SHIPMENT';
