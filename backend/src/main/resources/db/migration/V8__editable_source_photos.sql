ALTER TABLE order_files
    ADD COLUMN deleted boolean NOT NULL DEFAULT false,
    ADD COLUMN replaces_file_id uuid,
    ADD CONSTRAINT order_files_deleted_source CHECK (NOT deleted OR purpose = 'SOURCE'),
    ADD CONSTRAINT order_files_pending_replacement CHECK (
        replaces_file_id IS NULL OR (purpose = 'SOURCE' AND NOT ready)
    );

CREATE UNIQUE INDEX order_files_active_replacement ON order_files (replaces_file_id)
    WHERE replaces_file_id IS NOT NULL AND NOT deleted;

CREATE INDEX order_files_deleted ON order_files (created_at, id) WHERE deleted;
