"""Check copied certificate-login files against COPY_MANIFEST.json."""

import hashlib
import json
from pathlib import Path


root = Path(__file__).resolve().parent
manifest = json.loads((root / "COPY_MANIFEST.json").read_text(encoding="utf-8"))
failures = []
for item in manifest["files"]:
    path = root / item["path"]
    if not path.is_file():
        failures.append(f"missing: {item['path']}")
    elif path.stat().st_size != item["size"]:
        failures.append(f"size changed: {item['path']}")
    elif hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
        failures.append(f"hash changed: {item['path']}")

if failures:
    print("Verification failed:")
    print("\n".join(failures))
    raise SystemExit(1)
print(f"Verified {len(manifest['files'])} copied files (SHA-256)")
