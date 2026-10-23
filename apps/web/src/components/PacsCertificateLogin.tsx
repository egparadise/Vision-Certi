import { useCallback, useState } from 'react';
import { useApp } from '../context/AppContext';
import { CertificateEnrollmentView } from './CertificateEnrollmentView';
import { CertificateComplete } from './CertificateComplete';
import { pacsOpenerContext, pacsRedirectedFromHost } from '../utils/pacsOpener';

/** The PACS window redeems the one-time code with its server before it creates a PACS session. */
export function PacsCertificateLogin() {
  const { client } = useApp();
  const [context] = useState(pacsOpenerContext);
  const [redirectedFrom] = useState(pacsRedirectedFromHost);
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

  if (redirectedFrom) return <section role="alert">
    PACS가 이전 주소({redirectedFrom})로 로그인 창을 열어 결과를 PACS에 전달할 수 없습니다. PACS 인증 설정의 인증 서버 주소를 {window.location.origin}로 바꾼 뒤 다시 시도해 주세요.
  </section>;
  if (!context) return <section role="alert">PACS 로그인 요청이 유효하지 않습니다. PACS 로그인 화면에서 다시 시작해 주세요.</section>;
  if (delivered) return <CertificateComplete />;
  return <CertificateEnrollmentView initialMode="PC_LOGIN" proofMethod="external"
    onLoginComplete={complete} onLoginFailed={fail} />;
}
