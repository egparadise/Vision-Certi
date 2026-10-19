"""Isolated real DBs for integration verification; never uses system database data/config.

PostgreSQL binaries: official EDB Windows archive in work/postgres/pgsql.
MariaDB binaries: existing installation, but a NEW data directory and loopback port.
Passwords live only in files under work/ and are never command-line arguments.
This is a local verification tool, not an unattended production deployment script.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
HIDDEN = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, creationflags=HIDDEN, **kwargs)


def protect(path):
    if os.name == 'nt':
        identity = subprocess.check_output(['whoami'], text=True).strip()
        run(['icacls', path, '/inheritance:r', '/grant:r', f'{identity}:(OI)(CI)F'], stdout=subprocess.DEVNULL)


def password_file(path):
    if not path.exists():
        path.write_text(secrets.token_hex(32), encoding='ascii')
    return path


def unused(port):
    with socket.socket() as sock:
        try:
            sock.bind(('127.0.0.1', port))
        except OSError:
            raise SystemExit(f'Port {port} is occupied; will not touch an unknown server.')


def postgres():
    base = ROOT / 'work/postgres'
    bins = base / 'pgsql/bin'
    data = base / 'data'
    unused(15432)
    private = base / 'private'
    private.mkdir(exist_ok=True)
    protect(private)
    pwfile = password_file(private / 'owner.password')
    if not (data / 'PG_VERSION').exists():
        run([bins / 'initdb.exe', '-D', data, '-U', 'vc_local_owner', '--pwfile', pwfile,
             '--auth-host=scram-sha-256', '--auth-local=scram-sha-256', '--encoding=UTF8', '--locale=C'])
        protect(data)
    log = open(base / 'server.log', 'ab')
    process = subprocess.Popen([str(bins / 'postgres.exe'), '-D', str(data), '-h', '127.0.0.1', '-p', '15432'],
                               stdout=log, stderr=log, creationflags=HIDDEN)
    (base / 'owned.pid').write_text(str(process.pid))
    for _ in range(100):
        if process.poll() is not None:
            raise SystemExit('Isolated PostgreSQL exited; inspect work/postgres/server.log')
        ready = subprocess.run([str(bins / 'pg_isready.exe'), '-h','127.0.0.1','-p','15432'],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=HIDDEN)
        if ready.returncode == 0:
            break
        time.sleep(.1)
    else:
        process.terminate()
        raise SystemExit('PostgreSQL startup timeout')
    env = dict(os.environ, PGPASSWORD=pwfile.read_text().strip())
    command = [bins / 'psql.exe', '-h', '127.0.0.1', '-p', '15432', '-U', 'vc_local_owner', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1']
    sql = "SELECT 1 FROM pg_database WHERE datname='vision_certi_test';"
    exists = subprocess.check_output([str(x) for x in command] + ['-Atc', sql], env=env, text=True).strip()
    if exists != '1':
        run(command + ['-c', 'CREATE DATABASE vision_certi_test;'], env=env)
    run(command + ['-c', "DO $$ BEGIN IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='certi_app') THEN CREATE ROLE certi_app NOLOGIN; END IF; END $$;"], env=env)
    config = {'url': 'jdbc:postgresql://127.0.0.1:15432/vision_certi_test',
              'username': 'vc_local_owner', 'passwordFile': str(pwfile), 'port': 15432}
    (base / 'test-connection.json').write_text(json.dumps(config, indent=2), encoding='utf8')
    print('PostgreSQL ready: isolated vision_certi_test on 127.0.0.1:15432; connection file work/postgres/test-connection.json', flush=True)
    try:
        process.wait()
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait()
        log.close()


def mariadb():
    base = ROOT / 'work/sv70-mariadb'
    data = base / 'data'
    bins = Path('C:/Program Files/MariaDB 12.0/bin')
    unused(13316)
    private = base / 'private'
    private.mkdir(exist_ok=True)
    protect(private)
    pwfile = password_file(private / 'owner.password')
    # The bootstrap file is restricted and used only by this isolated instance.
    bootstrap = private / 'bootstrap.sql'
    bootstrap.write_text("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + pwfile.read_text().strip() + "';\n"
                         "CREATE DATABASE IF NOT EXISTS sv70_certi_test CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;\n"
                         "CREATE USER IF NOT EXISTS 'vc_test'@'127.0.0.1' IDENTIFIED BY '" + pwfile.read_text().strip() + "';\n"
                         "GRANT ALL ON sv70_certi_test.* TO 'vc_test'@'127.0.0.1';\n", encoding='utf8')
    config = base / 'isolated.ini'
    config.write_text('[mysqld]\nbasedir=C:/Program Files/MariaDB 12.0\n'
                      f'datadir={data.as_posix()}\nport=13316\nbind-address=127.0.0.1\n'
                      'skip-name-resolve=1\ncharacter-set-server=utf8mb4\ncollation-server=utf8mb4_bin\n', encoding='utf8')
    log = open(base / 'server.log', 'ab')
    process = subprocess.Popen([str(bins / 'mariadbd.exe'), '--defaults-file=' + str(config),
                                '--init-file=' + str(bootstrap), '--console'], stdout=log, stderr=log, creationflags=HIDDEN)
    (base / 'owned.pid').write_text(str(process.pid))
    (base / 'test-connection.json').write_text(json.dumps({'host':'127.0.0.1','port':13316,
        'user':'vc_test','database':'sv70_certi_test','passwordFile':str(pwfile)}, indent=2))
    try:
        for _ in range(100):
            if process.poll() is not None:
                raise SystemExit('Isolated MariaDB exited; inspect work/sv70-mariadb/server.log')
            try:
                with socket.create_connection(('127.0.0.1',13316), timeout=.2):
                    break
            except OSError:
                time.sleep(.1)
        else:
            raise SystemExit('Isolated MariaDB startup timeout')
        # TCP listen starts BEFORE --init-file is read. Wait for SQL readiness before removing it.
        client = private / 'client.ini'
        client.write_text('[client]\nuser=vc_test\npassword=' + pwfile.read_text().strip()
                          + '\nhost=127.0.0.1\nport=13316\nprotocol=tcp\n', encoding='ascii')
        for _ in range(100):
            if process.poll() is not None:
                raise SystemExit('MariaDB exited during bootstrap')
            ready = subprocess.run([str(bins / 'mariadb.exe'), '--defaults-file='+str(client),
                                    '-Nse', 'SELECT 1'], stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL, creationflags=HIDDEN)
            if ready.returncode == 0:
                break
            time.sleep(.1)
        else:
            raise SystemExit('MariaDB SQL readiness timeout')
        bootstrap.unlink()
        print('MariaDB ready: isolated sv70_certi_test on 127.0.0.1:13316', flush=True)
        process.wait()
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait()
        log.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('engine', choices=['postgres','mariadb'])
    args = parser.parse_args()
    globals()[args.engine]()
