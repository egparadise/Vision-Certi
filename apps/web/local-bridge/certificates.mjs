import { createHash, createPrivateKey, createSign } from 'node:crypto';
import { readFile, readdir, stat } from 'node:fs/promises';
import { basename, join, relative } from 'node:path';
import forge from 'korea-forge';

const MAX_CERT_BYTES = 128 * 1024;
const MAX_KEY_BYTES = 2 * 1024 * 1024;
const MAX_DIRS = 10000;
const MAX_DEPTH = 16;
const MAX_RESULTS = 200;

function binary(buffer) { return buffer.toString('binary'); }
function der(asn1) { return Buffer.from(forge.asn1.toDer(asn1).getBytes(), 'binary'); }
function fieldValue(field, fallback) {
  if (typeof field?.value !== 'string') return fallback;
  try {
    if (field.valueTagClass === forge.asn1.Type.UTF8) return Buffer.from(field.value, 'binary').toString('utf8');
    if (field.valueTagClass === forge.asn1.Type.BMPSTRING) {
      const input = Buffer.from(field.value, 'binary');
      const swapped = Buffer.allocUnsafe(input.length);
      for (let index = 0; index + 1 < input.length; index += 2) {
        swapped[index] = input[index + 1]; swapped[index + 1] = input[index];
      }
      return swapped.toString('utf16le');
    }
  } catch { return fallback; }
  return field.value || fallback;
}
async function boundedRead(path, max) {
  const metadata = await stat(path);
  if (!metadata.isFile() || metadata.size < 1 || metadata.size > max) throw new Error('인증서 파일 크기가 허용 범위를 벗어났습니다.');
  return readFile(path);
}
function parseCertificate(buffer) {
  return forge.pki.certificateFromAsn1(forge.asn1.fromDer(binary(buffer)));
}
function publicRecord(cert, id, location, kind) {
  return {
    id, kind,
    name: fieldValue(cert.subject.getField('CN'), basename(location)),
    issuer: fieldValue(cert.issuer.getField('O') ?? cert.issuer.getField('CN'), '발급기관 미표시'),
    expiresAt: cert.validity.notAfter.toISOString(), location,
  };
}

/** Scan only standard user certificate directories. Never open signPri.key while listing. */
export async function discoverCertificates(roots) {
  const records = [];
  const privateLocations = new Map();
  let visited = 0;
  const seen = new Set();
  async function visit(root, directory, depth) {
    if (depth > MAX_DEPTH || visited >= MAX_DIRS || records.length >= MAX_RESULTS) return;
    visited++;
    let entries;
    try { entries = await readdir(directory, { withFileTypes: true }); } catch { return; }
    const files = new Map(entries.filter(entry => entry.isFile()).map(entry => [entry.name.toLowerCase(), entry.name]));
    const certName = files.get('signcert.der');
    const keyName = files.get('signpri.key');
    if (certName && keyName) {
      try {
        const certificatePath = join(directory, certName);
        const certificateDer = await boundedRead(certificatePath, MAX_CERT_BYTES);
        const id = createHash('sha256').update(certificateDer).digest('hex');
        if (!seen.has(id)) {
          const cert = parseCertificate(certificateDer);
          const location = relative(root, directory) || basename(directory);
          records.push(publicRecord(cert, id, location, 'NPKI'));
          privateLocations.set(id, { kind: 'NPKI', certificatePath, privateKeyPath: join(directory, keyName),
            chainPath: files.get('cacert.der') ? join(directory, files.get('cacert.der')) : undefined });
          seen.add(id);
        }
      } catch { /* Ignore malformed public certificates; never inspect private keys here. */ }
    }
    for (const entry of entries) {
      if (records.length >= MAX_RESULTS) break;
      if (!entry.isFile() || !/\.(pfx|p12)$/i.test(entry.name)) continue;
      const filePath = join(directory, entry.name);
      try {
        const metadata = await stat(filePath);
        if (metadata.size < 1 || metadata.size > MAX_KEY_BYTES) continue;
        const id = createHash('sha256').update(filePath).digest('hex');
        if (seen.has(id)) continue;
        const location = relative(root, filePath) || entry.name;
        records.push({ id, kind: 'PFX', name: entry.name, issuer: '비밀번호 입력 후 확인', expiresAt: null, location });
        privateLocations.set(id, { kind: 'PFX', bundlePath: filePath });
        seen.add(id);
      } catch { /* Skip inaccessible files. */ }
    }
    const children = entries.filter(entry => entry.isDirectory()).sort((left, right) =>
      Number(right.name.toLowerCase() === 'npki') - Number(left.name.toLowerCase() === 'npki'));
    for (const entry of children) await visit(root, join(directory, entry.name), depth + 1);
  }
  for (const root of roots) await visit(root, root, 0);
  records.sort((left, right) => left.name.localeCompare(right.name, 'ko'));
  return { records, privateLocations };
}

