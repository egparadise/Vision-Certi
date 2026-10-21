import { useCallback, useState } from 'react';
import { useApp } from '../context/AppContext';
import { CertificateEnrollmentView } from './CertificateEnrollmentView';

type PacsLoginContext = { origin: string; nonce: string; audience: string; opener: Window };

function getPacsLoginContext(): PacsLoginContext | null {
  const query = new URLSearchParams(window.location.hash.split('?')[1] || '');
  const nonce = query.get('nonce');
  const audience = query.get('audience');
  const requestedOrigin = query.get('origin');
  if (!window.opener || window.opener.closed || !nonce || !/^[0-9a-f]{64}$/.test(nonce)
    || !audience || !/^[a-zA-Z0-9._:-]{1,128}$/.test(audience) || !requestedOrigin) return null;
  try {
    const origin = new URL(requestedOrigin).origin;
    const configured = (import.meta.env.VITE_PACS_ALLOWED_ORIGINS || '').split(',').map((item: string) => item.trim());
    if (origin === 'null' || requestedOrigin !== origin
      || !(origin === window.location.origin || configured.includes(origin))) return null;
    return { origin, nonce, audience, opener: window.opener };
  } catch { return null; }
}

/** The PACS window redeems the one-time code with its server before it creates a PACS session. */
export function PacsCertificateLogin() {
  const { client } = useApp();
  const [context] = useState(getPacsLoginContext);
  const [delivered, setDelivered] = useState(false);
  const complete = useCallback(async (activationToken: string) => {
    if (!context || context.opener.closed) throw new Error('PACS 로그인 창이 닫혔습니다. PACS에서 다시 시도해 주세요.');
    const handoff = await client.createPacsHandoff(activationToken, context.audience);
    const expiresAt = Date.parse(handoff.expiresAt);
    if (handoff.audience !== context.audience || !/^[A-Za-z0-9_-]{43}$/.test(handoff.handoffCode)
      || !Number.isFinite(expiresAt) || expiresAt <= Date.now() || context.opener.closed) {
      throw new Error('PACS 로그인 인증 결과를 전달할 수 없습니다. 다시 시도해 주세요.');
    }
    context.opener.postMessage({
      type: 'VC_HANDOFF_CODE',
      nonce: context.nonce,
      audience: context.audience,
      handoffCode: handoff.handoffCode,
      // Only reached after INVIZ verified a signature made with the private key that the certificate password
      // unlocked. Informational for the PACS screen: the PACS server must still redeem the code (server-to-server),
      // whose response carries the authoritative certificatePasswordVerified flag. The password never leaves the PC.
      certificatePasswordVerified: true,
    }, context.origin);
    setDelivered(true);
  }, [client, context]);
  const fail = useCallback((code: string) => {
    if (!context || context.opener.closed) return;
    context.opener.postMessage({ type: 'VC_HANDOFF_ERROR', nonce: context.nonce,
      audience: context.audience, code: /^[A-Z_]{1,80}$/.test(code) ? code : 'AUTHENTICATION_FAILED' },
      context.origin);
  }, [context]);

  if (!context) return <section role="alert">PACS 로그인 요청이 유효하지 않습니다. PACS 로그인 화면에서 다시 시작해 주세요.</section>;
  if (delivered) return <section className="certificate-complete" role="status">
    <h1>인증이 완료되었습니다.</h1>
    <p>공동인증서 확인 결과를 PACS에 전달했습니다. PACS 로그인 화면에서 결과를 확인해 주세요.</p>
  </section>;
  return <CertificateEnrollmentView initialMode="PC_LOGIN" proofMethod="external"
    onLoginComplete={complete} onLoginFailed={fail} />;
}
