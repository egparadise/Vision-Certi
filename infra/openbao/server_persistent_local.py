"""Persistent, loopback-only OpenBao for local integration testing; not production deployment.

The bootstrap/unseal material stays in an OS-restricted ignored directory. No secrets are printed.
Existing ephemeral dev keys cannot be recovered by this launcher.
"""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'infra'))
from local_database import protect

base = ROOT/'work/openbao'
private = base/'private'
private.mkdir(exist_ok=True)
protect(private)
data = base/'persistent-data'
data.mkdir(exist_ok=True)
protect(data)
with socket.socket() as probe:
    probe.bind(('127.0.0.1', 18200))
config = base/'persistent-local.hcl'
config.write_text('ui = false\ndisable_mlock = true\n'
    'api_addr = "http://127.0.0.1:18200"\ncluster_addr = "http://127.0.0.1:18201"\n'
    'listener "tcp" {\n address = "127.0.0.1:18200"\n tls_disable = true\n}\n'
    'storage "raft" {\n path = "'+data.as_posix()+'"\n node_id = "vision-certi-local"\n}\n', encoding='utf8')
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(path, body=None):
    request = urllib.request.Request('http://127.0.0.1:18200/v1/'+path,
        data=json.dumps(body).encode() if body is not None else None, headers={'Content-Type':'application/json'})
    with opener.open(request,timeout=60 if path == 'sys/init' and body is not None else 10) as response:
        raw=response.read()
        return json.loads(raw) if raw else None

with (base/'persistent.stdout.log').open('ab') as out, (base/'persistent.stderr.log').open('ab') as err:
    child=subprocess.Popen([str(base/'bin/bao.exe'),'server','-config='+str(config)],
        stdout=out,stderr=err,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    (base/'server.pid').write_text(str(child.pid))
    try:
        for _ in range(100):
            if child.poll() is not None:
                raise RuntimeError('OpenBao stopped; inspect persistent.stderr.log')
            try:
                initialized=call('sys/init')['initialized']; break
            except OSError:
                time.sleep(.1)
        else: raise RuntimeError('OpenBao startup timed out')
        bootstrap_file=private/'bootstrap.json'
        if not initialized:
            if bootstrap_file.exists():
                raise RuntimeError('Existing bootstrap but no Raft state: restore data; do not overwrite keys')
            # One share is deliberately limited to isolated development, never a production custody policy.
            bootstrap=call('sys/init',{'secret_shares':1,'secret_threshold':1})
            bootstrap_file.write_text(json.dumps(bootstrap),encoding='utf8')
        bootstrap=json.loads(bootstrap_file.read_text(encoding='utf8'))
        if call('sys/seal-status')['sealed']:
            call('sys/unseal',{'key':bootstrap['keys_base64'][0]})
        print('Persistent local OpenBao ready on 127.0.0.1:18200 (Raft, test custody only).',flush=True)
        child.wait()
    finally:
        if child.poll() is None:
            child.terminate(); child.wait()
