"""Loopback-only OpenBao development server. Keep this process alive; tokens never printed."""
import os, secrets, subprocess, pathlib, socket
root=pathlib.Path(__file__).resolve().parents[2]
runtime=root/'work/openbao'
probe=socket.socket(); probe.settimeout(1)
if probe.connect_ex(('127.0.0.1',18200)) == 0: raise SystemExit('Port 18200 already in use; preserving existing server and logs')
probe.close()
env={k.upper():v for k,v in os.environ.items()}; env['BAO_DEV_ROOT_TOKEN_ID']=secrets.token_urlsafe(32)
with (runtime/'server.stdout.log').open('wb') as out, (runtime/'server.stderr.log').open('wb') as err:
 child=subprocess.Popen([str(runtime/'bin/bao.exe'),'server','-dev','-dev-listen-address=127.0.0.1:18200'],env=env,stdout=out,stderr=err,stdin=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW)
 (runtime/'server.pid').write_text(str(child.pid))
 print(f'Local OpenBao PID {child.pid}, address 127.0.0.1:18200; development only',flush=True)
 try: child.wait()
 except KeyboardInterrupt: child.terminate(); child.wait()