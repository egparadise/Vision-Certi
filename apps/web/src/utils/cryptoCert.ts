/**
 * Utility functions for client-side X.509 Certificate and Cryptographic validation.
 *
 * SECURITY INVARIANT:
 * Zero private key handling. Any occurrence of private key identifiers, encrypted keys,
 * or PKCS#12/PFX bundles is immediately rejected.
 */

const PRIVATE_KEY_PATTERNS = [
  'PRIVATE KEY',
  'RSA PRIVATE KEY',
  'EC PRIVATE KEY',
  'ENCRYPTED PRIVATE KEY',
  'BEGIN PKCS7',
  'BEGIN ENCRYPTED',
  '0x06082a864886f70d0107', // PKCS#7 OID
];

/**
 * Checks whether an input contains private keys or credentials.
 * Throws an Error if forbidden keywords are detected.
 */
export function assertNoPrivateKeyOrPassword(text: string): void {
  const upper = text.toUpperCase();
  for (const pattern of PRIVATE_KEY_PATTERNS) {
    if (upper.includes(pattern)) {
      throw new Error(
        '보안 정책 위반: 개인키(PRIVATE KEY) 또는 암호화 키 데이터가 감지되었습니다. ' +
        '본 시스템은 공개 인증서(PUBLIC CERTIFICATE)만 수신합니다. 개인키나 비밀번호를 절대 입력하지 마십시오.'
      );
    }
  }
}

/**
 * Strips PEM header/footer and whitespaces, returning raw Base64 DER string.
 */
export function pemToBase64(pem: string): string {
  assertNoPrivateKeyOrPassword(pem);
  return pem
    .replace(/-----BEGIN [^-]+-----/g, '')
    .replace(/-----END [^-]+-----/g, '')
    .replace(/[\r\n\s]/g, '');
}

/**
 * Parses a single or multi-certificate PEM block into leaf and chain Base64 DER strings.
 */
export function parseCertificateChainPem(pem: string): { leaf: string; chain: string[] } {
  assertNoPrivateKeyOrPassword(pem);

  const certRegex = /-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/g;
  const matches = pem.match(certRegex);

  if (!matches || matches.length === 0) {
    // Treat as raw Base64 DER if no PEM headers
    const raw = pem.replace(/[\r\n\s]/g, '');
    if (!raw) {
      throw new Error('유효한 인증서 데이터가 제공되지 않았습니다.');
    }
    return { leaf: raw, chain: [] };
  }

  const derList = matches.map(m =>
    m.replace(/-----BEGIN CERTIFICATE-----/g, '')
     .replace(/-----END CERTIFICATE-----/g, '')
     .replace(/[\r\n\s]/g, '')
  );

  return {
    leaf: derList[0],
    chain: derList.slice(1),
  };
}

/**
 * Converts a Base64 string to Uint8Array.
 */
export function base64ToUint8Array(base64: string): Uint8Array {
  const binaryString = window.atob(base64);
  const len = binaryString.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }
  return bytes;
}

/**
 * Converts a Uint8Array to lowercase hex string.
 */
export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
}

/**
 * Computes lowercase hex SHA-256 of the FULL DER certificate.
 * Matches backend definition: fingerprintSha256 = lowercase hex SHA-256 of full DER certificate.
 */
export async function computeCertificateFingerprint(derBase64: string): Promise<string> {
  const bytes = base64ToUint8Array(derBase64);
  if (window.crypto && window.crypto.subtle) {
    const hashBuffer = await window.crypto.subtle.digest('SHA-256', bytes.buffer as ArrayBuffer);
    return bytesToHex(new Uint8Array(hashBuffer));
  }
  throw new Error('Web Cryptography API (crypto.subtle) unavailable in this environment.');
}

/**
 * Computes SHA-256 of an ArrayBuffer / Uint8Array.
 */
export async function computeSha256(data: Uint8Array | ArrayBuffer): Promise<string> {
  if (window.crypto && window.crypto.subtle) {
    const buffer = data instanceof Uint8Array ? (data.buffer as ArrayBuffer) : data;
    const hashBuffer = await window.crypto.subtle.digest('SHA-256', buffer);
    return bytesToHex(new Uint8Array(hashBuffer));
  }
  throw new Error('Web Cryptography API (crypto.subtle) unavailable.');
}
