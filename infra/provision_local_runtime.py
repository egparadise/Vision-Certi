"""Provision a separate persistent local runtime DB, distinct from the destructive backend test DB.

Requires the isolated PostgreSQL from local_database.py and psycopg. Never contacts a production host.
Apply only after the runtime migration has settled. Repeated runs verify its checksum, never drop state.
"""
import hashlib
import json
from pathlib import Path
import secrets
import sys

import psycopg
from psycopg import sql

sys.path.insert(0,str(Path(__file__).resolve().parent))
from local_database import protect

ROOT=Path(__file__).resolve().parents[1]
cfg=json.loads((ROOT/'work/postgres/test-connection.json').read_text())
assert cfg['url']=='jdbc:postgresql://127.0.0.1:15432/vision_certi_test'
private=ROOT/'work/postgres/private'
owner=dict(host='127.0.0.1',port=15432,user=cfg['username'],password=Path(cfg['passwordFile']).read_text().strip())
password=private/'runtime.password'
if not password.exists(): password.write_text(secrets.token_hex(32),encoding='ascii')
protect(private)
with psycopg.connect(**owner,dbname='postgres',autocommit=True) as c:
    if not c.execute("SELECT 1 FROM pg_roles WHERE rolname='vc_local_runtime'").fetchone():
        c.execute(sql.SQL('CREATE ROLE vc_local_runtime LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD {}').format(sql.Literal(password.read_text().strip())))
    c.execute('GRANT certi_app TO vc_local_runtime')
    if not c.execute("SELECT 1 FROM pg_database WHERE datname='vision_certi_local'").fetchone():
        c.execute('CREATE DATABASE vision_certi_local')
migrations=[ROOT/'database/migrations'/name for name in (
    'V005__production_runtime.sql', 'V006__pacs_self_enrollment.sql', 'V007__physician_accounts.sql',
    'V008__admin_system_settings.sql', 'V009__medical_institutions.sql',
    'V010__pacs_certificate_proofs.sql')]
checksums={m.name:hashlib.sha256(m.read_bytes()).hexdigest() for m in migrations}
with psycopg.connect(**owner,dbname='vision_certi_local') as c:
    c.execute('CREATE TABLE IF NOT EXISTS public.vc_local_migrations(name text PRIMARY KEY,sha256 text NOT NULL)')
    for migration in migrations:
        checksum=checksums[migration.name]
        applied=c.execute('SELECT sha256 FROM public.vc_local_migrations WHERE name=%s',(migration.name,)).fetchone()
        if applied:
            assert applied[0]==checksum,'Applied runtime migration changed; deliberate upgrade required'
        else:
            current=c.execute("SELECT version FROM certi_rt.schema_info WHERE component='runtime'").fetchone() if migration.name != 'V005__production_runtime.sql' else None
            target=int(migration.name[1:4])
            if current and current[0] >= target:
                # A pre-ledger local upgrade may already have applied this migration. Check its unique table.
                expected={8:'system_settings',9:'medical_institutions',10:'pacs_certificate_proofs'}.get(target)
                if not expected or not c.execute('SELECT to_regclass(%s)',(f'certi_rt.{expected}',)).fetchone()[0]:
                    raise RuntimeError(f'Untracked migration {migration.name} needs manual review')
            else:
                c.execute(migration.read_text(encoding='utf8'))
            c.execute('INSERT INTO public.vc_local_migrations VALUES(%s,%s)',(migration.name,checksum))
    c.execute('REVOKE CREATE ON SCHEMA public FROM PUBLIC')
result={'url':'jdbc:postgresql://127.0.0.1:15432/vision_certi_local','username':'vc_local_runtime',
        'passwordFile':str(password),'migrationSha256':checksums[migrations[0].name], 'migrationChecksums':checksums}
(ROOT/'work/postgres/runtime-connection.json').write_text(json.dumps(result,indent=2),encoding='utf8')
secret=ROOT/'work/sv70-integration.secret'
if not secret.exists(): secret.write_text(secrets.token_hex(32),encoding='ascii')
print('Persistent local runtime provisioned; non-owner runtime account and file-based credentials ready.')
