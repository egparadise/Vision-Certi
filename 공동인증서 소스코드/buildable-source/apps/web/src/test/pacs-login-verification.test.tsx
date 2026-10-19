import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { AppProvider } from '../context/AppContext';
import { CertificateEnrollmentView } from '../components/CertificateEnrollmentView';

vi.mock('../components/ExternalCertificateProof', () => ({
  ExternalCertificateProof: ({ onProof }: { onProof: (proof: unknown) => Promise<void> }) =>
    <button data-testid="signed-proof" onClick={() => void onProof({
      certificateDerBase64: 'Y2VydA==', chainDerBase64: [], signatureBase64: 'c2lnbmF0dXJl',
      signatureAlgorithm: 'SHA256withRSA', fingerprintSha256: '',
    }).catch(() => {})}>인증서 서명 제출</button>,
}));
vi.mock('../components/PhysicianSigningPortal', () => ({ PhysicianSigningPortal: () => <div>인증 완료</div> }));

const config = { pcCertificate: { trustConfigured: true, allowedSignatureAlgorithms: ['SHA256withRSA'] },
  mobile: { configured: false }, devMockCertificateEnabled: false };
const challenge = { purpose: 'CERTIFICATE_LOGIN', challengeId: 'login-1', registrationId: null,
  expiresAt: new Date(Date.now() + 600000).toISOString(), signedPayload: 'challenge',
  signedPayloadBase64: 'Y2hhbGxlbmdl', allowedSignatureAlgorithms: ['SHA256withRSA'] };

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it('does not return to PACS when INVIZ rejects the signed certificate proof', async () => {
  const onLoginComplete = vi.fn();
  globalThis.fetch = vi.fn().mockImplementation(async (url: string) => {
    if (url.includes('/config')) return new Response(JSON.stringify(config), { status: 200 });
    if (url.includes('/login-challenges')) return new Response(JSON.stringify(challenge), { status: 201 });
    if (url.endsWith('/certificate-enrollment/login')) return new Response(JSON.stringify({
      code: 'CERTIFICATE_LOGIN_DENIED', message: '인증서 검증 실패',
    }), { status: 401 });
    return new Response('{}', { status: 404 });
  });
  render(<AppProvider><CertificateEnrollmentView initialMode="PC_LOGIN" proofMethod="external"
    onLoginComplete={onLoginComplete} /></AppProvider>);
  fireEvent.click(await screen.findByTestId('request-login-challenge-btn'));
  fireEvent.click(await screen.findByTestId('signed-proof'));
  await waitFor(() => expect(globalThis.fetch).toHaveBeenCalledWith(
    expect.stringContaining('/certificate-enrollment/login'), expect.anything()));
  expect(onLoginComplete).not.toHaveBeenCalled();
});

it('hands off to PACS only after INVIZ confirms the certificate login', async () => {
  const onLoginComplete = vi.fn(async () => {});
  globalThis.fetch = vi.fn().mockImplementation(async (url: string) => {
    if (url.includes('/config')) return new Response(JSON.stringify(config), { status: 200 });
    if (url.includes('/login-challenges')) return new Response(JSON.stringify(challenge), { status: 201 });
    if (url.endsWith('/certificate-enrollment/login')) return new Response(JSON.stringify({
      activationToken: 'verified-doctor-session', doctor: { id: 'doctor-1', status: 'ACTIVE' },
      enrollment: { registrationId: 'registration-1', externalStatus: 'GOOD' },
    }), { status: 200 });
    return new Response('{}', { status: 404 });
  });
  render(<AppProvider><CertificateEnrollmentView initialMode="PC_LOGIN" proofMethod="external"
    onLoginComplete={onLoginComplete} /></AppProvider>);
  fireEvent.click(await screen.findByTestId('request-login-challenge-btn'));
  fireEvent.click(await screen.findByTestId('signed-proof'));
  await waitFor(() => expect(onLoginComplete).toHaveBeenCalledWith('verified-doctor-session'));
});
