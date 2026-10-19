import { useState, type FormEvent } from 'react';
import { useApp } from '../../context/AppContext';
import '../certificate-login.css';

export function LocalAdminLogin() {
  const { setActorId, setLocalAdminToken } = useApp();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError('');
    try {
      const response = await fetch('/v1/dev-admin/sessions', {
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
