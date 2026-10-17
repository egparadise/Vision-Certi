/**
 * PC Local Signing SDK Adapter Contract.
 * 
 * Formal interface for connecting to client-side hardware/software certificate security modules
 * (KISA/Accredited CA PC local signing agent or native PKCS#11 bridge).
 * 
 * In production, certificates and private keys remain strictly in the secure module;
 * the browser never accesses or invents private keys. If the native SDK is missing or
 * unconfigured, it reports unavailable (no fake or guessed successful provider operations).
 */

export interface LocalCertificateInfo {
  subjectDn: string;
  issuerDn: string;
  serialNumber: string;
  notBefore: string;
  notAfter: string;
  fingerprintSha256: string;
  algorithm: string;
  policyOid?: string;
  keyUsage?: string[];
}

export interface LocalSignChallengeOptions {
  challengeId: string;
  challengeNonce: string;
  dataToSignBase64?: string;
  targetFingerprintSha256?: string;
  algorithm?: string;
}

export interface LocalSignResult {
  certificateDerBase64: string;
  chainDerBase64: string[];
  signatureBase64: string;
  signatureAlgorithm: string;
  fingerprintSha256: string;
}

export interface LocalCertificateSdkAdapter {
  readonly id: string;
  readonly name: string;
  isAvailable(): Promise<boolean>;
  getCertificates(): Promise<LocalCertificateInfo[]>;
  signChallenge(options: LocalSignChallengeOptions): Promise<LocalSignResult>;
}

export const LOCAL_CERT_READY_TIMEOUT_MS = 4000;
export const LOCAL_CERT_LIST_TIMEOUT_MS = 10000;
export const LOCAL_CERT_SIGN_TIMEOUT_MS = 90000;

export class LocalCertificateTimeoutError extends Error {
  constructor() { super('보안프로그램의 응답 시간이 초과되었습니다. 프로그램 실행 상태를 확인해 주세요.'); }
}

export async function localCertificateDeadline<T>(action: () => T | Promise<T>, timeout: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      Promise.resolve().then(action),
      new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new LocalCertificateTimeoutError()), timeout); }),
    ]);
  } finally { if (timer !== undefined) clearTimeout(timer); }
}

const unavailable = () => new Error('PC 인증서 보안프로그램에 연결할 수 없습니다. 설치·실행 후 다시 확인해 주세요.');
const malformed = () => new Error('보안프로그램에서 올바른 공개 인증서 정보를 받지 못했습니다.');

function publicRecord(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw malformed();
  const record = value as Record<string, unknown>;
  if (Object.keys(record).some(key => /^(privatekey|password|passwd|passphrase|pin|pfx|pkcs12|encryptedkey)/i.test(key.replace(/[_\s-]/g, '')))) {
    throw new Error('보안프로그램은 공개 인증서와 서명값만 전달해야 합니다. 개인키·비밀번호는 받을 수 없습니다.');
  }
  return record;
}

function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || !value || value.length > max) throw malformed();
  return value;
}

export function localCertificateFingerprint(value: unknown): string {
  const result = text(value, 64);
  if (!/^[a-fA-F0-9]{64}$/.test(result)) throw malformed();
  return result.toLowerCase();
}

function publicBase64(value: unknown): string {
  const encoded = text(value, 262144).replace(/[\r\n\t ]/g, '');
  if (!encoded || encoded.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) throw malformed();
  return encoded;
}

export function sanitizeLocalCertificates(value: unknown): LocalCertificateInfo[] {
  if (!Array.isArray(value) || value.length > 100) throw malformed();
  const seen = new Set<string>();
  return value.map(item => {
    const cert = publicRecord(item);
    const fingerprintSha256 = localCertificateFingerprint(cert.fingerprintSha256);
    if (seen.has(fingerprintSha256)) throw malformed();
    seen.add(fingerprintSha256);
    return {
      subjectDn: text(cert.subjectDn, 2048), issuerDn: text(cert.issuerDn, 2048),
      serialNumber: text(cert.serialNumber, 256), notBefore: text(cert.notBefore, 100),
      notAfter: text(cert.notAfter, 100), fingerprintSha256, algorithm: text(cert.algorithm, 100),
    };
  });
}

