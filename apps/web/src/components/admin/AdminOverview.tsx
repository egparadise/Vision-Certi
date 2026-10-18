import React, { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, CheckCircle2, RefreshCw, XCircle } from 'lucide-react';
import { useApp } from '../../context/AppContext';
import type { DashboardSummary } from '../../types/api';
import { ErrorBanner } from '../ErrorBanner';
import { LoadingSpinner } from '../LoadingSpinner';
import { warningLabel } from './adminFormat';
import type { AdminSection } from './AdminConsole';

interface StatusItem {
  label: string;
  value: string;
  ok: boolean;
}

export const AdminOverview: React.FC<{ onNavigate: (section: AdminSection) => void }> = ({ onNavigate }) => {
  const { client, runtimeCapabilities: caps, capabilitiesLoading, tenantId, staffProfile } = useApp();
  const [dashboard, setDashboard] = useState<DashboardSummary | null>(null);
  const [certificateRows, setCertificateRows] = useState<Awaited<ReturnType<typeof client.getCertificateOperations>>>([]);
  const [certificateError, setCertificateError] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setDashboard(await client.getDashboard());
      try { setCertificateRows(await client.getCertificateOperations()); setCertificateError(null); }
      catch (cause) {
        setCertificateRows([]);
        setCertificateError(cause instanceof Error ? cause.message : '인증서 현황을 불러올 수 없습니다.');
      }
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  }, [client]);

  useEffect(() => {
    load();
  }, [load]);

  const status: StatusItem[] = caps
    ? [
        { label: '운영 프로파일', value: caps.profile === 'prod' ? '운영(prod)' : '개발(dev)', ok: caps.profile === 'prod' },
        {
          label: '데이터 저장소',
          value: caps.persistence.mode === 'POSTGRESQL'
            ? `PostgreSQL (스키마 ${caps.persistence.schemaVersion ?? '-'})`
            : '메모리(비영속)',
          ok: caps.persistence.mode === 'POSTGRESQL',
        },
        { label: '관리자 인증', value: caps.staffAuth.mode === 'OIDC' ? 'OIDC' : '개발용 헤더', ok: caps.staffAuth.mode === 'OIDC' },
        {
          label: '서명 백엔드',
          value: `${caps.signing.backend}${caps.signing.available ? '' : ' (사용 불가)'}`,
          ok: caps.signing.available && caps.signing.backend === 'OPENBAO_TRANSIT',
        },
        {
          label: 'PC 인증서 신뢰 루트',
          value: caps.doctorAuth.pcCertificate.configured
            ? `${caps.doctorAuth.pcCertificate.trustAnchors}개 · CRL ${caps.doctorAuth.pcCertificate.crlState}`
            : '설정 안 됨',
          ok: caps.doctorAuth.pcCertificate.configured,
        },
        {
          label: '모바일 인증서',
          value: caps.doctorAuth.mobile.configured ? caps.doctorAuth.mobile.provider || '설정됨' : '설정 안 됨',
          ok: caps.doctorAuth.mobile.configured,
        },
        {
          label: 'PACS 연동',
          value: caps.pacsIntegration?.available ? '사용 가능' : caps.pacsIntegration?.reason || '사용 불가',
          ok: Boolean(caps.pacsIntegration?.available),
        },
        {
          label: '의사 직접 가입',
          value: caps.personalSignup?.available ? '사용 가능' : caps.personalSignup?.reason || '사용 불가',
          ok: Boolean(caps.personalSignup?.available),
        },
      ]
    : [];

  const pendingRequests = dashboard?.signRequestsByStatus?.PENDING_APPROVAL ?? 0;
  const failedDeliveries = dashboard
    ? Object.entries(dashboard.deliveriesByStatus || {})
        .filter(([state]) => state.includes('FAIL') || state.includes('DEAD'))
        .reduce((sum, [, n]) => sum + n, 0)
    : 0;

  return (
    <div className="admin-panel" data-testid="admin-overview">
      <div className="admin-panel-header">
        <div>
          <h2>관리자 개요</h2>
          <p>
            기관 <strong>{tenantId}</strong>
            {staffProfile?.displayName ? ` · ${staffProfile.displayName}` : ''}의 운영 상태와 시스템 구성 요약입니다.
          </p>
        </div>
        <button type="button" className="btn btn-outline" onClick={load} disabled={loading}>
          <RefreshCw size={16} /> 새로고침
        </button>
      </div>

      {caps && (
        <div
          className={`alert ${caps.clinicalUseAllowed ? 'alert-info' : 'alert-warning'}`}
          role="status"
          data-testid="admin-clinical-status"
        >
          {caps.clinicalUseAllowed ? <CheckCircle2 size={18} /> : <AlertTriangle size={18} />}
          <div>
            <strong>{caps.clinicalUseAllowed ? '임상 사용 가능 구성입니다.' : '임상 사용 불가 구성입니다.'}</strong>
            {caps.clinicalUseReason && <div>{caps.clinicalUseReason}</div>}
          </div>
        </div>
      )}

      {error ? <ErrorBanner error={error as Error} onRetry={load} /> : null}

      <div className="metrics-grid">
        <button type="button" className="metric-card admin-metric" onClick={() => onNavigate('audit')}>
          <span className="metric-label">감사 이벤트</span>
          <span className="metric-value">{loading ? '…' : dashboard?.auditEvents ?? '-'}</span>
        </button>
        <button type="button" className="metric-card admin-metric" onClick={() => onNavigate('deliveries')}>
          <span className="metric-label">전송 실패</span>
          <span className="metric-value">{loading ? '…' : failedDeliveries}</span>
        </button>
        <div className="metric-card">
          <span className="metric-label">승인 대기 서명 요청</span>
          <span className="metric-value">{loading ? '…' : pendingRequests}</span>
        </div>
        <div className="metric-card">
          <span className="metric-label">판독문 / 서명</span>
          <span className="metric-value">
            {loading ? '…' : `${dashboard?.documents ?? '-'} / ${dashboard?.signatures ?? '-'}`}
          </span>
        </div>
      </div>

      <div className="card">
        <div className="card-header"><span className="card-title">공동인증서 등록·PACS 이용 현황</span></div>
        <div className="card-body" style={{ overflowX: 'auto' }}>
          {certificateError && <p role="alert">인증서 현황 조회 실패: {certificateError}</p>}
          <table className="admin-certificate-table">
            <thead><tr><th>이름 / PACS ID</th><th>의사면허</th><th>전문의 번호</th>
              <th>발급기관</th><th>만료일</th><th>상태</th><th>인증서 SHA-256</th>
              <th>로그인 시도</th><th>PACS 로그인 성공</th><th>판독 승인 요청</th>
              <th>PACS 연결 만료 / 연장</th></tr></thead>
            <tbody>{certificateRows.map(row => <tr key={`${row.doctorId}-${row.fingerprintSha256}`}>
              <td>{row.name}<br /><small>{row.pacsLoginId ?? '연결 전'}</small></td>
              <td>{row.licenseNumber ?? '미등록'}</td><td>{row.specialistQualificationNumber ?? '해당 없음'}</td>
              <td>{row.issuer ?? '확인 불가'}</td>
              <td>{row.notAfter ? new Date(row.notAfter).toLocaleDateString('ko-KR') : '확인 불가'}</td>
              <td>{row.registrationStatus}</td>
              <td title={row.fingerprintSha256}><code>{row.fingerprintSha256.slice(0, 16)}…</code></td>
              <td>{row.loginAttempts}</td><td>{row.loginApproved}</td><td>{row.reportApprovalRequests}</td>
              <td>{row.connectionExpiresAt
                ? <>{new Date(row.connectionExpiresAt).toLocaleString('ko-KR')}<br /><small>연장 {row.extensionCount}회 · 세션 {row.pacsSessionId}</small></>
                : '연결 기록 없음'}</td>
            </tr>)}</tbody>
          </table>
          {!loading && certificateRows.length === 0 && <p>등록된 인증서가 없습니다.</p>}
        </div>
      </div>

      <div className="card">
        <div className="card-header">
          <span className="card-title">시스템 구성 상태</span>
          <button type="button" className="btn btn-outline" onClick={() => onNavigate('system')}>
            상세 구성 보기
          </button>
        </div>
        <div className="card-body">
          {capabilitiesLoading && !caps ? (
            <LoadingSpinner message="런타임 구성을 확인하는 중..." />
          ) : !caps ? (
            <div className="alert alert-warning">런타임 구성을 조회할 수 없습니다.</div>
          ) : (
            <ul className="admin-status-list">
              {status.map((item) => (
                <li key={item.label}>
                  {item.ok ? <CheckCircle2 size={16} color="#0f766e" /> : <XCircle size={16} color="#b45309" />}
                  <span className="admin-status-label">{item.label}</span>
                  <span className="admin-status-value">{item.value}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      {caps?.warnings && caps.warnings.length > 0 && (
        <div className="card" data-testid="admin-warnings">
          <div className="card-header">
            <span className="card-title">구성 경고 ({caps.warnings.length})</span>
          </div>
          <div className="card-body">
            <ul className="admin-warning-list">
              {caps.warnings.map((w) => (
                <li key={w}>
                  <AlertTriangle size={14} /> {warningLabel(w)}
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}
    </div>
  );
};
