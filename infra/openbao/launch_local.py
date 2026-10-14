"""Start loopback-only development Bao without a visible console or duplicate PATH keys."""
import os, subprocess, sys
from pathlib import Path
exe, runtime = sys.argv[1:]
env = {k.upper(): v for k, v in os.environ.items()}
with open(Path(runtime)/'server.stdout.log', 'wb') as out, open(Path(runtime)/'server.stderr.log', 'wb') as err:
    child = subprocess.Popen([str(Path(exe).resolve()), 'server', '-dev', '-dev-listen-address=127.0.0.1:18200'],
        env=env, stdout=out, stderr=err, stdin=subprocess.DEVNULL,
        creationflags=subprocess.CREATE_NO_WINDOW)
print(child.pid)