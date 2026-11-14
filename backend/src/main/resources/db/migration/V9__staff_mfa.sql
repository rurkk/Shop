-- Invalidate earlier staff codes and keep the existing customer OTPs.
DELETE FROM access_codes WHERE scope LIKE 'staff:%';

ALTER TABLE access_mail_limits ADD COLUMN role text NOT NULL DEFAULT 'CUSTOMER';
ALTER TABLE access_mail_limits ALTER COLUMN role DROP DEFAULT;
ALTER TABLE access_mail_limits DROP CONSTRAINT access_mail_limits_pkey;
ALTER TABLE access_mail_limits ADD PRIMARY KEY (role, email);
ALTER TABLE access_mail_limits ADD CHECK (role IN ('CUSTOMER', 'STAFF'));

CREATE TABLE staff_totp_use (
    email text NOT NULL,
    secret_fingerprint bytea NOT NULL CHECK (octet_length(secret_fingerprint) = 32),
    last_step bigint NOT NULL CHECK (last_step >= 0),
    PRIMARY KEY (email, secret_fingerprint)
);
