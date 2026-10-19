-- V005: operational runtime schema for the Java backend (apps/backend, PostgresStore).
--
-- Schema certi_rt holds the state the running service actually reads and writes: one row per aggregate
-- (doctor, session, enrollment, document, sign request, signature, ...), with
--   id uuid PK, seq (insertion order), tenant_id, version (optimistic CAS), payload jsonb (the aggregate),
--   created_at/updated_at, expires_at (garbage collection of short-lived rows)
-- plus typed lookup columns derived from the payload on every write. A lookup column is NULL when it does not
-- apply, so UNIQUE columns also express "unique while in that state" (e.g. active_doctor_id: one ACTIVE
-- certificate enrollment per doctor). Raw secrets are never stored: token, code, nonce and scope columns hold
-- SHA-256 hex values only. Document bytes live in certi_rt.blobs.
--
-- The V001-V004 tables in schema certi are the earlier planning model and are not used by the runtime; see
-- apps/backend/docs/PRODUCTION_RUNTIME.md for the mapping. RLS is not enabled on certi_rt: tenant scoping is
-- enforced by every repository query (sessions, handoff codes and nonces are looked up by hash before a tenant is
-- known). Immutable tables reject UPDATE/DELETE by trigger and are granted SELECT/INSERT only.

CREATE SCHEMA certi_rt;

CREATE FUNCTION certi_rt.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'immutable record: %', TG_TABLE_NAME USING ERRCODE = '55000'; END $$;

CREATE TABLE certi_rt.schema_info (
  component text PRIMARY KEY,
  version integer NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT now()
);

