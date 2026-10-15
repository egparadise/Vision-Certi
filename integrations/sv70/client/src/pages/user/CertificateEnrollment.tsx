import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useUserStore } from '@stores/useUserStore';
import { VisionCertiService } from '@services/api/VisionCertiService';
import { enrollPacsCertificate } from '@services/api/pacsCertificateEnrollment';
import type { VcConfig } from '@shared/types/visionCerti.types';

export default function CertificateEnrollmentPage() {
  const navigate = useNavigate();
  const token = useUserStore(s => s.token);
  const [config, setConfig] = useState<VcConfig | null>(null);
  const [status, setStatus] = useState('CHECKING');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const checking = useRef(false);
  const refresh = useCallback(async () => {
    if (!token || checking.current) return;
    checking.current = true;
    setError(null);
    try {
      const cfg = await VisionCertiService.getConfig();
      if (useUserStore.getState().token !== token) return;
      setConfig(cfg);
      if (cfg.state === 'DISABLED') { navigate('/', { replace: true }); return; }
      const result = await VisionCertiService.getEnrollmentStatus();
      if (useUserStore.getState().token !== token) return;
      setStatus(result.registrationStatus);
      if (result.registrationStatus === 'ACTIVE' || result.registrationStatus === 'NOT_REQUIRED') {
        navigate('/', { replace: true });
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '인증서 등록 상태를 확인하지 못했습니다. 다시 확인해주세요.');
    } finally { checking.current = false; }
  }, [token, navigate]);
  useEffect(() => { if (token) void refresh(); }, [token, refresh]);
  const begin = async () => {
    if (!config || busy || !token) return;
    const original = token;
    setBusy(true); setError(null);
    try {
      await enrollPacsCertificate(config, async () => {
        const result = await VisionCertiService.startEnrollment();
        if (useUserStore.getState().token !== original) throw new Error('로그인 계정이 변경되었습니다.');
        return result;
      });
      if (useUserStore.getState().token === original) await refresh();
    } catch (e) { setError(e instanceof Error ? e.message : '인증서 등록을 진행하지 못했습니다.'); }
    finally { setBusy(false); }
  };
  const blocked = status === 'SUSPENDED' || status === 'REVOKED';
  return <main style={{ maxWidth: 720, margin: '80px auto', padding: 32 }}>
    <h1>최초 공동인증서 등록</h1>
    <p>처음 로그인할 때 본인의 공동인증서를 PACS 계정에 연결합니다. 등록·활성화 후에는 기존 ID·비밀번호로 로그인할 수 있습니다.</p>
    <p>초대코드 입력이나 관리자 일괄 등록은 필요하지 않습니다. 인증서·면허 연결 검토와 전자서명 이용 동의는 유지됩니다.</p>
    {!token && <p role="status">PACS 로그인이 필요합니다. <button onClick={() => navigate('/login')}>로그인 화면</button></p>}
    {blocked && <p role="alert">계정 또는 인증서 연결이 정지·해제되어 있습니다. 관리자에게 문의해주세요.</p>}
    {status === 'PENDING' && <p>인증서 검증·동의·검토·활성화가 모두 완료되어야 PACS를 사용할 수 있습니다.</p>}
    {error && <p role="alert">{error}</p>}
    <button disabled={!token || busy || blocked || !['PENDING', 'NOT_REGISTERED'].includes(status)} onClick={begin}>
      {busy ? '인증서 등록 진행 중' : '공동인증서 최초 등록'}
    </button>
    <button disabled={!token || busy} onClick={() => void refresh()}>등록 상태 다시 확인</button>
    <button onClick={() => { useUserStore.getState().actions.logout(); navigate('/login', { replace: true }); }}>로그아웃</button>
  </main>;
}
