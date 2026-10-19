import React, { useEffect, useState, useCallback } from 'react';
import {
  FileText,
  FileCheck,
  Send,
  ShieldCheck,
  RefreshCw,
  Clock,
  Layers,
  CheckCircle2,
  AlertTriangle,
  XCircle,
  HelpCircle,
  ArrowRight,
} from 'lucide-react';
import { useApp } from '../context/AppContext';
import { DashboardSummary } from '../types/api';
import { computeMockDashboard } from '../mock/mockData';
import { ErrorBanner } from './ErrorBanner';
import { LoadingSpinner } from './LoadingSpinner';
import { InfoPopover } from './InfoPopover';

const signRequestLabels: Record<string, string> = {
  COMPLETED: '완료',
  PENDING_APPROVAL: '승인 대기',
  FAILED: '실패',
  EXPIRED: '만료',
};

const deliveryLabels: Record<string, string> = {
  ACKED: '전송 완료',
  QUEUED: '전송 대기',
  DEAD_LETTER: '전송 실패',
};

export const DashboardView: React.FC = () => {
  const { tenantId, client, isMockMode, mockStore, setActiveTab, runtimeCapabilities } = useApp();
  const [data, setData] = useState<DashboardSummary | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<unknown>(null);

  const fetchGenRef = React.useRef<number>(0);

  const loadDashboard = useCallback(async () => {
    const currentGen = ++fetchGenRef.current;
    setLoading(true);
    setError(null);
    try {
      if (isMockMode) {
        // Explicit mock mode
        const mockSummary = computeMockDashboard(mockStore, tenantId);
        if (currentGen !== fetchGenRef.current) return;
        setData(mockSummary);
      } else {
        // Live API
        const summary = await client.getDashboard();
        if (currentGen !== fetchGenRef.current) return;
        setData(summary);
      }
    } catch (err) {
      if (currentGen === fetchGenRef.current) {
        setError(err);
      }
    } finally {
      if (currentGen === fetchGenRef.current) {
        setLoading(false);
      }
    }
  }, [client, isMockMode, mockStore, tenantId]);

  useEffect(() => {
    loadDashboard();
  }, [loadDashboard]);

  if (loading && !data) {
    return <LoadingSpinner message="대시보드 지표를 불러오는 중입니다..." />;
  }

  const persistence = runtimeCapabilities?.persistence.mode ?? data?.persistence ?? '확인 불가';
  const signing = runtimeCapabilities?.signing.backend ?? data?.signatureMode ?? '확인 불가';
  const environmentLabel = isMockMode
    ? '모의 데이터'
    : runtimeCapabilities?.clinicalUseAllowed
      ? '임상 운영'
      : runtimeCapabilities
        ? '임상 사용 전 검증 중'
        : '서버 상태 확인 필요';

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <h2 style={{ fontSize: '1.4rem', fontWeight: 800, color: 'var(--navy-900)' }}>
              판독·서명 현황
            </h2>
            <InfoPopover
              title="운영 대시보드 및 지표 안내"
              description={
                <span>
                  선택 기관(<strong>{tenantId}</strong>)의 판독문, 서명, 전송 및 감사 기록을 집계합니다. 검증 환경에는 시험 기록이 포함될 수 있습니다.
                </span>
              }
              bullets={[
                "등록 판독문: 기관에 접수된 전체 의료영상 소견서 및 버전 이력 건수",
                "전자서명 완료: 지정 전문의 본인확인 및 전자서명이 체결된 누적 건수",
                "병원 전송 큐: 서명 완료 후 원격 의료기관 PACS/EMR로 전송 대기 또는 수신완료(ACK)된 작업",
                "감사 이벤트: 위변조 방지 SHA-256 불변 해시 체인으로 누적 결속된 감사 트레일"
              ]}
              badge="지표 안내"
            />
          </div>
        </div>
        <button
          type="button"
          onClick={loadDashboard}
          className="btn btn-secondary"
          disabled={loading}
          aria-label="대시보드 새로고침"
        >
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
          지표 갱신
        </button>
      </div>

      <ErrorBanner error={error as Error} onRetry={loadDashboard} />

      <div className="dashboard-runtime-note" role="status">
        <span>{environmentLabel}</span>
        <InfoPopover
          title="현재 서버 구성"
          ariaLabel="현재 서버 구성 상세 보기"
          description={`저장소: ${persistence} · 서명 방식: ${signing}`}
          bullets={[
            `임상 사용: ${runtimeCapabilities?.clinicalUseAllowed ? '허용' : '허용되지 않음'}`,
            ...(runtimeCapabilities?.clinicalUseReason ? [`제한 사유: ${runtimeCapabilities.clinicalUseReason}`] : []),
            ...(persistence === 'IN_MEMORY' ? ['서버를 다시 시작하면 인메모리 데이터가 사라집니다.'] : []),
          ]}
        />
      </div>

      {data && (
        <>
          {/* Top Metrics Cards */}
          <div className="metrics-grid">
            <div className="metric-card" tabIndex={0} aria-label={`등록 판독문 ${data.documents}건`}>
              <div className="metric-label" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <FileText size={16} style={{ color: 'var(--teal-600)' }} />
                등록 판독문
                <InfoPopover title="등록 판독문" description="선택 기관에 등록된 판독문 수입니다. 버전 이력은 아래에 별도로 표시합니다." />
              </div>
              <div className="metric-value">{data.documents}</div>
              <div className="metric-sub">버전 {data.documentVersions}개</div>
            </div>

            <div className="metric-card" tabIndex={0} aria-label={`전자서명 완료 ${data.signatures}건`}>
              <div className="metric-label" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <FileCheck size={16} style={{ color: '#16a34a' }} />
                전자서명 완료
                <InfoPopover title="전자서명 완료" description="서명 요청 중 전자서명 결과가 완료로 기록된 건수입니다. 검증 환경의 시험 기록이 포함될 수 있습니다." />
              </div>
              <div className="metric-value" style={{ color: '#16a34a' }}>
                {data.signatures}
              </div>
            </div>

            <div className="metric-card" tabIndex={0} aria-label={`병원 전송 큐 ${data.deliveriesByStatus['QUEUED'] || 0}건 대기`}>
              <div className="metric-label" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <Send size={16} style={{ color: '#0284c7' }} />
                전송 대기
                <InfoPopover title="전송 대기" description="병원 PACS/EMR로 보낼 작업 중 대기 상태인 건수입니다." />
              </div>
              <div className="metric-value" style={{ color: '#0284c7' }}>
                {data.deliveriesByStatus['QUEUED'] || 0}
              </div>
              <div className="metric-sub">
                전송 완료: {data.deliveriesByStatus['ACKED'] || 0}건
              </div>
            </div>

            <div className="metric-card" tabIndex={0} aria-label={`감사 로그 ${data.auditEvents}건`}>
              <div className="metric-label" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <ShieldCheck size={16} style={{ color: '#7c3aed' }} />
                감사 이벤트
                <InfoPopover title="감사 이벤트" description="서버에 누적된 감사 기록 건수입니다. 각 기록은 해시 체인으로 연결됩니다." />
              </div>
              <div className="metric-value">{data.auditEvents}</div>
            </div>
          </div>

          {/* Breakdown Sections */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '20px' }}>
            {/* Sign Request Status Breakdown */}
            <div className="card">
              <div className="card-header">
                <span className="card-title">
                  <Clock size={18} style={{ color: 'var(--amber-600)' }} />
                  서명 요청 상태
                  <InfoPopover title="서명 요청 상태" description="판독문 승인 및 전자서명 요청의 처리 상태별 건수입니다." />
                </span>
                <button
                  type="button"
                  onClick={() => setActiveTab('review')}
                  className="btn btn-outline"
                  style={{ padding: '4px 10px', fontSize: '0.75rem', display: 'inline-flex', alignItems: 'center', gap: '4px' }}
                >
                  <span>승인 목록 이동</span>
                  <ArrowRight size={12} />
                </button>
              </div>
              <div className="card-body">
                <table className="table">
                  <thead>
                    <tr>
                      <th>상태</th>
                      <th style={{ textAlign: 'right' }}>건수</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(data.signRequestsByStatus).map(([status, count]) => (
                      <tr key={status}>
                        <td>
                          <span
                            className={`badge ${
                              status === 'COMPLETED'
                                ? 'badge-completed'
                                : status === 'PENDING_APPROVAL'
                                ? 'badge-pending'
                                : status === 'FAILED' || status === 'EXPIRED'
                                ? 'badge-failed'
                                : 'badge-indeterminate'
                            }`}
                          >
                            {status === 'COMPLETED' ? (
                              <CheckCircle2 size={12} />
                            ) : status === 'PENDING_APPROVAL' ? (
                              <Clock size={12} />
                            ) : status === 'FAILED' || status === 'EXPIRED' ? (
                              <AlertTriangle size={12} />
                            ) : (
                              <HelpCircle size={12} />
                            )}
                            {signRequestLabels[status] || status}
                          </span>
                        </td>
                        <td style={{ textAlign: 'right', fontWeight: 700 }}>{count}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            {/* Delivery Status Breakdown */}
            <div className="card">
              <div className="card-header">
                <span className="card-title">
                  <Layers size={18} style={{ color: 'var(--teal-600)' }} />
                  병원 전송 상태
                  <InfoPopover title="병원 전송 상태" description="PACS/EMR 전송 요청의 상태별 건수입니다." />
                </span>
                <button
                  type="button"
                  onClick={() => setActiveTab('deliveries')}
                  className="btn btn-outline"
                  style={{ padding: '4px 10px', fontSize: '0.75rem', display: 'inline-flex', alignItems: 'center', gap: '4px' }}
                >
                  <span>전송 큐 이동</span>
                  <ArrowRight size={12} />
                </button>
              </div>
              <div className="card-body">
                <table className="table">
                  <thead>
                    <tr>
                      <th>상태</th>
                      <th style={{ textAlign: 'right' }}>건수</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(data.deliveriesByStatus).map(([status, count]) => (
                      <tr key={status}>
                        <td>
                          <span
                            className={`badge ${
                              status === 'ACKED'
                                ? 'badge-completed'
                                : status === 'QUEUED'
                                ? 'badge-pending'
                                : status === 'DEAD_LETTER'
                                ? 'badge-failed'
                                : 'badge-indeterminate'
                            }`}
                          >
                            {status === 'ACKED' ? (
                              <CheckCircle2 size={12} />
                            ) : status === 'QUEUED' ? (
                              <Clock size={12} />
                            ) : status === 'DEAD_LETTER' ? (
                              <XCircle size={12} />
                            ) : (
                              <Send size={12} />
                            )}
                            {deliveryLabels[status] || status}
                          </span>
                        </td>
                        <td style={{ textAlign: 'right', fontWeight: 700 }}>{count}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  );
};
