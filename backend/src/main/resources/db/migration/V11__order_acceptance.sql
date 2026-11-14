ALTER TABLE order_deliveries
    ADD COLUMN offer_valid_through date,
    ADD COLUMN deliver_by date,
    ADD CONSTRAINT order_deliveries_offer_dates_check CHECK (
        (price_kopecks IS NULL AND offer_valid_through IS NULL AND deliver_by IS NULL)
        OR (price_kopecks IS NOT NULL AND offer_valid_through IS NOT NULL AND deliver_by IS NOT NULL
            AND deliver_by > offer_valid_through)
    ) NOT VALID;

CREATE TABLE order_acceptances (
    order_id uuid PRIMARY KEY REFERENCES orders (id) ON DELETE CASCADE,
    offer_version varchar(64) NOT NULL CHECK (offer_version ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    accepted_at timestamptz NOT NULL,
    terms jsonb NOT NULL CHECK (jsonb_typeof(terms) = 'object')
);
