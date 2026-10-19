import forge from 'korea-forge';
import type { CertificateFiles } from './certificateFileSigner';

export type CertificateMedium = 'hard-disk' | 'removable-disk';
export type ReadableDirectoryHandle = FileSystemDirectoryHandle & {
  entries(): AsyncIterableIterator<[string, FileSystemHandle]>;
  queryPermission(descriptor?: { mode: 'read' }): Promise<PermissionState>;
  requestPermission(descriptor?: { mode: 'read' }): Promise<PermissionState>;
};

export interface DiscoveredCertificate {
  id: string;
  kind: 'NPKI' | 'PFX';
  name: string;
  issuer: string;
  expiresAt?: Date;
  path: string;
  getFiles(): Promise<CertificateFiles>;
}

const DB_NAME = 'vision-certi-certificate-locations';
const STORE_NAME = 'read-only-directory-handles';
const MAX_DEPTH = 16;
const MAX_ENTRIES = 30000;
const MAX_RESULTS = 200;

export interface CertificateScanOptions {
  onFound?: (certificate: DiscoveredCertificate) => void;
  onLimit?: () => void;
  shouldStop?: () => boolean;
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (!globalThis.indexedDB) return reject(new Error('이 브라우저는 인증서 폴더 기억 기능을 지원하지 않습니다.'));
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => { request.result.createObjectStore(STORE_NAME); };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export async function getRememberedDirectory(medium: CertificateMedium): Promise<ReadableDirectoryHandle | undefined> {
  const db = await openDatabase();
  try {
    return await new Promise((resolve, reject) => {
      const request = db.transaction(STORE_NAME, 'readonly').objectStore(STORE_NAME).get(medium);
      request.onsuccess = () => resolve(request.result as ReadableDirectoryHandle | undefined);
      request.onerror = () => reject(request.error);
    });
  } finally { db.close(); }
}

export async function rememberDirectory(medium: CertificateMedium, handle: ReadableDirectoryHandle): Promise<void> {
  const db = await openDatabase();
  try {
    await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction(STORE_NAME, 'readwrite');
      transaction.objectStore(STORE_NAME).put(handle, medium);
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error);
    });
  } finally { db.close(); }
}

function binary(data: Uint8Array): string {
  let output = '';
  for (let offset = 0; offset < data.length; offset += 8192) output += String.fromCharCode(...data.subarray(offset, offset + 8192));
  return output;
}

function directoryPriority(name: string): number {
  const value = name.toLowerCase();
  if (value === 'npki') return 0;
  if (value === 'appdata' || value === 'locallow' || value === 'local' || value === 'roaming') return 1;
  if (value === 'users' || value === 'user') return 2;
  if (value === 'documents' || value === 'desktop' || value === 'downloads') return 3;
  return 4;
}

function displayField(field: { value?: unknown; valueTagClass?: number } | undefined, fallback: string): string {
  if (typeof field?.value !== 'string') return fallback;
  const raw = field.value;
  try {
    if (field.valueTagClass === forge.asn1.Type.UTF8) {
      return new TextDecoder('utf-8', { fatal: true }).decode(Uint8Array.from(raw, char => char.charCodeAt(0)));
    }
    if (field.valueTagClass === forge.asn1.Type.BMPSTRING) {
      return new TextDecoder('utf-16be', { fatal: true }).decode(Uint8Array.from(raw, char => char.charCodeAt(0)));
    }
  } catch { return fallback; }
  return raw || fallback;
}

async function certificateDetails(file: File): Promise<{ name: string; issuer: string; expiresAt: Date } | undefined> {
  if (file.size < 1 || file.size > 128 * 1024) return undefined;
  try {
    const cert = forge.pki.certificateFromAsn1(forge.asn1.fromDer(binary(new Uint8Array(await file.arrayBuffer()))));
    return {
      name: displayField(cert.subject.getField('CN'), file.name),
      issuer: displayField(cert.issuer.getField('O') ?? cert.issuer.getField('CN'), '발급기관 미표시'),
      expiresAt: cert.validity.notAfter,
    };
  } catch { return undefined; }
}

/** Enumerates only the directory explicitly authorized by the doctor; private keys are never read here. */
export async function scanCertificateDirectory(root: ReadableDirectoryHandle, medium: CertificateMedium,
  options: CertificateScanOptions = {}): Promise<DiscoveredCertificate[]> {
  const results: DiscoveredCertificate[] = [];
  let visited = 0;
  let limited = false;
  async function visit(directory: ReadableDirectoryHandle, path: string, depth: number): Promise<void> {
    if (options.shouldStop?.()) return;
    if (depth > MAX_DEPTH || visited >= MAX_ENTRIES || results.length >= MAX_RESULTS) {
      limited = true;
      return;
    }
    const files = new Map<string, FileSystemFileHandle>();
    const children: Array<[string, ReadableDirectoryHandle]> = [];
    for await (const [name, handle] of directory.entries()) {
      if (options.shouldStop?.()) return;
      if (++visited > MAX_ENTRIES) { limited = true; break; }
      if (handle.kind === 'file') files.set(name.toLowerCase(), handle as FileSystemFileHandle);
      else if (depth < MAX_DEPTH) children.push([name, handle as ReadableDirectoryHandle]);
      else limited = true;
    }
    const cert = files.get('signcert.der');
    const key = files.get('signpri.key');
    if (cert && key) {
      const details = await certificateDetails(await cert.getFile());
      if (details) {
        const chain = files.get('cacert.der');
        const found: DiscoveredCertificate = { id: `${medium}:${path}:NPKI`, kind: 'NPKI', path, ...details,
          getFiles: async () => ({ kind: 'NPKI', certificate: await cert.getFile(), privateKey: await key.getFile(),
            chain: chain ? await chain.getFile() : undefined }) };
        results.push(found);
        options.onFound?.(found);
      }
    }
    for (const [name, handle] of files) {
      if (results.length >= MAX_RESULTS) { limited = true; break; }
      if (!/\.(pfx|p12)$/.test(name)) continue;
      const filePath = path ? `${path}/${name}` : name;
      const found: DiscoveredCertificate = { id: `${medium}:${filePath}:PFX`, kind: 'PFX', path: filePath,
        name, issuer: '비밀번호 입력 후 확인', getFiles: async () => ({ kind: 'PFX', bundle: await handle.getFile() }) };
      results.push(found);
      options.onFound?.(found);
    }
    children.sort(([left], [right]) => directoryPriority(left) - directoryPriority(right));
    for (const [name, child] of children) {
      if (options.shouldStop?.()) return;
      if (visited >= MAX_ENTRIES || results.length >= MAX_RESULTS) break;
      await visit(child, path ? `${path}/${name}` : name, depth + 1);
    }
  }
  await visit(root, '', 0);
  if (results.length >= MAX_RESULTS) limited = true;
  if (limited) options.onLimit?.();
  return results.sort((a, b) => a.name.localeCompare(b.name, 'ko'));
}
