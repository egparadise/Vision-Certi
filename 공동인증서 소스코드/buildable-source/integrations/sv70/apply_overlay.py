"""Check/apply the reviewed SV70 integration overlay against its exact handover baseline.

Defaults to validation only. --apply writes after ALL files validate, preserving originals in a backup folder.
Never reads secrets, follows symlinks, applies SQL or starts the PACS server.
"""
import argparse
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import shutil

HERE=Path(__file__).resolve().parent
def digest(data): return hashlib.sha256(data).hexdigest()

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('component',choices=['server','client'])
    p.add_argument('target',type=Path)
    p.add_argument('--apply',action='store_true')
    args=p.parse_args()
    target=args.target.resolve(strict=True)
    marker=target/('app/main.py' if args.component=='server' else 'package.json')
    if not marker.is_file(): p.error('Target does not look like the expected PACS source root')
    manifest=json.loads((HERE/'manifest.json').read_text(encoding='utf8'))
    pending=[]
    for entry in manifest['files']:
        if entry['component']!=args.component: continue
        rel=Path(entry['path'])
        if rel.is_absolute() or '..' in rel.parts: raise ValueError('Unsafe manifest path')
        src=HERE/args.component/rel
        dst=target/rel
        if not dst.resolve().is_relative_to(target) or dst.is_symlink(): raise ValueError('Unsafe target path')
        data=src.read_bytes()
        if digest(data)!=entry['sha256']: raise ValueError('Overlay checksum mismatch: '+entry['path'])
        if dst.exists():
            current=dst.read_bytes()
            if digest(current)==entry['sha256']: continue  # already applied
            expected=entry['originalNormalizedSha256']
            prior=entry.get('upgradeFromSha256',[])
            if digest(current) not in prior and (expected is None or digest(current.replace(b'\r\n',b'\n'))!=expected):
                raise ValueError('Local changes/baseline mismatch, nothing written: '+entry['path'])
        elif entry['originalSha256'] is not None:
            raise ValueError('Missing baseline file: '+entry['path'])
        pending.append((src,dst,rel))
    if not args.apply:
        print(f'Validated {len(pending)} files; rerun with --apply to write.')
        return
    backup=target/('.vision-certi-backup-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    for src,dst,rel in pending:
        if dst.exists():
            saved=backup/rel
            saved.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(dst,saved)
        dst.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(src,dst)
    print(f'Applied {len(pending)} files. Original modified files: {backup}')

if __name__=='__main__': main()
