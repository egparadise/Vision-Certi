-- Direct physician accounts and separate, enrollment-only password sessions. Existing records are preserved.
BEGIN;
DO $$ BEGIN
  IF (SELECT version FROM certi_rt.schema_info WHERE component = 'runtime') IS DISTINCT FROM 6 THEN
    RAISE EXCEPTION 'V007 requires runtime schema 6';
  END IF;
END $$;
CREATE TABLE certi_rt.physician_accounts (
  id uuid PRIMARY KEY, seq bigint GENERATED ALWAYS AS IDENTITY, tenant_id text,
  version bigint NOT NULL CHECK (version >= 1), payload jsonb NOT NULL,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, expires_at timestamptz,
  username_key text UNIQUE NOT NULL, doctor_id text UNIQUE NOT NULL
);
CREATE TABLE certi_rt.physician_account_sessions (
  id uuid PRIMARY KEY, seq bigint GENERATED ALWAYS AS IDENTITY, tenant_id text,
  version bigint NOT NULL CHECK (version >= 1), payload jsonb NOT NULL,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
  token_hash text UNIQUE NOT NULL, account_id text NOT NULL
);
CREATE INDEX physician_accounts_tenant_idx ON certi_rt.physician_accounts(tenant_id, seq);
CREATE INDEX physician_account_sessions_account_idx ON certi_rt.physician_account_sessions(account_id);
CREATE INDEX physician_account_sessions_expiry_idx ON certi_rt.physician_account_sessions(expires_at);
GRANT SELECT, INSERT, UPDATE, DELETE ON certi_rt.physician_accounts, certi_rt.physician_account_sessions TO certi_app;
GRANT USAGE, SELECT ON SEQUENCE certi_rt.physician_accounts_seq_seq, certi_rt.physician_account_sessions_seq_seq TO certi_app;
UPDATE certi_rt.schema_info SET version = 7, applied_at = now() WHERE component = 'runtime';
COMMIT;
