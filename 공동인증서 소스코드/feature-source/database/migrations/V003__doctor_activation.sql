-- ADR 2026-10-13: consent-first, per-doctor Google / Transit / private PKI.
-- Additive foundation. HTTP development repositories remain separately documented.
CREATE TABLE certi.doctor_profiles (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES certi.tenants(id),
 full_name text NOT NULL CHECK(length(trim(full_name))>0), license_number text NOT NULL,
 center_name text NOT NULL, invitation_email text NOT NULL,
 state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','IDENTITY_VERIFIED','CONSENTED','ACTIVE','SUSPENDED','REVOKED')),
 created_by uuid NOT NULL REFERENCES certi.identities(id), created_at timestamptz NOT NULL DEFAULT now(),
 authority_version bigint NOT NULL DEFAULT 1 CHECK(authority_version>0),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,license_number)
);
CREATE TABLE certi.doctor_reviews (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, doctor_id uuid NOT NULL,
 reviewer_id uuid NOT NULL REFERENCES certi.identities(id),
 identity_evidence_ref text NOT NULL CHECK(length(trim(identity_evidence_ref))>0),
 license_evidence_ref text NOT NULL CHECK(length(trim(license_evidence_ref))>0),
 reviewed_at timestamptz NOT NULL, outcome text NOT NULL CHECK(outcome IN ('VERIFIED','REJECTED')),
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,doctor_id) REFERENCES certi.doctor_profiles(tenant_id,id)
);
CREATE TABLE certi.doctor_invites (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, doctor_id uuid NOT NULL,
 token_digest text NOT NULL UNIQUE CHECK(token_digest ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
 consumed_at timestamptz, revoked_at timestamptz, attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 10),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,doctor_id,id),
 FOREIGN KEY(tenant_id,doctor_id) REFERENCES certi.doctor_profiles(tenant_id,id),
 CHECK(expires_at>created_at), CHECK(consumed_at IS NULL OR consumed_at<expires_at)
);
CREATE TABLE certi.doctor_bindings (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, doctor_id uuid NOT NULL, invite_id uuid NOT NULL,
 issuer text NOT NULL CHECK(issuer IN ('https://accounts.google.com','urn:vision-certi:mock-identity')),
 subject text NOT NULL CHECK(length(subject)>0), identity_mode text NOT NULL CHECK(identity_mode IN ('GOOGLE_OIDC','MOCK_IDENTITY')),
 authentication_evidence_ref text NOT NULL, authenticated_at timestamptz NOT NULL,
 UNIQUE(issuer,subject), UNIQUE(tenant_id,id), UNIQUE(tenant_id,doctor_id), UNIQUE(tenant_id,doctor_id,id),
 FOREIGN KEY(tenant_id,doctor_id) REFERENCES certi.doctor_profiles(tenant_id,id),
 FOREIGN KEY(tenant_id,doctor_id,invite_id) REFERENCES certi.doctor_invites(tenant_id,doctor_id,id),
 CHECK((identity_mode='GOOGLE_OIDC' AND issuer='https://accounts.google.com') OR
 (identity_mode='MOCK_IDENTITY' AND issuer='urn:vision-certi:mock-identity'))
);
CREATE TABLE certi.doctor_consents (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, doctor_id uuid NOT NULL, binding_id uuid NOT NULL,
 version text NOT NULL, terms_sha256 text NOT NULL CHECK(terms_sha256 ~ '^[0-9a-f]{64}$'),
 authentication boolean NOT NULL CHECK(authentication), signature boolean NOT NULL CHECK(signature), privacy boolean NOT NULL CHECK(privacy),
 consented_at timestamptz NOT NULL, evidence_ref text NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,doctor_id,id),
 FOREIGN KEY(tenant_id,doctor_id,binding_id) REFERENCES certi.doctor_bindings(tenant_id,doctor_id,id)
);
CREATE TABLE certi.doctor_keys (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, doctor_id uuid NOT NULL, consent_id uuid NOT NULL,
 provider text NOT NULL CHECK(provider IN ('OPENBAO_TRANSIT','HSM_KMS','DEV_SIMULATED')),
 key_ref text NOT NULL, algorithm text NOT NULL CHECK(algorithm='ECDSA_P256_SHA256'),
 key_version integer NOT NULL CHECK(key_version>0), public_key_pem text NOT NULL, UNIQUE(key_ref,key_version),
 provisioned_at timestamptz NOT NULL, UNIQUE(tenant_id,id), UNIQUE(tenant_id,doctor_id,id),
 FOREIGN KEY(tenant_id,doctor_id,consent_id) REFERENCES certi.doctor_consents(tenant_id,doctor_id,id)
);
CREATE TABLE certi.doctor_certificates (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, doctor_id uuid NOT NULL, key_id uuid NOT NULL,
 serial_number text NOT NULL, certificate_pem text, certificate_sha256 text CHECK(certificate_sha256 ~ '^[0-9a-f]{64}$'),
 certificate_kind text NOT NULL CHECK(certificate_kind IN ('INVIZ_PRIVATE_X509','DEV_SIMULATED')),
 issuer_ref text NOT NULL, not_before timestamptz NOT NULL, not_after timestamptz NOT NULL,
 revoked_at timestamptz, revocation_reason text,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,doctor_id,id), UNIQUE(issuer_ref,serial_number),
 FOREIGN KEY(tenant_id,doctor_id,key_id) REFERENCES certi.doctor_keys(tenant_id,doctor_id,id),
 CHECK(not_after>not_before), CHECK((revoked_at IS NULL)=(revocation_reason IS NULL)),
 CHECK(certificate_kind<>'INVIZ_PRIVATE_X509' OR (certificate_pem IS NOT NULL AND certificate_sha256 IS NOT NULL))
);
CREATE TABLE certi.doctor_events (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, doctor_id uuid NOT NULL,
 event_type text NOT NULL, actor_ref text NOT NULL, evidence_ref text NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,doctor_id) REFERENCES certi.doctor_profiles(tenant_id,id)
);
ALTER TABLE certi.signatures DROP CONSTRAINT signatures_mode_check;
ALTER TABLE certi.signatures ADD CONSTRAINT signatures_mode_check CHECK(mode IN ('MOCK_NOT_VALID_FOR_CLINICAL_USE','CADES','OPENBAO_TRANSIT_ECDSA_P256','DEV_SIMULATED_ECDSA_P256'));
CREATE TABLE certi.signature_identity_evidence (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, signature_id uuid NOT NULL, doctor_id uuid NOT NULL,
 binding_id uuid NOT NULL, certificate_id uuid NOT NULL, approval_id uuid NOT NULL, audit_id uuid NOT NULL,
 key_ref text NOT NULL, key_version integer NOT NULL CHECK(key_version>0),
 report_hash text NOT NULL CHECK(report_hash ~ '^[0-9a-f]{64}$'), signed_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,signature_id),
 FOREIGN KEY(tenant_id,signature_id) REFERENCES certi.signatures(tenant_id,id),
 FOREIGN KEY(tenant_id,doctor_id,binding_id) REFERENCES certi.doctor_bindings(tenant_id,doctor_id,id),
 FOREIGN KEY(tenant_id,doctor_id,certificate_id) REFERENCES certi.doctor_certificates(tenant_id,doctor_id,id),
 FOREIGN KEY(tenant_id,approval_id) REFERENCES certi.approval_evidence(tenant_id,id),
 FOREIGN KEY(tenant_id,audit_id) REFERENCES certi.audit_events(tenant_id,id)
);
-- Transition invariant supplements application authorization; it is not an identity verifier.
CREATE FUNCTION certi.check_doctor_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'PENDING' THEN RAISE EXCEPTION 'doctor must start PENDING' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 IF NEW.state=OLD.state THEN RETURN NEW; END IF;
 IF NOT ((OLD.state='PENDING' AND NEW.state='IDENTITY_VERIFIED') OR
 (OLD.state='IDENTITY_VERIFIED' AND NEW.state='CONSENTED') OR
 (OLD.state='CONSENTED' AND NEW.state='ACTIVE') OR
 (OLD.state IN ('PENDING','IDENTITY_VERIFIED','CONSENTED','ACTIVE') AND NEW.state IN ('SUSPENDED','REVOKED')) OR
 (OLD.state='SUSPENDED' AND NEW.state='REVOKED')) THEN
  RAISE EXCEPTION 'invalid doctor state transition' USING ERRCODE='23514';
 END IF;
 IF NEW.state='IDENTITY_VERIFIED' AND (NOT EXISTS(SELECT 1 FROM certi.doctor_reviews WHERE tenant_id=NEW.tenant_id AND doctor_id=NEW.id AND outcome='VERIFIED') OR
 NOT EXISTS(SELECT 1 FROM certi.doctor_bindings WHERE tenant_id=NEW.tenant_id AND doctor_id=NEW.id)) THEN
  RAISE EXCEPTION 'separate administrator review and doctor authentication required' USING ERRCODE='23514';
 END IF;
 IF NEW.state='CONSENTED' AND NOT EXISTS(SELECT 1 FROM certi.doctor_consents WHERE tenant_id=NEW.tenant_id AND doctor_id=NEW.id) THEN
  RAISE EXCEPTION 'doctor consent required' USING ERRCODE='23514';
 END IF;
 IF NEW.state='ACTIVE' AND NOT EXISTS(SELECT 1 FROM certi.doctor_certificates WHERE tenant_id=NEW.tenant_id AND doctor_id=NEW.id AND revoked_at IS NULL AND now() BETWEEN not_before AND not_after) THEN
  RAISE EXCEPTION 'valid certificate required' USING ERRCODE='23514';
 END IF;
 NEW.authority_version=OLD.authority_version+1;
 RETURN NEW;