function rsaKey(key) {
  if (!key || !('n' in key) || !('e' in key)) throw new Error('RSA 인증서만 지원합니다.');
  return key;
}
function matchedCertificate(key, cert) {
  if (!('n' in cert.publicKey) || cert.publicKey.n.compareTo(key.n) !== 0 ||
    cert.publicKey.e.compareTo(key.e) !== 0) throw new Error('인증서와 개인키가 일치하지 않습니다.');
}
async function openCertificate(location, password) {
  if (location.kind === 'NPKI') {
    const [certificateDer, encryptedKey] = await Promise.all([
      boundedRead(location.certificatePath, MAX_CERT_BYTES), boundedRead(location.privateKeyPath, MAX_KEY_BYTES),
    ]);
    try {
      const encrypted = forge.asn1.fromDer(binary(encryptedKey));
      const decrypted = forge.pki.decryptPrivateKeyInfo(encrypted, password);
      if (!decrypted) throw new Error('인증서 암호가 틀렸거나 지원하지 않는 형식입니다.');
      const key = rsaKey(forge.pki.privateKeyFromAsn1(decrypted));
      const cert = parseCertificate(certificateDer);
      matchedCertificate(key, cert);
      const chain = location.chainPath ? [await boundedRead(location.chainPath, MAX_CERT_BYTES)] : [];
      return { key, certificateDer, chain };
    } finally { encryptedKey.fill(0); }
  }
  const input = await boundedRead(location.bundlePath, MAX_KEY_BYTES);
  try {
    const p12 = forge.pkcs12.pkcs12FromAsn1(forge.asn1.fromDer(binary(input)), true, password);
    const bags = p12.safeContents.flatMap(item => item.safeBags);
    const keys = bags.filter(item => item.key).map(item => rsaKey(item.key));
    if (keys.length !== 1) throw new Error('PFX/P12에 서명용 개인키가 정확히 하나 있어야 합니다.');
    const certs = bags.filter(item => item.cert).map(item => item.cert);
    const leaf = certs.find(cert => 'n' in cert.publicKey && cert.publicKey.n.compareTo(keys[0].n) === 0 &&
      cert.publicKey.e.compareTo(keys[0].e) === 0);
    if (!leaf) throw new Error('인증서와 개인키가 일치하지 않습니다.');
    return { key: keys[0], certificateDer: der(forge.pki.certificateToAsn1(leaf)),
      chain: certs.filter(cert => cert !== leaf).map(cert => der(forge.pki.certificateToAsn1(cert))) };
  } finally { input.fill(0); }
}

/** Only a signature and public certificates leave this process. */
export async function signChallenge(privateLocations, id, password, payloadBase64) {
  if (typeof password !== 'string' || !password || password.length > 256) throw new Error('인증서 암호를 확인해 주세요.');
  if (typeof payloadBase64 !== 'string' || payloadBase64.length > 65536 || !/^[A-Za-z0-9+/]*={0,2}$/.test(payloadBase64))
    throw new Error('서명 요청 형식이 올바르지 않습니다.');
  const location = privateLocations.get(id);
  if (!location) throw new Error('인증서 목록을 다시 불러와 주세요.');
  const payload = Buffer.from(payloadBase64, 'base64');
  if (!payload.length || payload.length > 32768 || payload.toString('base64') !== payloadBase64)
    throw new Error('서명 요청 형식 또는 크기가 허용 범위를 벗어났습니다.');
  try {
    const { key, certificateDer, chain } = await openCertificate(location, password);
    const pem = forge.pki.privateKeyToPem(key);
    const privateKey = createPrivateKey(pem);
    const signer = createSign('RSA-SHA256');
    signer.update(payload); signer.end();
    return {
      certificateDerBase64: certificateDer.toString('base64'),
      chainDerBase64: chain.map(item => item.toString('base64')),
      signatureBase64: signer.sign(privateKey).toString('base64'),
      signatureAlgorithm: 'SHA256withRSA',
      fingerprintSha256: createHash('sha256').update(certificateDer).digest('hex'),
    };
  } finally { payload.fill(0); }
}
