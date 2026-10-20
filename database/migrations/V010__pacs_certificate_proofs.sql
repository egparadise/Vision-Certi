-- PACS certificate-only proof. PACS credentials remain exclusively with PACS.
BEGIN;
DO $$ BEGIN
  IF (SELECT version FROM certi_rt.schema_info WHERE component = 'runtime') IS DISTINCT FROM 9 THEN
    RAISE EXCEPTION 'V010 requires runtime schema 9';
  END IF;
END $$;

CREATE TABLE certi_rt.pacs_proof_challenges (
  id uuid PRIMARY KEY, seq bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id text, version bigint NOT NULL CHECK (version >= 1), payload jsonb NOT NULL,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, expires_at timestamptz,
  client_id text NOT NULL
);
CREATE INDEX pacs_proof_challenges_client_idx ON certi_rt.pacs_proof_challenges(client_id, seq);

CREATE TABLE certi_rt.pacs_certificate_proofs (
  id uuid PRIMARY KEY, seq bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id text, version bigint NOT NULL CHECK (version >= 1), payload jsonb NOT NULL,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, expires_at timestamptz,
  code_hash text UNIQUE NOT NULL, client_id text NOT NULL
);
CREATE INDEX pacs_certificate_proofs_client_idx ON certi_rt.pacs_certificate_proofs(client_id, seq);

GRANT SELECT, INSERT, UPDATE, DELETE ON certi_rt.pacs_proof_challenges, certi_rt.pacs_certificate_proofs TO certi_app;
GRANT USAGE, SELECT ON SEQUENCE certi_rt.pacs_proof_challenges_seq_seq,
  certi_rt.pacs_certificate_proofs_seq_seq TO certi_app;
UPDATE certi_rt.schema_info SET version = 10, applied_at = now() WHERE component = 'runtime';
COMMIT;
