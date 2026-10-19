import type { VcConfig } from '@shared/types/visionCerti.types';

/** Correlate exactly one response from the configured INVIZ popup. No bearer enters URLs/storage. */
export function requestCertificateHandoff(config: VcConfig): Promise<string> {
  if (config.state !== 'ENABLED' || !config.visionCertiBaseUrl || !config.audience) {
    return Promise.reject(new Error('인증 서버 연결 설정이 준비되지 않았습니다.'));
  }
  const base = new URL(config.visionCertiBaseUrl);
  if (base.username || base.password || !(base.protocol === 'https:' ||
      (base.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(base.hostname)))) {
    return Promise.reject(new Error('인증 서버 주소를 사용할 수 없습니다.'));
  }
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  const nonce = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  const query = new URLSearchParams({nonce, audience: config.audience, origin: window.location.origin});
  base.hash = '/certificates/pacs-login?' + query.toString();
  const popup = window.open(base.href, 'VisionCertiAuthPopup', 'width=720,height=850');
  if (!popup) return Promise.reject(new Error('브라우저 팝업을 허용해 주세요.'));
  return new Promise((resolve, reject) => {
    let completed = false;
    const finish = (code?: string, error?: string) => {
      if (completed) return;
      completed = true;
      window.removeEventListener('message', receive);
      clearInterval(poll); clearTimeout(timeout);
      if (!popup.closed) popup.close();
      if (code) resolve(code); else reject(new Error(error || '본인확인이 취소되었습니다.'));
    };
    const receive = (event: MessageEvent) => {
      if (event.origin !== base.origin || event.source !== popup || event.data?.type !== 'VC_HANDOFF_CODE') return;
      if (event.data.nonce !== nonce || event.data.audience !== config.audience) return;
      if (typeof event.data.handoffCode !== 'string' || !event.data.handoffCode || event.data.handoffCode.length > 512) return;
      finish(event.data.handoffCode);
    };
    window.addEventListener('message', receive);
    const poll = window.setInterval(() => { if (popup.closed) finish(); }, 500);
    const timeout = window.setTimeout(() => finish(undefined, '본인확인 시간이 만료되었습니다. 다시 시도해 주세요.'), 300000);
  });
}
