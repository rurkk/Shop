CREATE TABLE sketch_generation_jobs (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders (id),
    requested_by text NOT NULL,
    through_sequence bigint NOT NULL CHECK (through_sequence >= 0),
    max_variants integer NOT NULL CHECK (max_variants BETWEEN 1 AND 3),
    previous_file_id uuid,
    feedback varchar(2000),
    status text NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    failure text,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    CHECK ((previous_file_id IS NULL) = (feedback IS NULL)),
    CHECK ((status = 'FAILED') = (failure IS NOT NULL)),
    UNIQUE (order_id, id)
);

CREATE UNIQUE INDEX sketch_generation_one_active_order ON sketch_generation_jobs (order_id)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX sketch_generation_queue ON sketch_generation_jobs (created_at, id) WHERE status = 'QUEUED';
CREATE INDEX sketch_generation_order_history ON sketch_generation_jobs (order_id, created_at DESC, id DESC);

CREATE TABLE sketch_generation_variants (
    order_id uuid NOT NULL,
    file_id uuid PRIMARY KEY,
    job_id uuid NOT NULL,
    position integer NOT NULL CHECK (position BETWEEN 1 AND 3),
    interpretation varchar(240),
    UNIQUE (order_id, file_id),
    UNIQUE (job_id, position),
    FOREIGN KEY (order_id, file_id) REFERENCES order_files (order_id, id),
    FOREIGN KEY (order_id, job_id) REFERENCES sketch_generation_jobs (order_id, id)
);

ALTER TABLE sketch_generation_jobs ADD FOREIGN KEY (order_id, previous_file_id)
    REFERENCES sketch_generation_variants (order_id, file_id);
