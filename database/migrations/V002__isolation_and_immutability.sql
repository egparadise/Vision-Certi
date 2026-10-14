-- NOLOGIN group role: provision real logins separately; never give application migration credentials.
CREATE ROLE certi_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA certi TO certi_app;
GRANT EXECUTE ON FUNCTION certi.current_tenant() TO certi_app;
GRANT SELECT ON certi.tenants TO certi_app;
ALTER TABLE certi.tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE certi.tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY own_tenant ON certi.tenants TO certi_app USING(id=certi.current_tenant());

DO $$
DECLARE t text;
BEGIN
 FOREACH t IN ARRAY ARRAY['memberships','tenant_policies','documents','document_versions','sign_requests',
 'approval_evidence','sign_jobs','signatures','evidence_packages','deliveries','delivery_attempts',
 'outbox_events','audit_events','audit_checkpoints','idempotency_records'] LOOP
  EXECUTE format('ALTER TABLE certi.%I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE certi.%I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY tenant_isolation ON certi.%I TO certi_app USING (tenant_id=certi.current_tenant()) WITH CHECK (tenant_id=certi.current_tenant())',t);
  EXECUTE format('GRANT SELECT, INSERT ON certi.%I TO certi_app',t);
 END LOOP;
END $$;
-- Immutable tables have no UPDATE/DELETE grant. Triggers also protect ordinary migration owner mistakes.
CREATE FUNCTION certi.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'immutable record: %', TG_TABLE_NAME USING ERRCODE='55000'; END $$;
DO $$
DECLARE t text;
BEGIN
 FOREACH t IN ARRAY ARRAY['document_versions','tenant_policies','approval_evidence','signatures',
 'evidence_packages','audit_events','audit_checkpoints','delivery_attempts'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON certi.%I FOR EACH ROW EXECUTE FUNCTION certi.reject_mutation()',t);
 END LOOP;
END $$;
GRANT UPDATE ON certi.memberships, certi.sign_requests, certi.sign_jobs,
 certi.deliveries, certi.outbox_events, certi.idempotency_records TO certi_app;
REVOKE ALL ON certi.identities FROM certi_app;
REVOKE ALL ON SCHEMA certi FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA certi FROM PUBLIC;
GRANT EXECUTE ON FUNCTION certi.current_tenant() TO certi_app;
