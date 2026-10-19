import type { VcConfig } from '@shared/types/visionCerti.types';

/** Only correlated messages from the configured INVIZ popup are accepted. No credentials in URLs. */
export function enrollPacsCertificate(
  config: VcConfig,
  begin: () => Promise<{ challenge: Record<string, unknown> }>,
): Promise<void> {
  if (config.state !== 'ENABLED' || !config.visionCertiBaseUrl || !config.audience) {
    return Promise.reject(new Error('인증서 등록 서버가 준비되지 않았습니다.'));
  }
  const target = new URL(config.visionCertiBaseUrl);
  if (target.username || target.password || !(target.protocol === 'https:' ||
      (target.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(target.hostname)))) {
    return Promise.reject(new Error('인증서 등록 서버 주소가 올바르지 않습니다.'));
  }
  if (window.location.origin === 'null') {
    return Promise.reject(new Error('이 PACS 데스크톱 환경에는 인증기관 창과 연결하는 검증된 브리지가 필요합니다.'));
  }
  const nonce = Array.from(crypto.getRandomValues(new Uint8Array(32)), b => b.toString(16).padStart(2, '0')).join('');
  target.hash = '/certificates/pacs-enroll?' + new URLSearchParams({
    nonce, audience: config.audience, origin: window.location.origin,
  }).toString();
  const popup = window.open(target.href, 'VisionCertiEnrollment', 'width=900,height=900');
  if (!popup) return Promise.reject(new Error('브라우저 팝업을 허용해주세요.'));
  return new Promise((resolve, reject) => {
    let done = false;
    let ready = false;
    let challenge: Record<string, unknown> | null = null;
    const send = () => {
      if (!done && ready && challenge) popup.postMessage({
        type: 'VC_ENROLLMENT_CHALLENGE', nonce, audience: config.audience, challenge,
      }, target.origin);
    };
    const finish = (error?: Error) => {
      if (done) return;
      done = true;
      window.removeEventListener('message', receive);
      window.clearInterval(poll);
      window.clearTimeout(timeout);
      if (!popup.closed) popup.close();
      if (error) reject(error); else resolve();
    };
    const receive = (event: MessageEvent) => {
      if (done || event.origin !== target.origin || event.source !== popup ||
          event.data?.nonce !== nonce || event.data?.audience !== config.audience) return;
      if (event.data.type === 'VC_ENROLLMENT_READY') { ready = true; send(); }
      if (event.data.type === 'VC_ENROLLMENT_COMPLETE' && ready && challenge) finish();
    };
    window.addEventListener('message', receive);
    const poll = window.setInterval(() => {
      if (popup.closed) finish(new Error('인증서 등록 창을 닫았습니다. 등록 상태를 확인하거나 다시 진행해주세요.'));
    }, 500);
    const timeout = window.setTimeout(() => finish(new Error('등록 시간이 지났습니다. 다시 진행해주세요.')), 900000);
    begin().then(result => { if (!done) { challenge = result.challenge; send(); } })
      .catch(() => finish(new Error('PACS 계정의 인증서 등록 요청에 실패했습니다. 계정·면허·소속 정보를 확인해주세요.')));
  });
}
