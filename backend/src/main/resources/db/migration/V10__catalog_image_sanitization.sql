ALTER TABLE catalog_images
    ADD COLUMN encoding text NOT NULL DEFAULT 'LEGACY'
        CHECK (encoding IN ('LEGACY', 'SANITIZED_COPY', 'SANITIZED')),
    ADD COLUMN sanitize_after timestamptz NOT NULL DEFAULT now();

ALTER TABLE catalog_images ALTER COLUMN encoding DROP DEFAULT;

CREATE INDEX catalog_images_to_sanitize ON catalog_images
    ((encoding = 'SANITIZED_COPY') DESC, sanitize_after, created_at, id)
    WHERE status = 'READY' AND encoding <> 'SANITIZED';
