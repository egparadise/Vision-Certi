import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PacsCertificateLogin } from '../components/PacsCertificateLogin';

const mocks = vi.hoisted(() => ({ createPacsHandoff: vi.fn() }));
vi.mock('../context/AppContext', () => ({ useApp: () => ({ client: { createPacsHandoff: mocks.createPacsHandoff } }) }));
vi.mock('../components/CertificateEnrollmentView', () => ({
  CertificateEnrollmentView: ({ onLoginComplete, onLoginFailed }: {
    onLoginComplete: (token: string) => Promise<void>; onLoginFailed: (code: string) => void;
  }) => <>
    <button onClick={() => void onLoginComplete('server-verified-certificate-session').catch(() => {})}>
      서버 인증 완료
    </button>
    <button onClick={() => onLoginFailed('CERTIFICATE_NOT_ENROLLED')}>서버 인증 거부</button>
  </>,
}));

describe('PACS certificate login handoff', () => {
  const nonce = 'a'.repeat(64);
  const opener = { postMessage: vi.fn(), closed: false };
  beforeEach(() => {
    vi.stubEnv('VITE_PACS_ALLOWED_ORIGINS', 'https://pacs.internal');
    vi.stubGlobal('opener', opener);
    opener.closed = false;
    opener.postMessage.mockClear();
    mocks.createPacsHandoff.mockReset();
    window.location.hash = '/certificates/pacs-login?' + new URLSearchParams({
      nonce, audience: 'pacs-sv70', origin: 'https://pacs.internal',
    });
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); window.location.hash = ''; });

  it('returns a short-lived code to the exact PACS opener after verified login', async () => {
    const code = 'A'.repeat(43);
    mocks.createPacsHandoff.mockResolvedValue({ handoffCode: code, audience: 'pacs-sv70',
      expiresAt: new Date(Date.now() + 60000).toISOString() });
    render(<PacsCertificateLogin />);
    fireEvent.click(screen.getByRole('button', { name: '서버 인증 완료' }));
    await waitFor(() => expect(opener.postMessage).toHaveBeenCalledWith({
      type: 'VC_HANDOFF_CODE', nonce, audience: 'pacs-sv70', handoffCode: code, certificatePasswordVerified: true,
    }, 'https://pacs.internal'));
    expect(mocks.createPacsHandoff).toHaveBeenCalledWith('server-verified-certificate-session', 'pacs-sv70');
    expect(screen.getByRole('status')).toHaveTextContent('PACS');
    expect(screen.getByRole('heading', { name: '인증되었습니다.' })).toBeInTheDocument();
    expect(JSON.stringify(opener.postMessage.mock.calls)).not.toContain('server-verified-certificate-session');
  });

  it('never posts a code if PACS handoff issuance fails or the result is expired', async () => {
    mocks.createPacsHandoff.mockRejectedValueOnce(new Error('not enrolled'))
      .mockResolvedValueOnce({ handoffCode: 'A'.repeat(43), audience: 'pacs-sv70', expiresAt: '2000-01-01T00:00:00Z' });
    render(<PacsCertificateLogin />);
    fireEvent.click(screen.getByRole('button', { name: '서버 인증 완료' }));
    await waitFor(() => expect(mocks.createPacsHandoff).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: '서버 인증 완료' }));
    await waitFor(() => expect(mocks.createPacsHandoff).toHaveBeenCalledTimes(2));
    expect(opener.postMessage).not.toHaveBeenCalled();
  });

  it('returns a correlated failure to PACS without issuing a handoff code', () => {
    render(<PacsCertificateLogin />);
    fireEvent.click(screen.getByRole('button', { name: '서버 인증 거부' }));
    expect(opener.postMessage).toHaveBeenCalledWith({ type: 'VC_HANDOFF_ERROR', nonce,
      audience: 'pacs-sv70', code: 'CERTIFICATE_NOT_ENROLLED' }, 'https://pacs.internal');
    expect(mocks.createPacsHandoff).not.toHaveBeenCalled();
  });

  it('explains an outdated PACS address instead of starting certificate login', () => {
    window.location.hash = '/certificates/pacs-login?' + new URLSearchParams({
      nonce, audience: 'pacs-sv70', origin: 'https://pacs.internal', vcRedirectedFrom: 'vision-certi.firebaseapp.com',
    });
    render(<PacsCertificateLogin />);
    expect(screen.getByRole('alert')).toHaveTextContent('vision-certi.firebaseapp.com');
    expect(screen.queryByRole('button', { name: '서버 인증 완료' })).toBeNull();
    expect(mocks.createPacsHandoff).not.toHaveBeenCalled();
  });

  it('rejects an untrusted PACS origin before certificate login begins', () => {
    window.location.hash = '/certificates/pacs-login?' + new URLSearchParams({
      nonce, audience: 'pacs-sv70', origin: 'https://attacker.invalid',
    });
    render(<PacsCertificateLogin />);
    expect(screen.getByRole('alert')).toHaveTextContent('유효하지 않습니다');
    expect(screen.queryByRole('button', { name: '서버 인증 완료' })).toBeNull();
    expect(mocks.createPacsHandoff).not.toHaveBeenCalled();
  });
});
