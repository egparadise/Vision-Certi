import { useCallback, useEffect, useState } from 'react';
import { useApp } from '../context/AppContext';
import { ApiClientError } from '../api/client';
import { ExternalCertificateProof } from './ExternalCertificateProof';
import { CertificateComplete } from './CertificateComplete';
import { computeCertificateFingerprint } from '../utils/cryptoCert';
import { pacsOpenerContext, pacsRedirectedFromHost } from '../utils/pacsOpener';
import type { CertificateChallenge } from '../types/api';
import type { LocalSignResult } from '../services/localCertSdkAdapter';

/** Server codes whose messages are English; the certificate validator already answers in Korean. */
const SERVER_MESSAGES: Record<string, string> = {
  CHALLENGE_ALREADY_USED: '이 인증 요청은 이미 사용되었습니다. 새 요청으로 다시 확인해 주세요.',
  CHALLENGE_EXPIRED: '인증 요청이 만료되었습니다. 새 요청으로 다시 확인해 주세요.',
  CHALLENGE_NOT_FOUND: '인증 요청을 찾을 수 없습니다. 새 요청으로 다시 확인해 주세요.',
  FINGERPRINT_MISMATCH: '인증서 정보가 서명과 일치하지 않습니다. 인증서를 다시 선택해 주세요.',
  PROOF_AUDIENCE_INVALID: 'PACS 연동 식별자가 인증 서버 설정과 다릅니다. PACS 인증 설정을 확인해 주세요.',
  CERTIFICATE_TRUST_NOT_CONFIGURED: '인증 서버에 인증기관 신뢰 설정이 없어 지금은 확인할 수 없습니다.',
  CERTIFICATE_CAPACITY_EXCEEDED: '인증 서버가 혼잡합니다. 잠시 후 다시 시도해 주세요.',
};

function describe(failure: unknown, fallback: string): string {
  if (failure instanceof ApiClientError) return SERVER_MESSAGES[failure.code] ?? failure.message;
  return failure instanceof Error ? failure.message : fallback;
}

const noticeStyle = { maxWidth: 560, margin: '64px auto', padding: '40px 24px', textAlign: 'center' as const,
  background: '#fff', border: '1px solid #dce5ef', borderRadius: 16, color: '#153153' };

