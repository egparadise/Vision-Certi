# Database foundation

V005 creates **certi_rt**, the PostgreSQL store used by the Java runtime. It persists doctors, certificate bindings, sessions, documents and bytes, sign requests, signatures, idempotency and audit evidence. Apply with a migration owner; run the application as a separate non-owner certi_app member. See [runtime setup](../apps/backend/docs/PRODUCTION_RUNTIME.md).

V001?V004 create the earlier **certi** planning schema. The RLS tests below cover that planning schema, not certi_rt. Runtime tenant isolation is application-enforced; certi_rt has no RLS. Backend PostgreSQL tests cover runtime persistence, concurrency, immutability and authorization. The SV70 MySQL/MariaDB migration is separate: [001](sv70/001_vision_certi_integration.sql).

The SQL files follow Flyway naming. The development Compose uses PostgreSQL entrypoint initialization on an empty volume; it is **not** a migration runner for existing databases. Never delete an existing database volume to apply changes. Use Flyway and a reviewed migration account for subsequent versions.

## Local validation

In PowerShell, set a synthetic local password and start the loopback-only database:

```powershell
$env:CERTI_DEV_DB_PASSWORD = 'local-test-only-change-me'
docker compose -f infra/compose.yaml up -d --wait
docker compose -f infra/compose.yaml exec -T postgres psql -U certi_migration -d vision_certi -f /tests/isolation.sql
```

`certi_migration` is the development migration superuser, never an application credential. `certi_app` is a NOLOGIN application group. Production provisioning must create separate least-privilege logins and further split Approval/Worker roles. RLS relies on trusted middleware selecting the authenticated tenant; it does not defend against a compromised DB role freely setting arbitrary tenant context.

No-tenant queries return no rows. Malformed tenant context errors instead of opening access. Each application transaction must set the tenant locally. Cross-tenant references include tenant columns. The test uses synthetic data and rolls it back.

When Docker is unavailable, the same migration and isolation SQL can be executed against [PGlite's WASM PostgreSQL engine](https://pglite.dev/docs/) with `npm ci --prefix tests/database` then `npm test --prefix tests/database`. The report records the actual engine version. This verifies SQL behavior, not PostgreSQL 18 deployment, concurrent worker behavior, backups or production performance.

Immutability triggers do not stop a DB superuser or a role that can disable them. Independent WORM copies and audit checkpoints are required for that threat. The schema stores audit hashes but does not calculate or externally anchor them yet.
