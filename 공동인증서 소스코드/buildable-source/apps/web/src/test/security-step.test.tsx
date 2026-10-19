import { createHash, webcrypto } from 'node:crypto';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CertificateSecurityStep, certificateInstaller } from '../components/CertificateSecurityStep';
import {
  WindowInjectedLocalSdkAdapter, registerLocalCertSdkAdapter, sanitizeLocalSignResult,
  LOCAL_CERT_READY_TIMEOUT_MS, LOCAL_CERT_SIGN_TIMEOUT_MS,
  type LocalCertificateInfo, type LocalSignResult,
} from '../services/localCertSdkAdapter';
import type { CertificateChallenge } from '../types/api';

// Public bytes only: this fixture tests full-buffer hashing and transport validation, not a CA signature.
const DER = 'MAcwBQIBAgUA';
const fingerprint = createHash('sha256').update(Uint8Array.from(atob(DER), c => c.charCodeAt(0))).digest('hex');
const cert: LocalCertificateInfo = {
  subjectDn: 'CN=테스트 의사,OU=Medical', issuerDn: 'CN=Test Public CA,O=Test', serialNumber: '123',
  notBefore: '2020-01-01T00:00:00Z', notAfter: '2099-01-01T00:00:00Z', fingerprintSha256: fingerprint,
  algorithm: 'RSA-2048',
};
const proof: LocalSignResult = {
  certificateDerBase64: DER, chainDerBase64: [], signatureBase64: 'AQIDBA==',
  signatureAlgorithm: 'SHA256withRSA', fingerprintSha256: fingerprint,
};
function challenge(overrides: Partial<CertificateChallenge> = {}): CertificateChallenge {
  return {
    challengeId: 'challenge-test', registrationId: null, purpose: 'CERTIFICATE_LOGIN', doctorId: null,
    tenantId: null, serviceId: 'test-service', nonce: 'test-nonce-do-not-display',
    issuedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 600000).toISOString(),
    signedPayload: 'test payload do not display', signedPayloadBase64: 'c2lnbi1leGFjdC1ieXRlcw==',
    allowedSignatureAlgorithms: ['SHA256withRSA', 'SHA256withECDSA'], ...overrides,
  };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(r => { resolve = r; });
  return { promise, resolve };
}
let sdk: { isReady: ReturnType<typeof vi.fn>; listCerts: ReturnType<typeof vi.fn>; sign: ReturnType<typeof vi.fn> };

beforeEach(() => {
  vi.stubEnv('VITE_CERT_INSTALLER_URL', '');
  vi.stubEnv('VITE_CERT_INSTALLER_NAME', '');
  vi.stubEnv('VITE_CERT_INSTALLER_SHA256', '');
  Object.defineProperty(window, 'crypto', { configurable: true, value: webcrypto });
  sdk = { isReady: vi.fn().mockResolvedValue(true), listCerts: vi.fn().mockResolvedValue([cert]), sign: vi.fn().mockResolvedValue(proof) };
  Object.defineProperty(window, 'pcCertSdk', { configurable: true, writable: true, value: sdk });
  registerLocalCertSdkAdapter(new WindowInjectedLocalSdkAdapter());
});
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllEnvs(); vi.restoreAllMocks(); });

async function selectCertificate() {
  const radio = await screen.findByRole('radio');
  fireEvent.click(radio);
  return screen.getByRole('button', { name: '공동인증서로 로그인' });
}

describe('Local certificate SDK availability and public-data boundary', () => {
  it('requires isReady, listCerts and sign instead of assuming listCerts means installed', async () => {
    Object.defineProperty(window, 'pcCertSdk', { configurable: true, value: { listCerts: sdk.listCerts } });
    expect(await new WindowInjectedLocalSdkAdapter().isAvailable()).toBe(false);
  });
  it('treats readiness false, throw and timeout as unavailable', async () => {
    const adapter = new WindowInjectedLocalSdkAdapter();
    sdk.isReady.mockResolvedValue(false);
    expect(await adapter.isAvailable()).toBe(false);
    sdk.isReady.mockImplementation(() => { throw new Error('native error'); });
    expect(await adapter.isAvailable()).toBe(false);
    vi.useFakeTimers();
    sdk.isReady.mockReturnValue(new Promise(() => {}));
    const pending = adapter.isAvailable();
    await vi.advanceTimersByTimeAsync(LOCAL_CERT_READY_TIMEOUT_MS + 1);
    expect(await pending).toBe(false);
  });
  it('whitelists public proof fields and refuses password/private-key fields', () => {
    expect(sanitizeLocalSignResult({ ...proof, extraTransportMetadata: 'ignored' })).toEqual(proof);
    for (const key of ['privateKey', 'private_key_pem', 'password', 'pin', 'pfx']) {
      expect(() => sanitizeLocalSignResult({ ...proof, [key]: 'do-not-forward' })).toThrow();
    }
  });
});

describe('Installer configuration', () => {
  const config = { VITE_CERT_INSTALLER_URL: '/downloads/provider.exe', VITE_CERT_INSTALLER_NAME: 'Test Security Program', VITE_CERT_INSTALLER_SHA256: 'ab'.repeat(32) };
  it('permits configured HTTPS files and same-origin relative HTTPS paths', () => {
    expect(certificateInstaller(config, 'https://cert.test/path').url).toBe('https://cert.test/downloads/provider.exe');
    expect(certificateInstaller({ ...config, VITE_CERT_INSTALLER_URL: 'https://provider.test/security.exe' }).url).toBe('https://provider.test/security.exe');
  });
  it('rejects unsafe URLs and incomplete installer metadata', () => {
    for (const url of ['javascript:alert(1)', 'data:text/plain,install', 'http://provider.test/a.exe', '//other.test/a.exe', 'https://user:pass@provider.test/a.exe']) {
      expect(certificateInstaller({ ...config, VITE_CERT_INSTALLER_URL: url }, 'https://cert.test').url).toBeNull();
    }
    expect(certificateInstaller({ ...config, VITE_CERT_INSTALLER_SHA256: '' }, 'https://cert.test').url).toBeNull();
    expect(certificateInstaller({}, 'https://cert.test').url).toBeNull();
  });
});

