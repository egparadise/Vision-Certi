import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { PacsCertificateEnrollment } from '../components/PacsCertificateEnrollment';

vi.mock('../components/CertificateEnrollmentView', () => ({
  CertificateEnrollmentView: ({ initialChallenge, onEnrollmentComplete, proofMethod }: any) =>
    <button data-testid="accepted-challenge" data-proof-method={proofMethod} onClick={onEnrollmentComplete}>{initialChallenge.challengeId}</button>,
}));

describe('PACS first-login enrollment channel', () => {
  const nonce = 'a'.repeat(64);
  const opener = { postMessage: vi.fn() };
  const challenge = {
    purpose: 'CERTIFICATE_ENROLLMENT', registrationId: 'registration-1', challengeId: 'challenge-1',
    doctorId: 'doctor-1', tenantId: 'tenant-1', signedPayload: '{}', signedPayloadBase64: 'e30=',
    expiresAt: '2099-01-01T00:00:00Z', allowedSignatureAlgorithms: ['SHA256withRSA'],
  };
  const data = { type: 'VC_ENROLLMENT_CHALLENGE', nonce, audience: 'pacs-sv70', challenge };
  const deliver = (message = data, origin = 'https://pacs.internal', source = opener) => act(() => {
    window.dispatchEvent(new MessageEvent('message', { data: message, origin, source: source as unknown as Window }));
  });
  beforeEach(() => {
    vi.stubEnv('VITE_PACS_ALLOWED_ORIGINS', 'https://pacs.internal');
    vi.stubGlobal('opener', opener);
    opener.postMessage.mockClear();
    window.location.hash = '/certificates/pacs-enroll?' + new URLSearchParams({ nonce, audience: 'pacs-sv70', origin: 'https://pacs.internal' });
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); window.location.hash = ''; });

  it('rejects other origins, windows and nonces, accepts once and sends no credentials on completion', () => {
    render(<PacsCertificateEnrollment />);
    deliver(data, 'https://attacker.invalid');
    deliver(data, 'https://pacs.internal', { postMessage: vi.fn() });
    deliver({ ...data, nonce: 'b'.repeat(64) });
    expect(screen.queryByTestId('accepted-challenge')).toBeNull();
    deliver();
    expect(screen.getByTestId('accepted-challenge')).toHaveTextContent('challenge-1');
    expect(screen.getByTestId('accepted-challenge')).toHaveAttribute('data-proof-method', 'external');
    deliver({ ...data, challenge: { ...challenge, challengeId: 'replacement' } });
    expect(screen.getByTestId('accepted-challenge')).toHaveTextContent('challenge-1');
    fireEvent.click(screen.getByTestId('accepted-challenge'));
    expect(opener.postMessage).toHaveBeenLastCalledWith({ type: 'VC_ENROLLMENT_COMPLETE', nonce, audience: 'pacs-sv70' }, 'https://pacs.internal');
  });

  it('does not accept an expired challenge or an unconfigured PACS origin', () => {
    const view = render(<PacsCertificateEnrollment />);
    deliver({ ...data, challenge: { ...challenge, expiresAt: '2000-01-01T00:00:00Z' } });
    expect(screen.queryByTestId('accepted-challenge')).toBeNull();
    view.unmount();
    vi.stubEnv('VITE_PACS_ALLOWED_ORIGINS', '');
    render(<PacsCertificateEnrollment />);
    deliver();
    expect(screen.queryByTestId('accepted-challenge')).toBeNull();
    expect(screen.getByText(/관리자 초대코드는 필요하지 않습니다/)).toBeInTheDocument();
  });
});
