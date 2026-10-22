import { useCallback, useEffect, useState } from 'react';
import { useApp } from '../context/AppContext';
import { ExternalCertificateProof } from './ExternalCertificateProof';
import { CertificateComplete } from './CertificateComplete';
import { computeCertificateFingerprint } from '../utils/cryptoCert';
import type { CertificateChallenge } from '../types/api';
import type { LocalSignResult } from '../services/localCertSdkAdapter';

type PacsContext = { origin: string; nonce: string; audience: string; opener: Window };

function pacsContext(): PacsContext | null {
  const query = new URLSearchParams(window.location.hash.split('?')[1] || '');
  const nonce = query.get('nonce');
  const audience = query.get('audience');
  const requestedOrigin = query.get('origin');
  if (!window.opener || window.opener.closed || !nonce || !/^[0-9a-f]{64}$/.test(nonce)
    || !audience || !/^[a-zA-Z0-9._:-]{1,128}$/.test(audience) || !requestedOrigin) return null;
  try {
    const origin = new URL(requestedOrigin).origin;
    const allowed = (import.meta.env.VITE_PACS_ALLOWED_ORIGINS || '').split(',').map((value: string) => value.trim());
    if (origin === 'null' || requestedOrigin !== origin ||
      !(origin === window.location.origin || allowed.includes(origin))) return null;
    return { origin, nonce, audience, opener: window.opener };
  } catch { return null; }
}

/** Certificate-only attestation. PACS validates its own ID/PW and redeems the one-time code server-to-server. */
export function PacsCertificateProof() {
  const { client } = useApp();
  const [context] = useState(pacsContext);
  const [challenge, setChallenge] = useState<CertificateChallenge | null>(null);
  const [loading, setLoading] = useState(false);
  const [delivered, setDelivered] = useState(false);
  const [error, setError] = useState('');

  const begin = useCallback(async () => {
    if (!context) return;
    setLoading(true); setError(''); setChallenge(null);
    try {
      const next = await client.createPacsCertificateProofChallenge(context.audience);
      if (next.purpose !== 'PACS_CERTIFICATE_PROOF' || !next.signedPayload.includes(`audience=${context.audience}\n`)
        || !Number.isFinite(Date.parse(next.expiresAt)) || Date.parse(next.expiresAt) <= Date.now()) throw new Error('인증서 검증 요청이 유효하지 않습니다.');
      setChallenge(next);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '인증서 검증 요청에 실패했습니다.');
    } finally { setLoading(false); }
  }, [client, context]);

  useEffect(() => { void begin(); }, [begin]);

  const submit = useCallback(async (proof: LocalSignResult) => {
    if (!challenge || !context || context.opener.closed) throw new Error('PACS 창을 다시 열어 주세요.');
    setLoading(true); setError('');
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
      const message = failure instanceof Error ? failure.message : '인증서 검증에 실패했습니다.';
      setError(message);
      context.opener.postMessage({ type: 'VC_CERTIFICATE_PROOF_ERROR', nonce: context.nonce,
        audience: context.audience, code: 'CERTIFICATE_PROOF_FAILED' }, context.origin);
      throw failure;
    } finally { setLoading(false); }
  }, [challenge, client, context]);

  if (!context) return <section role="alert">PACS에서 인증서 확인을 다시 시작해 주세요.</section>;
  if (delivered) return <CertificateComplete />;
  return <section className="personal-certificate-page">
    <h2>공동인증서 확인</h2>
    <p>PACS 계정 비밀번호는 PACS에서만 확인합니다. 이 화면은 공동인증서와 개인키 소유만 확인합니다.</p>
    {error && <p role="alert">{error}</p>}
    {challenge ? <ExternalCertificateProof key={challenge.challengeId} challenge={challenge} busy={loading}
      actionLabel="공동인증서 확인" onProof={submit} onRestart={() => void begin()} />
      : <button type="button" disabled={loading} onClick={() => void begin()}>
        {loading ? '인증서 검증 준비 중' : '다시 시도'}
      </button>}
  </section>;
}
