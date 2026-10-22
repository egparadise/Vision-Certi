import { useEffect, useState } from 'react';
import { useApp } from '../context/AppContext';
import { InfoPopover } from './InfoPopover';

/** Connectivity only; a responding API does not imply clinical approval. */
export function CertificateServerStatus() {
  const { client, isMockMode } = useApp();
  const [state, setState] = useState<'checking' | 'online' | 'offline'>('checking');
  const [checkedAt, setCheckedAt] = useState<Date | null>(null);
  useEffect(() => {
    if (isMockMode) return;
    let disposed = false;
    let pending: AbortController | null = null;
    const check = async () => {
      if (pending) return;
      const controller = new AbortController();
      pending = controller;
      const timeout = window.setTimeout(() => controller.abort(), 5_000);
      try {
        const health = await client.getHealth(controller.signal);
        if (!disposed) setState(health?.status === 'UP' && health.service === 'vision-certi-backend' ? 'online' : 'offline');
      } catch {
        if (!disposed) setState('offline');
      } finally {
        window.clearTimeout(timeout);
        pending = null;
        if (!disposed) setCheckedAt(new Date());
      }
    };
    setState('checking'); setCheckedAt(null);
    void check();
    const timer = window.setInterval(check, 30_000);
    const wake = () => { if (!document.hidden) void check(); };
    window.addEventListener('online', wake);
    document.addEventListener('visibilitychange', wake);
    return () => {
      disposed = true; pending?.abort(); window.clearInterval(timer);
      window.removeEventListener('online', wake);
      document.removeEventListener('visibilitychange', wake);
    };
  }, [client, isMockMode]);
  const label = isMockMode ? '인증서버 · 모의 모드' : state === 'online' ? '인증서버 ON' : state === 'offline' ? '인증서버 연결 안 됨' : '인증서버 확인 중';
  return <span className="certificate-server-status">
    <span className={`server-connection ${isMockMode ? 'checking' : state}`} role="status" aria-live="polite">
      <span className="server-connection-dot" aria-hidden="true" />{label}
    </span>
    <InfoPopover title="인증서버 연결 상태" ariaLabel="인증서버 연결 상태 상세 보기" align="right" className="runtime-info"
      description={isMockMode ? '모의 모드에서는 실제 서버 연결을 확인하지 않습니다.' : '현재 사이트의 인증 API 응답을 30초마다 확인합니다. 연결 실패는 서버 중지 또는 이 PC의 통신 문제일 수 있습니다.'}
      bullets={isMockMode ? undefined : [`마지막 확인: ${checkedAt ? checkedAt.toLocaleString('ko-KR') : '확인 중'}`, 'ON은 API 응답 상태입니다. 인증서 유효성 및 임상 사용 허가와는 별도로 확인합니다.']} />
  </span>;
}
