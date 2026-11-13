DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM order_payments WHERE installment <> 'FULL') THEN
        RAISE EXCEPTION 'Split-payment test orders require a fresh pre-launch database';
    END IF;
END $$;

ALTER TABLE orders
    DROP CONSTRAINT orders_type_status_check,
    DROP CONSTRAINT orders_item_price_check,
    DROP COLUMN estimated_delivery_days,
    ADD COLUMN cancellation_reason varchar(400),
    ADD CONSTRAINT orders_item_price_check CHECK (item_kopecks BETWEEN 1 AND 9007199254740991),
    ADD CONSTRAINT orders_type_status_check CHECK (
        status IN ('DRAFT', 'EXPIRED', 'IN_REVIEW', 'AWAITING_PAYMENT', 'READY_TO_SHIP', 'SHIPPED', 'REJECTED',
                   'CANCELLATION_REQUESTED', 'CANCELLED')
        OR (order_type = 'CUSTOM_PHOTO' AND status IN (
            'AWAITING_DELIVERY_QUOTE', 'PREPARING_SKETCH', 'SKETCH_REVIEW', 'CHANGES_REQUESTED', 'IN_PRODUCTION'
        ))
        OR (order_type = 'CATALOG' AND status = 'PREPARING_ORDER')
    ),
    ADD CONSTRAINT orders_cancellation_reason_check CHECK (
        (status IN ('CANCELLATION_REQUESTED', 'CANCELLED')) = (cancellation_reason IS NOT NULL)
    );

ALTER TABLE order_payments
    DROP COLUMN installment,
    ADD UNIQUE (order_id);

CREATE TABLE payment_attempts (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES order_payments (id),
    shop_id varchar(32) NOT NULL,
    provider_id uuid UNIQUE,
    status text NOT NULL CHECK (status IN ('CREATING', 'PENDING', 'SUCCEEDED', 'CANCELED')),
    return_url text NOT NULL,
    checkout_url text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'CREATING') = (provider_id IS NULL)),
    CHECK ((status = 'PENDING') = (checkout_url IS NOT NULL))
);

CREATE UNIQUE INDEX payment_attempts_active ON payment_attempts (payment_id) WHERE status <> 'CANCELED';

CREATE TABLE payment_refunds (
    id uuid PRIMARY KEY,
    attempt_id uuid NOT NULL REFERENCES payment_attempts (id),
    provider_id uuid UNIQUE,
    status text NOT NULL CHECK (status IN ('CREATING', 'PENDING', 'SUCCEEDED', 'CANCELED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'CREATING') = (provider_id IS NULL))
);

CREATE UNIQUE INDEX payment_refunds_active ON payment_refunds (attempt_id) WHERE status <> 'CANCELED';

CREATE TABLE order_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    actor_email text,
    action text NOT NULL,
    details jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX order_events_by_order ON order_events (order_id, id DESC);
