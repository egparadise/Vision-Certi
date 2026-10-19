\set ON_ERROR_STOP on
-- Run as isolated development database owner. Fixture changes rollback.
BEGIN;
INSERT INTO certi.tenants(id,code,name,status) VALUES
('00000000-0000-0000-0000-000000000001','test-a','Synthetic A','ACTIVE'),
('00000000-0000-0000-0000-000000000002','test-b','Synthetic B','ACTIVE');
INSERT INTO certi.documents(id,tenant_id,connector_key,external_document_id,external_study_id,title) VALUES
('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','test','r1','s1','A'),
('10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000002','test','r2','s2','B');
INSERT INTO certi.document_versions(id,tenant_id,document_id,version_no,object_key,object_version,sha256,byte_length,content_type)
VALUES ('20000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',
'10000000-0000-0000-0000-000000000001',1,'test/a.pdf','1',repeat('a',64),100,'application/pdf');
SET LOCAL ROLE certi_app;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM certi.documents) THEN RAISE EXCEPTION 'missing tenant leaked rows'; END IF;
END $$;
SET LOCAL app.tenant_id='00000000-0000-0000-0000-000000000001';
DO $$ BEGIN
 IF (SELECT count(*) FROM certi.documents)<>1 THEN RAISE EXCEPTION 'tenant filter failed'; END IF;
 IF EXISTS(SELECT 1 FROM certi.documents WHERE title='B') THEN RAISE EXCEPTION 'cross tenant read'; END IF;
 BEGIN
  INSERT INTO certi.documents(id,tenant_id,connector_key,external_document_id,external_study_id,title)
  VALUES(gen_random_uuid(),'00000000-0000-0000-0000-000000000002','test','bad','bad','bad');
  RAISE EXCEPTION 'cross tenant insert allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN
  INSERT INTO certi.document_versions(id,tenant_id,document_id,version_no,object_key,object_version,sha256,byte_length,content_type)
  VALUES(gen_random_uuid(),'00000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000002',1,'bad','1',repeat('b',64),10,'application/pdf');
  RAISE EXCEPTION 'cross tenant FK allowed';
 EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 BEGIN
  UPDATE certi.document_versions SET object_key='tampered';
  RAISE EXCEPTION 'immutable update allowed';
 EXCEPTION WHEN insufficient_privilege OR object_not_in_prerequisite_state THEN NULL; END;
 BEGIN
  DELETE FROM certi.document_versions;
  RAISE EXCEPTION 'immutable delete allowed';
 EXCEPTION WHEN insufficient_privilege OR object_not_in_prerequisite_state THEN NULL; END;
END $$;
SET LOCAL app.tenant_id='00000000-0000-0000-0000-000000000002';
DO $$ BEGIN
 IF (SELECT count(*) FROM certi.documents WHERE title='B')<>1 THEN RAISE EXCEPTION 'tenant switch failed'; END IF;
 IF EXISTS(SELECT 1 FROM certi.document_versions) THEN RAISE EXCEPTION 'version leak'; END IF;
END $$;
ROLLBACK;
\echo 'PASS: missing tenant, tenant read/write, composite FK, immutable versions, context switch'
