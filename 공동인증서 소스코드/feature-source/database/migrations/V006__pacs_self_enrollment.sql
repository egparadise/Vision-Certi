-- Persistent, service-client/tenant/PACS-account scoped binding. It remains reserved after revocation.
BEGIN;
ALTER TABLE certi_rt.doctors ADD COLUMN pacs_account_key text UNIQUE;
UPDATE certi_rt.schema_info SET version = 6, applied_at = now() WHERE component = 'runtime' AND version = 5;
COMMIT;
