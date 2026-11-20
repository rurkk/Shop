ALTER TABLE orders
    ADD COLUMN embroidery_width_mm integer CHECK (embroidery_width_mm BETWEEN 1 AND 9999),
    ADD COLUMN embroidery_height_mm integer CHECK (embroidery_height_mm BETWEEN 1 AND 9999);

ALTER TABLE sketch_generation_jobs
    ADD COLUMN garment_side text CHECK (garment_side IN ('FRONT', 'BACK')),
    ADD COLUMN panel_width_mm integer CHECK (panel_width_mm BETWEEN 1 AND 9999),
    ADD COLUMN panel_height_mm integer CHECK (panel_height_mm BETWEEN 1 AND 9999),
    ADD COLUMN embroidery_width_mm integer CHECK (embroidery_width_mm BETWEEN 1 AND panel_width_mm),
    ADD COLUMN embroidery_height_mm integer CHECK (embroidery_height_mm BETWEEN 1 AND panel_height_mm);

UPDATE sketch_generation_jobs
SET status = 'FAILED', failure = 'INTERRUPTED'
WHERE status IN ('QUEUED', 'RUNNING');

ALTER TABLE sketch_generation_jobs ADD CONSTRAINT sketch_active_job_has_placement CHECK (
    status NOT IN ('QUEUED', 'RUNNING') OR
    (garment_side IS NOT NULL AND panel_width_mm IS NOT NULL AND panel_height_mm IS NOT NULL
     AND embroidery_width_mm IS NOT NULL AND embroidery_height_mm IS NOT NULL)
);
