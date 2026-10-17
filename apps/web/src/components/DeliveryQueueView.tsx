import React, { useEffect, useState, useCallback } from 'react';
import { Send, RefreshCw, Layers, CheckCircle2, Clock, AlertTriangle } from 'lucide-react';
import { useApp } from '../context/AppContext';
import { Delivery } from '../types/api';
import { ErrorBanner } from './ErrorBanner';
import { LoadingSpinner } from './LoadingSpinner';
import { EmptyState } from './EmptyState';
import { InfoPopover } from './InfoPopover';

export const DeliveryQueueView: React.FC = () => {
  const { tenantId, client, isMockMode, mockStore } = useApp();

  const [deliveries, setDeliveries] = useState<Delivery[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<unknown>(null);

  const fetchGenRef = React.useRef<number>(0);

  const loadDeliveries = useCallback(async () => {
    const currentGen = ++fetchGenRef.current;
    setLoading(true);
    setError(null);
    try {
      if (isMockMode) {
        const tenantDels = mockStore.deliveries[tenantId] || [];
        if (currentGen !== fetchGenRef.current) return;
        setDeliveries(tenantDels);
      } else {
        const res = await client.getDeliveries();
        if (currentGen !== fetchGenRef.current) return;
        setDeliveries(res.items || []);
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
    loadDeliveries();
  }, [loadDeliveries]);

  const renderStatusBadge = (status: string) => {
    switch (status) {
      case 'ACKED':
        return (
          <span className="badge badge-completed">
            <CheckCircle2 size={12} /> 수신 확인 (ACKED)
          </span>
        );
      case 'QUEUED':
        return (
          <span className="badge badge-pending">
            <Clock size={12} /> 전송 대기 (QUEUED)
          </span>
        );
      case 'SENDING':
        return (
          <span className="badge badge-indeterminate">
            <Send size={12} /> 전송 중 (SENDING)
          </span>
        );
      case 'RETRY_WAIT':
        return (
          <span className="badge badge-pending">
            <Clock size={12} /> 재시도 대기 (RETRY_WAIT)
          </span>
        );
      case 'DEAD_LETTER':
      default:
        return (
          <span className="badge badge-failed">
            <AlertTriangle size={12} /> 전송 실패 (DEAD_LETTER)
          </span>
        );
    }
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <h2 style={{ fontSize: '1.4rem', fontWeight: 800, color: 'var(--navy-900)' }}>
              병원 EMR/PACS 전송 큐 (Outbox Delivery)
            </h2>
            <InfoPopover
              title="서명 완료와 병원 전송 분리 원칙"
              buttonText="전송 원칙 안내"
              description="의사의 전자서명 완료와 외부 병원 EMR/PACS 전송은 시스템 장애 격리를 위해 엄격히 분리(Decoupled)되어 있습니다."
              bullets={[
                "비동기 전송 큐: 네트워크 일시 장애 시 지수 백오프(Exponential Backoff)로 자동 재전송",
                "서명 무효화 방지: 전송 실패가 발생하더라도 이미 생성된 의사 전자서명은 온전히 보존",
                "수신확인 (ACK): 외부 병원 PACS가 정상 접수 시 상태가 ACKED로 전이"
              ]}
              badge="장애 격리 아키텍처"
            />
          </div>
          <p style={{ fontSize: '0.85rem', color: 'var(--navy-600)', marginTop: '2px' }}>
            서명 완료된 판독문의 외부 의료기관 원격 전송 상태 및 업무 수신확인(ACK) 대기열
          </p>
        </div>
        <button
          type="button"
          onClick={loadDeliveries}
          className="btn btn-secondary"
          disabled={loading}
          aria-label="전송 큐 새로고침"
        >
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
          새로고침
        </button>
      </div>

      <ErrorBanner error={error as Error} onRetry={loadDeliveries} />

      {/* Separation of Concerns notice */}
      <div className="alert alert-info" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '10px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <Layers size={20} style={{ flexShrink: 0, color: 'var(--teal-700)' }} />
          <div>
            <strong>서명 완료와 병원 전송 분리 원칙:</strong> 의사의 전자서명이 완료되었다고 해서 병원 EMR/PACS
            전송이 완료된 것은 아닙니다. 네트워크 장애 시 전송은 재시도 큐에서 자동 백오프 처리되며,
            전송 실패가 발생하더라도 이미 생성된 의사 전자서명은 무효화되지 않습니다.
          </div>
        </div>
      </div>

      {loading && !deliveries.length ? (
        <LoadingSpinner message="병원 전송 큐를 조회 중입니다..." />
      ) : deliveries.length === 0 ? (
        <EmptyState
          title="전송 대기 중인 항목이 없습니다"
          message="현재 기관의 전송 큐가 비어 있습니다. 판독문 전자서명이 완료되면 자동으로 전송 작업이 큐에 등록됩니다."
        />
      ) : (
        <div className="card">
          <div className="table-responsive">
            <table className="table">
              <thead>
                <tr>
                  <th>전송 작업 ID</th>
                  <th>문서 ID</th>
                  <th>서명 ID (결속)</th>
                  <th>전송 커넥터 및 목적지</th>
                  <th>전송 상태</th>
                  <th>시도 횟수</th>
                  <th>등록 일시</th>
                </tr>
              </thead>
              <tbody>
                {deliveries.map((del) => (
                  <tr key={del.id}>
                    <td>
                      <code style={{ fontSize: '0.8rem' }}>{del.id.substring(0, 8)}...</code>
                    </td>
                    <td>
                      <code>{del.documentId.substring(0, 8)}...</code>
                    </td>
                    <td>
                      <span className="badge badge-completed" style={{ fontSize: '0.65rem' }}>
                        서명완료
                      </span>{' '}
                      <code style={{ fontSize: '0.75rem' }}>{del.signatureId.substring(0, 8)}...</code>
                    </td>
                    <td>
                      <div>
                        <strong>{del.connectorId}</strong>
                      </div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)', fontFamily: 'var(--font-mono)' }}>
                        {del.destination}
                      </div>
                    </td>
                    <td>{renderStatusBadge(del.status)}</td>
                    <td style={{ textAlign: 'center', fontWeight: 600 }}>{del.attempts} 회</td>
                    <td style={{ fontSize: '0.8rem' }}>
                      {new Date(del.createdAt).toLocaleString('ko-KR')}
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
