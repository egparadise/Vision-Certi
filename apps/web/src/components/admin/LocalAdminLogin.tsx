import { useState, type FormEvent } from 'react';
import { useApp } from '../../context/AppContext';
import '../certificate-login.css';
import { apiBaseUrl } from '../../api/baseUrl';
import { signInGoogleStaff, signOutGoogleStaff } from '../../services/firebaseStaffAuth';

export function LocalAdminLogin() {
  const { setActorId, setLocalAdminToken, setStaffToken, setStaffProfile, client,
    runtimeCapabilities, capabilitiesLoading, capabilitiesError } = useApp();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function googleLogin() {
    setBusy(true); setError('');
    try {
      const token = await signInGoogleStaff();
      try {
        const profile = await client.getStaffMe(token);
        setStaffToken(token);
        setStaffProfile(profile);
        window.location.hash = '#/admin';
      } catch (cause) {
        await signOutGoogleStaff();
        throw cause;
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Google 로그인에 실패했습니다.');
    } finally { setBusy(false); }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError('');
    try {
      const response = await fetch(`${apiBaseUrl()}/dev-admin/sessions`, {
        method: 'POST', cache: 'no-store', credentials: 'omit',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
      if (!response.ok) throw new Error(response.status === 429
        ? '로그인 시도가 제한되었습니다. 10분 뒤 다시 시도해 주세요.'
        : '관리자 ID 또는 비밀번호를 확인해 주세요.');
      const result = await response.json() as { token: string };
      setLocalAdminToken(result.token);
      setActorId('admin-demo');
      setPassword('');
      window.location.hash = '#/admin';
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '로그인에 실패했습니다.');
    } finally { setBusy(false); }
  }

  if (capabilitiesLoading || !runtimeCapabilities) return <main className="local-admin-login">
    <a href="#/login">← 공동인증서 로그인</a>
    <p role={capabilitiesError ? 'alert' : 'status'}>
      {capabilitiesError || '인증 서비스에 연결 중입니다…'}
    </p>
  </main>;

  if (runtimeCapabilities.profile === 'prod') return <main className="local-admin-login">
    <a href="#/login">← 공동인증서 로그인</a>
    <div>
      <h1>관리자 로그인</h1>
      <p>운영 대시보드는 등록된 Google 관리자 계정으로 로그인한 뒤 볼 수 있습니다.</p>
      {error && <p role="alert">{error}</p>}
      <button type="button" onClick={googleLogin} disabled={busy}>
        {busy ? '확인 중…' : 'Google 계정으로 로그인'}
      </button>
    </div>
  </main>;

  return <main className="local-admin-login">
    <a href="#/login">← 공동인증서 로그인</a>
    <form onSubmit={submit}>
      <h1>관리자 로그인</h1>
      <label>ID<input value={username} autoComplete="username" onChange={e => setUsername(e.target.value)} required /></label>
      <label>비밀번호<input type="password" value={password} autoComplete="current-password"
        onChange={e => setPassword(e.target.value)} required /></label>
      {error && <p role="alert">{error}</p>}
      <button type="submit" disabled={busy}>{busy ? '확인 중…' : '관리자 페이지 입장'}</button>
    </form>
  </main>;
}