describe('CertificateSecurityStep', () => {
  it('shows unavailable state with disabled installation when no actual installer is configured', async () => {
    sdk.isReady.mockResolvedValue(false);
    render(<CertificateSecurityStep challenge={challenge()} onProof={vi.fn()} />);
    expect(await screen.findByText('설치 또는 실행 확인 필요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '설치파일 연결 준비 중' })).toBeDisabled();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });
  it('passes exact challenge bytes and selected certificate, hashes full DER and submits only public fields', async () => {
    const onProof = vi.fn().mockResolvedValue(undefined), request = challenge();
    sdk.sign.mockResolvedValue({ ...proof, providerDebug: 'not-for-api' });
    render(<CertificateSecurityStep challenge={request} onProof={onProof} />);
    expect(await screen.findByText('테스트 의사')).toBeInTheDocument();
    expect(screen.getByText('발급기관: Test Public CA')).toBeInTheDocument();
    expect(screen.queryByText(request.nonce)).not.toBeInTheDocument();
    expect(screen.queryByText(request.signedPayloadBase64)).not.toBeInTheDocument();
    fireEvent.click(await selectCertificate());
    await waitFor(() => expect(onProof).toHaveBeenCalledWith(proof));
    expect(sdk.sign).toHaveBeenCalledWith({ challengeId: request.challengeId, challengeNonce: request.nonce,
      dataToSignBase64: request.signedPayloadBase64, targetFingerprintSha256: fingerprint, algorithm: 'SHA256withRSA' });
    expect(screen.getByRole('button', { name: '공동인증서로 로그인' })).toBeDisabled();
  });
  it('rejects selected-certificate and SDK fingerprint mismatches', async () => {
    sdk.sign.mockResolvedValue({ ...proof, fingerprintSha256: 'ab'.repeat(32) });
    const onProof = vi.fn();
    render(<CertificateSecurityStep challenge={challenge()} onProof={onProof} />);
    fireEvent.click(await selectCertificate());
    expect(await screen.findByRole('alert')).toHaveTextContent('선택한 인증서');
    expect(onProof).not.toHaveBeenCalled();
  });
  it('rejects algorithms outside the challenge allowlist', async () => {
    sdk.sign.mockResolvedValue({ ...proof, signatureAlgorithm: 'SHA1withRSA' });
    const onProof = vi.fn();
    render(<CertificateSecurityStep challenge={challenge()} onProof={onProof} />);
    fireEvent.click(await selectCertificate());
    expect(await screen.findByRole('alert')).toHaveTextContent('서명 방식');
    expect(onProof).not.toHaveBeenCalled();
  });
  it('does not permit expired or not-yet-valid certificates', async () => {
    sdk.listCerts.mockResolvedValue([{ ...cert, notAfter: '2001-01-01T00:00:00Z' }]);
    render(<CertificateSecurityStep challenge={challenge()} onProof={vi.fn()} />);
    expect(await screen.findByRole('radio')).toBeDisabled();
    expect(screen.getByRole('button', { name: '공동인증서로 로그인' })).toBeDisabled();
  });
  it('ignores old proof after the challenge changes and prevents duplicate clicks', async () => {
    const signing = deferred<LocalSignResult>();
    sdk.sign.mockReturnValue(signing.promise);
    const onProof = vi.fn(), request = challenge();
    const view = render(<CertificateSecurityStep challenge={request} onProof={onProof} />);
    const button = await selectCertificate();
    fireEvent.click(button); fireEvent.click(button);
    await waitFor(() => expect(sdk.sign).toHaveBeenCalledTimes(1));
    view.rerender(<CertificateSecurityStep challenge={{ ...request, nonce: 'different-nonce' }} onProof={onProof} />);
    await act(async () => signing.resolve(proof));
    expect(onProof).not.toHaveBeenCalled();
  });
  it('times out native signing and ignores any late completion', async () => {
    const signing = deferred<LocalSignResult>();
    sdk.sign.mockReturnValue(signing.promise);
    const onProof = vi.fn();
    render(<CertificateSecurityStep challenge={challenge()} onProof={onProof} />);
    const button = await selectCertificate();
    vi.useFakeTimers();
    fireEvent.click(button);
    await act(async () => { await vi.advanceTimersByTimeAsync(LOCAL_CERT_SIGN_TIMEOUT_MS + 1); });
    expect(screen.getByRole('alert')).toHaveTextContent('응답 시간');
    await act(async () => signing.resolve(proof));
    expect(onProof).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '공동인증서로 로그인' })).toBeDisabled();
  });
  it('announces expired challenge once and never starts signing', async () => {
    const onProof = vi.fn(), onExpired = vi.fn();
    render(<CertificateSecurityStep challenge={challenge({ expiresAt: '2001-01-01T00:00:00Z' })} onProof={onProof} onExpired={onExpired} />);
    await screen.findByRole('radio');
    expect(onExpired).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: '공동인증서로 로그인' })).toBeDisabled();
    expect(sdk.sign).not.toHaveBeenCalled();
    expect(onProof).not.toHaveBeenCalled();
  });
});
