"""Run reviewed PACS DDL against the isolated REAL MariaDB, never the handover's DB.

Run with work/certificate-tools/Scripts/python.exe. Credentials are read locally and never printed.
Test rows are synthetic and intentionally retained: append-only storage must not permit cleanup deletes.
"""
import hashlib
import json
from pathlib import Path
import secrets
import uuid

import pymysql

ROOT = Path(__file__).resolve().parents[1]
cfg = json.loads((ROOT/'work/sv70-mariadb/test-connection.json').read_text())
assert (cfg['host'],cfg['port'],cfg['database']) == ('127.0.0.1',13316,'sv70_certi_test')
args = {k:cfg[k] for k in ('host','port','user','database')}
args.update(password=Path(cfg['passwordFile']).read_text().strip(),charset='utf8mb4',autocommit=True)
connection=pymysql.connect(**args)
checks=[]


def statements(source):
    delimiter=';'
    buffer=[]
    for line in source.splitlines():
        if line.startswith('--') or not line.strip():
            continue
        if line.startswith('DELIMITER '):
            assert not buffer
            delimiter=line.split()[1]
            continue
        buffer.append(line)
        if line.rstrip().endswith(delimiter):
            yield '\n'.join(buffer).rstrip()[:-len(delimiter)]
            buffer=[]
    assert not buffer


def blocked(cursor,sql,params=()):
    try:
        cursor.execute(sql,params)
    except pymysql.MySQLError as error:
        # Not permission/connection/syntax/missing-table errors masquerading as a passing test.
        assert error.args[0] in (1062,1452,1644,4025,3819), error
        return
    raise AssertionError('Database unexpectedly accepted prohibited mutation')


