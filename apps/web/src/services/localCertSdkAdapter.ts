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

class WindowInjectedLocalSdkAdapter implements LocalCertificateSdkAdapter {
  readonly id = 'standard-pc-cert-sdk';
  readonly name = 'Standard PC Security Client Module';

  async isAvailable(): Promise<boolean> {
    const win = window as unknown as {
      pcCertSdk?: {
        isReady?: () => boolean;
        listCerts?: () => Promise<LocalCertificateInfo[]>;
        sign?: (opts: LocalSignChallengeOptions) => Promise<LocalSignResult>;
      };
    };
    return Boolean(win.pcCertSdk && typeof win.pcCertSdk.listCerts === 'function');
  }

  async getCertificates(): Promise<LocalCertificateInfo[]> {
    const win = window as unknown as {
      pcCertSdk?: {
        listCerts: () => Promise<LocalCertificateInfo[]>;
      };
    };
    if (!win.pcCertSdk) {
      throw new Error('PC 로컬 인증서 보안 모듈이 설치되어 있지 않거나 실행 중이지 않습니다.');
    }
    return win.pcCertSdk.listCerts();
  }

  async signChallenge(options: LocalSignChallengeOptions): Promise<LocalSignResult> {
    const win = window as unknown as {
      pcCertSdk?: {
        sign: (opts: LocalSignChallengeOptions) => Promise<LocalSignResult>;
      };
    };
    if (!win.pcCertSdk) {
      throw new Error('PC 로컬 인증서 보안 모듈이 설치되어 있지 않거나 실행 중이지 않습니다.');
    }
    return win.pcCertSdk.sign(options);
  }
}

let activeAdapter: LocalCertificateSdkAdapter = new WindowInjectedLocalSdkAdapter();

export function getLocalCertSdkAdapter(): LocalCertificateSdkAdapter {
  return activeAdapter;
}

export function registerLocalCertSdkAdapter(adapter: LocalCertificateSdkAdapter): void {
  activeAdapter = adapter;
}
