ALTER TABLE orders
    DROP COLUMN embroidery_width_mm,
    DROP COLUMN embroidery_height_mm,
    ADD COLUMN embroidery_frame text CHECK (embroidery_frame IN ('SMALL', 'STANDARD', 'LARGE')),
    ADD COLUMN three_photo_background boolean NOT NULL DEFAULT false,
    ADD COLUMN license_plate boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT orders_embroidery_configuration CHECK (
        (embroidery_frame IS NULL AND NOT three_photo_background AND NOT license_plate)
        OR (embroidery_frame IS NOT NULL AND order_type = 'CUSTOM_PHOTO'
            AND (NOT three_photo_background OR embroidery_frame = 'LARGE'))
    );