-- Creates one aggregate table with the common columns. Lookup columns are added below.
CREATE FUNCTION certi_rt.create_aggregate_table(name text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE format('CREATE TABLE certi_rt.%I (
      id uuid PRIMARY KEY,
      seq bigint GENERATED ALWAYS AS IDENTITY,
      tenant_id text,
      version bigint NOT NULL CHECK (version >= 1),
      payload jsonb NOT NULL,
      created_at timestamptz NOT NULL,
      updated_at timestamptz NOT NULL,
      expires_at timestamptz
    )', name);
  EXECUTE format('CREATE INDEX %I ON certi_rt.%I (tenant_id, seq)', name || '_tenant_idx', name);
  EXECUTE format('CREATE INDEX %I ON certi_rt.%I (expires_at) WHERE expires_at IS NOT NULL', name || '_expires_idx', name);
END $$;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['audit_events','audit_heads','documents','document_versions','sign_requests','signatures',
      'deliveries','idempotency_records','integration_nonces','pacs_handoffs','doctors','doctor_challenges',
      'doctor_sessions','rate_events','doctor_evidence','certificate_challenges','certificate_enrollments',
      'mobile_transactions','staff_principals'] LOOP
    PERFORM certi_rt.create_aggregate_table(t);
  END LOOP;
END $$;

DROP FUNCTION certi_rt.create_aggregate_table(text);

-- Lookup columns. UNIQUE constraints are named <table>_<column>_key (PostgreSQL's default), which the runtime maps
-- back to a business conflict code.
ALTER TABLE certi_rt.audit_events ADD COLUMN chain_key text UNIQUE;          -- sha256(tenant)|seq
ALTER TABLE certi_rt.documents ADD COLUMN external_key text UNIQUE;          -- sha256(tenant, externalDocumentId)
ALTER TABLE certi_rt.document_versions ADD COLUMN document_id text;
ALTER TABLE certi_rt.sign_requests ADD COLUMN document_version_id text, ADD COLUMN signer_id text,
  ADD COLUMN created_by text, ADD COLUMN pending_key text UNIQUE;            -- set only while PENDING_APPROVAL
ALTER TABLE certi_rt.signatures ADD COLUMN sign_request_id text UNIQUE;      -- one logical signature per request
ALTER TABLE certi_rt.deliveries ADD COLUMN signature_id text;
ALTER TABLE certi_rt.idempotency_records ADD COLUMN scope_hash text UNIQUE;
ALTER TABLE certi_rt.integration_nonces ADD COLUMN nonce_key text UNIQUE;
ALTER TABLE certi_rt.pacs_handoffs ADD COLUMN code_hash text UNIQUE, ADD COLUMN client_id text;
ALTER TABLE certi_rt.doctors ADD COLUMN license_key text UNIQUE,            -- null when REVOKED
  ADD COLUMN email_key text UNIQUE,                                          -- null when REVOKED
  ADD COLUMN invitation_hash text UNIQUE,                                    -- only while ISSUED
  ADD COLUMN binding_key text UNIQUE;                                        -- Google/dev identity, null when REVOKED
ALTER TABLE certi_rt.doctor_challenges ADD COLUMN doctor_id text;
ALTER TABLE certi_rt.doctor_sessions ADD COLUMN token_hash text UNIQUE, ADD COLUMN doctor_id text;
ALTER TABLE certi_rt.rate_events ADD COLUMN bucket text;
ALTER TABLE certi_rt.doctor_evidence ADD COLUMN doctor_id text;
ALTER TABLE certi_rt.certificate_challenges ADD COLUMN purpose text;
ALTER TABLE certi_rt.certificate_enrollments ADD COLUMN doctor_id text,
  ADD COLUMN live_fingerprint text UNIQUE,                                   -- PENDING or ACTIVE
  ADD COLUMN live_public_key text,
  ADD COLUMN active_doctor_id text UNIQUE,                                   -- one ACTIVE enrollment per doctor
  ADD COLUMN active_fingerprint text;
ALTER TABLE certi_rt.mobile_transactions ADD COLUMN purpose text;
ALTER TABLE certi_rt.staff_principals ADD COLUMN subject_key text UNIQUE;   -- sha256(issuer, subject)

CREATE INDEX document_versions_document_idx ON certi_rt.document_versions (document_id);
CREATE INDEX sign_requests_version_idx ON certi_rt.sign_requests (document_version_id);
CREATE INDEX sign_requests_signer_idx ON certi_rt.sign_requests (signer_id);
CREATE INDEX sign_requests_created_by_idx ON certi_rt.sign_requests (created_by);
CREATE INDEX deliveries_signature_idx ON certi_rt.deliveries (signature_id);
CREATE INDEX pacs_handoffs_client_idx ON certi_rt.pacs_handoffs (client_id);
CREATE INDEX doctor_challenges_doctor_idx ON certi_rt.doctor_challenges (doctor_id);
CREATE INDEX doctor_sessions_doctor_idx ON certi_rt.doctor_sessions (doctor_id);
CREATE INDEX rate_events_bucket_idx ON certi_rt.rate_events (bucket, expires_at);
CREATE INDEX doctor_evidence_doctor_idx ON certi_rt.doctor_evidence (doctor_id);
CREATE INDEX certificate_enrollments_doctor_idx ON certi_rt.certificate_enrollments (doctor_id);
CREATE INDEX certificate_enrollments_public_key_idx ON certi_rt.certificate_enrollments (live_public_key);
CREATE INDEX certificate_enrollments_active_fp_idx ON certi_rt.certificate_enrollments (active_fingerprint);

-- Exact document bytes, insert-only.
CREATE TABLE certi_rt.blobs (
  id uuid PRIMARY KEY,
  tenant_id text NOT NULL,
  sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
  content bytea NOT NULL,
  created_at timestamptz NOT NULL,
  CHECK (octet_length(content) = size_bytes)
);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['audit_events','document_versions','signatures','doctor_evidence','blobs'] LOOP
    EXECUTE format('CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON certi_rt.%I
        FOR EACH ROW EXECUTE FUNCTION certi_rt.reject_mutation()', t);
  END LOOP;
END $$;

-- Grants for the application group role from V002 (certi_app, NOLOGIN). The runtime login is a member of
-- certi_app; it is neither the schema owner nor a superuser.
REVOKE ALL ON SCHEMA certi_rt FROM PUBLIC;
GRANT USAGE ON SCHEMA certi_rt TO certi_app;
GRANT SELECT ON certi_rt.schema_info TO certi_app;
GRANT SELECT, INSERT ON certi_rt.audit_events, certi_rt.document_versions, certi_rt.signatures,
  certi_rt.doctor_evidence, certi_rt.blobs TO certi_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON certi_rt.audit_heads, certi_rt.documents, certi_rt.sign_requests,
  certi_rt.deliveries, certi_rt.idempotency_records, certi_rt.integration_nonces, certi_rt.pacs_handoffs,
  certi_rt.doctors, certi_rt.doctor_challenges, certi_rt.doctor_sessions, certi_rt.rate_events,
  certi_rt.certificate_challenges, certi_rt.certificate_enrollments, certi_rt.mobile_transactions,
  certi_rt.staff_principals TO certi_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA certi_rt TO certi_app;
REVOKE ALL ON FUNCTION certi_rt.reject_mutation() FROM PUBLIC;

INSERT INTO certi_rt.schema_info (component, version) VALUES ('runtime', 5);
