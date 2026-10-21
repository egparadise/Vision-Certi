import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PacsCertificateProof } from '../components/PacsCertificateProof';

const mocks = vi.hoisted(() => {
  const start = vi.fn();
  const verify = vi.fn();
  return { start, verify, client: {
    createPacsCertificateProofChallenge: start, verifyPacsCertificateProof: verify,
  } };
});
vi.mock('../context/AppContext', () => ({ useApp: () => ({ client: mocks.client }) }));
vi.mock('../utils/cryptoCert', () => ({ computeCertificateFingerprint: async () => 'f'.repeat(64) }));
vi.mock('../components/ExternalCertificateProof', () => ({
  ExternalCertificateProof: ({ onProof }: { onProof: (value: object) => Promise<void> }) =>
    <button onClick={() => void onProof({ certificateDerBase64: 'public-cert', chainDerBase64: [],
      signatureBase64: 'signature', signatureAlgorithm: 'SHA256withRSA' }).catch(() => {})}>
      인증서 확인
    </button>,
}));

describe('PACS certificate-only proof', () => {
  const nonce = 'b'.repeat(64);
  const opener = { postMessage: vi.fn(), closed: false };
  beforeEach(() => {
    vi.stubEnv('VITE_PACS_ALLOWED_ORIGINS', 'https://pacs.internal');
    vi.stubGlobal('opener', opener);
    opener.postMessage.mockClear();
    mocks.start.mockReset(); mocks.verify.mockReset();
    window.location.hash = '/certificates/pacs-proof?' + new URLSearchParams({
      nonce, audience: 'pacs-sv70', origin: 'https://pacs.internal',
    });
    mocks.start.mockResolvedValue({ challengeId: 'challenge-1', purpose: 'PACS_CERTIFICATE_PROOF',
      signedPayload: 'purpose=PACS_CERTIFICATE_PROOF\naudience=pacs-sv70\n',
      expiresAt: new Date(Date.now() + 60000).toISOString() });
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); window.location.hash = ''; });

  it('posts only the one-time code after server verifies the signed certificate challenge', async () => {
    mocks.verify.mockResolvedValue({ verificationCode: 'A'.repeat(43), audience: 'pacs-sv70',
      expiresAt: new Date(Date.now() + 60000).toISOString() });
    render(<PacsCertificateProof />);
    fireEvent.click(await screen.findByRole('button', { name: '인증서 확인' }));
    await waitFor(() => expect(opener.postMessage).toHaveBeenCalledWith({
      type: 'VC_CERTIFICATE_PROOF_CODE', nonce, audience: 'pacs-sv70', verificationCode: 'A'.repeat(43),
    }, 'https://pacs.internal'));
    expect(mocks.verify).toHaveBeenCalledWith(expect.objectContaining({
      challengeId: 'challenge-1', fingerprintSha256: 'f'.repeat(64), certificate: 'public-cert',
    }));
    expect(JSON.stringify(opener.postMessage.mock.calls)).not.toContain('public-cert');
    expect(await screen.findByRole('heading', { name: '인증이 완료되었습니다.' })).toBeInTheDocument();
  });

  it('does not start verification for an untrusted caller', () => {
    window.location.hash = '/certificates/pacs-proof?' + new URLSearchParams({
      nonce, audience: 'pacs-sv70', origin: 'https://attacker.invalid',
    });
    render(<PacsCertificateProof />);
    expect(screen.getByRole('alert')).toHaveTextContent('PACS');
    expect(mocks.start).not.toHaveBeenCalled();
  });
});
