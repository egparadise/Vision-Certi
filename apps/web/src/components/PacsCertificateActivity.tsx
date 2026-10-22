import { useCallback, useEffect, useRef, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { useApp } from '../context/AppContext';
import { InfoPopover } from './InfoPopover';
import type { PacsActivityPage } from '../types/pacsActivity';

const actions: Record<string, string> = {
  REQUESTED: '인증 요청', ATTEMPTED: '서명 검증 시도', VERIFIED: '인증 성공',
  REDEEMED: 'PACS 결과 수신', CHECKED: '유효 상태 재확인', DENIED: '거절',
  LOGIN_ATTEMPTED: 'PACS 로그인 시도', LOGIN_SUCCEEDED: 'PACS 로그인 완료', LOGIN_FAILED: 'PACS 로그인 실패', APPROVAL_REQUESTED: '판독 승인 요청',
};
const fields: Record<string, string> = {
  clientId: 'PACS Client ID', purpose: '요청 목적', challengeId: '요청 ID', verificationId: '인증 ID',
  challengeExpiresAt: '요청 만료', fingerprintSha256: '인증서 SHA-256', subjectDn: '소유자', issuerDn: '발급기관',
  certificateNotBefore: '인증서 시작일', certificateNotAfter: '인증서 만료일', certificateSerialNumber: '인증서 일련번호',
  verifiedAt: '인증 시각', expiresAt: '인증 연결 만료', signatureAlgorithm: '서명 알고리즘', validation: '검증 방식',
  peerIp: '서버 직접 접속 IP', forwardedFor: '프록시 전달 IP (미검증)', forwardedIpTrust: '전달 IP 신뢰 상태',
  userAgent: '접속 프로그램', origin: '웹 출처', code: '오류 코드', stage: '처리 단계',
  pacsLoginId: 'PACS 로그인 ID', pacsAccountId: 'PACS 계정 번호', computerName: 'PC 이름', deviceId: 'PC 식별자',
  clientInfoSource: '계정·PC 정보 출처', reportKeySha256: '판독문 식별 해시', eventId: 'PACS 이벤트 ID', eventFingerprint: '이벤트 중복 확인 해시',
};
const date = (value?: string | null) => value ? new Date(value).toLocaleString('ko-KR') : '기록 없음';

export function PacsCertificateActivity() {
  const { client, tenantId } = useApp();
  const [data, setData] = useState<PacsActivityPage | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState('');
  const [offset, setOffset] = useState(0);
  const generation = useRef(0);
  const load = useCallback(async () => {
    const id = ++generation.current;
    setLoading(true);
    try {
      const result = await client.getPacsCertificateActivity(search, offset);
      if (id !== generation.current) return;
      if (!result?.totals || !Array.isArray(result.events) || !Array.isArray(result.certificates)) {
        throw new Error('인증 기록 응답을 확인할 수 없습니다. 다시 갱신해 주세요.');
      }
      setData(result); setError('');
    } catch (cause) {
      if (id === generation.current) setError(cause instanceof Error ? cause.message : '인증 기록을 불러오지 못했습니다.');
    } finally { if (id === generation.current) setLoading(false); }
  }, [client, tenantId, search, offset]);

  useEffect(() => {
    setData(null); void load();
    const timer = window.setInterval(() => { if (!document.hidden) void load(); }, 30_000);
    return () => { generation.current++; window.clearInterval(timer); };
  }, [load]);

  const totals = data?.totals;
  return <section className="card" data-testid="pacs-certificate-activity" style={{ marginBottom: 24 }}>
    <div className="card-header" style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
      <h2 className="card-title">PACS 공동인증서 인증 현황</h2>
      <InfoPopover title="PACS 인증 기록" description="서버에서 처리한 인증 요청·검증·결과 수신·재확인을 DB에 자동 보관합니다. 화면은 30초마다 갱신됩니다."
        bullets={[
          '인증 성공은 서버의 인증서 검증 결과입니다. PACS 로그인 완료는 PACS가 로그인 성공 후 전송한 기록으로 별도 집계합니다.',
          '프록시 전달 IP는 참고 정보이며 위조될 수 있습니다. 공유기·VPN 환경에서는 여러 PC가 같은 외부 IP를 사용합니다.',
          '계정·PC 이름 및 로그인·판독 요청 기록에는 PACS 기록 연동 패치가 필요합니다. 미전송된 과거 값은 소급 생성하지 않습니다. PC에서만 처리된 인증서 비밀번호 오류는 서버 검증 시도에 포함되지 않습니다.',
          '승인 요청 횟수와 요청한 판독문 수를 구분합니다. 같은 판독문의 재요청은 판독문 수에 중복 합산하지 않습니다. 승인 완료를 의미하지 않습니다.',
          '비밀번호·개인키·일회용 인증코드는 저장하지 않습니다. 과거에 수집하지 않은 항목은 기록 없음으로 표시합니다.',
        ]} />
      <button className="btn btn-outline" onClick={() => void load()} disabled={loading} type="button"><RefreshCw size={16} /> 갱신</button>
      <a className="btn btn-outline" href="/downloads/vision-certi-pacs-activity-patch.zip" download>PACS 기록 연동 패치</a>
    </div>
    <div className="card-body">
      {error && <p role="alert" className="alert alert-error">{error}</p>}
      <form onSubmit={event => { event.preventDefault(); setOffset(0); setSearch(query.trim()); }} style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        <input aria-label="인증 기록 검색" placeholder="계정 · PC 이름 · IP · 인증서 검색" maxLength={128} value={query} onChange={e => setQuery(e.target.value)} style={{ flex: 1 }} />
        <button className="btn btn-outline" type="submit">검색</button>
      </form>
      <div className="metrics-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))' }}>
        {([['인증 요청', totals?.requested], ['검증 시도', totals?.attempted], ['인증 성공', totals?.verified],
          ['PACS 결과 수신', totals?.redeemed], ['상태 재확인', totals?.checked], ['거절', totals?.denied]] as const)
          .map(([label, value]) => <div className="metric-card" key={label} style={{ display: 'flex', flexDirection: 'column', gap: 8 }}><span className="metric-label">{label}</span><span className="metric-value">{value ?? '…'}</span></div>)}
      </div>
      <div className="metrics-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))' }}>
        {([['PACS 로그인 시도', totals?.loginAttempted], ['PACS 로그인 완료', totals?.loginSucceeded], ['PACS 로그인 실패', totals?.loginFailed],
          ['판독 승인 요청 횟수', totals?.approvalRequests], ['승인 요청 판독문', totals?.approvalDocuments]] as const)
          .map(([label, value]) => <div className="metric-card" key={label}><span className="metric-label">{label}</span><div className="metric-value">{value ?? '…'}</div></div>)}
      </div>
      {!!data?.certificates.length && <details open>
        <summary>인증서별 로그인·판독 집계 ({data.certificates.length}개)</summary>
        <div className="table-responsive"><table className="table"><thead><tr><th>소유자 / SHA-256</th><th>계정 / PC</th><th>발급기관 / 만료</th><th>인증 시도 / 성공</th><th>로그인 시도 / 완료 / 실패</th><th>승인 요청 / 판독문 수</th><th>최근 기록</th></tr></thead>
          <tbody>{data.certificates.map(cert => <tr key={cert.fingerprintSha256}>
            <td>{cert.subjectDn || '과거 기록: 소유자 정보 없음'}<br /><code style={{ overflowWrap: 'anywhere' }}>{cert.fingerprintSha256}</code></td>
            <td>{cert.accounts?.join(', ') || '계정 미전송'}<br />{cert.computers?.join(', ') || 'PC 이름 미전송'}</td>
            <td>{cert.issuerDn || '기록 없음'}<br />{date(cert.notAfter)}</td><td>{cert.attempted ?? '—'} / {cert.verified}</td>
            <td>{cert.loginAttempted ?? '—'} / {cert.loginSucceeded ?? '—'} / {cert.loginFailed ?? '—'}</td>
            <td>{cert.approvalRequests ?? '—'} / {cert.approvalDocuments ?? '—'}</td><td>{date(cert.lastSeenAt)}</td>
          </tr>)}</tbody></table></div>
      </details>}
      <div className="table-responsive"><table className="table"><thead><tr><th>시각 / 단계</th><th>PACS / 인증서</th><th>요청 계정 / PC</th><th>접속 IP</th><th>결과 / 상세</th></tr></thead>
        <tbody>{data?.events.map(event => <tr key={event.id}>
          <td style={{ verticalAlign: 'top' }}>{date(event.occurredAt)}<br /><strong>{actions[event.action] || event.action}</strong>{event.legacy && <small> · 과거 기록</small>}</td>
          <td style={{ verticalAlign: 'top' }}>{event.details.clientId || '기록 없음'}<br />{event.details.subjectDn || (event.details.fingerprintSha256 ? `${event.details.fingerprintSha256.slice(0, 16)}…` : '인증서 확인 전')}</td>
          <td style={{ verticalAlign: 'top' }}>{event.details.pacsLoginId || '계정 미전송'}<br />{event.details.computerName || 'PC 이름 미전송'}<br /><small>{event.details.deviceId}</small></td>
          <td style={{ verticalAlign: 'top' }}>{event.details.forwardedFor && <div title={event.details.forwardedFor}>전달: {event.details.forwardedFor}<small> (미검증)</small></div>}
            <div>직접: {event.details.peerIp || '기록 없음'}</div></td>
          <td>{event.outcome === 'DENIED' ? '거절' : '처리 완료'}{event.details.code && <div>{event.details.code}</div>}
            <details><summary>상세 보기</summary><dl style={{ maxWidth: 480, overflowWrap: 'anywhere' }}>
              <dt>추적 ID</dt><dd>{event.correlationId || '기록 없음'}</dd><dt>대상 ID</dt><dd>{event.targetId}</dd>
              {Object.entries(event.details).map(([key, value]) => <div key={key}><dt>{fields[key] || key}</dt><dd>{value}</dd></div>)}
            </dl></details>
          </td>
        </tr>)}</tbody></table></div>
      {!loading && data?.total === 0 && <p>기록된 PACS 인증 요청이 없습니다.</p>}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <button className="btn btn-outline" type="button" disabled={loading || offset === 0} onClick={() => setOffset(Math.max(0, offset - 50))}>이전</button>
        <span>{data ? `${data.total ? offset + 1 : 0}–${Math.min(offset + 50, data.total)} / ${data.total}건` : '불러오는 중'}</span>
        <button className="btn btn-outline" type="button" disabled={loading || !data || offset + 50 >= data.total} onClick={() => setOffset(offset + 50)}>다음</button>
      </div>
    </div>
  </section>;
}
