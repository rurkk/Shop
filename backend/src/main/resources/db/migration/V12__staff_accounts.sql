CREATE TABLE staff_accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL UNIQUE,
    can_manage_staff boolean NOT NULL,
    credential_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    encrypted_totp bytea NOT NULL CHECK (octet_length(encrypted_totp) = 60)
);

-- The scope for staff codes now includes the credential ID.
DELETE FROM access_codes WHERE scope LIKE 'staff:%';
