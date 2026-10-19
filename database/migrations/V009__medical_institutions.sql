-- Administrator-entered medical institutions and explicit physician assignments.
-- Existing physician, certificate, signature and audit records are preserved.
BEGIN;
DO $$ BEGIN
  IF (SELECT version FROM certi_rt.schema_info WHERE component = 'runtime') IS DISTINCT FROM 8 THEN
    RAISE EXCEPTION 'V009 requires runtime schema 8';
  END IF;
END $$;

CREATE TABLE certi_rt.medical_institutions (
  id uuid PRIMARY KEY, seq bigint GENERATED ALWAYS AS IDENTITY, tenant_id text NOT NULL,
  version bigint NOT NULL CHECK (version >= 1), payload jsonb NOT NULL,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, expires_at timestamptz,
  code_key text UNIQUE NOT NULL
);
CREATE INDEX medical_institutions_tenant_idx ON certi_rt.medical_institutions(tenant_id, seq);

CREATE TABLE certi_rt.medical_institution_doctors (
  id uuid PRIMARY KEY, seq bigint GENERATED ALWAYS AS IDENTITY, tenant_id text NOT NULL,
  version bigint NOT NULL CHECK (version >= 1), payload jsonb NOT NULL,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, expires_at timestamptz,
  assignment_key text UNIQUE NOT NULL, doctor_id text NOT NULL
);
CREATE INDEX medical_institution_doctors_tenant_idx ON certi_rt.medical_institution_doctors(tenant_id, seq);
CREATE INDEX medical_institution_doctors_doctor_idx ON certi_rt.medical_institution_doctors(doctor_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON certi_rt.medical_institutions, certi_rt.medical_institution_doctors TO certi_app;
GRANT USAGE, SELECT ON SEQUENCE certi_rt.medical_institutions_seq_seq,
  certi_rt.medical_institution_doctors_seq_seq TO certi_app;
UPDATE certi_rt.schema_info SET version = 9, applied_at = now() WHERE component = 'runtime';
COMMIT;
