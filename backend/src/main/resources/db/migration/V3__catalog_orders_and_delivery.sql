ALTER TABLE orders RENAME COLUMN total_kopecks TO item_kopecks;

ALTER TABLE orders
    DROP CONSTRAINT orders_garment_check,
    DROP CONSTRAINT orders_status_check,
    DROP CONSTRAINT orders_total_kopecks_check,
    ALTER COLUMN garment TYPE varchar(80),
    ALTER COLUMN garment_size TYPE varchar(32),
    ALTER COLUMN description DROP NOT NULL,
    ADD COLUMN order_type text NOT NULL DEFAULT 'CUSTOM_PHOTO',
    ADD COLUMN estimated_delivery_days integer CHECK (estimated_delivery_days > 0),
    ADD COLUMN rejection_reason varchar(400),
    ADD CONSTRAINT orders_type_fields_check CHECK (
        (order_type = 'CUSTOM_PHOTO' AND garment IN ('HOODIE', 'T_SHIRT', 'SWEATSHIRT')
            AND description IS NOT NULL)
        OR (order_type = 'CATALOG' AND description IS NULL AND item_kopecks IS NOT NULL)
    ),
    ADD CONSTRAINT orders_item_price_check CHECK (
        item_kopecks BETWEEN CASE WHEN order_type = 'CATALOG' THEN 1 ELSE 2 END AND 9007199254740991
    ),
    ADD CONSTRAINT orders_type_status_check CHECK (
        status IN ('DRAFT', 'EXPIRED', 'IN_REVIEW', 'READY_TO_SHIP', 'SHIPPED', 'REJECTED')
        OR (order_type = 'CUSTOM_PHOTO' AND status IN (
            'AWAITING_DEPOSIT', 'PREPARING_SKETCH', 'SKETCH_REVIEW', 'CHANGES_REQUESTED',
            'IN_PRODUCTION', 'AWAITING_DELIVERY_QUOTE', 'AWAITING_BALANCE'
        ))
        OR (order_type = 'CATALOG' AND status IN ('AWAITING_PAYMENT', 'PREPARING_ORDER'))
    ),
    ADD CONSTRAINT orders_rejection_reason_check CHECK (
        (status = 'REJECTED') = (rejection_reason IS NOT NULL)
    );

ALTER TABLE orders ALTER COLUMN order_type DROP DEFAULT;

CREATE INDEX orders_submitted_created_at ON orders (created_at DESC, id)
    WHERE status NOT IN ('DRAFT', 'EXPIRED');
CREATE INDEX orders_submitted_email ON orders (email) WHERE status NOT IN ('DRAFT', 'EXPIRED');

-- Keep the purchased variant snapshot even if the catalog changes.
CREATE TABLE catalog_order_items (
    order_id uuid PRIMARY KEY REFERENCES orders (id) ON DELETE CASCADE,
    variant_id uuid NOT NULL,
    catalog_slug varchar(80) NOT NULL,
    product_slug varchar(80) NOT NULL,
    product_name varchar(160) NOT NULL,
    sku varchar(64) NOT NULL,
    availability text NOT NULL CHECK (availability IN ('IN_STOCK', 'MADE_TO_ORDER'))
);

CREATE TABLE order_deliveries (
    order_id uuid PRIMARY KEY REFERENCES orders (id) ON DELETE CASCADE,
    method text NOT NULL DEFAULT 'CDEK_PICKUP' CHECK (method = 'CDEK_PICKUP'),
    recipient_name varchar(160) NOT NULL,
    phone varchar(16) NOT NULL,
    city varchar(120) NOT NULL,
    pickup_point_code varchar(40),
    pickup_point_address varchar(300),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    price_kopecks bigint CHECK (price_kopecks BETWEEN 0 AND 9007199254740991),
    delivery_days integer CHECK (delivery_days > 0),
    CHECK (pickup_point_code IS NOT NULL OR pickup_point_address IS NOT NULL),
    CHECK ((price_kopecks IS NULL) = (delivery_days IS NULL)),
    CHECK (price_kopecks IS NULL OR (pickup_point_code IS NOT NULL AND pickup_point_address IS NOT NULL))
);

ALTER TABLE order_payments
    DROP CONSTRAINT order_payments_installment_check,
    ADD CONSTRAINT order_payments_installment_check CHECK (installment IN ('DEPOSIT', 'BALANCE', 'FULL'));
