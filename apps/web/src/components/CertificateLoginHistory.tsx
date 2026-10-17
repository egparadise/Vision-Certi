import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, ShieldCheck } from 'lucide-react';
import { useApp } from '../context/AppContext';
import type { AuditEvent } from '../types/api';

/** Admin-only view of PACS sessions that actually completed certificate login. */
export function CertificateLoginHistory() {
  const { client, tenantId } = useApp();
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const response = await client.getCertificateLogins();
      setEvents(response.items ?? []);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '로그인 이력을 불러오지 못했습니다.');
    } finally {
      setLoading(false);
    }
  }, [client, tenantId]);

  useEffect(() => { void load(); }, [load]);
  const normalized = query.trim().toLocaleLowerCase();
  const visible = events.filter(event => !normalized || [
    event.details.pacsLoginId, event.details.pacsAccountId, event.targetId,
  ].some(value => value?.toLocaleLowerCase().includes(normalized)));

  return (
    <section className="card" aria-label="공동인증서 PACS 로그인 이력" style={{ marginBottom: 24 }}>
      <div className="card-header" style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
        <span className="card-title"><ShieldCheck size={18} /> 공동인증서 PACS 로그인 이력</span>
        <button type="button" className="btn btn-secondary" onClick={() => void load()} disabled={loading}>
          <RefreshCw size={14} /> 새로고침
        </button>
      </div>
      <div className="card-body">
        <p style={{ marginTop: 0, color: 'var(--navy-600)' }}>
          PACS가 인증서 확인 후 실제 계정 세션을 발급한 기록입니다. 최근 200건까지 표시합니다.
          이전 버전의 로그인에는 PACS ID가 기록되지 않았습니다.
        </p>
        <label style={{ display: 'block', marginBottom: 12 }}>
          PACS 로그인 ID · 계정 번호 · 의사 ID 검색
          <input type="search" value={query} onChange={event => setQuery(event.target.value)}
            placeholder="로그인 ID 또는 의사 ID" style={{ display: 'block', width: '100%', maxWidth: 360, marginTop: 6 }} />
        </label>
        {error && <div className="alert alert-error" role="alert">{error}</div>}
        {loading && !events.length ? <p role="status">로그인 이력을 불러오는 중입니다.</p> :
          visible.length === 0 ? <p>표시할 공동인증서 로그인 기록이 없습니다.</p> :
          <div className="table-responsive">
            <table className="table">
              <thead><tr><th>로그인 시각</th><th>PACS 로그인 ID</th><th>PACS 계정 번호</th>
                <th>INVIZ 의사 ID</th><th>인증서 지문</th><th>결과</th></tr></thead>
              <tbody>{visible.map(event => <tr key={event.id}>
                <td>{new Date(event.occurredAt).toLocaleString('ko-KR')}</td>
                <td><strong>{event.details.pacsLoginId ?? '—'}</strong></td>
                <td>{event.details.pacsAccountId ?? '—'}</td>
                <td><code title={event.targetId}>{event.targetId}</code></td>
                <td><code title={event.details.certificateFingerprintSha256 ?? ''}>
                  {event.details.certificateFingerprintSha256?.slice(0, 16) ?? '—'}…
                </code></td>
                <td><span className="badge badge-completed">인증 완료</span></td>
              </tr>)}</tbody>
            </table>
          </div>}
      </div>
    </section>
  );
}
