"""Idempotent bring-up of the local INVIZ test stack for the Saintview PACS AI integration (same PC, test CA only).

Safe to run any time (e.g. from Saintview's start_saintview.bat): only missing pieces are started.
  1. PostgreSQL 15432            (infra/local_database.py postgres)
  2. OpenBao 18200, unsealed     (infra/openbao/server_persistent_local.py)
  3. OpenBao service tokens      — looked up for real; if expired (24h TTL) they are re-issued and the API restarted
  4. synthetic test CRL          — re-issued when it expires within 2 days (tests/refresh_test_crl.py)
  5. API 127.0.0.1:18082         (infra/run_backend.py --database --integration, test CA + CRL)
  6. LAN web https://<ip>:5184   (infra/start_lan_test.py) with VITE_PACS_ALLOWED_ORIGINS = Saintview portal origins
Long-running pieces are started detached (they survive this script). Logs: work/saintview-stack/*.log
"""
import argparse
import datetime
import json
import os
import pathlib
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
PY = ROOT / 'work/certificate-tools/Scripts/python.exe'
LOGS = ROOT / 'work/saintview-stack'
DETACHED = 0x00000008 | 0x00000200 | 0x08000000  # DETACHED_PROCESS | NEW_PROCESS_GROUP | NO_WINDOW
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def listening(host: str, port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex((host, port)) == 0


def wait_for(check, what: str, seconds: int = 90) -> None:
    for _ in range(seconds * 2):
        if check():
            return
        time.sleep(0.5)
    raise SystemExit(f'{what} did not become ready; see {LOGS}')


def detach(name: str, args: list, env=None) -> None:
    LOGS.mkdir(parents=True, exist_ok=True)
    log = open(LOGS / f'{name}.log', 'ab')
    subprocess.Popen([str(a) for a in args], cwd=ROOT, env=env, stdout=log, stderr=log,
                     stdin=subprocess.DEVNULL, creationflags=DETACHED, close_fds=True)


def http_json(url: str, token: str | None = None):
    headers = {'X-Vault-Token': token} if token else {}
    with _opener.open(urllib.request.Request(url, headers=headers), timeout=5) as r:
        return json.loads(r.read())


def api_ready() -> bool:
    try:
        return http_json('http://127.0.0.1:18082/v1/runtime/capabilities')['persistence']['state'] == 'READY'
    except (OSError, ValueError, KeyError):
        return False


def openbao_unsealed() -> bool:
    try:
        return http_json('http://127.0.0.1:18200/v1/sys/seal-status')['sealed'] is False
    except (OSError, ValueError, KeyError):
        return False


def _probe(token: str, method: str, path: str) -> int:
    request = urllib.request.Request('http://127.0.0.1:18200/v1/' + path, method=method,
                                     data=b'{"input":""}' if method == 'POST' else None,
                                     headers={'X-Vault-Token': token, 'Content-Type': 'application/json'})
    try:
        with _opener.open(request, timeout=5) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


def tokens_valid() -> bool:
    """The service policies deny lookup-self, so probe a key that never exists with each token's own capability:
    an expired/unauthorized token gets 403; a valid one gets 'not found' (no key is created, nothing is signed)."""
    try:
        secrets = json.loads((ROOT / 'work/openbao/runtime.secrets.json').read_text())
        probe = 'vc-doctor-saintview-token-probe-never-created'
        return (_probe(secrets['provisioningToken'], 'GET', f'transit/keys/{probe}') != 403
                and _probe(secrets['signingToken'], 'POST', f'transit/sign/{probe}') != 403)
    except (OSError, ValueError, KeyError):
        return False


def stop_port(port: int) -> None:
    out = subprocess.run(['netstat', '-ano'], capture_output=True, text=True).stdout
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[1].endswith(f':{port}') and parts[3] == 'LISTENING':
            subprocess.run(['taskkill', '/F', '/T', '/PID', parts[4]], capture_output=True)
    wait_for(lambda: not listening('127.0.0.1', port), f'port {port} release', 20)


def crl_expiring() -> bool:
    from cryptography import x509
    crl = x509.load_pem_x509_crl((ROOT / 'work/certificate-fixtures/ca.crl.pem').read_bytes())
    return crl.next_update_utc - datetime.datetime.now(datetime.timezone.utc) < datetime.timedelta(days=2)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--address', default='192.168.45.160', help='this PC LAN IPv4 (INVIZ web)')
    parser.add_argument('--pacs-origins', default=None,
                        help='comma list for VITE_PACS_ALLOWED_ORIGINS (default: SaintView dev and Tauri production origins)')
    args = parser.parse_args()
    origins = args.pacs_origins or ','.join([
        *(f'https://{h}:{p}' for h in ('localhost', '127.0.0.1', args.address) for p in (5174, 5175)),
        'http://localhost:1421',
        'http://tauri.localhost',
    ])
    report = {}

    if not listening('127.0.0.1', 15432):
        subprocess.run([str(PY), 'infra/local_database.py', 'postgres'], cwd=ROOT, check=True,
                       stdout=subprocess.DEVNULL)
        report['postgres'] = 'started'
    else:
        report['postgres'] = 'running'

    if not listening('127.0.0.1', 18200):
        detach('openbao', [PY, 'infra/openbao/server_persistent_local.py'])
        report['openbao'] = 'started'
    else:
        report['openbao'] = 'running'
    wait_for(openbao_unsealed, 'OpenBao (unsealed)')

    restart_api = False
    if not tokens_valid():
        subprocess.run([str(PY), 'infra/openbao/configure_existing_local.py'], cwd=ROOT, check=True,
                       stdout=subprocess.DEVNULL)
        report['openbaoTokens'] = 'reissued'
        restart_api = True
    else:
        report['openbaoTokens'] = 'valid'

    if crl_expiring():
        subprocess.run([str(PY), 'tests/refresh_test_crl.py'], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
        report['crl'] = 'refreshed'
    else:
        report['crl'] = 'fresh'

    if restart_api and listening('127.0.0.1', 18082):
        stop_port(18082)
    if not listening('127.0.0.1', 18082):
        detach('api', [PY, 'infra/run_backend.py', '--port', '18082', '--database', '--integration',
                       '--certificate-trust', 'work/certificate-fixtures/ca.pem',
                       '--certificate-crl', 'work/certificate-fixtures/ca.crl.pem'])
        report['api'] = 'restarted' if restart_api else 'started'
    else:
        report['api'] = 'running'
    wait_for(api_ready, 'INVIZ API 18082', 120)

    if not listening(args.address, 5184):
        detach('web', [PY, 'infra/start_lan_test.py', '--address', args.address],
               env=dict(os.environ, VITE_PACS_ALLOWED_ORIGINS=origins))
        report['web'] = 'started'
        wait_for(lambda: listening(args.address, 5184), 'INVIZ web 5184', 90)
    else:
        report['web'] = 'running (restart it to change VITE_PACS_ALLOWED_ORIGINS)'

    caps = http_json('http://127.0.0.1:18082/v1/runtime/capabilities')
    report['certificate'] = caps['doctorAuth']['pcCertificate']['crlState']
    report['signing'] = caps.get('signing', {}).get('available')
    report['url'] = f'https://{args.address}:5184'
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    sys.exit(main())
