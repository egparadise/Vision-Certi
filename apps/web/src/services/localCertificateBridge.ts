import type { LocalSignResult } from './localCertSdkAdapter';
import type { CertificateMedium, DiscoveredCertificate } from './localCertificateDiscovery';

const BRIDGE = 'http://127.0.0.1:18187';

/** Check only the local process, without rescanning an entire drive. */
export async function probeLocalCertificateBridge(): Promise<boolean> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 3000);
  try {
    const response = await fetch(`${BRIDGE}/v1/certificates`, {
      method: 'OPTIONS', mode: 'cors', credentials: 'omit', cache: 'no-store', signal: controller.signal,
    });
    return response.status === 204;
  } catch {
    return false;
  } finally {
    window.clearTimeout(timeout);
  }
}

export interface BridgeCertificate extends Omit<DiscoveredCertificate, 'getFiles'> {
  source: 'LOCAL_BRIDGE';
  sign(password: string, payloadBase64: string): Promise<LocalSignResult>;
}

interface BridgeRecord {
  id: string;
  kind: 'NPKI' | 'PFX';
  name: string;
  issuer: string;
  expiresAt: string | null;
  location: string;
}

function validRecord(value: unknown): value is BridgeRecord {
  if (!value || typeof value !== 'object') return false;
  const item = value as Record<string, unknown>;
  return typeof item.id === 'string' && /^[a-f0-9]{64}$/.test(item.id) &&
    (item.kind === 'NPKI' || item.kind === 'PFX') &&
    typeof item.name === 'string' && typeof item.issuer === 'string' &&
    (item.expiresAt === null || (typeof item.expiresAt === 'string' && Number.isFinite(Date.parse(item.expiresAt)))) &&
    typeof item.location === 'string';
}

async function sign(id: string, password: string, payloadBase64: string): Promise<LocalSignResult> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 30000);
  let response: Response;
  try {
    response = await fetch(`${BRIDGE}/v1/sign`, {
      method: 'POST', mode: 'cors', credentials: 'omit', cache: 'no-store', signal: controller.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ certificateId: id, password, payloadBase64 }),
    });
  } catch (error) {
    if (controller.signal.aborted) throw new Error('PC 인증서 확인 시간이 초과되었습니다. 다시 시도해 주세요.');
    throw error;
  } finally { window.clearTimeout(timeout); }
  const result = await response.json();
  if (!response.ok) throw new Error(typeof result.error === 'string' ? result.error : 'PC 인증서 서명에 실패했습니다.');
  if (typeof result.certificateDerBase64 !== 'string' || typeof result.signatureBase64 !== 'string' ||
    !Array.isArray(result.chainDerBase64) || result.signatureAlgorithm !== 'SHA256withRSA' ||
    typeof result.fingerprintSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(result.fingerprintSha256))
    throw new Error('PC 인증서 프로그램의 응답이 올바르지 않습니다.');
  return result as LocalSignResult;
}

/** A loopback-only companion discovers NPKI certificates without exposing private key files to the web page. */
export async function discoverBridgeCertificates(medium: CertificateMedium): Promise<BridgeCertificate[]> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 90000);
  try {
    const path = medium === 'removable-disk' ? '/v1/certificates?medium=removable-disk' : '/v1/certificates';
    const response = await fetch(`${BRIDGE}${path}`, {
      mode: 'cors', credentials: 'omit', cache: 'no-store', signal: controller.signal,
    });
    if (!response.ok) throw new Error('PC 인증서 프로그램이 이 사이트의 요청을 허용하지 않습니다. 최신 버전으로 설치해 주세요.');
    const data: unknown = await response.json();
    const records = (data as { certificates?: unknown })?.certificates;
    if (!Array.isArray(records)) return [];
    return records.filter(validRecord).map(record => ({
      id: `bridge:${record.id}`, kind: record.kind, name: record.name, issuer: record.issuer,
      expiresAt: record.expiresAt ? new Date(record.expiresAt) : undefined,
      path: record.kind === 'NPKI' ? '이 PC의 NPKI' : record.location,
      source: 'LOCAL_BRIDGE' as const,
      sign: (password, payloadBase64) => sign(record.id, password, payloadBase64),
    }));
  } finally { window.clearTimeout(timeout); }
}
