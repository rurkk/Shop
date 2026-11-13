CREATE TABLE catalog_images (
    id uuid PRIMARY KEY,
    media_type text NOT NULL CHECK (media_type IN ('image/jpeg', 'image/png')),
    byte_size bigint NOT NULL CHECK (byte_size BETWEEN 1 AND 10485760),
    status text NOT NULL CHECK (status IN ('PENDING', 'READY', 'DELETING')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX catalog_images_to_clean ON catalog_images (created_at) WHERE status <> 'READY';

CREATE TABLE catalogs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug varchar(80) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name varchar(160) NOT NULL,
    description varchar(4000) NOT NULL,
    kind text NOT NULL CHECK (kind IN ('COLLECTION', 'SELECTION')),
    cover_image_id uuid REFERENCES catalog_images (id),
    display_order integer NOT NULL CHECK (display_order >= 0),
    published boolean NOT NULL
);

CREATE INDEX catalogs_display_order ON catalogs (display_order, slug);
CREATE INDEX catalogs_cover_image_id ON catalogs (cover_image_id) WHERE cover_image_id IS NOT NULL;

CREATE TABLE products (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug varchar(80) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name varchar(160) NOT NULL,
    design varchar(160) NOT NULL,
    garment_type varchar(80) NOT NULL,
    description varchar(4000) NOT NULL,
    published boolean NOT NULL
);

CREATE TABLE catalog_products (
    catalog_id uuid NOT NULL REFERENCES catalogs (id),
    product_id uuid NOT NULL REFERENCES products (id),
    display_order integer NOT NULL CHECK (display_order >= 0),
    PRIMARY KEY (catalog_id, product_id)
);

CREATE INDEX catalog_products_display_order ON catalog_products (catalog_id, display_order, product_id);
CREATE INDEX catalog_products_product_id ON catalog_products (product_id, catalog_id);

CREATE TABLE product_images (
    product_id uuid NOT NULL REFERENCES products (id),
    image_id uuid NOT NULL REFERENCES catalog_images (id),
    display_order integer NOT NULL CHECK (display_order >= 0),
    PRIMARY KEY (product_id, display_order),
    UNIQUE (product_id, image_id)
);

CREATE INDEX product_images_image_id ON product_images (image_id);

CREATE TABLE product_variants (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES products (id),
    sku varchar(64) NOT NULL UNIQUE CHECK (sku ~ '^[A-Z0-9][A-Z0-9_-]*$'),
    size varchar(32) NOT NULL,
    color varchar(80) NOT NULL,
    price_kopecks bigint NOT NULL CHECK (price_kopecks BETWEEN 1 AND 9007199254740991),
    availability text NOT NULL CHECK (availability IN ('IN_STOCK', 'MADE_TO_ORDER', 'OUT_OF_STOCK')),
    production_days integer,
    CHECK (
        (availability = 'MADE_TO_ORDER' AND production_days IS NOT NULL AND production_days > 0)
        OR (availability <> 'MADE_TO_ORDER' AND production_days IS NULL)
    )
);

CREATE UNIQUE INDEX product_variants_size_color ON product_variants (product_id, lower(size), lower(color));
CREATE INDEX product_variants_price ON product_variants (product_id, price_kopecks);

INSERT INTO catalogs (slug, name, description, kind, display_order, published)
VALUES
    ('team-choice', 'Выбор команды', '', 'SELECTION', 0, true),
    ('rus', 'Русь', '', 'COLLECTION', 10, true);
