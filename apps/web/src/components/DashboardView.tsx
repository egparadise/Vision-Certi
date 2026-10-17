import React, { useEffect, useState, useCallback } from 'react';
import {
  FileText,
  FileCheck,
  Send,
  ShieldCheck,
  AlertCircle,
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

export const DashboardView: React.FC = () => {
  const { tenantId, client, isMockMode, mockStore, setActiveTab } = useApp();
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

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <h2 style={{ fontSize: '1.4rem', fontWeight: 800, color: 'var(--navy-900)' }}>
              기관별 운영 대시보드
            </h2>
            <InfoPopover
              title="운영 대시보드 및 지표 안내"
              buttonText="대시보드 안내"
              description={
                <span>
                  현재 선택 기관(<strong>{tenantId}</strong>)의 원격 의료영상 판독문 등록, 의사 전자서명 승인, 병원 EMR/PACS 전송 큐 및 감사 로그의 실시간 운영 지표입니다.
                </span>
              }
              bullets={[
                "등록 판독문: 기관에 접수된 전체 의료영상 소견서 및 버전 이력 건수",
                "전자서명 완료: 지정 전문의 본인확인 및 전자서명이 체결된 누적 건수",
                "병원 전송 큐: 서명 완료 후 원격 의료기관 PACS/EMR로 전송 대기 또는 수신완료(ACK)된 작업",
                "감사 이벤트: 위변조 방지 SHA-256 불변 해시 체인으로 누적 결속된 감사 트레일"
              ]}
              badge="실시간 운영 지표"
            />
          </div>
          <p style={{ fontSize: '0.85rem', color: 'var(--navy-600)', marginTop: '2px' }}>
            현재 선택 기관: <strong style={{ color: 'var(--teal-700)' }}>{tenantId}</strong> 의 판독 및 서명 처리 현황 요약
          </p>
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

      {/* Security & Persistence Notice */}
      <div className="alert alert-info" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '10px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <AlertCircle size={18} style={{ flexShrink: 0, color: 'var(--teal-700)' }} />
          <div style={{ fontSize: '0.825rem' }}>
            <strong>개발 슬라이스 환경 정보:</strong> 현재 백엔드는{' '}
            <code>IN_MEMORY</code> 비영속 저장소 및 <code>MOCK-HMAC-SHA256</code> 임시 서명 모드로 구동 중입니다.
            서버 재시작 시 데이터가 초기화됩니다. 실제 HSM/TSA는 연결되지 않았으며 의료용으로 사용할 수 없습니다.
          </div>
        </div>
        <InfoPopover
          title="개발 슬라이스 모의 환경 상세 규약"
          buttonText="상세 규약"
          description="현재 백엔드는 개발 및 기능 검증 전용 프로파일로 설정되어 있습니다."
          bullets={[
            "비영속성: 인메모리 저장소로 구동되어 프로세스 재시작 시 데이터가 초기화됩니다.",
            "임시 서명: HMAC-SHA256 모의 알고리즘으로 서명되며, 법적 전자서명 효력을 갖지 않습니다.",
            "운영 배포: 실제 임상 적용 시에는 OpenBao Transit PKI 및 전자인증 연동 프로파일로 전환됩니다."
          ]}
          badge="보안 안내"
        />
      </div>

      {data && (
        <>
          {/* Top Metrics Cards */}
          <div className="metrics-grid">
            <div className="metric-card" tabIndex={0} aria-label={`등록 판독문 ${data.documents}건`}>
              <div className="metric-label" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <FileText size={16} style={{ color: 'var(--teal-600)' }} />
                등록 판독문 (Documents)
              </div>
              <div className="metric-value">{data.documents}</div>
              <div className="metric-sub">총 버전: {data.documentVersions}개 (이력 보존)</div>
            </div>

            <div className="metric-card" tabIndex={0} aria-label={`전자서명 완료 ${data.signatures}건`}>
              <div className="metric-label" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <FileCheck size={16} style={{ color: '#16a34a' }} />
                전자서명 완료 (Signatures)
              </div>
              <div className="metric-value" style={{ color: '#16a34a' }}>
                {data.signatures}
              </div>
              <div className="metric-sub">모의 전자서명 완료 누적</div>
            </div>

            <div className="metric-card" tabIndex={0} aria-label={`병원 전송 큐 ${data.deliveriesByStatus['QUEUED'] || 0}건 대기`}>
              <div className="metric-label" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <Send size={16} style={{ color: '#0284c7' }} />
                전송 대기 (Delivery Queue)
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
                감사 이벤트 (Audit Trail)
              </div>
              <div className="metric-value">{data.auditEvents}</div>
              <div className="metric-sub">불변 해시 체인 연계</div>
            </div>
          </div>

          {/* Breakdown Sections */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '20px' }}>
            {/* Sign Request Status Breakdown */}
            <div className="card">
              <div className="card-header">
                <span className="card-title">
                  <Clock size={18} style={{ color: 'var(--amber-600)' }} />
                  서명 요청 상태 분포 (Sign Requests)
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
                      <th>상태 (Status)</th>
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
                            {status}
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
                  병원 전송 상태 분포 (Deliveries)
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
                      <th>상태 (Status)</th>
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
                            {status}
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
