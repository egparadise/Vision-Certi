import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useUserStore } from '@stores/useUserStore';
import { VisionCertiService } from '@services/api/VisionCertiService';
import { enrollPacsCertificate } from '@services/api/pacsCertificateEnrollment';
import { requestCertificateHandoff } from '@services/api/certificateHandoff';
import type { VcConfig } from '@shared/types/visionCerti.types';

export default function CertificateEnrollmentPage() {
  const navigate = useNavigate();
  const token = useUserStore(s => s.token);
  const userIdx = useUserStore(s => s.userData?.user_idx);
  const [config, setConfig] = useState<VcConfig | null>(null);
  const [status, setStatus] = useState('CHECKING');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const checking = useRef<string | null>(null);
  const operation = useRef(false);
  const sameAccount = useCallback(() => {
    const current = useUserStore.getState();
    return current.token === token && current.userData?.user_idx === userIdx;
  }, [token, userIdx]);
  const refresh = useCallback(async () => {
    if (!token || checking.current === token) return;
    checking.current = token;
    setError(null);
    setStatus('CHECKING');
    try {
      const cfg = await VisionCertiService.getConfig();
      if (!sameAccount()) return;
      setConfig(cfg);
      if (cfg.state === 'DISABLED') { navigate('/', { replace: true }); return; }
      if (token.startsWith('vc1.')) {
        // The server checks the live session and current certificate enrollment before answering.
        const session = await VisionCertiService.getSession();
        if (!sameAccount()) return;
        if (session.userIdx !== userIdx || !session.expiresAt ||
            !Number.isFinite(Date.parse(session.expiresAt)) || Date.parse(session.expiresAt) <= Date.now()) {
          throw new Error('공동인증서 로그인 세션을 다시 확인해 주세요.');
        }
        setStatus('ACTIVE');
        navigate('/', { replace: true });
        return;
      }
      const result = await VisionCertiService.getEnrollmentStatus();
      if (!sameAccount()) return;
      setStatus(result.registrationStatus);
      if (result.registrationStatus === 'NOT_REQUIRED') navigate('/', { replace: true });
      // ACTIVE enrollment alone does not authenticate this login: explicit certificate step-up follows.
    } catch (e) {
      if (sameAccount()) setError(e instanceof Error ? e.message : '인증서 로그인 상태를 확인하지 못했습니다. 다시 확인해 주세요.');
    } finally { if (checking.current === token) checking.current = null; }
  }, [token, userIdx, navigate, sameAccount]);
  useEffect(() => { if (token) void refresh(); }, [token, refresh]);

  const begin = async () => {
    if (!config || operation.current || !token || !sameAccount()) return;
    operation.current = true;
    setBusy(true); setError(null);
    try {
      await enrollPacsCertificate(config, async () => {
        if (!sameAccount()) throw new Error('로그인 계정이 변경되었습니다.');
        const result = await VisionCertiService.startEnrollment();
        if (!sameAccount()) throw new Error('로그인 계정이 변경되었습니다.');
        return result;
      });
      if (sameAccount()) await refresh();
    } catch (e) { if (sameAccount()) setError(e instanceof Error ? e.message : '인증서 등록을 진행하지 못했습니다.'); }
    finally { operation.current = false; setBusy(false); }
  };

  const certificateLogin = async () => {
    if (!config || operation.current || !token || token.startsWith('vc1.') ||
        userIdx == null || status !== 'ACTIVE' || !sameAccount()) return;
    operation.current = true;
    setBusy(true); setError(null);
    try {
      const code = await requestCertificateHandoff(config);
      if (!sameAccount()) throw new Error('로그인 계정이 변경되었습니다.');
      const result = await VisionCertiService.certificateStepUp(code);
      if (!sameAccount()) throw new Error('로그인 계정이 변경되었습니다.');
      if (result.user_data.user_idx !== userIdx || !result.token.startsWith('vc1.')) {
        throw new Error('현재 PACS 계정과 인증서 소유자가 일치하지 않습니다.');
      }
      await useUserStore.getState().actions.loginWithCertificate(result);
      const current = useUserStore.getState();
      if (current.token === result.token && current.userData?.user_idx === userIdx) {
        navigate('/', { replace: true });
      }
    } catch (e) { if (sameAccount()) setError(e instanceof Error ? e.message : '공동인증서 로그인에 실패했습니다.'); }
    finally { operation.current = false; setBusy(false); }
  };

  const blocked = status === 'SUSPENDED' || status === 'REVOKED';
  return <main style={{ maxWidth: 720, margin: '80px auto', padding: 32 }}>
    <h1>공동인증서 등록 및 로그인</h1>
    <p>최초 접속 시 본인의 공동인증서를 PACS 계정에 등록합니다. 판독의는 등록 후에도 매번 공동인증서로 로그인해야 합니다.</p>
    <p>초대코드 입력이나 관리자 일괄 등록은 필요하지 않습니다. 인증서와 면허 연결 검증 및 전자서명 이용 동의를 완료해 주세요.</p>
    {!token && <p role="status">PACS 계정 확인이 필요합니다. <button onClick={() => navigate('/login')}>로그인 화면</button></p>}
    {blocked && <p role="alert">계정 또는 인증서 연결이 정지·해제되어 있습니다. 관리자에게 문의해 주세요.</p>}
    {status === 'PENDING' && <p>인증서 검증·동의·활성화가 완료되면 공동인증서 로그인을 진행할 수 있습니다.</p>}
    {status === 'ACTIVE' && !token?.startsWith('vc1.') && <p role="status">인증서 등록이 완료되었습니다. 공동인증서로 로그인해 주세요.</p>}
    {error && <p role="alert">{error}</p>}
    {status === 'ACTIVE' && !token?.startsWith('vc1.') ?
      <button disabled={!token || busy} onClick={certificateLogin}>{busy ? '공동인증서 로그인 중' : '공동인증서 로그인'}</button> :
      <button disabled={!token || busy || blocked || !['PENDING', 'NOT_REGISTERED'].includes(status)} onClick={begin}>
        {busy ? '인증서 등록 진행 중' : '공동인증서 최초 등록'}
      </button>}
    <button disabled={!token || busy} onClick={() => void refresh()}>인증 상태 다시 확인</button>
    <button onClick={() => { useUserStore.getState().actions.logout(); navigate('/login', { replace: true }); }}>로그아웃</button>
  </main>;
}
