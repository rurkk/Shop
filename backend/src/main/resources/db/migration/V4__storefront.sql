CREATE TABLE storefront (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    published boolean NOT NULL DEFAULT false,
    hero_title varchar(120),
    hero_image_id uuid REFERENCES catalog_images (id),
    CHECK (NOT published OR (hero_title IS NOT NULL AND hero_image_id IS NOT NULL))
);

INSERT INTO storefront DEFAULT VALUES;

CREATE TABLE storefront_gallery (
    display_order integer PRIMARY KEY CHECK (display_order >= 0),
    embroidery_image_id uuid NOT NULL REFERENCES catalog_images (id),
    source_image_id uuid REFERENCES catalog_images (id),
    caption varchar(160)
);
