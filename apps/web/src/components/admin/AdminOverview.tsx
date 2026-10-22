import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { useApp } from '../../context/AppContext';
import { PacsCertificateActivity } from '../PacsCertificateActivity';
import type { MedicalInstitution } from '../../types/admin';
import type { AdminSection } from './AdminConsole';

type CertificateRow = Awaited<ReturnType<ReturnType<typeof useApp>['client']['getCertificateOperations']>>[number];

export function AdminOverview({ onNavigate }: { onNavigate: (section: AdminSection) => void }) {
  const { client, runtimeCapabilities } = useApp();
  const [rows, setRows] = useState<CertificateRow[]>([]);
  const [institutions, setInstitutions] = useState<MedicalInstitution[]>([]);
  const [institutionId, setInstitutionId] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [certificates, registered] = await Promise.all([
        client.getCertificateOperations(), client.getMedicalInstitutions(),
      ]);
      setRows(certificates);
      setInstitutions(registered);
    } catch (cause) {
      setRows([]);
      setError(cause instanceof Error ? cause.message : '인증서 판독 현황을 불러오지 못했습니다.');
    } finally { setLoading(false); }
  }, [client]);

  useEffect(() => { void load(); }, [load]);

  const visible = useMemo(() => {
    if (!institutionId) return rows;
    const institution = institutions.find(i => i.id === institutionId);
    return institution ? rows.filter(row => institution.doctorIds.includes(row.doctorId)) : [];
  }, [rows, institutions, institutionId]);

  const sums = {
    signed: visible.reduce((n, row) => n + row.signedReports, 0),
    requested: visible.reduce((n, row) => n + row.reportApprovalRequests, 0),
    logins: visible.reduce((n, row) => n + row.loginApproved, 0),
  };

  return <div className="admin-panel" data-testid="admin-overview">
    <PacsCertificateActivity />
    <div className="admin-panel-header">
      <div><h2>의사 등록 인증서·판독 현황</h2>
        <p>등록된 외부 인증서의 로그인과 판독 서명 기록을 서버에서 집계합니다. 합성 시험 인증서는 제외합니다. 기관 필터는 배정 의사를 골라 보여주며, 복수 기관에 배정된 의사의 판독 건수를 기관별로 분할하지 않습니다.</p>
      </div>
      <button type="button" className="btn btn-outline" onClick={() => void load()} disabled={loading}>
        <RefreshCw size={16} /> 새로고침
      </button>
    </div>

    {runtimeCapabilities && !runtimeCapabilities.clinicalUseAllowed &&
      <p className="alert alert-warning" role="status" data-testid="admin-clinical-status">
        현재 서버는 임상 사용 검증이 완료되지 않았습니다. 실제 인증기관 신뢰 설정과 운영 구성을 확인해 주세요.
      </p>}
    {error && <p className="alert alert-error" role="alert">{error}</p>}

    <div style={{ display: 'flex', gap: 12, alignItems: 'end', flexWrap: 'wrap', marginBottom: 18 }}>
      <label>기관 배정 의사 필터<br />
        <select aria-label="기관 배정 의사 필터" value={institutionId} onChange={e => setInstitutionId(e.target.value)}>
          <option value="">전체 등록 기관</option>
          {institutions.map(i => <option key={i.id} value={i.id}>{i.name}</option>)}
        </select>
      </label>
      <button type="button" className="btn btn-outline" onClick={() => onNavigate('institutions')}>의료기관 등록·의사 배정</button>
    </div>

    <div className="metrics-grid">
      <div className="metric-card"><span className="metric-label">외부 인증서 등록 기록</span>
        <span className="metric-value">{loading ? '…' : visible.length}</span></div>
      <div className="metric-card"><span className="metric-label">판독 전자서명 완료</span>
        <span className="metric-value">{loading ? '…' : sums.signed}</span></div>
      <div className="metric-card"><span className="metric-label">판독 승인 요청</span>
        <span className="metric-value">{loading ? '…' : sums.requested}</span></div>
      <div className="metric-card"><span className="metric-label">PACS 인증서 로그인</span>
        <span className="metric-value">{loading ? '…' : sums.logins}</span></div>
    </div>

    <div className="card">
      <div className="card-header"><span className="card-title">인증서별 상세 기록</span></div>
      <div className="card-body" style={{ overflowX: 'auto' }}>
        <table className="admin-certificate-table">
          <thead><tr><th>의사 / PACS ID</th><th>배정 의료기관</th><th>면허 / 전문의 번호</th>
            <th>발급기관 / 만료일</th><th>등록 상태</th><th>인증서 SHA-256</th><th>로그인 시도 / 성공</th>
            <th>승인 요청</th><th>서명 완료 판독</th><th>마지막 연결 만료 기록 / 연장</th></tr></thead>
          <tbody>{visible.map(row => <tr key={`${row.doctorId}-${row.fingerprintSha256}`}>
            <td>{row.name}<br /><small>{row.pacsLoginId || 'PACS 연결 전'}</small></td>
            <td>{row.institutionNames?.length ? row.institutionNames.join(', ') : '미배정'}</td>
            <td>{row.licenseNumber || '미등록'}<br /><small>{row.specialistQualificationNumber || '전문의 번호 없음'}</small></td>
            <td>{row.issuer || '확인 불가'}<br /><small>{row.notAfter ? new Date(row.notAfter).toLocaleDateString('ko-KR') : '만료일 확인 불가'}</small></td>
            <td>{row.registrationStatus}</td>
            <td title={row.fingerprintSha256}><code>{row.fingerprintSha256.slice(0, 16)}…</code></td>
            <td>{row.loginAttempts} / {row.loginApproved}<br /><small>{row.lastLoginAt ? new Date(row.lastLoginAt).toLocaleString('ko-KR') : '성공 기록 없음'}</small></td>
            <td>{row.reportApprovalRequests}</td><td><strong>{row.signedReports}</strong></td>
            <td>{row.connectionExpiresAt ? <>{new Date(row.connectionExpiresAt).toLocaleString('ko-KR')}
              <br /><small>연장 {row.extensionCount}회</small></> : '연결 기록 없음'}</td>
          </tr>)}</tbody>
        </table>
        {!loading && visible.length === 0 && <p>표시할 실제 공동인증서가 없습니다. 의사가 외부 인증기관 인증서를 등록하고 관리자 검토를 마치면 이곳에 표시됩니다.</p>}
      </div>
    </div>
  </div>;
}
