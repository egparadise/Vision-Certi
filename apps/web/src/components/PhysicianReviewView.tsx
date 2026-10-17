import React, { useEffect, useState, useCallback } from 'react';
import {
  ClipboardCheck,
  Clock,
  CheckCircle2,
  RefreshCw,
  ExternalLink,
  ShieldX,
  ListFilter,
} from 'lucide-react';
import { useApp } from '../context/AppContext';
import { SignRequest } from '../types/api';
import { ErrorBanner } from './ErrorBanner';
import { LoadingSpinner } from './LoadingSpinner';
import { EmptyState } from './EmptyState';
import { InfoPopover } from './InfoPopover';

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
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <h2 style={{ fontSize: '1.4rem', fontWeight: 800, color: 'var(--navy-900)' }}>
              의사 판독 승인 대기 목록
            </h2>
            <InfoPopover
              title="의사 판독 승인 및 전자서명 규약"
              buttonText="서명 규약 안내"
              description="판독 전문의가 원격 의료영상의 판독 소견을 최종 검토하고 1회용 보안 토큰을 결속하여 전자서명을 체결하는 업무 포털입니다."
              bullets={[
                "원격 판독문 전문 검토: PDF 원본 또는 PACS 정형 리포트 전문 확인 필수",
                "1회 승인 원칙: 건당 고유 Idempotency-Key로 중복 승인 방지",
                "관리자 서명 제한: 의료법에 따라 관리자(ADMIN)는 서명 승인 권한이 엄격히 제한됨",
                "서명 즉시 해시 체인 결속 및 병원 EMR/PACS 전송 큐 자동 인계"
              ]}
              badge="전문의 전용"
            />
          </div>
          <p style={{ fontSize: '0.85rem', color: 'var(--navy-600)', marginTop: '2px' }}>
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
        <div className="alert alert-warning" role="alert" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '10px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <ShieldX size={20} style={{ flexShrink: 0, color: 'var(--amber-700)' }} />
            <div>
              <strong>관리자 권한 안내:</strong> 현재 관리자(ADMIN) 계정으로 접속 중입니다.
              의료법 및 전자서명 보안 원칙에 따라 <u>관리자는 의사 전자서명 승인 버튼을 사용할 수 없습니다.</u>
              (서명 승인 테스트는 상단 역할 선택기에서 <code>doctor-demo</code> 로 전환하십시오.)
            </div>
          </div>
          <InfoPopover
            title="관리자 전자서명 제한 법적 근거"
            buttonText="권한 규정"
            description="의료법 제23조(전자의무기록) 및 전자서명법에 따라 의료영상 판독 전자서명은 면허를 보유한 의사 본인만 행사할 수 있습니다."
            bullets={[
              "원무 행정직/시스템 관리자는 대리 서명 불가",
              "관리자 계정은 의사 계정 연동 및 등록 심사만 수행 가능",
              "비인가 서명 시도시 시스템이 Fail-closed 원칙으로 원천 차단"
            ]}
            badge="의료법 규정"
          />
        </div>
      ) : (
        <div className="alert alert-info" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '10px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <ClipboardCheck size={20} style={{ flexShrink: 0, color: 'var(--teal-700)' }} />
            <div>
              <strong>의사 전자서명 안내:</strong> 판독문을 열람하고 내용을 최종 확인한 후,
              건별 1회 승인 버튼을 통해 모의 전자서명을 수행합니다.
            </div>
          </div>
          <InfoPopover
            title="전문의 판독 서명 절차 안내"
            buttonText="절차 안내"
            description="판독문 승인 시 다음의 필수 무결성 절차가 순차적으로 진행됩니다."
            bullets={[
              "1. 판독문 PDF 또는 정형 보고서 원본 열람",
              "2. 전문의 본인의 내용 확인 체크박스 동의",
              "3. SHA-256 해시 검증 및 서명키 결속 완료",
              "4. 병원 EMR/PACS 수신 큐 자동 전달"
            ]}
            badge="절차 가이드"
          />
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
          <CheckCircle2 size={14} />
          서명 완료 ({requests.filter((r) => r.status === 'COMPLETED').length})
        </button>
        <button
          type="button"
          className={`btn ${filter === 'ALL' ? 'btn-primary' : 'btn-secondary'}`}
          onClick={() => setFilter('ALL')}
          style={{ padding: '6px 14px', fontSize: '0.8rem' }}
        >
          <ListFilter size={14} />
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