/** Reconstruct an explicit public-data DTO; never spread SDK output into an API request. */
export function sanitizeLocalSignResult(value: unknown): LocalSignResult {
  const proof = publicRecord(value);
  if (!Array.isArray(proof.chainDerBase64) || proof.chainDerBase64.length > 8) throw malformed();
  return {
    certificateDerBase64: publicBase64(proof.certificateDerBase64),
    chainDerBase64: proof.chainDerBase64.map(publicBase64),
    signatureBase64: publicBase64(proof.signatureBase64),
    signatureAlgorithm: text(proof.signatureAlgorithm, 64),
    fingerprintSha256: localCertificateFingerprint(proof.fingerprintSha256),
  };
}

interface InjectedSdk {
  isReady: () => boolean | Promise<boolean>;
  listCerts: () => Promise<LocalCertificateInfo[]>;
  sign: (opts: LocalSignChallengeOptions) => Promise<LocalSignResult>;
}

function injectedSdk(): InjectedSdk | null {
  const sdk = (window as unknown as { pcCertSdk?: Partial<InjectedSdk> }).pcCertSdk;
  return sdk && typeof sdk.isReady === 'function' && typeof sdk.listCerts === 'function' && typeof sdk.sign === 'function'
    ? sdk as InjectedSdk : null;
}

export class WindowInjectedLocalSdkAdapter implements LocalCertificateSdkAdapter {
  readonly id = 'standard-pc-cert-sdk';
  readonly name = 'Standard PC Security Client Module';

  async isAvailable(): Promise<boolean> {
    try {
      const sdk = injectedSdk();
      return !!sdk && await localCertificateDeadline(() => sdk.isReady(), LOCAL_CERT_READY_TIMEOUT_MS) === true;
    } catch { return false; }
  }

  async getCertificates(): Promise<LocalCertificateInfo[]> {
    const sdk = injectedSdk();
    if (!sdk || !await this.isAvailable() || sdk !== injectedSdk()) throw unavailable();
    try {
      return sanitizeLocalCertificates(await localCertificateDeadline(() => sdk.listCerts(), LOCAL_CERT_LIST_TIMEOUT_MS));
    } catch (error) { if (error instanceof LocalCertificateTimeoutError) throw error; throw unavailable(); }
  }

  async signChallenge(options: LocalSignChallengeOptions): Promise<LocalSignResult> {
    const sdk = injectedSdk();
    if (!sdk || !await this.isAvailable() || sdk !== injectedSdk()) throw unavailable();
    // The challenge bytes are not regenerated, decoded/re-encoded or replaced with only the nonce.
    const request: LocalSignChallengeOptions = {
      challengeId: text(options.challengeId, 128), challengeNonce: text(options.challengeNonce, 4096),
      dataToSignBase64: text(options.dataToSignBase64, 262144),
      targetFingerprintSha256: localCertificateFingerprint(options.targetFingerprintSha256),
      ...(options.algorithm ? { algorithm: text(options.algorithm, 64) } : {}),
    };
    try {
      return sanitizeLocalSignResult(await localCertificateDeadline(() => sdk.sign(request), LOCAL_CERT_SIGN_TIMEOUT_MS));
    } catch (error) { if (error instanceof LocalCertificateTimeoutError) throw error; throw unavailable(); }
  }
}

let activeAdapter: LocalCertificateSdkAdapter = new WindowInjectedLocalSdkAdapter();

export function getLocalCertSdkAdapter(): LocalCertificateSdkAdapter {
  return activeAdapter;
}

export function registerLocalCertSdkAdapter(adapter: LocalCertificateSdkAdapter): void {
  activeAdapter = adapter;
}
