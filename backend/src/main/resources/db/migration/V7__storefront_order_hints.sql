ALTER TABLE storefront
    ADD COLUMN photo_order_price_hint varchar(160),
    ADD COLUMN photo_order_timing_hint varchar(160),
    ADD COLUMN delivery_price_hint varchar(160);
