import { PGlite } from '@electric-sql/pglite';
import { readFile, readdir, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import assert from 'node:assert/strict';
import { verifyDoctorSchema } from './doctor-schema.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const db = new PGlite();
try {
  const version = (await db.query('select version() as version')).rows[0].version;
  const files = (await readdir(path.join(root, 'database/migrations'))).filter(f => f.endsWith('.sql')).sort();
  for (const file of files) {
    await db.exec(await readFile(path.join(root, 'database/migrations', file), 'utf8'));
  }
  const role = (await db.query("select rolsuper, rolbypassrls, rolcanlogin from pg_roles where rolname='certi_app'")).rows[0];
  assert.deepEqual(role, { rolsuper: false, rolbypassrls: false, rolcanlogin: false });
  const tables = (await db.query("select relname, relrowsecurity, relforcerowsecurity from pg_class c join pg_namespace n on c.relnamespace=n.oid where n.nspname='certi' and c.relkind='r'")).rows;
  for (const table of tables.filter(t => t.relname !== 'identities')) {
    assert.ok(table.relrowsecurity && table.relforcerowsecurity, `RLS missing: ${table.relname}`);
  }
  const sql = (await readFile(path.join(root,'database/tests/isolation.sql'),'utf8'))
    .split('\n').filter(line => !line.startsWith('\\')).join('\n');
  await db.exec(sql);
  const count = (await db.query('select count(*)::int as count from certi.tenants')).rows[0].count;
  assert.equal(count, 0, 'Fixture rollback failed');
  const doctorChecks = await verifyDoctorSchema(db);
  const result = { status:'PASS', engine:version, migrations:files,
    checks:['migration execution','nonprivileged NOLOGIN application role','FORCE RLS on tenant tables',
      'missing tenant fail closed','cross tenant read/write rejection','cross tenant composite FK rejection',
      'immutable version update/delete rejection','tenant context switching','fixture rollback', ...doctorChecks],
    limitation:'WASM PostgreSQL functional validation; not a Docker/PostgreSQL 18 deployment, concurrency, backup, or performance test.' };
  await mkdir(path.join(root,'work'),{recursive:true});
  await writeFile(path.join(root,'work/database-test-result.json'),JSON.stringify(result,null,2)+'\n');
  console.log(JSON.stringify(result,null,2));
} finally { await db.close(); }