/** Certificate-only attestation. PACS validates its own ID/PW and redeems the one-time code server-to-server. */
export function PacsCertificateProof() {
  const { client } = useApp();
  const [context] = useState(pacsOpenerContext);
  const [redirectedFrom] = useState(pacsRedirectedFromHost);
  const [challenge, setChallenge] = useState<CertificateChallenge | null>(null);
  const [loading, setLoading] = useState(false);
  const [delivered, setDelivered] = useState(false);
  const [cancelled, setCancelled] = useState(false);
  const [error, setError] = useState('');

  const begin = useCallback(async (keepError = false) => {
    if (!context || redirectedFrom) return;
    setLoading(true); if (!keepError) setError(''); setChallenge(null);
    try {
      const next = await client.createPacsCertificateProofChallenge(context.audience, context.codeChallenge ? {
        pcNonce: context.nonce, codeChallenge: context.codeChallenge, codeChallengeMethod: 'S256',
      } : undefined);
      if (next.purpose !== 'PACS_CERTIFICATE_PROOF' || !next.signedPayload.includes(`audience=${context.audience}\n`)
        || !Number.isFinite(Date.parse(next.expiresAt)) || Date.parse(next.expiresAt) <= Date.now()) throw new Error('인증서 검증 요청이 유효하지 않습니다.');
      if (context.codeChallenge) {
        const lines = next.signedPayload.split('\n');
        if (!lines.includes(`pcNonce=${context.nonce}`) || !lines.includes('codeChallengeMethod=S256') ||
          !lines.includes(`codeChallenge=${context.codeChallenge}`)) throw new Error('PC 인증 요청을 확인할 수 없습니다.');
      }
      setChallenge(next);
    } catch (failure) {
      setError(describe(failure, '인증서 검증 요청에 실패했습니다.'));
    } finally { setLoading(false); }
  }, [client, context, redirectedFrom]);

  useEffect(() => { void begin(); }, [begin]);

  const submit = useCallback(async (proof: LocalSignResult) => {
    if (!challenge || !context || context.opener.closed) throw new Error('PACS 창을 다시 열어 주세요.');
    setLoading(true); setError('');
    let refresh = false;
    try {
      const result = await client.verifyPacsCertificateProof({
        challengeId: challenge.challengeId,
        fingerprintSha256: await computeCertificateFingerprint(proof.certificateDerBase64),
        certificate: proof.certificateDerBase64,
        chain: proof.chainDerBase64,
        signature: proof.signatureBase64,
        signatureAlgorithm: proof.signatureAlgorithm,
      });
      if (result.audience !== context.audience || !/^[A-Za-z0-9_-]{43}$/.test(result.verificationCode)
        || !Number.isFinite(Date.parse(result.expiresAt)) || Date.parse(result.expiresAt) <= Date.now() || context.opener.closed) {
        throw new Error('검증 결과를 PACS에 전달할 수 없습니다.');
      }
      context.opener.postMessage({ type: 'VC_CERTIFICATE_PROOF_CODE', nonce: context.nonce,
        audience: context.audience, verificationCode: result.verificationCode }, context.origin);
      setDelivered(true);
    } catch (failure) {
      setError(describe(failure, '인증서 검증에 실패했습니다.'));
      refresh = failure instanceof ApiClientError;
      // A temporary provider outage must not cancel the helper's still-pending S256 attempt.
      if (!context.opener.closed && !(failure instanceof ApiClientError && failure.retryable)) {
        context.opener.postMessage({ type: 'VC_CERTIFICATE_PROOF_ERROR', nonce: context.nonce,
          audience: context.audience, code: 'CERTIFICATE_PROOF_FAILED' }, context.origin);
      }
      throw failure;
    } finally {
      setLoading(false);
      // A server verification attempt consumes its challenge even when revocation data is unavailable.
      if (refresh) void begin(true);
    }
  }, [begin, challenge, client, context]);

  // An installed PACS cannot close this popup from script without leaving an empty native frame, so the page stays
  // and asks the doctor to close it. PACS treats the closed window as a cancelled login.
  const cancel = useCallback(() => {
    if (!context) return;
    setCancelled(true);
    if (!context.opener.closed) {
      context.opener.postMessage({ type: 'VC_CERTIFICATE_PROOF_ERROR', nonce: context.nonce,
        audience: context.audience, code: 'CERTIFICATE_PROOF_CANCELLED' }, context.origin);
    }
  }, [context]);

  if (redirectedFrom) return <section role="alert" style={noticeStyle}>
    <h2>PACS 인증 설정의 주소가 오래되었습니다</h2>
    <p>PACS가 이전 주소 <code>{redirectedFrom}</code>로 인증 창을 열어, 이 창의 확인 결과를 PACS가 받을 수 없습니다.</p>
    <p>PACS 인증 설정의 인증 서버 주소를 <code>{window.location.origin}</code>로 바꾼 뒤 다시 시도해 주세요. 이 창은 오른쪽 위 X로 닫아 주세요.</p>
  </section>;
  if (!context) return <section role="alert">PACS에서 인증서 확인을 다시 시작해 주세요.</section>;
  if (delivered) return context.codeChallenge
    ? <section role="status"><h2>PACS 로그인 확인 중</h2><p>인증서 확인 결과를 PACS에 연결하고 있습니다. 잠시 기다려 주세요.</p></section>
    : <CertificateComplete />;
  if (cancelled) return <section role="status" style={noticeStyle}>
    <h1 style={{ fontSize: 28, margin: '16px 0' }}>인증을 취소했습니다.</h1>
    <p>오른쪽 위 X로 이 창을 닫으면 PACS 로그인 화면으로 돌아갑니다.</p>
  </section>;
  return <section className="pacs-proof-container">
    <h2 className="enroll-sr-only">공동인증서 확인</h2>
    <p className="enroll-sr-only">PACS 계정 비밀번호는 PACS에서만 확인합니다. 이 화면은 공동인증서와 개인키 소유만 확인합니다.</p>
    {error && <p role="alert">{error}</p>}
    {challenge ? <ExternalCertificateProof key={challenge.challengeId} challenge={challenge} busy={loading}
      actionLabel="공동인증서 확인" onProof={submit} onRestart={() => void begin()}
      onCancel={context.nativeOpener ? cancel : undefined} />
      : <button type="button" disabled={loading} onClick={() => void begin()}>
        {loading ? '인증서 검증 준비 중' : '다시 시도'}
      </button>}
  </section>;
}
