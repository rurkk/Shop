ALTER TABLE sketch_generation_jobs
    ADD COLUMN frame_x_percent integer NOT NULL DEFAULT 50 CHECK (frame_x_percent BETWEEN 0 AND 100),
    ADD COLUMN frame_y_percent integer NOT NULL DEFAULT 50 CHECK (frame_y_percent BETWEEN 0 AND 100);

ALTER TABLE sketch_generation_jobs
    ALTER COLUMN frame_x_percent DROP DEFAULT,
    ALTER COLUMN frame_y_percent DROP DEFAULT;