with connection.cursor() as c:
    c.execute("CREATE TABLE IF NOT EXISTS vc_test_migrations (name VARCHAR(100) PRIMARY KEY,sha256 CHAR(64) NOT NULL)")
    migration=ROOT/'database/sv70/001_vision_certi_integration.sql'
    digest=hashlib.sha256(migration.read_bytes()).hexdigest()
    c.execute('SELECT sha256 FROM vc_test_migrations WHERE name=%s',(migration.name,))
    old=c.fetchone()
    if old:
        assert old[0]==digest,'Applied migration was changed; create a new migration rather than overwrite it'
    else:
        # Recover an EMPTY partial DDL application in this fixed disposable test DB only.
        tables=['pacs_vc_audit','pacs_vc_report_approval','pacs_vc_session','pacs_vc_doctor_link']
        existing=[]
        for table in tables:
            c.execute('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s AND table_name=%s',(cfg['database'],table))
            if c.fetchone()[0]:
                c.execute('SELECT COUNT(*) FROM '+table)
                assert c.fetchone()[0]==0,'Partial migration has data; manual recovery required'
                existing.append(table)
        for table in existing:
            c.execute('DROP TABLE '+table)
        for statement in statements(migration.read_text(encoding='utf8')):
            c.execute(statement)
        c.execute('INSERT INTO vc_test_migrations VALUES (%s,%s)',(migration.name,digest))
    checks.append('reviewed migration applied to actual MariaDB')
    number=secrets.randbelow(1_000_000_000)+1
    tenant='synthetic-'+uuid.uuid4().hex[:8]
    doctor=str(uuid.uuid4())
    c.execute("""INSERT INTO pacs_vc_doctor_link(user_idx,vc_tenant_id,vc_doctor_id,status,active_user_idx,
      active_doctor_key,evidence_ref,created_by_user_idx,created_at)
      VALUES (%s,%s,%s,'ACTIVE',%s,%s,'synthetic/verified-evidence',1,UTC_TIMESTAMP(6))""",
      (number,tenant,doctor,number,tenant+':'+doctor))
    link=c.lastrowid
    blocked(c,"INSERT INTO pacs_vc_doctor_link(user_idx,vc_tenant_id,vc_doctor_id,status,active_user_idx,active_doctor_key,evidence_ref,created_by_user_idx,created_at) SELECT user_idx,vc_tenant_id,vc_doctor_id,status,active_user_idx,active_doctor_key,evidence_ref,created_by_user_idx,created_at FROM pacs_vc_doctor_link WHERE link_idx=%s",(link,))
    blocked(c,'UPDATE pacs_vc_doctor_link SET vc_doctor_id=%s WHERE link_idx=%s',(str(uuid.uuid4()),link))
    checks.append('duplicate active account mapping and identity substitution rejected')
    token_hash=hashlib.sha256(secrets.token_bytes(32)).hexdigest()
    c.execute("""INSERT INTO pacs_vc_session(token_sha256,user_idx,link_idx,vc_tenant_id,vc_doctor_id,
     vc_enrollment_id,vc_certificate_fingerprint,vc_identity_mode,vc_authenticated_at,vc_session_expires_at,
     created_at,expires_at) VALUES(%s,%s,%s,%s,%s,%s,%s,'PC_CERTIFICATE',UTC_TIMESTAMP(6),
     DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 60 MINUTE),UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 MINUTE))""",
     (token_hash,number,link,tenant,doctor,str(uuid.uuid4()),'a'*64))
    session=c.lastrowid
    blocked(c,'UPDATE pacs_vc_session SET expires_at=DATE_ADD(created_at,INTERVAL 61 MINUTE) WHERE session_idx=%s',(session,))
    checks.append('PACS session cannot outlive certificate session or one hour')
    snapshot=json.dumps({'schema':'synthetic-database-test/v1','reading':'합성 판독문 — 변경 금지','revision':1},ensure_ascii=False,separators=(',',':'))
    sha=hashlib.sha256(snapshot.encode()).hexdigest()
    approval=str(uuid.uuid4())
    c.execute("""INSERT INTO pacs_vc_report_approval(approval_id,study_idx,report_idx,revision,target_status,
      user_idx,link_idx,session_idx,vc_tenant_id,vc_doctor_id,content_sha256,snapshot_json,snapshot_sha256,
      state,created_at,updated_at) VALUES(%s,%s,%s,1,'A',%s,%s,%s,%s,%s,%s,%s,%s,
      'PREPARING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))""",
      (approval,number,number,number,link,session,tenant,doctor,sha,snapshot,sha))
    blocked(c,"UPDATE pacs_vc_report_approval SET snapshot_json='{}',snapshot_sha256=SHA2('{}',256) WHERE approval_id=%s",(approval,))
    blocked(c,"UPDATE pacs_vc_report_approval SET state='APPROVED',committed_at=UTC_TIMESTAMP(6) WHERE approval_id=%s",(approval,))
    checks.append('snapshot mutation and approval without signature evidence rejected')
    # Structural test only: signature cryptography is separately verified by the integration service tests.
    c.execute("""UPDATE pacs_vc_report_approval SET state='SIGNED_REMOTE',vc_sign_request_id=%s,
      vc_document_version_id=%s,vc_signature_id=%s,vc_signature_value='synthetic-structural-test',
      vc_signed_at=UTC_TIMESTAMP(6),vc_evidence_json='{"synthetic":true}' WHERE approval_id=%s""",
      (str(uuid.uuid4()),str(uuid.uuid4()),str(uuid.uuid4()),approval))
    blocked(c,"UPDATE pacs_vc_report_approval SET vc_signature_value='replaced' WHERE approval_id=%s",(approval,))
    blocked(c,'DELETE FROM pacs_vc_report_approval WHERE approval_id=%s',(approval,))
    checks.append('signature evidence is write-once and approval history cannot be deleted')
    c.execute("INSERT INTO pacs_vc_audit(at,action,target_type,target_id,outcome) VALUES(UTC_TIMESTAMP(6),'SYNTHETIC_TEST','VC_APPROVAL',%s,'SUCCESS')",(approval,))
    audit=c.lastrowid
    blocked(c,"UPDATE pacs_vc_audit SET outcome='ERROR' WHERE audit_idx=%s",(audit,))
    blocked(c,'DELETE FROM pacs_vc_audit WHERE audit_idx=%s',(audit,))
    checks.append('audit UPDATE and DELETE rejected by database')
connection.close()
with pymysql.connect(**args) as restarted_client:
    with restarted_client.cursor() as c:
        c.execute('SELECT snapshot_json,snapshot_sha256,vc_signature_value FROM pacs_vc_report_approval WHERE approval_id=%s',(approval,))
        stored=c.fetchone()
        assert stored==(snapshot,sha,'synthetic-structural-test')
checks.append('committed UTF-8 snapshot/evidence survives connection teardown and reopen')
result={'result':'PASS','engine':'REAL_MARIADB_12','checks':checks,'syntheticData':True,'approvalId':approval}
(ROOT/'work/sv70-database-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps({'result':'PASS','checks':checks},ensure_ascii=False))
