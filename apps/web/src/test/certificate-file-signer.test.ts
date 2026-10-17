import { beforeAll, afterAll, describe, expect, it, vi } from 'vitest';
import { generateKeyPairSync, createVerify, webcrypto } from 'node:crypto';
import forge from 'korea-forge';
import { PASSWORD_MISMATCH, signWithCertificateFiles } from '../services/certificateFileSigner';

function file(name: string, bytes: Uint8Array): File {
  const result = new File([bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer], name);
  Object.defineProperty(result, 'arrayBuffer', { value: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) });
  return result;
}

describe('doctor PC certificate file signing', () => {
  const password = 'correct-horse-2026';
  const payload = new TextEncoder().encode('vision-certi\0report-id\0exact-document-hash');
  let cert: forge.pki.Certificate;
  let key: forge.pki.rsa.PrivateKey;
  let certDer: Uint8Array;

  beforeAll(() => {
    vi.stubGlobal('crypto', webcrypto);
    const pair = generateKeyPairSync('rsa', { modulusLength: 2048 });
    key = forge.pki.privateKeyFromPem(pair.privateKey.export({ type: 'pkcs1', format: 'pem' }).toString());
    cert = forge.pki.createCertificate();
    cert.publicKey = forge.pki.publicKeyFromPem(pair.publicKey.export({ type: 'spki', format: 'pem' }).toString());
    cert.serialNumber = '01';
    cert.validity.notBefore = new Date('2026-01-01T00:00:00Z');
    cert.validity.notAfter = new Date('2027-01-01T00:00:00Z');
    cert.setSubject([{ name: 'commonName', value: 'Test Doctor' }]);
    cert.setIssuer([{ name: 'commonName', value: 'Test Doctor' }]);
    cert.sign(key, forge.md.sha256.create());
    certDer = Uint8Array.from(forge.asn1.toDer(forge.pki.certificateToAsn1(cert)).getBytes(), c => c.charCodeAt(0));
  });
  afterAll(() => vi.unstubAllGlobals());

  function check(signatureBase64: string) {
    const verifier = createVerify('sha256');
    verifier.update(payload);
    verifier.end();
    expect(verifier.verify(forge.pki.certificateToPem(cert), Buffer.from(signatureBase64, 'base64'))).toBe(true);
  }

  it('opens PFX/P12 and signs the exact request bytes without returning the key or password', async () => {
    const pfx = Uint8Array.from(forge.asn1.toDer(forge.pkcs12.toPkcs12Asn1(key, cert, password)).getBytes(), c => c.charCodeAt(0));
    const proof = await signWithCertificateFiles({ kind: 'PFX', bundle: file('doctor.p12', pfx) }, password,
      Buffer.from(payload).toString('base64'));
    expect(proof.certificateDerBase64).toBe(Buffer.from(certDer).toString('base64'));
    expect(JSON.stringify(proof)).not.toContain(password);
    expect(proof.signatureAlgorithm).toBe('SHA256withRSA');
    check(proof.signatureBase64);
    await expect(signWithCertificateFiles({ kind: 'PFX', bundle: file('doctor.p12', pfx) }, 'wrong-password',
      Buffer.from(payload).toString('base64'))).rejects.toThrow(PASSWORD_MISMATCH);
  });

  it('opens an encrypted PKCS#8 NPKI key and rejects a mismatched certificate', async () => {
    const keyInfo = forge.pki.wrapRsaPrivateKey(forge.pki.privateKeyToAsn1(key));
    const encrypted = Uint8Array.from(forge.asn1.toDer(forge.pki.encryptPrivateKeyInfo(keyInfo, password, {
      algorithm: 'aes256', count: 2048,
    })).getBytes(), c => c.charCodeAt(0));
    const files = { kind: 'NPKI' as const, certificate: file('signCert.der', certDer), privateKey: file('signPri.key', encrypted) };
    const proof = await signWithCertificateFiles(files, password, Buffer.from(payload).toString('base64'));
    check(proof.signatureBase64);
    for (const wrong of ['wrong-password', password.toUpperCase(), password + ' ']) {
      await expect(signWithCertificateFiles(files, wrong, Buffer.from(payload).toString('base64')))
        .rejects.toThrow(PASSWORD_MISMATCH);
    }
    await expect(signWithCertificateFiles({ ...files, certificate: file('signCert.der', new Uint8Array([1, 2, 3])) },
      password, Buffer.from(payload).toString('base64'))).rejects.toThrow();
  });

  it('opens a Korean SEED-CBC/SHA-1 encrypted NPKI signPri.key', async () => {
    const salt = '12345678';
    const iterations = 1024;
    const first = forge.md.sha1.create();
    first.update(password, 'utf8');
    first.update(salt, 'raw');
    let digest = first.digest().getBytes();
    for (let i = 1; i < iterations; i++) {
      const next = forge.md.sha1.create();
      next.update(digest, 'raw'); digest = next.digest().getBytes();
    }
    const ivDigest = forge.md.sha1.create();
    ivDigest.update(digest.substring(16, 20), 'raw');
    const iv = ivDigest.digest().getBytes().substring(0, 16);
    const cipher = forge.cipher.createCipher('SEED-CBC' as forge.cipher.Algorithm, digest.substring(0, 16));
    cipher.start({ iv });
    const plain = forge.asn1.toDer(forge.pki.wrapRsaPrivateKey(forge.pki.privateKeyToAsn1(key))).getBytes();
    cipher.update(forge.util.createBuffer(plain));
    expect(cipher.finish()).toBe(true);
    const { Class, Type } = forge.asn1;
    const epki = forge.asn1.create(Class.UNIVERSAL, Type.SEQUENCE, true, [
      forge.asn1.create(Class.UNIVERSAL, Type.SEQUENCE, true, [
        forge.asn1.create(Class.UNIVERSAL, Type.OID, false, forge.asn1.oidToDer('1.2.410.200004.1.15').getBytes()),
        forge.asn1.create(Class.UNIVERSAL, Type.SEQUENCE, true, [
          forge.asn1.create(Class.UNIVERSAL, Type.OCTETSTRING, false, salt),
          forge.asn1.create(Class.UNIVERSAL, Type.INTEGER, false, forge.asn1.integerToDer(iterations).getBytes()),
        ]),
      ]),
      forge.asn1.create(Class.UNIVERSAL, Type.OCTETSTRING, false, cipher.output.getBytes()),
    ]);
    const encrypted = Uint8Array.from(forge.asn1.toDer(epki).getBytes(), c => c.charCodeAt(0));
    const proof = await signWithCertificateFiles({ kind: 'NPKI', certificate: file('signCert.der', certDer),
      privateKey: file('signPri.key', encrypted) }, password, Buffer.from(payload).toString('base64'));
    check(proof.signatureBase64);
    await expect(signWithCertificateFiles({ kind: 'NPKI', certificate: file('signCert.der', certDer),
      privateKey: file('signPri.key', encrypted) }, 'not-the-password', Buffer.from(payload).toString('base64')))
      .rejects.toThrow(PASSWORD_MISMATCH);
  });
});
