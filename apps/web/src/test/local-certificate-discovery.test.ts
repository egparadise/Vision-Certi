import { generateKeyPairSync } from 'node:crypto';
import forge from 'korea-forge';
import { expect, it, vi } from 'vitest';
import { scanCertificateDirectory, type ReadableDirectoryHandle } from '../services/localCertificateDiscovery';

function file(name: string, content: Uint8Array): File {
  const result = new File([content.buffer.slice(content.byteOffset, content.byteOffset + content.byteLength) as ArrayBuffer], name);
  Object.defineProperty(result, 'arrayBuffer', { value: async () => content.buffer.slice(content.byteOffset, content.byteOffset + content.byteLength) });
  return result;
}

function fileHandle(name: string, content: Uint8Array, reads: string[]) {
  return { kind: 'file', name, getFile: async () => { reads.push(name); return file(name, content); } } as FileSystemFileHandle;
}

function directory(name: string, children: Record<string, FileSystemHandle>): ReadableDirectoryHandle {
  return {
    kind: 'directory', name,
    async *entries() { for (const entry of Object.entries(children)) yield entry; },
    queryPermission: async () => 'granted', requestPermission: async () => 'granted',
  } as ReadableDirectoryHandle;
}

it('finds NPKI pairs and PFX files in an authorized tree without opening private keys during listing', async () => {
  const pair = generateKeyPairSync('rsa', { modulusLength: 1024 });
  const key = forge.pki.privateKeyFromPem(pair.privateKey.export({ type: 'pkcs1', format: 'pem' }).toString());
  const certificate = forge.pki.createCertificate();
  certificate.publicKey = forge.pki.publicKeyFromPem(pair.publicKey.export({ type: 'spki', format: 'pem' }).toString());
  certificate.serialNumber = '01';
  certificate.validity.notBefore = new Date('2026-01-01T00:00:00Z');
  certificate.validity.notAfter = new Date('2027-05-11T00:00:00Z');
  certificate.setSubject([{ name: 'commonName', value: '박성철', valueTagClass: forge.asn1.Type.UTF8 as unknown as forge.asn1.Class }]);
  certificate.setIssuer([{ name: 'organizationName', value: '테스트 인증기관', valueTagClass: forge.asn1.Type.UTF8 as unknown as forge.asn1.Class }]);
  certificate.sign(key, forge.md.sha256.create());
  const der = Uint8Array.from(forge.asn1.toDer(forge.pki.certificateToAsn1(certificate)).getBytes(), char => char.charCodeAt(0));
  const reads: string[] = [];
  const certFolder = directory('doctor', {
    'signCert.der': fileHandle('signCert.der', der, reads),
    'signPri.key': fileHandle('signPri.key', new Uint8Array([1, 2, 3]), reads),
  });
  const root = directory('NPKI', {
    KICA: directory('KICA', { USER: directory('USER', { doctor: certFolder }) }),
    'backup.p12': fileHandle('backup.p12', new Uint8Array([1, 2]), reads),
    invalid: directory('invalid', { 'signCert.der': fileHandle('signCert.der', new Uint8Array([0]), reads),
      'signPri.key': fileHandle('signPri.key', new Uint8Array([0]), reads) }),
  });

  const found = await scanCertificateDirectory(root, 'hard-disk');
  expect(found).toHaveLength(2);
  const npki = found.find(item => item.kind === 'NPKI')!;
  expect(npki).toMatchObject({ name: '박성철', issuer: '테스트 인증기관', path: 'KICA/USER/doctor' });
  expect(npki.expiresAt).toEqual(new Date('2027-05-11T00:00:00Z'));
  expect(reads).not.toContain('signPri.key');
  const files = await npki.getFiles();
  expect(files.kind).toBe('NPKI');
  expect(reads).toContain('signPri.key');
  expect(found.find(item => item.kind === 'PFX')?.path).toBe('backup.p12');
});

it('searches deeply nested folders and reports each certificate as it is found', async () => {
  const reads: string[] = [];
  let nested = directory('level-10', { 'doctor.p12': fileHandle('doctor.p12', new Uint8Array([1]), reads) });
  for (let depth = 9; depth >= 1; depth--) nested = directory(`level-${depth}`, { [`level-${depth + 1}`]: nested });
  const onFound = vi.fn();
  const found = await scanCertificateDirectory(nested, 'removable-disk', { onFound });
  expect(found).toHaveLength(1);
  expect(onFound).toHaveBeenCalledWith(found[0]);
  expect(found[0].path).toContain('doctor.p12');
  expect(reads).toEqual([]);
});

it('searches NPKI folders before unrelated directories so existing certificates appear first', async () => {
  const reads: string[] = [];
  const root = directory('selected', {
    archive: directory('archive', { 'old.p12': fileHandle('old.p12', new Uint8Array([1]), reads) }),
    NPKI: directory('NPKI', { 'doctor.p12': fileHandle('doctor.p12', new Uint8Array([1]), reads) }),
  });
  const order: string[] = [];
  await scanCertificateDirectory(root, 'hard-disk', { onFound: entry => order.push(entry.path) });
  expect(order).toEqual(['NPKI/doctor.p12', 'archive/old.p12']);
});
