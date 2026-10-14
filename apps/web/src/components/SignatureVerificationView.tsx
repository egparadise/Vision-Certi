import React, { useEffect, useState, useCallback } from 'react';
import {
  KeyRound,
  ShieldCheck,
  AlertTriangle,
  RefreshCw,
  CheckCircle2,
  XCircle,
  HelpCircle,
} from 'lucide-react';
import { useApp } from '../context/AppContext';
import { Signature, Verification } from '../types/api';
import { createMockVerification } from '../mock/mockData';
import { ErrorBanner } from './ErrorBanner';
import { LoadingSpinner } from './LoadingSpinner';
import { EmptyState } from './EmptyState';

export const SignatureVerificationView: React.FC = () => {
  const { tenantId, client, isMockMode, mockStore } = useApp();

  const [signatures, setSignatures] = useState<Signature[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<unknown>(null);

  // Active verification result modal / details
  const [activeVerification, setActiveVerification] = useState<Verification | null>(null);
  const [verifyingId, setVerifyingId] = useState<string | null>(null);
  const [verifyError, setVerifyError] = useState<unknown>(null);

  const fetchGenRef = React.useRef<number>(0);

  const loadSignatures = useCallback(async () => {
    const currentGen = ++fetchGenRef.current;
    setLoading(true);
    setError(null);
    try {
      if (isMockMode) {
        const tenantSigs = mockStore.signatures[tenantId] || [];
        if (currentGen !== fetchGenRef.current) return;
        setSignatures(tenantSigs);
      } else {
        const res = await client.getSignatures();
        if (currentGen !== fetchGenRef.current) return;
        setSignatures(res.items || []);
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
    loadSignatures();
  }, [loadSignatures]);

  const handleVerify = async (sig: Signature) => {
    setVerifyingId(sig.id);
    setVerifyError(null);
    try {
      if (isMockMode) {
        const mockVer = createMockVerification(sig.id);
        setActiveVerification(mockVer);
      } else {
        const ver = await client.verifySignature(sig.id);
        setActiveVerification(ver);
      }
    } catch (err) {
      setVerifyError(err);
    } finally {
      setVerifyingId(null);
    }
  };

  const renderCheckBadge = (status: string) => {
    switch (status) {
      case 'VALID':
        return (
          <span className="badge badge-valid">
            <CheckCircle2 size={12} /> 유효 (VALID)
          </span>
        );
      case 'INVALID':
        return (
          <span className="badge badge-invalid">
            <XCircle size={12} /> 위변조/무효 (INVALID)
          </span>
        );
      case 'INDETERMINATE':
      default:
        return (
          <span className="badge badge-indeterminate">
            <HelpCircle size={12} /> 판정 불가 (INDETERMINATE)
          </span>
        );
    }
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
        <div>
          <h2 style={{ fontSize: '1.4rem', fontWeight: 800, color: 'var(--navy-900)' }}>
            서명 내역 및 유효성 검증 포털
          </h2>
          <p style={{ fontSize: '0.85rem', color: 'var(--navy-600)' }}>
            생성된 판독문 전자서명 원본 및 RFC 3161 시점확인·인증체인·원본 무결성 검증
          </p>
        </div>
        <button
          type="button"
          onClick={loadSignatures}
          className="btn btn-secondary"
          disabled={loading}
          aria-label="서명 목록 새로고침"
        >
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
          새로고침
        </button>
      </div>

      <ErrorBanner error={error as Error} onRetry={loadSignatures} />
      <ErrorBanner error={verifyError as Error} />

      {/* Verification Notice */}
      <div className="alert alert-warning">
        <AlertTriangle size={20} style={{ flexShrink: 0 }} />
        <div>
          <strong>개발 슬라이스 검증 안내:</strong> 현재 모의 서명 슬라이스에서 전체 상태는 결코 <code>VALID</code>가
          될 수 없습니다 (정상 시 <code>INDETERMINATE</code> 판정). 원본 무결성 해시가 일치하더라도 실제 인증체인과
          TSA 시점확인은 구현되지 않았습니다. 임상 사용이나 법적 적합성은 검증하지 않습니다.
        </div>
      </div>

      {loading && !signatures.length ? (
        <LoadingSpinner message="서명 내역을 조회 중입니다..." />
      ) : signatures.length === 0 ? (
        <EmptyState
          title="생성된 전자서명이 없습니다"
          message="현재 기관에서 완료된 전자서명 내역이 없습니다. 의사 서명 대기 화면에서 판독문을 승인하세요."
        />
      ) : (
        <div className="card">
          <div className="table-responsive">
            <table className="table">
              <thead>
                <tr>
                  <th>서명 ID</th>
                  <th>문서 ID</th>
                  <th>서명자 (Signer)</th>
                  <th>알고리즘</th>
                  <th>서명 일시</th>
                  <th>임상 사용 허용 여부</th>
                  <th>검증 작업</th>
                </tr>
              </thead>
              <tbody>
                {signatures.map((sig) => (
                  <tr key={sig.id}>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <KeyRound size={15} style={{ color: 'var(--teal-700)' }} />
                        <code style={{ fontSize: '0.8rem' }}>{sig.id}</code>
                      </div>
                    </td>
                    <td>
                      <code>{sig.documentId.substring(0, 8)}...</code>
                    </td>
                    <td>
                      <strong>{sig.signerId}</strong>
                    </td>
                    <td>
                      <span className="badge badge-planned">{sig.algorithm}</span>
                    </td>
                    <td style={{ fontSize: '0.8rem' }}>
                      {new Date(sig.createdAt).toLocaleString('ko-KR')}
                    </td>
                    <td>
                      <span className="badge badge-failed" style={{ fontSize: '0.7rem' }}>
                        임상 불가 (clinicalUseAllowed: false)
                      </span>
                    </td>
                    <td>
                      <button
                        type="button"
                        onClick={() => handleVerify(sig)}
                        className="btn btn-primary"
                        disabled={verifyingId === sig.id}
                        style={{ padding: '5px 12px', fontSize: '0.75rem' }}
                      >
                        <ShieldCheck size={14} />
                        {verifyingId === sig.id ? '검증 수행 중...' : '서명 유효성 검증'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Verification Result Modal / Detailed Panel */}
      {activeVerification && (
        <div className="modal-overlay" role="dialog" aria-modal="true" aria-labelledby="verify-modal-title">
          <div className="modal-content" style={{ maxWidth: '680px' }}>
            <div className="modal-header">
              <h3 id="verify-modal-title" style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--navy-900)' }}>
                전자서명 종합 유효성 검증 결과 (Verification Report)
              </h3>
              <button
                type="button"
                onClick={() => setActiveVerification(null)}
                className="btn btn-secondary"
                style={{ padding: '4px 8px' }}
                aria-label="닫기"
              >
                닫기
              </button>
            </div>

            <div className="modal-body">
              {/* Overall status banner */}
              <div
                style={{
                  padding: '16px',
                  borderRadius: '8px',
                  backgroundColor:
                    activeVerification.status === 'INDETERMINATE' ? '#ede9fe' : '#fee2e2',
                  border:
                    activeVerification.status === 'INDETERMINATE'
                      ? '1px solid #c4b5fd'
                      : '1px solid #fca5a5',
                  marginBottom: '20px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                }}
              >
                <div>
                  <div style={{ fontSize: '0.8rem', color: 'var(--navy-700)', fontWeight: 600 }}>
                    종합 서명 검증 판정
                  </div>
                  <div
                    style={{
                      fontSize: '1.3rem',
                      fontWeight: 800,
                      color: activeVerification.status === 'INDETERMINATE' ? '#5b21b6' : '#991b1b',
                    }}
                  >
                    {activeVerification.status === 'INDETERMINATE'
                      ? 'INDETERMINATE (판정 불가 / 증거 불충분)'
                      : 'INVALID (위변조 또는 불일치)'}
                  </div>
                </div>
                <div style={{ textAlign: 'right' }}>
                  <span className="badge badge-failed" style={{ fontSize: '0.75rem' }}>
                    의료용 사용 불가
                  </span>
                </div>
              </div>

              {/* 6-point Verification Checks */}
              <div style={{ marginBottom: '20px' }}>
                <h4 style={{ fontSize: '0.9rem', fontWeight: 700, marginBottom: '10px', color: 'var(--navy-800)' }}>
                  세부 규격별 6대 검증 항목
                </h4>
                <table className="table" style={{ fontSize: '0.85rem' }}>
                  <tbody>
                    <tr>
                      <td style={{ fontWeight: 600 }}>1. 문서 무결성 (documentIntegrity)</td>
                      <td style={{ textAlign: 'right' }}>
                        {renderCheckBadge(activeVerification.checks.documentIntegrity)}
                      </td>
                    </tr>
                    <tr>
                      <td style={{ fontWeight: 600 }}>2. 서명 유효성 (signature)</td>
                      <td style={{ textAlign: 'right' }}>
                        {renderCheckBadge(activeVerification.checks.signature)}
                      </td>
                    </tr>
                    <tr>
                      <td style={{ fontWeight: 600 }}>3. 인증서 신뢰 체인 (trustChain)</td>
                      <td style={{ textAlign: 'right' }}>
                        {renderCheckBadge(activeVerification.checks.trustChain)}
                      </td>
                    </tr>
                    <tr>
                      <td style={{ fontWeight: 600 }}>4. 시점확인 토큰 (timestamp / TSA)</td>
                      <td style={{ textAlign: 'right' }}>
                        {renderCheckBadge(activeVerification.checks.timestamp)}
                      </td>
                    </tr>
                    <tr>
                      <td style={{ fontWeight: 600 }}>5. 서명 시점 폐기 여부 (revocationAtSigning)</td>
                      <td style={{ textAlign: 'right' }}>
                        {renderCheckBadge(activeVerification.checks.revocationAtSigning)}
                      </td>
                    </tr>
                    <tr>
                      <td style={{ fontWeight: 600 }}>6. 서명 시점 권한 자격 (authorizationAtSigning)</td>
                      <td style={{ textAlign: 'right' }}>
                        {renderCheckBadge(activeVerification.checks.authorizationAtSigning)}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              {/* Reason Codes */}
              <div>
                <h4 style={{ fontSize: '0.9rem', fontWeight: 700, marginBottom: '8px', color: 'var(--navy-800)' }}>
                  판정 사유 코드 (Reason Codes)
                </h4>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
                  {activeVerification.reasonCodes.map((rc, idx) => (
                    <span
                      key={idx}
                      style={{
                        fontFamily: 'var(--font-mono)',
                        fontSize: '0.75rem',
                        padding: '3px 8px',
                        backgroundColor: 'var(--navy-100)',
                        borderRadius: '4px',
                        color: 'var(--navy-800)',
                        border: '1px solid var(--navy-300)',
                      }}
                    >
                      {rc}
                    </span>
                  ))}
                </div>
              </div>

              <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)', marginTop: '16px' }}>
                검증 실행 일시: {new Date(activeVerification.verifiedAt).toLocaleString('ko-KR')} | 서명 ID: {activeVerification.signatureId}
              </div>
            </div>

            <div className="modal-footer">
              <button
                type="button"
                onClick={() => setActiveVerification(null)}
                className="btn btn-secondary"
              >
                확인 완료
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
