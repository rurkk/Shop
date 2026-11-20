ALTER TABLE orders
    DROP CONSTRAINT orders_embroidery_frame_check,
    ADD CONSTRAINT orders_embroidery_frame_check CHECK (embroidery_frame IN ('MINI', 'SMALL', 'STANDARD', 'LARGE'));
