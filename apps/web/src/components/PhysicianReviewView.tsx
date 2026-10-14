import React, { useEffect, useState, useCallback } from 'react';
import {
  ClipboardCheck,
  Clock,
  CheckCircle,
  RefreshCw,
  ExternalLink,
  ShieldX,
} from 'lucide-react';
import { useApp } from '../context/AppContext';
import { SignRequest } from '../types/api';
import { ErrorBanner } from './ErrorBanner';
import { LoadingSpinner } from './LoadingSpinner';
import { EmptyState } from './EmptyState';

export const PhysicianReviewView: React.FC = () => {
  const {
    tenantId,
    actorId,
    role,
    client,
    isMockMode,
    mockStore,
    setSelectedDocumentId,
    setActiveTab,
  } = useApp();

  const [requests, setRequests] = useState<SignRequest[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<unknown>(null);
  const [filter, setFilter] = useState<'ALL' | 'PENDING' | 'COMPLETED'>('PENDING');

  const fetchGenRef = React.useRef<number>(0);

  const loadRequests = useCallback(async () => {
    const currentGen = ++fetchGenRef.current;
    setLoading(true);
    setError(null);
    try {
      if (isMockMode) {
        const tenantReqs = mockStore.signRequests[tenantId] || [];
        if (currentGen !== fetchGenRef.current) return;
        setRequests(tenantReqs);
      } else {
        const res = await client.getSignRequests();
        if (currentGen !== fetchGenRef.current) return;
        setRequests(res.items || []);
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
    loadRequests();
  }, [loadRequests]);

  const filtered = requests.filter((req) => {
    if (filter === 'PENDING') return req.status === 'PENDING_APPROVAL';
    if (filter === 'COMPLETED') return req.status === 'COMPLETED';
    return true;
  });

  const handleOpenDocument = (documentId: string) => {
    setSelectedDocumentId(documentId);
    setActiveTab('documents');
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
        <div>
          <h2 style={{ fontSize: '1.4rem', fontWeight: 800, color: 'var(--navy-900)' }}>
            의사 판독 승인 대기 목록
          </h2>
          <p style={{ fontSize: '0.85rem', color: 'var(--navy-600)' }}>
            접속자: <strong>{actorId}</strong> ({role === 'DOCTOR' ? '판독 전문의' : '원무 관리자'}) | 소속:{' '}
            <strong>{tenantId}</strong>
          </p>
        </div>
        <div style={{ display: 'flex', gap: '10px' }}>
          <button
            type="button"
            onClick={loadRequests}
            className="btn btn-secondary"
            disabled={loading}
            aria-label="목록 새로고침"
          >
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
            새로고침
          </button>
        </div>
      </div>

      {/* Role Notice */}
      {role === 'ADMIN' ? (
        <div className="alert alert-warning" role="alert">
          <ShieldX size={20} style={{ flexShrink: 0 }} />
          <div>
            <strong>관리자 권한 안내:</strong> 현재 관리자(ADMIN) 계정으로 접속 중입니다.
            의료법 및 전자서명 보안 원칙에 따라 <u>관리자는 의사 전자서명 승인 버튼을 사용할 수 없습니다.</u>
            (서명 승인 테스트는 상단 역할 선택기에서 <code>doctor-demo</code> 로 전환하십시오.)
          </div>
        </div>
      ) : (
        <div className="alert alert-info">
          <ClipboardCheck size={20} style={{ flexShrink: 0 }} />
          <div>
            <strong>의사 전자서명 안내:</strong> 판독문을 열람하고 내용을 최종 확인한 후,
            건별 1회 승인 버튼을 통해 모의 전자서명을 수행합니다.
          </div>
        </div>
      )}

      <ErrorBanner error={error as Error} onRetry={loadRequests} />

      {/* Filter Tabs */}
      <div style={{ display: 'flex', gap: '8px', marginBottom: '16px' }}>
        <button
          type="button"
          className={`btn ${filter === 'PENDING' ? 'btn-primary' : 'btn-secondary'}`}
          onClick={() => setFilter('PENDING')}
          style={{ padding: '6px 14px', fontSize: '0.8rem' }}
        >
          <Clock size={14} />
          서명 대기 ({requests.filter((r) => r.status === 'PENDING_APPROVAL').length})
        </button>
        <button
          type="button"
          className={`btn ${filter === 'COMPLETED' ? 'btn-primary' : 'btn-secondary'}`}
          onClick={() => setFilter('COMPLETED')}
          style={{ padding: '6px 14px', fontSize: '0.8rem' }}
        >
          <CheckCircle size={14} />
          서명 완료 ({requests.filter((r) => r.status === 'COMPLETED').length})
        </button>
        <button
          type="button"
          className={`btn ${filter === 'ALL' ? 'btn-primary' : 'btn-secondary'}`}
          onClick={() => setFilter('ALL')}
          style={{ padding: '6px 14px', fontSize: '0.8rem' }}
        >
          전체 보기 ({requests.length})
        </button>
      </div>

      {loading && !requests.length ? (
        <LoadingSpinner message="서명 요청 목록을 조회 중입니다..." />
      ) : filtered.length === 0 ? (
        <EmptyState
          title="해당 상태의 판독문이 없습니다"
          message={
            filter === 'PENDING'
              ? '현재 서명 대기 중인 판독문이 없습니다. 새 판독문을 등록하여 서명 요청을 생성하세요.'
              : '조회된 서명 요청 내역이 없습니다.'
          }
          actionText="새 판독문 등록하러 가기"
          onAction={() => setActiveTab('documents')}
        />
      ) : (
        <div className="card">
          <div className="table-responsive">
            <table className="table">
              <thead>
                <tr>
                  <th>요청 ID</th>
                  <th>문서 참조</th>
                  <th>지정 서명의</th>
                  <th>해시 결속 (SHA-256)</th>
                  <th>상태</th>
                  <th>요청 생성일시</th>
                  <th>작업</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((req) => (
                  <tr key={req.id}>
                    <td>
                      <code style={{ fontSize: '0.75rem', color: 'var(--navy-600)' }}>
                        {req.id.substring(0, 8)}...
                      </code>
                    </td>
                    <td>
                      <span style={{ fontWeight: 600 }}>{req.documentId.substring(0, 8)}...</span>
                      <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)' }}>
                        버전 ID: {req.documentVersionId.substring(0, 8)}...
                      </div>
                    </td>
                    <td>
                      <span style={{ fontWeight: 600 }}>{req.signerId}</span>
                      {req.signerId === actorId && (
                        <span className="badge badge-valid" style={{ marginLeft: '6px', fontSize: '0.65rem' }}>
                          본인
                        </span>
                      )}
                    </td>
                    <td>
                      <code style={{ fontSize: '0.75rem', color: 'var(--teal-700)' }}>
                        {req.documentSha256.substring(0, 12)}...
                      </code>
                    </td>
                    <td>
                      <span
                        className={`badge ${
                          req.status === 'COMPLETED'
                            ? 'badge-completed'
                            : req.status === 'PENDING_APPROVAL'
                            ? 'badge-pending'
                            : req.status === 'FAILED' || req.status === 'EXPIRED'
                            ? 'badge-failed'
                            : 'badge-indeterminate'
                        }`}
                      >
                        {req.status === 'PENDING_APPROVAL' ? '서명 대기' : req.status}
                      </span>
                    </td>
                    <td style={{ fontSize: '0.8rem', color: 'var(--navy-600)' }}>
                      {new Date(req.createdAt).toLocaleString('ko-KR')}
                    </td>
                    <td>
                      <button
                        type="button"
                        onClick={() => handleOpenDocument(req.documentId)}
                        className="btn btn-secondary"
                        style={{ padding: '5px 10px', fontSize: '0.75rem' }}
                      >
                        <ExternalLink size={14} />
                        문서 열람 및 승인
                      </button>
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
