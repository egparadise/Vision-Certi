import assert from 'node:assert/strict';
import { createVerify } from 'node:crypto';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
import test from 'node:test';
import forge from 'korea-forge';
import { discoverCertificates, signChallenge } from './certificates.mjs';

test('discovers a standard NPKI pair and signs exact challenge bytes without exposing the key', async () => {
  const root = await mkdtemp(join(tmpdir(), 'vision-certi-bridge-'));
  assert.ok(resolve(root).startsWith(resolve(tmpdir()) + sep));
  try {
    const folder = join(root, 'NPKI', 'yessign', 'USER', 'doctor');
    await mkdir(folder, { recursive: true });
    const pair = forge.pki.rsa.generateKeyPair({ bits: 1024, e: 0x10001 });
    const cert = forge.pki.createCertificate();
    cert.publicKey = pair.publicKey;
    cert.serialNumber = '01';
    cert.validity.notBefore = new Date('2026-01-01T00:00:00Z');
    cert.validity.notAfter = new Date('2027-05-11T00:00:00Z');
    cert.setSubject([{ name: 'commonName', value: 'Doctor Test' }]);
    cert.setIssuer([{ name: 'organizationName', value: 'Test CA' }]);
    cert.sign(pair.privateKey, forge.md.sha256.create());
    const certDer = Buffer.from(forge.asn1.toDer(forge.pki.certificateToAsn1(cert)).getBytes(), 'binary');
    const privateInfo = forge.pki.wrapRsaPrivateKey(forge.pki.privateKeyToAsn1(pair.privateKey));
    const encrypted = forge.pki.encryptPrivateKeyInfo(privateInfo, 'secret', { algorithm: 'aes256' });
    const encryptedDer = Buffer.from(forge.asn1.toDer(encrypted).getBytes(), 'binary');
    await writeFile(join(folder, 'signCert.der'), certDer);
    await writeFile(join(folder, 'signPri.key'), encryptedDer);
    const p12 = forge.pkcs12.toPkcs12Asn1(pair.privateKey, [cert], 'secret', { algorithm: '3des' });
    await writeFile(join(root, 'doctor.p12'), Buffer.from(forge.asn1.toDer(p12).getBytes(), 'binary'));

    const { records, privateLocations } = await discoverCertificates([root]);
    assert.equal(records.length, 2);
    const npki = records.find(record => record.kind === 'NPKI');
    const pfx = records.find(record => record.kind === 'PFX');
    assert.equal(npki.name, 'Doctor Test');
    assert.equal(npki.issuer, 'Test CA');
    assert.equal(pfx.name, 'doctor.p12');
    assert.ok(!JSON.stringify(records).includes('signPri.key'));
    const payload = Buffer.from('exact-challenge-bytes');
    const proof = await signChallenge(privateLocations, npki.id, 'secret', payload.toString('base64'));
    const verifier = createVerify('RSA-SHA256');
    verifier.update(payload); verifier.end();
    assert.equal(verifier.verify(forge.pki.publicKeyToPem(pair.publicKey), Buffer.from(proof.signatureBase64, 'base64')), true);
    assert.equal(proof.certificateDerBase64, certDer.toString('base64'));
    const pfxProof = await signChallenge(privateLocations, pfx.id, 'secret', payload.toString('base64'));
    assert.equal(pfxProof.certificateDerBase64, certDer.toString('base64'));
    await assert.rejects(signChallenge(privateLocations, npki.id, 'wrong', payload.toString('base64')));
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
