import React, { useEffect, useState, useCallback } from 'react';
import { ShieldCheck, RefreshCw, CheckCircle2, XCircle, Link2 } from 'lucide-react';
import { useApp } from '../context/AppContext';
import { AuditEvent } from '../types/api';
import { ErrorBanner } from './ErrorBanner';
import { LoadingSpinner } from './LoadingSpinner';
import { EmptyState } from './EmptyState';
import { CertificateLoginHistory } from './CertificateLoginHistory';

export const AuditEventsView: React.FC = () => {
  const { tenantId, client, isMockMode, mockStore, role } = useApp();

  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<unknown>(null);

  const fetchGenRef = React.useRef<number>(0);

  const loadAuditEvents = useCallback(async () => {
    const currentGen = ++fetchGenRef.current;
    setLoading(true);
    setError(null);
    try {
      if (isMockMode) {
        const tenantAuds = mockStore.auditEvents[tenantId] || [];
        if (currentGen !== fetchGenRef.current) return;
        setEvents(tenantAuds);
      } else {
        const res = await client.getAuditEvents();
        if (currentGen !== fetchGenRef.current) return;
        setEvents(res.items || []);
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
    loadAuditEvents();
  }, [loadAuditEvents]);

  return (
    <div>
      {role === 'ADMIN' && !isMockMode && <CertificateLoginHistory />}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
        <div>
          <h2 style={{ fontSize: '1.4rem', fontWeight: 800, color: 'var(--navy-900)' }}>
            감사 이벤트 추적 (Audit Trail)
          </h2>
          <p style={{ fontSize: '0.85rem', color: 'var(--navy-600)' }}>
            위변조 방지 SHA-256 해시 체인으로 기록된 기관 <strong>{tenantId}</strong> 의 전 행위 감사 로그
          </p>
        </div>
        <button
          type="button"
          onClick={loadAuditEvents}
          className="btn btn-secondary"
          disabled={loading}
          aria-label="감사 로그 새로고침"
        >
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
          새로고침
        </button>
      </div>

      <ErrorBanner error={error as Error} onRetry={loadAuditEvents} />

      <div className="alert alert-info">
        <ShieldCheck size={20} style={{ flexShrink: 0 }} />
        <div>
          <strong>감사 체인 보안 원칙:</strong> 모든 승인·거절·문서 등록은 이전 블록의 SHA-256 해시와 순차 결속되어
          중간 로그 삭제나 순서 변경이 즉시 탐지됩니다. (개인정보보호 원칙에 따라 Nonce 및 민감 환자정보는 로그에 일절 기록되지 않습니다.)
        </div>
      </div>

      {loading && !events.length ? (
        <LoadingSpinner message="감사 로그 체인을 검증 및 조회 중입니다..." />
      ) : events.length === 0 ? (
        <EmptyState
          title="기록된 감사 이벤트가 없습니다"
          message="현재 기관에서 발생한 감사 이벤트가 없습니다. 판독문 등록이나 승인을 진행하면 자동으로 기록됩니다."
        />
      ) : (
        <div className="card">
          <div className="table-responsive">
            <table className="table">
              <thead>
                <tr>
                  <th>순번 (Seq)</th>
                  <th>발생 일시</th>
                  <th>수행자 (Actor)</th>
                  <th>감사 행위 (Action)</th>
                  <th>대상 (Target)</th>
                  <th>결과</th>
                  <th>추적 ID (Correlation)</th>
                  <th>해시 체인 결속 (SHA-256)</th>
                </tr>
              </thead>
              <tbody>
                {events.map((evt) => (
                  <tr key={evt.id}>
                    <td style={{ fontWeight: 700, color: 'var(--navy-700)' }}>
                      #{evt.sequence}
                    </td>
                    <td style={{ fontSize: '0.8rem', whiteSpace: 'nowrap' }}>
                      {new Date(evt.occurredAt).toLocaleString('ko-KR')}
                    </td>
                    <td>
                      <span style={{ fontWeight: 600 }}>{evt.actorId}</span>
                    </td>
                    <td>
                      <code style={{ fontSize: '0.8rem', fontWeight: 600, color: 'var(--teal-800)' }}>
                        {evt.action}
                      </code>
                    </td>
                    <td>
                      <div style={{ fontSize: '0.8rem' }}>{evt.targetType}</div>
                      <code style={{ fontSize: '0.7rem', color: 'var(--navy-500)' }}>
                        {evt.targetId.substring(0, 8)}...
                      </code>
                    </td>
                    <td>
                      {evt.outcome === 'SUCCESS' ? (
                        <span className="badge badge-completed">
                          <CheckCircle2 size={12} /> 성공
                        </span>
                      ) : (
                        <span className="badge badge-failed">
                          <XCircle size={12} /> 거절 (DENIED)
                        </span>
                      )}
                    </td>
                    <td>
                      <code style={{ fontSize: '0.75rem', color: 'var(--navy-600)' }}>
                        {evt.correlationId}
                      </code>
                    </td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                        <Link2 size={12} style={{ color: 'var(--teal-600)' }} />
                        <code style={{ fontSize: '0.7rem', color: 'var(--navy-600)' }}>
                          {evt.hash.substring(0, 10)}...
                        </code>
                      </div>
                      <div style={{ fontSize: '0.65rem', color: 'var(--navy-400)' }}>
                        이전: {evt.previousHash.substring(0, 8)}...
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
};
