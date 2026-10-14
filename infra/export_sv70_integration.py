"""Export only changed integration source from the private handover, never its secrets/binaries.

The resulting overlay is version-bound by SHA-256 of every original file. Run after both agents settle.
"""
import difflib
import hashlib
import json
from pathlib import Path
import zipfile

ROOT=Path(__file__).resolve().parents[1]
HANDOVER=ROOT/'SaintViewPACS-handover-full-20260904/SaintViewPACS-handover-full-20260721'
OUT=ROOT/'integrations/sv70'
SPECS={
 'server':(HANDOVER/'05-server-source/extracted/webpacs_api',HANDOVER/'05-server-source/webpacs_api_source_20260721.zip','webpacs_api/',('app/','tests/vision_certi/','sql/2026-10-14_vision_certi','docs/VISION_CERTI')),
 'client':(HANDOVER/'01-client-source/extracted',HANDOVER/'01-client-source/SaintViewPACS-src-3.3.7-20260721.zip','',('src/',)),
}
TEXT={'.py','.ts','.tsx','.scss','.css','.json','.md','.sql'}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def export():
    entries=[]
    for component,(directory,archive,prefix,allowed) in SPECS.items():
        patches=[]
        with zipfile.ZipFile(archive) as source:
            names=set(source.namelist())
            for file in sorted(directory.rglob('*')):
                if not file.is_file() or file.suffix not in TEXT:
                    continue
                rel=file.relative_to(directory).as_posix()
                if not rel.startswith(allowed) or any(p in file.parts for p in ('node_modules','__pycache__','.git')):
                    continue
                before=source.read(prefix+rel) if prefix+rel in names else None
                after=file.read_bytes()
                # Ignore newline-only differences from extraction/editor settings.
                if before is not None and before.replace(b'\r\n',b'\n')==after.replace(b'\r\n',b'\n'):
                    continue
                target=OUT/component/rel
                target.parent.mkdir(parents=True,exist_ok=True)
                target.write_bytes(after)
                entries.append({'component':component,'path':rel,'originalSha256':digest(before) if before is not None else None,
                                'originalNormalizedSha256':digest(before.replace(b'\r\n',b'\n')) if before is not None else None,
                                'sha256':digest(after)})
                patches.extend(difflib.unified_diff((before or b'').decode('utf8').splitlines(True),after.decode('utf8').splitlines(True),
                               fromfile='a/'+rel if before is not None else '/dev/null',tofile='b/'+rel))
        (OUT/(component+'.patch')).write_text(''.join(patches),encoding='utf8')
    manifest={'format':'vision-certi-sv70-overlay/v1','baseline':'SaintViewPACS handover 2026-07-21 (3.3.7)',
              'files':entries,'excludes':['03-secrets','environment files','private keys','original source archives','clinical data']}
    (OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps({'exportedFiles':len(entries),'byComponent':{c:sum(x['component']==c for x in entries) for c in SPECS}}))


if __name__=='__main__':
    export()