END $$;
CREATE TRIGGER doctor_transition BEFORE INSERT OR UPDATE ON certi.doctor_profiles FOR EACH ROW EXECUTE FUNCTION certi.check_doctor_transition();
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['doctor_profiles','doctor_reviews','doctor_invites','doctor_bindings','doctor_consents','doctor_keys','doctor_certificates','doctor_events','signature_identity_evidence'] LOOP
  EXECUTE format('ALTER TABLE certi.%I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE certi.%I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY tenant_isolation ON certi.%I TO certi_app USING (tenant_id=certi.current_tenant()) WITH CHECK (tenant_id=certi.current_tenant())',t);
  EXECUTE format('GRANT SELECT, INSERT ON certi.%I TO certi_app',t);
 END LOOP;
 FOREACH t IN ARRAY ARRAY['doctor_reviews','doctor_bindings','doctor_consents','doctor_keys','doctor_events','signature_identity_evidence'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON certi.%I FOR EACH ROW EXECUTE FUNCTION certi.reject_mutation()',t);
 END LOOP;
END $$;
GRANT UPDATE(state) ON certi.doctor_profiles TO certi_app;
GRANT UPDATE(consumed_at,revoked_at,attempts) ON certi.doctor_invites TO certi_app;
GRANT UPDATE(revoked_at,revocation_reason) ON certi.doctor_certificates TO certi_app;
REVOKE ALL ON FUNCTION certi.check_doctor_transition() FROM PUBLIC;