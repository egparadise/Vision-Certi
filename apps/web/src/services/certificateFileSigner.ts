import forge from 'korea-forge';
import type { LocalSignResult } from './localCertSdkAdapter';

export type CertificateFiles =
  | { kind: 'PFX'; bundle: File }
  | { kind: 'NPKI'; certificate: File; privateKey: File; chain?: File };

const MAX_BUNDLE = 2 * 1024 * 1024;
const MAX_CERTIFICATE = 128 * 1024;

function binary(bytes: Uint8Array): string {
  let result = '';
  for (let offset = 0; offset < bytes.length; offset += 8192) {
    result += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
  }
  return result;
}

function bytes(value: string): Uint8Array {
  return Uint8Array.from(value, char => char.charCodeAt(0));
}

function base64(value: Uint8Array): string {
  return btoa(binary(value));
}

function fromBase64(value: string): Uint8Array {
  return bytes(atob(value));
}

async function readFile(file: File, limit: number): Promise<Uint8Array> {
  if (!file || file.size < 1 || file.size > limit) throw new Error(`인증서 파일은 ${Math.round(limit / 1024)}KB 이하여야 합니다.`);
  return new Uint8Array(await file.arrayBuffer());
}

function rsaKey(key: forge.pki.PrivateKey | undefined): forge.pki.rsa.PrivateKey {
  if (!key || !('n' in key) || !('e' in key)) throw new Error('이 파일의 개인키 형식은 지원되지 않습니다. RSA 공동인증서를 선택해 주세요.');
  return key as forge.pki.rsa.PrivateKey;
}

function matchingCertificate(key: forge.pki.rsa.PrivateKey, candidates: forge.pki.Certificate[]): forge.pki.Certificate {
  const matches = candidates.filter(cert => 'n' in cert.publicKey && 'e' in cert.publicKey &&
    cert.publicKey.n.compareTo(key.n) === 0 && cert.publicKey.e.compareTo(key.e) === 0);
  if (matches.length !== 1) throw new Error('개인키와 정확히 일치하는 공개 인증서를 찾지 못했습니다.');
  return matches[0];
}

function certificateDer(cert: forge.pki.Certificate): Uint8Array {
  return bytes(forge.asn1.toDer(forge.pki.certificateToAsn1(cert)).getBytes());
}

async function open(files: CertificateFiles, password: string): Promise<{ key: forge.pki.rsa.PrivateKey; leaf: Uint8Array; chain: Uint8Array[] }> {
  if (!password) throw new Error('인증서 비밀번호를 입력해 주세요.');
  if (files.kind === 'PFX') {
    const input = await readFile(files.bundle, MAX_BUNDLE);
    try {
      const p12 = forge.pkcs12.pkcs12FromAsn1(forge.asn1.fromDer(binary(input)), true, password);
      const bags = p12.safeContents.flatMap(item => item.safeBags);
      const keys = bags.filter(item => item.key).map(item => rsaKey(item.key));
      if (keys.length !== 1) throw new Error('PFX/P12에 서명용 개인키가 정확히 하나 있어야 합니다.');
      const certs = bags.filter(item => item.cert).map(item => item.cert!);
      const leaf = matchingCertificate(keys[0], certs);
      return { key: keys[0], leaf: certificateDer(leaf), chain: certs.filter(cert => cert !== leaf).map(certificateDer) };
    } catch (error) {
      if (error instanceof Error && /정확히|지원되지|찾지 못했습니다/.test(error.message)) throw error;
      throw new Error('PFX/P12를 열 수 없습니다. 파일 형식과 비밀번호를 확인해 주세요.');
    } finally { input.fill(0); }
  }

  const [certificate, encryptedKey, chainInput] = await Promise.all([
    readFile(files.certificate, MAX_CERTIFICATE),
    readFile(files.privateKey, MAX_BUNDLE),
    files.chain ? readFile(files.chain, MAX_CERTIFICATE) : Promise.resolve(undefined),
  ]);
  try {
    const encrypted = forge.asn1.fromDer(binary(encryptedKey));
    const decrypted = forge.pki.decryptPrivateKeyInfo(encrypted, password);
    if (!decrypted) throw new Error('인증서 비밀번호가 틀렸거나 지원하지 않는 암호화 형식입니다.');
    const key = rsaKey(forge.pki.privateKeyFromAsn1(decrypted));
    const leaf = forge.pki.certificateFromAsn1(forge.asn1.fromDer(binary(certificate)));
    matchingCertificate(key, [leaf]);
    const chain = chainInput ? [chainInput] : [];
    return { key, leaf: certificate, chain };
  } catch (error) {
    if (error instanceof Error && /비밀번호|지원되지|찾지 못했습니다/.test(error.message)) throw error;
    throw new Error('NPKI 인증서 또는 개인키를 열 수 없습니다. 파일과 비밀번호를 확인해 주세요.');
  } finally { encryptedKey.fill(0); }
}

/** File bytes and password stay in this browser process. Only public certificate and signature leave it. */
export async function signWithCertificateFiles(files: CertificateFiles, password: string, payloadBase64: string): Promise<LocalSignResult> {
  if (!globalThis.crypto?.subtle) throw new Error('안전한 HTTPS 연결에서만 인증서를 사용할 수 있습니다.');
  const { key, leaf, chain } = await open(files, password);
  const pkcs8 = bytes(forge.asn1.toDer(forge.pki.wrapRsaPrivateKey(forge.pki.privateKeyToAsn1(key))).getBytes());
  const payload = fromBase64(payloadBase64);
  try {
    const imported = await crypto.subtle.importKey('pkcs8', pkcs8 as BufferSource,
      { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
    const signature = new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', imported, payload as BufferSource));
    const fingerprint = new Uint8Array(await crypto.subtle.digest('SHA-256', leaf as BufferSource));
    return {
      certificateDerBase64: base64(leaf), chainDerBase64: chain.map(base64),
      signatureBase64: base64(signature), signatureAlgorithm: 'SHA256withRSA',
      fingerprintSha256: Array.from(fingerprint, b => b.toString(16).padStart(2, '0')).join(''),
    };
  } finally { pkcs8.fill(0); payload.fill(0); }
}
