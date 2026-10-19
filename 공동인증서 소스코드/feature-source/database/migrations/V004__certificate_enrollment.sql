-- External authentication certificates are NOT the INVIZ/OpenBao signing certificates.
-- Preserve doctor_certificates from V003 as the private signing registry.
ALTER TABLE certi.doctor_bindings DROP CONSTRAINT doctor_bindings_issuer_check;
ALTER TABLE certi.doctor_bindings DROP CONSTRAINT doctor_bindings_identity_mode_check;
ALTER TABLE certi.doctor_bindings DROP CONSTRAINT doctor_bindings_check;
ALTER TABLE certi.doctor_bindings ADD CONSTRAINT doctor_binding_method CHECK (
 (identity_mode='GOOGLE_OIDC' AND issuer='https://accounts.google.com') OR
 (identity_mode='MOCK_IDENTITY' AND issuer='urn:vision-certi:mock-identity') OR
 (identity_mode='PC_CERT' AND issuer LIKE 'urn:inviz:pc-ca:%') OR
 (identity_mode='MOBILE_CERT' AND issuer LIKE 'urn:inviz:mobile-provider:%'));
CREATE TABLE certi.doctor_auth_certificates (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, doctor_id uuid NOT NULL,
 certificate_type text NOT NULL CHECK(certificate_type IN ('PC_CERT','MOBILE_CERT')),
 provider text NOT NULL, issuer text, serial_number text,
 certificate_fingerprint text CHECK(certificate_fingerprint ~ '^[0-9a-f]{64}$'), public_certificate text,
 provider_subject text, previous_registration_id uuid,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACTIVE','REVOKED')),
 external_status text NOT NULL DEFAULT 'UNKNOWN' CHECK(external_status IN ('GOOD','REVOKED','UNKNOWN','STALE')),
 registered_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
 last_verified_at timestamptz, revoked_at timestamptz, revocation_reason text,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,doctor_id,id),
 FOREIGN KEY(tenant_id,doctor_id) REFERENCES certi.doctor_profiles(tenant_id,id),
 FOREIGN KEY(tenant_id,doctor_id,previous_registration_id) REFERENCES certi.doctor_auth_certificates(tenant_id,doctor_id,id),
 CHECK(expires_at>registered_at), CHECK((status='REVOKED')=(revoked_at IS NOT NULL)),
 CHECK((revoked_at IS NULL)=(revocation_reason IS NULL)),
 CHECK((certificate_type='PC_CERT' AND issuer IS NOT NULL AND serial_number IS NOT NULL AND certificate_fingerprint IS NOT NULL AND public_certificate IS NOT NULL AND provider_subject IS NULL) OR
 (certificate_type='MOBILE_CERT' AND provider_subject IS NOT NULL AND certificate_fingerprint IS NULL AND public_certificate IS NULL))
);
CREATE UNIQUE INDEX unique_active_pc_certificate ON certi.doctor_auth_certificates(certificate_fingerprint) WHERE certificate_type='PC_CERT' AND status<>'REVOKED';
CREATE UNIQUE INDEX unique_active_mobile_subject ON certi.doctor_auth_certificates(provider,provider_subject) WHERE certificate_type='MOBILE_CERT' AND status<>'REVOKED';
CREATE TABLE certi.certificate_challenges (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, doctor_id uuid NOT NULL,
 purpose text NOT NULL CHECK(purpose IN ('CERTIFICATE_ENROLLMENT','CERTIFICATE_LOGIN','CERTIFICATE_RENEWAL')),
 service_id text NOT NULL, nonce_digest text NOT NULL UNIQUE CHECK(nonce_digest ~ '^[0-9a-f]{64}$'),
 canonical_payload bytea NOT NULL, payload_sha256 text NOT NULL CHECK(payload_sha256 ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL, consumed_at timestamptz,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,doctor_id,id),
 FOREIGN KEY(tenant_id,doctor_id) REFERENCES certi.doctor_profiles(tenant_id,id), CHECK(expires_at>created_at)
);
CREATE TABLE certi.certificate_validation_evidence (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, doctor_id uuid NOT NULL, certificate_id uuid NOT NULL, challenge_id uuid NOT NULL,
 proof_ref text NOT NULL, signature_algorithm text NOT NULL,
 proof_status text NOT NULL CHECK(proof_status IN ('VALID','INVALID')),
 chain_status text NOT NULL CHECK(chain_status IN ('TRUSTED','UNTRUSTED','UNKNOWN')),
 revocation_status text NOT NULL CHECK(revocation_status IN ('GOOD','REVOKED','UNKNOWN','STALE')),
 revocation_method text NOT NULL CHECK(revocation_method IN ('CRL','OCSP','PROVIDER','NONE')),
 revocation_evidence_ref text, this_update timestamptz, next_update timestamptz, produced_at timestamptz,
 validated_at timestamptz NOT NULL, trust_policy_version text NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,doctor_id,certificate_id,id),
 FOREIGN KEY(tenant_id,doctor_id,certificate_id) REFERENCES certi.doctor_auth_certificates(tenant_id,doctor_id,id),
 FOREIGN KEY(tenant_id,doctor_id,challenge_id) REFERENCES certi.certificate_challenges(tenant_id,doctor_id,id),
 CHECK(revocation_status<>'GOOD' OR (revocation_evidence_ref IS NOT NULL AND next_update>validated_at AND this_update<=validated_at))
);
CREATE TABLE certi.certificate_binding_reviews (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, doctor_id uuid NOT NULL, certificate_id uuid NOT NULL, validation_id uuid NOT NULL,
 reviewer_id uuid NOT NULL REFERENCES certi.identities(id), identity_license_evidence_ref text NOT NULL,
 outcome text NOT NULL CHECK(outcome IN ('VERIFIED','REJECTED')), reviewed_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,doctor_id,certificate_id,validation_id) REFERENCES certi.certificate_validation_evidence(tenant_id,doctor_id,certificate_id,id)
);
CREATE TABLE certi.mobile_auth_transactions (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, doctor_id uuid NOT NULL, provider text NOT NULL,
 purpose text NOT NULL CHECK(purpose IN ('DOCTOR_ENROLLMENT','DOCTOR_LOGIN','CERTIFICATE_RENEWAL')),
 provider_transaction_id text, nonce_digest text NOT NULL UNIQUE CHECK(nonce_digest ~ '^[0-9a-f]{64}$'),
 callback_config_id text NOT NULL, state text NOT NULL CHECK(state IN ('PENDING','VERIFIED','CONSUMED','FAILED','EXPIRED')),
 expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 result_evidence_ref text, provider_subject text, authenticated_at timestamptz,
 UNIQUE(tenant_id,id), UNIQUE(provider,provider_transaction_id),
 FOREIGN KEY(tenant_id,doctor_id) REFERENCES certi.doctor_profiles(tenant_id,id),
 CHECK(expires_at>created_at), CHECK(state NOT IN ('VERIFIED','CONSUMED') OR (result_evidence_ref IS NOT NULL AND provider_subject IS NOT NULL AND authenticated_at IS NOT NULL))
);
CREATE FUNCTION certi.check_auth_certificate_activation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' AND NEW.status<>'PENDING' THEN
  RAISE EXCEPTION 'certificate must start PENDING' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND OLD.status='REVOKED' AND NEW.status<>'REVOKED' THEN
  RAISE EXCEPTION 'revoked registration cannot reactivate; enroll a new registration' USING ERRCODE='23514';
 END IF;
 IF NEW.status='ACTIVE' AND (TG_OP='INSERT' OR OLD.status<>NEW.status) THEN
  IF NEW.external_status<>'GOOD' OR NEW.expires_at<=now() OR NOT EXISTS(
   SELECT 1 FROM certi.certificate_validation_evidence e JOIN certi.certificate_binding_reviews r
    ON r.tenant_id=e.tenant_id AND r.validation_id=e.id
   WHERE e.tenant_id=NEW.tenant_id AND e.certificate_id=NEW.id AND e.doctor_id=NEW.doctor_id
   AND e.proof_status='VALID' AND e.chain_status='TRUSTED' AND e.revocation_status='GOOD' AND e.next_update>now()
   AND r.outcome='VERIFIED') OR NOT EXISTS(
   SELECT 1 FROM certi.doctor_consents WHERE tenant_id=NEW.tenant_id AND doctor_id=NEW.doctor_id)
  THEN RAISE EXCEPTION 'proof, trust, fresh revocation evidence, independent review and doctor consent required' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER auth_certificate_activation BEFORE INSERT OR UPDATE ON certi.doctor_auth_certificates FOR EACH ROW EXECUTE FUNCTION certi.check_auth_certificate_activation();
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['doctor_auth_certificates','certificate_challenges','certificate_validation_evidence','certificate_binding_reviews','mobile_auth_transactions'] LOOP
  EXECUTE format('ALTER TABLE certi.%I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE certi.%I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY tenant_isolation ON certi.%I TO certi_app USING (tenant_id=certi.current_tenant()) WITH CHECK (tenant_id=certi.current_tenant())',t);
  EXECUTE format('GRANT SELECT, INSERT ON certi.%I TO certi_app',t);
 END LOOP;
 FOREACH t IN ARRAY ARRAY['certificate_validation_evidence','certificate_binding_reviews'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON certi.%I FOR EACH ROW EXECUTE FUNCTION certi.reject_mutation()',t);
 END LOOP;
END $$;
GRANT UPDATE(status,external_status,last_verified_at,revoked_at,revocation_reason) ON certi.doctor_auth_certificates TO certi_app;
GRANT UPDATE(consumed_at) ON certi.certificate_challenges TO certi_app;
GRANT UPDATE(state,provider_transaction_id,result_evidence_ref,provider_subject,authenticated_at) ON certi.mobile_auth_transactions TO certi_app;
REVOKE ALL ON FUNCTION certi.check_auth_certificate_activation() FROM PUBLIC;
-- The default application policy is CERTIFICATE_REQUIRED, so DB activation enforces both tiers.
CREATE FUNCTION certi.check_doctor_external_activation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.state='ACTIVE' AND OLD.state<>'ACTIVE' AND NOT EXISTS(
  SELECT 1 FROM certi.doctor_auth_certificates
  WHERE tenant_id=NEW.tenant_id AND doctor_id=NEW.id
  AND status='ACTIVE' AND external_status='GOOD' AND expires_at>now()
 ) THEN
  RAISE EXCEPTION 'active external authentication certificate required' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER doctor_external_activation BEFORE UPDATE OF state ON certi.doctor_profiles
 FOR EACH ROW EXECUTE FUNCTION certi.check_doctor_external_activation();
REVOKE ALL ON FUNCTION certi.check_doctor_external_activation() FROM PUBLIC;

