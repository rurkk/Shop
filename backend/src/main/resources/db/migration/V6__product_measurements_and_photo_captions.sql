ALTER TABLE product_images ADD COLUMN caption varchar(200) CHECK (caption <> '');

CREATE TABLE product_measurements (
    product_id uuid NOT NULL REFERENCES products (id),
    display_order integer NOT NULL CHECK (display_order >= 0),
    size varchar(32) NOT NULL CHECK (btrim(size) <> ''),
    width_cm numeric(4, 1) NOT NULL CHECK (width_cm BETWEEN 0.1 AND 999.9),
    length_cm numeric(4, 1) NOT NULL CHECK (length_cm BETWEEN 0.1 AND 999.9),
    sleeve_length_cm numeric(4, 1) CHECK (sleeve_length_cm BETWEEN 0.1 AND 999.9),
    PRIMARY KEY (product_id, display_order)
);

CREATE UNIQUE INDEX product_measurements_size ON product_measurements (product_id, lower(size));
