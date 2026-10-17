-- Administrator-managed operational policy overrides (com.inviz.visioncerti.admin.SystemSettings). System-wide rows
-- (tenant_id NULL), one per setting key; deleting a row restores the deployment default. Changes are audited.
BEGIN;
DO $$ BEGIN
  IF (SELECT version FROM certi_rt.schema_info WHERE component = 'runtime') IS DISTINCT FROM 7 THEN
    RAISE EXCEPTION 'V008 requires runtime schema 7';
  END IF;
END $$;
CREATE TABLE certi_rt.system_settings (
  id uuid PRIMARY KEY, seq bigint GENERATED ALWAYS AS IDENTITY, tenant_id text,
  version bigint NOT NULL CHECK (version >= 1), payload jsonb NOT NULL,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, expires_at timestamptz,
  setting_key text UNIQUE NOT NULL
);
GRANT SELECT, INSERT, UPDATE, DELETE ON certi_rt.system_settings TO certi_app;
GRANT USAGE, SELECT ON SEQUENCE certi_rt.system_settings_seq_seq TO certi_app;
UPDATE certi_rt.schema_info SET version = 8, applied_at = now() WHERE component = 'runtime';
COMMIT;
