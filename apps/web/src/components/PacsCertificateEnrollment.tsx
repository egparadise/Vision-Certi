import { useCallback, useEffect, useRef, useState } from 'react';
import { CertificateEnrollmentView } from './CertificateEnrollmentView';
import type { CertificateChallenge } from '../types/api';

function enrollmentContext() {
  const query = new URLSearchParams(window.location.hash.split('?')[1] || '');
  const nonce = query.get('nonce');
  const audience = query.get('audience');
  const inputOrigin = query.get('origin');
  if (!window.opener || !nonce || !/^[0-9a-f]{64}$/.test(nonce) || !audience || !inputOrigin) return null;
  try {
    const origin = new URL(inputOrigin).origin;
    const configured = (import.meta.env.VITE_PACS_ALLOWED_ORIGINS || '').split(',').map((s: string) => s.trim());
    if (origin === 'null' || !(origin === window.location.origin || configured.includes(origin))) return null;
    return { origin, nonce, audience, opener: window.opener as Window };
  } catch { return null; }
}

function validChallenge(value: unknown): value is CertificateChallenge {
  if (!value || typeof value !== 'object') return false;
  const c = value as CertificateChallenge;
  return c.purpose === 'CERTIFICATE_ENROLLMENT' && typeof c.registrationId === 'string'
    && typeof c.challengeId === 'string' && typeof c.doctorId === 'string'
    && typeof c.tenantId === 'string' && typeof c.signedPayload === 'string' && c.signedPayload.length < 16384
    && typeof c.signedPayloadBase64 === 'string' && c.signedPayloadBase64.length < 32768
    && Array.isArray(c.allowedSignatureAlgorithms) && Date.parse(c.expiresAt) > Date.now();
}

export function PacsCertificateEnrollment() {
  const [context] = useState(enrollmentContext);
  const [challenge, setChallenge] = useState<CertificateChallenge | null>(null);
  const [completed, setCompleted] = useState(false);
  const accepted = useRef(false);
  useEffect(() => {
    if (!context) return;
    const ready = () => {
      if (!accepted.current) context.opener.postMessage({ type: 'VC_ENROLLMENT_READY', nonce: context.nonce, audience: context.audience }, context.origin);
    };
    const receive = (event: MessageEvent) => {
      if (accepted.current || event.source !== context.opener || event.origin !== context.origin) return;
      if (event.data?.type !== 'VC_ENROLLMENT_CHALLENGE' || event.data.nonce !== context.nonce || event.data.audience !== context.audience) return;
      if (!validChallenge(event.data.challenge)) return;
      accepted.current = true;
      setChallenge(event.data.challenge);
    };
    window.addEventListener('message', receive);
    ready();
    const interval = window.setInterval(ready, 1000);
    return () => { window.removeEventListener('message', receive); window.clearInterval(interval); };
  }, [context]);
  const complete = useCallback(() => {
    setCompleted(true);
    context?.opener.postMessage({ type: 'VC_ENROLLMENT_COMPLETE', nonce: context.nonce, audience: context.audience }, context.origin);
  }, [context]);

  if (challenge) return <>
    {completed && <p role="status">등록·활성화가 완료되었습니다. PACS에서 등록 상태를 확인한 후 계속 진행해주세요.</p>}
    <CertificateEnrollmentView key={challenge.challengeId} initialChallenge={challenge} onEnrollmentComplete={complete} />
  </>;
  return <section className="personal-certificate-provider-notice" aria-labelledby="pacs-enrollment-title">
    <h2 id="pacs-enrollment-title">PACS 첫 로그인 시 공동인증서 등록</h2>
    <p>{context ? 'PACS에서 로그인 계정을 확인하고 있습니다.' : 'PACS 로그인 화면에서 기존 ID·비밀번호를 입력한 뒤, 최초 공동인증서 등록을 진행해주세요. 관리자 초대코드는 필요하지 않습니다.'}</p>
    <p>등록·활성화 후에도 매번 공동인증서로 로그인합니다. 판독문 최종 승인 시 서명 확인은 별도로 진행합니다.</p>
  </section>;
}
