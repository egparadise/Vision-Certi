-- Foundation schema only. The initial HTTP demo uses in-memory repositories.
-- Run with a migration owner. Application must SET LOCAL app.tenant_id per transaction.
CREATE SCHEMA certi;
CREATE FUNCTION certi.current_tenant() RETURNS uuid LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

CREATE TABLE certi.tenants (
 id uuid PRIMARY KEY, code text NOT NULL UNIQUE, name text NOT NULL,
 status text NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE certi.identities (
 id uuid PRIMARY KEY, oidc_issuer text NOT NULL, oidc_subject text NOT NULL,
 display_name text NOT NULL, status text NOT NULL CHECK (status IN ('PENDING','ACTIVE','SUSPENDED')),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(oidc_issuer,oidc_subject)
);
CREATE TABLE certi.memberships (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES certi.tenants(id),
 identity_id uuid NOT NULL REFERENCES certi.identities(id),
 role text NOT NULL CHECK (role IN ('DOCTOR','TENANT_ADMIN','REVIEWER','AUDITOR')),
 status text NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
 valid_from timestamptz NOT NULL DEFAULT now(), valid_to timestamptz,
 authority_version bigint NOT NULL DEFAULT 1 CHECK (authority_version > 0),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,identity_id,role),
 CHECK(valid_to IS NULL OR valid_to > valid_from)
);
CREATE TABLE certi.tenant_policies (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES certi.tenants(id),
 version integer NOT NULL CHECK(version > 0), policy_json jsonb NOT NULL,
 approved_by uuid NOT NULL REFERENCES certi.identities(id), effective_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,version)
);
CREATE TABLE certi.documents (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES certi.tenants(id),
 connector_key text NOT NULL, external_document_id text NOT NULL,
 external_study_id text NOT NULL, title text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,connector_key,external_document_id)
);
CREATE TABLE certi.document_versions (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, document_id uuid NOT NULL,
 version_no integer NOT NULL CHECK(version_no > 0), previous_version_id uuid,
 object_key text NOT NULL, object_version text NOT NULL,
 sha256 text NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 byte_length bigint NOT NULL CHECK(byte_length > 0), content_type text NOT NULL CHECK(content_type='application/pdf'),
 amendment_reason text, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,document_id,id), UNIQUE(tenant_id,document_id,version_no),
 FOREIGN KEY(tenant_id,document_id) REFERENCES certi.documents(tenant_id,id),
 FOREIGN KEY(tenant_id,document_id,previous_version_id) REFERENCES certi.document_versions(tenant_id,document_id,id),
 CHECK((version_no=1 AND previous_version_id IS NULL AND amendment_reason IS NULL)
 OR (version_no>1 AND previous_version_id IS NOT NULL AND amendment_reason IS NOT NULL AND length(trim(amendment_reason))>0))
);
CREATE TABLE certi.sign_requests (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, document_version_id uuid NOT NULL,
 signer_id uuid NOT NULL REFERENCES certi.identities(id),
 doc_hash text NOT NULL CHECK(doc_hash ~ '^[0-9a-f]{64}$'),
 nonce_digest text NOT NULL CHECK(nonce_digest ~ '^[0-9a-f]{64}$'),
 policy_version integer NOT NULL,
 state text NOT NULL CHECK(state IN ('PENDING_APPROVAL','APPROVED','SIGNING','EVIDENCE_PENDING','COMPLETED','EXPIRED','REJECTED','FAILED','RECONCILIATION_REQUIRED')),
 expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,nonce_digest),
 FOREIGN KEY(tenant_id,document_version_id) REFERENCES certi.document_versions(tenant_id,id),
 FOREIGN KEY(tenant_id,policy_version) REFERENCES certi.tenant_policies(tenant_id,version),
 CHECK(expires_at > created_at)
);
CREATE TABLE certi.approval_evidence (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, request_id uuid NOT NULL, jti uuid NOT NULL UNIQUE,
 object_ref text NOT NULL, approval_hash text NOT NULL CHECK(approval_hash ~ '^[0-9a-f]{64}$'),
 auth_context_ref text NOT NULL, approved_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,request_id),
 FOREIGN KEY(tenant_id,request_id) REFERENCES certi.sign_requests(tenant_id,id), CHECK(expires_at > approved_at)
);
CREATE TABLE certi.sign_jobs (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, request_id uuid NOT NULL,
 state text NOT NULL CHECK(state IN ('READY','LEASED','RESULT_RECORDED','EVIDENCE_PENDING','DONE','RECONCILIATION_REQUIRED','FAILED')),
 lease_owner text, lease_until timestamptz, attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 execution_ref text, last_error_code text, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,request_id),
 FOREIGN KEY(tenant_id,request_id) REFERENCES certi.sign_requests(tenant_id,id)
);
CREATE TABLE certi.signatures (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, request_id uuid NOT NULL,
 signature_object_ref text NOT NULL, signed_at timestamptz NOT NULL,
 mode text NOT NULL CHECK(mode IN ('MOCK_NOT_VALID_FOR_CLINICAL_USE','CADES')),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,request_id),
 FOREIGN KEY(tenant_id,request_id) REFERENCES certi.sign_requests(tenant_id,id)
);
CREATE TABLE certi.evidence_packages (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, signature_id uuid NOT NULL,
 generation integer NOT NULL CHECK(generation>0), object_key text NOT NULL,
 sha256 text NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 verification_status text NOT NULL CHECK(verification_status IN ('VALID','INVALID','INDETERMINATE')),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant_id,id), UNIQUE(tenant_id,signature_id,generation),
 FOREIGN KEY(tenant_id,signature_id) REFERENCES certi.signatures(tenant_id,id)
);
CREATE TABLE certi.deliveries (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, signature_id uuid NOT NULL, connector_key text NOT NULL,
 event_type text NOT NULL, state text NOT NULL CHECK(state IN ('QUEUED','SENDING','ACKED','RETRY_WAIT','DEAD_LETTER')),
 next_attempt_at timestamptz, ack_ref text, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,signature_id,connector_key,event_type),
 FOREIGN KEY(tenant_id,signature_id) REFERENCES certi.signatures(tenant_id,id)
);
CREATE TABLE certi.delivery_attempts (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, delivery_id uuid NOT NULL,
 attempt_no integer NOT NULL CHECK(attempt_no>0), sent_at timestamptz NOT NULL, response_code integer,
 ack_ref text, error_code text, UNIQUE(tenant_id,id), UNIQUE(tenant_id,delivery_id,attempt_no),
 FOREIGN KEY(tenant_id,delivery_id) REFERENCES certi.deliveries(tenant_id,id)
);
CREATE TABLE certi.outbox_events (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES certi.tenants(id),
 aggregate_type text NOT NULL, aggregate_id uuid NOT NULL, event_type text NOT NULL, payload jsonb NOT NULL,
 available_at timestamptz NOT NULL DEFAULT now(), processed_at timestamptz, UNIQUE(tenant_id,id)
);
CREATE TABLE certi.audit_events (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES certi.tenants(id), sequence_no bigint NOT NULL CHECK(sequence_no>0),
 actor_id uuid, action text NOT NULL, target_type text NOT NULL, target_id uuid,
 correlation_id text NOT NULL, previous_hash text NOT NULL CHECK(previous_hash ~ '^[0-9a-f]{64}$'),
 event_hash text NOT NULL CHECK(event_hash ~ '^[0-9a-f]{64}$'), occurred_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,sequence_no)
);
CREATE TABLE certi.audit_checkpoints (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES certi.tenants(id), last_sequence bigint NOT NULL,
 last_hash text NOT NULL CHECK(last_hash ~ '^[0-9a-f]{64}$'), object_ref text NOT NULL, anchored_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,last_sequence)
);
CREATE TABLE certi.idempotency_records (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES certi.tenants(id), actor_id uuid NOT NULL,
 route_key text NOT NULL, idempotency_key text NOT NULL,
 request_hash text NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'), response_ref text,
 state text NOT NULL CHECK(state IN ('PROCESSING','COMPLETED','FAILED')), expires_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,actor_id,route_key,idempotency_key)
);
CREATE INDEX sign_request_work ON certi.sign_requests(tenant_id,state,created_at);
CREATE INDEX delivery_work ON certi.deliveries(tenant_id,next_attempt_at) WHERE state IN ('QUEUED','RETRY_WAIT');
CREATE INDEX pending_outbox ON certi.outbox_events(tenant_id,available_at) WHERE processed_at IS NULL;
CREATE INDEX audit_search ON certi.audit_events(tenant_id,occurred_at DESC);
