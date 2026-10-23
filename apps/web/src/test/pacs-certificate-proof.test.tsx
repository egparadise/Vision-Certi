import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PacsCertificateProof } from '../components/PacsCertificateProof';
import { ApiClientError } from '../api/client';

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
  ExternalCertificateProof: ({ onProof, onCancel }: { onProof: (value: object) => Promise<void>; onCancel?: () => void }) => <>
    <button onClick={() => void onProof({ certificateDerBase64: 'public-cert', chainDerBase64: [],
      signatureBase64: 'signature', signatureAlgorithm: 'SHA256withRSA' }).catch(() => {})}>
      인증서 확인
    </button>
    {onCancel && <button onClick={onCancel}>취소</button>}
  </>,
}));

describe('PACS certificate-only proof', () => {
  const nonce = 'b'.repeat(64);
  const opener = { postMessage: vi.fn(), closed: false };
  const challenge = (challengeId: string) => ({ challengeId, purpose: 'PACS_CERTIFICATE_PROOF',
    signedPayload: 'purpose=PACS_CERTIFICATE_PROOF\naudience=pacs-sv70\n',
    expiresAt: new Date(Date.now() + 60000).toISOString() });
  const serverError = (code: string, message: string) =>
    new ApiClientError(422, { code, message, correlationId: 'test', retryable: false });
  beforeEach(() => {
    vi.stubEnv('VITE_PACS_ALLOWED_ORIGINS', 'https://pacs.internal,http://tauri.localhost');
    vi.stubGlobal('opener', opener);
    opener.postMessage.mockClear();
    mocks.start.mockReset(); mocks.verify.mockReset();
    window.location.hash = '/certificates/pacs-proof?' + new URLSearchParams({
      nonce, audience: 'pacs-sv70', origin: 'https://pacs.internal',
    });
    mocks.start.mockResolvedValue(challenge('challenge-1'));
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
    expect(await screen.findByRole('heading', { name: '인증되었습니다.' })).toBeInTheDocument();
  });

  it('does not start verification for an untrusted caller', () => {
    window.location.hash = '/certificates/pacs-proof?' + new URLSearchParams({
      nonce, audience: 'pacs-sv70', origin: 'https://attacker.invalid',
    });
    render(<PacsCertificateProof />);
    expect(screen.getByRole('alert')).toHaveTextContent('PACS');
    expect(mocks.start).not.toHaveBeenCalled();
  });

  it('binds PC proof to S256 and waits for PACS before the final success screen', async () => {
    const challenge = 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM';
    window.location.hash += `&codeChallenge=${challenge}&codeChallengeMethod=S256`;
    mocks.start.mockResolvedValue({ challengeId: 'challenge-1', purpose: 'PACS_CERTIFICATE_PROOF',
      signedPayload: `audience=pacs-sv70\npcNonce=${nonce}\ncodeChallengeMethod=S256\ncodeChallenge=${challenge}`,
      expiresAt: new Date(Date.now() + 60000).toISOString() });
    mocks.verify.mockResolvedValue({ verificationCode: 'A'.repeat(43), audience: 'pacs-sv70',
      expiresAt: new Date(Date.now() + 60000).toISOString() });
    render(<PacsCertificateProof />);
    fireEvent.click(await screen.findByRole('button', { name: '인증서 확인' }));
    await screen.findByRole('status');
    expect(mocks.start).toHaveBeenCalledWith('pacs-sv70', { pcNonce: nonce, codeChallenge: challenge, codeChallengeMethod: 'S256' });
    expect(screen.queryByRole('heading', { name: '인증되었습니다.' })).not.toBeInTheDocument();
  });

  it('rejects a server challenge which silently drops the PC binding', async () => {
    window.location.hash += '&codeChallenge=' + 'A'.repeat(43) + '&codeChallengeMethod=S256';
    render(<PacsCertificateProof />);
    expect(await screen.findByRole('alert')).toHaveTextContent('PC 인증 요청');
    expect(screen.queryByRole('button', { name: '인증서 확인' })).not.toBeInTheDocument();
    expect(mocks.verify).not.toHaveBeenCalled();
  });

  it.each(['invalid-date', new Date(0).toISOString()])('never displays completion for an invalid expiry: %s', async expiresAt => {
    mocks.verify.mockResolvedValue({ verificationCode: 'A'.repeat(43), audience: 'pacs-sv70', expiresAt });
    render(<PacsCertificateProof />);
    fireEvent.click(await screen.findByRole('button', { name: '인증서 확인' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('전달할 수 없습니다'));
    expect(screen.queryByRole('heading', { name: '인증되었습니다.' })).not.toBeInTheDocument();
    expect(opener.postMessage).not.toHaveBeenCalledWith(expect.objectContaining({ type: 'VC_CERTIFICATE_PROOF_CODE' }), expect.anything());
  });

  it('keeps a failed verification on the input screen instead of showing success', async () => {
    mocks.verify.mockRejectedValue(new Error('서명 검증에 실패했습니다.'));
    render(<PacsCertificateProof />);
    fireEvent.click(await screen.findByRole('button', { name: '인증서 확인' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('서명 검증에 실패'));
    expect(screen.queryByRole('heading', { name: '인증되었습니다.' })).not.toBeInTheDocument();
  });

  it('obtains a fresh challenge after revocation lookup fails so the same window can retry', async () => {
    mocks.verify.mockRejectedValueOnce(new ApiClientError(503, {
      code: 'CERTIFICATE_REVOCATION_UNAVAILABLE', message: '폐지 상태 조회 실패', correlationId: 'test-crl', retryable: true,
    })).mockResolvedValueOnce({ verificationCode: 'A'.repeat(43), audience: 'pacs-sv70',
      expiresAt: new Date(Date.now() + 60000).toISOString() });
    render(<PacsCertificateProof />);
    fireEvent.click(await screen.findByRole('button', { name: '인증서 확인' }));
    await waitFor(() => expect(mocks.start).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole('alert')).toHaveTextContent('폐지 상태 조회 실패');
    expect(opener.postMessage).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole('button', { name: '인증서 확인' }));
    expect(await screen.findByRole('heading', { name: '인증되었습니다.' })).toBeInTheDocument();
  });
  it('requests a fresh challenge after the server rejects a proof and keeps the reason visible', async () => {
    mocks.start.mockResolvedValueOnce(challenge('challenge-1')).mockResolvedValueOnce(challenge('challenge-2'));
    mocks.verify.mockRejectedValueOnce(serverError('CERTIFICATE_UNTRUSTED', '설정된 신뢰 기관까지 유효한 인증서 경로를 만들 수 없습니다.'))
      .mockResolvedValueOnce({ verificationCode: 'A'.repeat(43), audience: 'pacs-sv70',
        expiresAt: new Date(Date.now() + 60000).toISOString() });
    render(<PacsCertificateProof />);
    fireEvent.click(await screen.findByRole('button', { name: '인증서 확인' }));
    await waitFor(() => expect(mocks.start).toHaveBeenCalledTimes(2));
    expect(screen.getByRole('alert')).toHaveTextContent('유효한 인증서 경로');
    expect(opener.postMessage).toHaveBeenCalledWith(expect.objectContaining({ type: 'VC_CERTIFICATE_PROOF_ERROR' }), 'https://pacs.internal');
    expect(screen.queryByRole('heading', { name: '인증되었습니다.' })).not.toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: '인증서 확인' }));
    await waitFor(() => expect(mocks.verify).toHaveBeenLastCalledWith(expect.objectContaining({ challengeId: 'challenge-2' })));
    expect(await screen.findByRole('heading', { name: '인증되었습니다.' })).toBeInTheDocument();
  });

  it('explains an already-used challenge in Korean', async () => {
    mocks.verify.mockRejectedValue(serverError('CHALLENGE_ALREADY_USED', 'Challenge already used'));
    render(<PacsCertificateProof />);
    fireEvent.click(await screen.findByRole('button', { name: '인증서 확인' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('이미 사용되었습니다'));
    expect(screen.getByRole('alert')).not.toHaveTextContent('Challenge already used');
  });

  it('stops with configuration guidance when the PACS opened an outdated address', () => {
    window.location.hash = '/certificates/pacs-proof?' + new URLSearchParams({
      nonce, audience: 'pacs-sv70', origin: 'https://pacs.internal', vcRedirectedFrom: 'hahattalk-vision-certi.web.app',
    });
    render(<PacsCertificateProof />);
    expect(screen.getByRole('alert')).toHaveTextContent('hahattalk-vision-certi.web.app');
    expect(mocks.start).not.toHaveBeenCalled();
    expect(opener.postMessage).not.toHaveBeenCalled();
  });

  it('keeps an installed PACS popup open on cancel instead of closing its native frame', async () => {
    window.location.hash = '/certificates/pacs-proof?' + new URLSearchParams({
      nonce, audience: 'pacs-sv70', origin: 'http://tauri.localhost',
    });
    render(<PacsCertificateProof />);
    fireEvent.click(await screen.findByRole('button', { name: '취소' }));
    expect(screen.getByRole('status')).toHaveTextContent('인증을 취소했습니다');
    expect(opener.postMessage).toHaveBeenCalledWith({ type: 'VC_CERTIFICATE_PROOF_ERROR', nonce,
      audience: 'pacs-sv70', code: 'CERTIFICATE_PROOF_CANCELLED' }, 'http://tauri.localhost');
    expect(mocks.verify).not.toHaveBeenCalled();
  });

  it('lets a browser PACS popup close itself on cancel', async () => {
    render(<PacsCertificateProof />);
    await screen.findByRole('button', { name: '인증서 확인' });
    expect(screen.queryByRole('button', { name: '취소' })).toBeNull();
  });
});
