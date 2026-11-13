CREATE TABLE orders (
    id uuid PRIMARY KEY,
    email text NOT NULL,
    garment text NOT NULL CHECK (garment IN ('HOODIE', 'T_SHIRT', 'SWEATSHIRT')),
    garment_size varchar(24) NOT NULL,
    color varchar(80) NOT NULL,
    description varchar(2000) NOT NULL,
    status text NOT NULL CHECK (status IN (
        'DRAFT', 'EXPIRED', 'IN_REVIEW', 'AWAITING_DEPOSIT', 'PREPARING_SKETCH',
        'SKETCH_REVIEW', 'CHANGES_REQUESTED', 'IN_PRODUCTION',
        'AWAITING_BALANCE', 'READY_TO_SHIP', 'SHIPPED'
    )),
    total_kopecks bigint CHECK (total_kopecks >= 2),
    production_days integer CHECK (production_days > 0),
    tracking_number varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX orders_status_created_at ON orders (status, created_at DESC);

CREATE TABLE order_files (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders (id),
    purpose text NOT NULL CHECK (purpose IN ('SOURCE', 'SKETCH', 'CHAT')),
    uploader_email text NOT NULL,
    media_type text NOT NULL CHECK (media_type IN ('image/jpeg', 'image/png')),
    byte_size bigint NOT NULL CHECK (byte_size BETWEEN 1 AND 10485760),
    ready boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_id, id)
);

CREATE TABLE sketches (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL,
    file_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    approved_at timestamptz,
    FOREIGN KEY (order_id, file_id) REFERENCES order_files (order_id, id),
    UNIQUE (order_id, version),
    UNIQUE (file_id)
);

CREATE TABLE order_payments (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders (id),
    installment text NOT NULL CHECK (installment IN ('DEPOSIT', 'BALANCE')),
    amount_kopecks bigint NOT NULL CHECK (amount_kopecks > 0),
    paid_at timestamptz,
    UNIQUE (order_id, installment)
);

CREATE TABLE order_messages (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders (id),
    sequence_no bigint NOT NULL CHECK (sequence_no > 0),
    client_message_id uuid NOT NULL,
    sender_role text NOT NULL CHECK (sender_role IN ('CUSTOMER', 'STAFF')),
    sender_email text NOT NULL,
    body varchar(4000) NOT NULL,
    attachment_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (order_id, attachment_id) REFERENCES order_files (order_id, id),
    UNIQUE (order_id, sequence_no),
    UNIQUE (order_id, sender_role, sender_email, client_message_id),
    CHECK (length(body) > 0 OR attachment_id IS NOT NULL)
);

CREATE INDEX order_messages_attachment_id ON order_messages (attachment_id) WHERE attachment_id IS NOT NULL;

CREATE TABLE access_codes (
    scope text PRIMARY KEY,
    digest bytea NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    expires_at timestamptz NOT NULL
);

CREATE TABLE access_mail_limits (
    email text PRIMARY KEY,
    window_start timestamptz NOT NULL,
    last_sent_at timestamptz NOT NULL,
    sent_count integer NOT NULL CHECK (sent_count BETWEEN 1 AND 10)
);

CREATE TABLE mail_outbox (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recipient text NOT NULL,
    subject text NOT NULL,
    body text NOT NULL,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0
);

CREATE INDEX mail_outbox_due ON mail_outbox (next_attempt_at);
