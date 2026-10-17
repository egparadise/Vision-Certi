import React, { useEffect, useState, useCallback, useRef } from 'react';
import {
  ArrowLeft,
  FileText,
  Clock,
  Key,
  ShieldCheck,
  CheckCircle2,
  FileEdit,
  RefreshCw,
  AlertTriangle,
  Lock,
  CheckSquare,
  ExternalLink,
} from 'lucide-react';
import { useApp } from '../context/AppContext';
import { Document, DocumentVersion, SignRequest } from '../types/api';
import { DocumentAmendModal } from './DocumentAmendModal';
import { ErrorBanner } from './ErrorBanner';
import { LoadingSpinner } from './LoadingSpinner';
import { InfoPopover } from './InfoPopover';

interface DocumentDetailViewProps {
  documentId: string;
  onBack: () => void;
}

export const DocumentDetailView: React.FC<DocumentDetailViewProps> = ({
  documentId,
  onBack,
}) => {
  const {
    client,
    isMockMode,
    tenantId,
    actorId,
    role,
    mockStore,
    setMockStore,
  } = useApp();

  const [document, setDocument] = useState<Document | null>(null);
  const [selectedVersion, setSelectedVersion] = useState<DocumentVersion | null>(null);
  const [signRequest, setSignRequest] = useState<SignRequest | null>(null);
  const [pdfUrl, setPdfUrl] = useState<string | null>(null);

  const [loading, setLoading] = useState<boolean>(true);
  const [pdfLoading, setPdfLoading] = useState<boolean>(false);
  const [pdfLoaded, setPdfLoaded] = useState<boolean>(false);
  const [pdfError, setPdfError] = useState<string | null>(null);
  const [pdfViewConfirmed, setPdfViewConfirmed] = useState<boolean>(false);

  const [actionLoading, setActionLoading] = useState<boolean>(false);
  const [error, setError] = useState<unknown>(null);
  const [isAmendModalOpen, setIsAmendModalOpen] = useState<boolean>(false);

  // Active blob URL ref for leak-free revocation
  const activeBlobUrlRef = useRef<string | null>(null);

  // Requirement: Preserve one Idempotency-Key per sign request across approval retries
  const approvalIdempotencyKeysRef = useRef<Map<string, string>>(new Map());

  // Generation counter to discard stale async requests when tenant/document changes
  const fetchGenerationRef = useRef<number>(0);

  const revokePdfUrl = useCallback(() => {
    if (activeBlobUrlRef.current) {
      URL.revokeObjectURL(activeBlobUrlRef.current);
      activeBlobUrlRef.current = null;
    }
    setPdfUrl(null);
    setPdfLoaded(false);
  }, []);

  useEffect(() => {
    return () => {
      revokePdfUrl();
    };
  }, [revokePdfUrl]);

  // Reset transient view states when tenant, actor, mode, or document changes
  useEffect(() => {
    setSignRequest(null);
    setSelectedVersion(null);
    setPdfViewConfirmed(false);
    setError(null);
  }, [tenantId, actorId, isMockMode, documentId]);

  // Load Document and SignRequest
  const loadDocumentData = useCallback(async () => {
    const currentGen = ++fetchGenerationRef.current;
    setLoading(true);
    setError(null);

    try {
      if (isMockMode) {
        const found = (mockStore.documents[tenantId] || []).find((d) => d.id === documentId);
        if (currentGen !== fetchGenerationRef.current) return;

        if (!found) {
          throw new Error(`문서를 찾을 수 없습니다: ${documentId}`);
        }
        setDocument(found);
        setSelectedVersion(found.latestVersion);

        const req = (mockStore.signRequests[tenantId] || []).find(
          (r) => r.documentVersionId === found.latestVersion.id
        );
        setSignRequest(req || null);
      } else {
        const doc = await client.getDocument(documentId);
        if (currentGen !== fetchGenerationRef.current) return;
        setDocument(doc);
        setSelectedVersion(doc.latestVersion);

        // Fetch sign-requests; do NOT swallow error if it fails
        try {
          const reqList = await client.getSignRequests();
          if (currentGen !== fetchGenerationRef.current) return;
          const req = reqList.items.find((r) => r.documentVersionId === doc.latestVersion.id);
          setSignRequest(req || null);
        } catch (reqErr) {
          if (currentGen === fetchGenerationRef.current) {
            setError(reqErr);
          }
        }
      }
    } catch (err) {
      if (currentGen === fetchGenerationRef.current) {
        setError(err);
      }
    } finally {
      if (currentGen === fetchGenerationRef.current) {
        setLoading(false);
      }
    }
  }, [client, documentId, isMockMode, mockStore, tenantId]);

  useEffect(() => {
    loadDocumentData();
  }, [loadDocumentData]);

  // Load PDF content whenever selectedVersion changes
  useEffect(() => {
    if (!document || !selectedVersion) return;

    let isSubscribed = true;
    revokePdfUrl();
    setPdfLoading(true);
    setPdfLoaded(false);
    setPdfError(null);
    setPdfViewConfirmed(false);

    const fetchPdf = async () => {
      try {
        if (isMockMode) {
          const minimalPdf = `%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/MediaBox[0 0 612 792]/Parent 2 0 R/Resources<<>>>>endobj\nxref\n0 4\n0000000000 65535 f\n0000000009 00000 n\n0000000052 00000 n\n0000000101 00000 n\ntrailer<</Size 4/Root 1 0 R>>\nstartxref\n178\n%%EOF`;
          const blob = new Blob([minimalPdf], { type: 'application/pdf' });
          if (!isSubscribed) return;
          const url = URL.createObjectURL(blob);
          activeBlobUrlRef.current = url;
          setPdfUrl(url);
          // Note: iframe onLoad marks frame loaded
        } else {
          const blob = await client.getDocumentContentBlob(document.id, selectedVersion.id);
          if (!isSubscribed) return;
          if (blob.size === 0) {
            throw new Error('수신된 PDF 파일 크기가 0 바이트입니다.');
          }
          const url = URL.createObjectURL(blob);
          activeBlobUrlRef.current = url;
          setPdfUrl(url);
          // Note: iframe onLoad marks frame loaded
        }
      } catch (err) {
        if (!isSubscribed) return;
        setPdfError(
          err instanceof Error
            ? err.message
            : 'PDF 문서를 불러올 수 없습니다. 원본 열람 실패 시 승인이 불가합니다.'
        );
      } finally {
        if (isSubscribed) {
          setPdfLoading(false);
        }
      }
    };

    fetchPdf();

    return () => {
      isSubscribed = false;
      revokePdfUrl();
    };
  }, [client, document, isMockMode, revokePdfUrl, selectedVersion]);

  // Create Sign Request
  const handleCreateSignRequest = async () => {
    if (!document || !selectedVersion) return;
    setActionLoading(true);
    setError(null);
    const idempotencyKey = `sign-req-${Date.now()}-${Math.random().toString(36).substring(2, 8)}`;

    try {
      if (isMockMode) {
        const newReq: SignRequest = {
          id: `req-mock-${Date.now()}`,
          tenantId,
          documentId: document.id,
          documentVersionId: selectedVersion.id,
          documentSha256: selectedVersion.sha256,
          signerId: document.signerId,
          nonce: `mock-nonce-${Math.random().toString(36).substring(2, 12)}`,
          status: 'PENDING_APPROVAL',
          signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE',
          createdAt: new Date().toISOString(),
          createdBy: actorId,
          expiresAt: new Date(Date.now() + 600000).toISOString(),
          approvedAt: null,
          approvedBy: null,
          signatureId: null,
        };

        setMockStore((prev) => ({
          ...prev,
          signRequests: {
            ...prev.signRequests,
            [tenantId]: [newReq, ...(prev.signRequests[tenantId] || [])],
          },
        }));
        setSignRequest(newReq);
      } else {
        const created = await client.createSignRequest(
          selectedVersion.id,
          document.signerId,
          idempotencyKey
        );
        setSignRequest(created);
      }
    } catch (err) {
      setError(err);
    } finally {
      setActionLoading(false);
    }
  };

  // Explicit Final Sign Approval with Idempotency preservation & status reconciliation
  const handleApproveSign = async () => {
    if (!signRequest || !selectedVersion) return;

    // Check Role
    if (role !== 'DOCTOR') {
      setError(new Error('관리자는 서명 권한이 없습니다. 의사 계정으로만 승인이 가능합니다.'));
      return;
    }

    // Check Assigned Doctor
    if (actorId !== signRequest.signerId) {
      setError(new Error(`서명자 불일치: 지정된 의사(${signRequest.signerId})만 승인할 수 있습니다.`));
      return;
    }

    // Check Version and Hash match
    if (
      selectedVersion.id !== signRequest.documentVersionId ||
      selectedVersion.sha256 !== signRequest.documentSha256
    ) {
      setError(new Error('문서 버전 또는 해시 불일치: 현재 열람 중인 버전과 서명 요청 대상이 다릅니다.'));
      return;
    }

    // Check Expiry
    if (new Date(signRequest.expiresAt).getTime() <= Date.now()) {
      setError(new Error('서명 요청 유효 기한(10분)이 만료되었습니다. 새 서명 요청을 발급받으십시오.'));
      return;
    }

    // Check Nonce
    if (!signRequest.nonce) {
      setError(new Error('승인용 1회성 토큰이 없거나 이미 사용되었습니다.'));
      return;
    }

    // Check PDF loaded & explicit view confirmation
    if (!pdfLoaded || !pdfUrl || Boolean(pdfError)) {
      setError(new Error('판독문 PDF 원본이 정상 열람되지 않았습니다. 열람 실패 시 서명이 차단됩니다.'));
      return;
    }

    if (!pdfViewConfirmed) {
      setError(new Error('판독문 전문 열람 확인에 동의해야 전자서명 승인이 가능합니다.'));
      return;
    }

    setActionLoading(true);
    setError(null);

    // Requirement: Preserve one Idempotency-Key per sign request across retries
    let idempotencyKey = approvalIdempotencyKeysRef.current.get(signRequest.id);
    if (!idempotencyKey) {
      idempotencyKey = `sign-appr-${signRequest.id}-${Date.now()}`;
      approvalIdempotencyKeysRef.current.set(signRequest.id, idempotencyKey);
    }

    try {
      if (isMockMode) {
        const sigId = `sig-mock-${Date.now()}`;
        const updatedReq: SignRequest = {
          ...signRequest,
          status: 'COMPLETED',
          approvedAt: new Date().toISOString(),
          approvedBy: actorId,
          signatureId: sigId,
          nonce: null,
        };

        const newSignature = {
          id: sigId,
          tenantId,
          signRequestId: signRequest.id,
          documentId: signRequest.documentId,
          documentVersionId: signRequest.documentVersionId,
          documentSha256: signRequest.documentSha256,
          signerId: actorId,
          signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE' as const,
          algorithm: 'MOCK-HMAC-SHA256',
          signatureValue: 'bW9jay1zaWduYXR1cmUtdmFsdWUtYXBwcm92ZWQ=',
          clinicalUseAllowed: false as const,
          createdAt: new Date().toISOString(),
        };

        const newDelivery = {
          id: `del-mock-${Date.now()}`,
          tenantId,
          signatureId: sigId,
          signRequestId: signRequest.id,
          documentId: signRequest.documentId,
          connectorId: 'mock-connector',
          eventType: 'SIGNATURE_COMPLETED',
          destination: `mock://${tenantId}/report-receiver`,
          status: 'QUEUED' as const,
          attempts: 0,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        };

        setMockStore((prev) => ({
          ...prev,
          signRequests: {
            ...prev.signRequests,
            [tenantId]: (prev.signRequests[tenantId] || []).map((r) =>
              r.id === signRequest.id ? updatedReq : r
            ),
          },
          signatures: {
            ...prev.signatures,
            [tenantId]: [newSignature, ...(prev.signatures[tenantId] || [])],
          },
          deliveries: {
            ...prev.deliveries,
            [tenantId]: [newDelivery, ...(prev.deliveries[tenantId] || [])],
          },
        }));

        setSignRequest(updatedReq);
      } else {
        try {
          const approved = await client.approveSignRequest(
            signRequest.id,
            signRequest.documentSha256,
            signRequest.nonce,
            idempotencyKey
          );
          setSignRequest(approved);
        } catch (approvalErr) {
          // Requirement: On network ambiguity, query request status and reconcile success before retry
          try {
            const reconciled = await client.getSignRequest(signRequest.id);
            if (reconciled.status === 'COMPLETED') {
              setSignRequest(reconciled);
              setError(null);
              return;
            }
          } catch {
            // reconciliation lookup failed; show original error
          }
          throw approvalErr;
        }
      }
    } catch (err) {
      setError(err);
    } finally {
      setActionLoading(false);
    }
  };

  if (loading && !document) {
    return <LoadingSpinner message="판독문 세부 정보와 버전을 조회 중입니다..." />;
  }

  if (!document) {
    return (
      <div>
        <button type="button" onClick={onBack} className="btn btn-secondary" style={{ marginBottom: '16px' }}>
          <ArrowLeft size={14} /> 목록으로 돌아가기
        </button>
        <ErrorBanner error={error as Error || '문서 정보를 조회할 수 없습니다.'} onRetry={loadDocumentData} />
      </div>
    );
  }

  // Precondition calculations
  const isLatestVersion = selectedVersion ? document.latestVersion.id === selectedVersion.id : false;
  const isExpired = signRequest ? new Date(signRequest.expiresAt).getTime() <= Date.now() : false;
  const isSignerMismatch = signRequest ? actorId !== signRequest.signerId : false;
  const isVersionMismatch = signRequest && selectedVersion
    ? signRequest.documentVersionId !== selectedVersion.id || signRequest.documentSha256 !== selectedVersion.sha256
    : false;

  const canApprove =
    role === 'DOCTOR' &&
    !actionLoading &&
    !isExpired &&
    !isSignerMismatch &&
    !isVersionMismatch &&
    signRequest?.status === 'PENDING_APPROVAL' &&
    Boolean(signRequest?.nonce) &&
    pdfLoaded &&
    Boolean(pdfUrl) &&
    !pdfError &&
    pdfViewConfirmed;

  return (
    <div>
      {/* Top action bar */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
        <button type="button" onClick={onBack} className="btn btn-secondary">
          <ArrowLeft size={14} />
          목록으로 복귀
        </button>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px' }}>
          <button
            type="button"
            onClick={() => setIsAmendModalOpen(true)}
            className="btn btn-outline"
          >
            <FileEdit size={14} />
            정정본 등록 (새 버전)
          </button>
          <button
            type="button"
            onClick={loadDocumentData}
            className="btn btn-secondary"
            disabled={loading}
          >
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
            새로고침
          </button>
        </div>
      </div>

      <ErrorBanner error={error as Error} />

      {/* Document Overview Header */}
      <div className="card" style={{ marginBottom: '20px' }}>
        <div className="card-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <span className="card-title">
              <FileText size={20} style={{ color: 'var(--teal-700)' }} />
              {document.title}
            </span>
            <InfoPopover
              title="판독 소견서 정보 및 라이프사이클"
              buttonText="소견서 정보 안내"
              description="PACS 의료영상에 배정된 판독 소견서의 버전, 지정 전문의, 검사 식별자 정보입니다."
              bullets={[
                "외부 문서 ID: 연계 의료기관 EMR 시스템의 원본 문서 번호",
                "검사 번호 (Study ID): DICOM Study Instance UID 또는 접수 번호",
                "판독 지정의: 해당 영상의 판독 및 서명을 위임받은 영상의학과 전문의",
                "정정 관리: 수정 필요 시 '정정본 등록'을 통해 신규 버전으로 안전하게 생성"
              ]}
              badge="문서 무결성"
            />
          </div>
          <span className="badge badge-planned">
            최신: v{document.latestVersion.versionNumber}
          </span>
        </div>
        <div className="card-body" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '16px' }}>
          <div>
            <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)', fontWeight: 600 }}>외부 문서 ID</div>
            <div style={{ fontSize: '0.9rem', fontWeight: 700 }}>{document.externalDocumentId}</div>
          </div>
          <div>
            <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)', fontWeight: 600 }}>검사 번호 (Study ID)</div>
            <div style={{ fontSize: '0.9rem', fontWeight: 700 }}>{document.externalStudyId}</div>
          </div>
          <div>
            <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)', fontWeight: 600 }}>판독 지정의 (Signer)</div>
            <div style={{ fontSize: '0.9rem', fontWeight: 700 }}>{document.signerId}</div>
          </div>
          <div>
            <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)', fontWeight: 600 }}>등록 일시</div>
            <div style={{ fontSize: '0.85rem' }}>{new Date(document.createdAt).toLocaleString('ko-KR')}</div>
          </div>
        </div>
      </div>

      {/* Responsive Detail Grid (item 6) */}
      <div className="detail-grid">
        {/* Left Column: PDF Preview & Sign Approval Panel */}
        <div>
          {/* PDF Viewer Card */}
          <div className="card">
            <div className="card-header">
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <span className="card-title">
                  판독문 PDF 미리보기 (브라우저 PDF 뷰어)
                </span>
                <InfoPopover
                  title="PDF 뷰어 및 무결성 확인 규약"
                  buttonText="뷰어 규약"
                  description="의료진이 원본 문서를 직접 열람하였음을 보증하기 위해 화면 내 뷰어 로드 및 명시적 확인 체크박스가 필수입니다."
                  bullets={[
                    "렌더링 검증: 브라우저 내장 PDF 엔진으로 원본 문서 렌더링",
                    "해시 일치 확인: 수신된 문서 바이트의 SHA-256 해시가 서버 등록값과 일치해야 승인 활성화",
                    "의사 본인 확인 체크: 하단의 확인 체크박스를 체크해야 최종 승인 버튼이 활성화됨"
                  ]}
                  badge="의료진 직접 확인"
                />
              </div>
              {selectedVersion && (
                <span style={{ fontSize: '0.8rem', color: 'var(--navy-600)' }}>
                  열람 버전: <strong>v{selectedVersion.versionNumber}</strong> ({selectedVersion.sizeBytes} bytes)
                </span>
              )}
            </div>
            <div className="card-body">
              {pdfLoading ? (
                <LoadingSpinner message="판독문 PDF 문서를 불러오는 중입니다..." />
              ) : pdfError ? (
                <div className="alert alert-error" role="alert">
                  <AlertTriangle size={18} />
                  <div style={{ width: '100%' }}>
                    <strong>PDF 열람 실패:</strong> {pdfError}
                    <div style={{ fontSize: '0.75rem', marginTop: '4px' }}>
                      보안 규약상 판독문이 정상 표시되지 않으면 승인 버튼이 활성화되지 않습니다.
                    </div>
                    {pdfUrl && (
                      <div style={{ marginTop: '8px' }}>
                        <a
                          href={pdfUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="btn btn-secondary"
                          style={{ fontSize: '0.8rem', padding: '4px 10px', textDecoration: 'none', display: 'inline-flex', alignItems: 'center', gap: '4px' }}
                          data-testid="open-pdf-external-link"
                        >
                          <ExternalLink size={14} />
                          새 탭에서 원본 PDF 열기
                        </a>
                      </div>
                    )}
                  </div>
                </div>
              ) : pdfUrl ? (
                <div>
                  <iframe
                    title="판독문 PDF 뷰어"
                    src={pdfUrl}
                    className="pdf-viewer-frame"
                    onLoad={() => setPdfLoaded(true)}
                    onError={() => {
                      setPdfLoaded(false);
                      setPdfError('브라우저 내장 뷰어 로드 중 오류가 발생했습니다. 아래 원본 열기 링크를 확인하십시오.');
                    }}
                  />
                  <div style={{ marginTop: '8px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '8px' }}>
                    <a
                      href={pdfUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="btn btn-outline"
                      style={{ fontSize: '0.8rem', padding: '4px 10px', textDecoration: 'none', display: 'inline-flex', alignItems: 'center', gap: '4px' }}
                      data-testid="open-pdf-external-link"
                    >
                      <ExternalLink size={14} />
                      새 탭에서 원본 PDF 열기
                    </a>
                    <span style={{ fontSize: '0.75rem', color: 'var(--navy-500)' }}>
                      내장 뷰어 표시 이상 시 위 링크로 직접 확인하십시오.
                    </span>
                  </div>
                  <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)', marginTop: '6px', lineHeight: '1.4' }}>
                    * 브라우저 내장 PDF 플러그인으로 렌더링됩니다. 브라우저 및 뷰어 환경에 따라 표시 방식이 상이할 수 있으므로, 의료진이 직접 화면 표시 상태를 확인해야 합니다.
                  </div>

                  {/* Explicit User Confirmation Checkbox (Requirement 3) */}
                  <div
                    style={{
                      marginTop: '12px',
                      padding: '10px 14px',
                      backgroundColor: 'var(--navy-50)',
                      borderRadius: '6px',
                      border: '1px solid var(--navy-300)',
                    }}
                  >
                    <label style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', cursor: 'pointer' }}>
                      <input
                        type="checkbox"
                        checked={pdfViewConfirmed}
                        onChange={(e) => setPdfViewConfirmed(e.target.checked)}
                        style={{ marginTop: '3px', cursor: 'pointer' }}
                        data-testid="pdf-view-confirm-checkbox"
                      />
                      <span style={{ fontSize: '0.85rem', color: 'var(--navy-900)', fontWeight: 600 }}>
                        <CheckSquare size={16} style={{ display: 'inline', verticalAlign: '-3px', marginRight: '4px', color: 'var(--teal-700)' }} />
                        본인은 위 판독문 전문(v{selectedVersion?.versionNumber})을 열람 및 확인하였으며, 최종 판독 결과로서 전자서명을 승인합니다.
                      </span>
                    </label>
                  </div>
                </div>
              ) : (
                <div style={{ padding: '30px', textAlign: 'center', color: 'var(--navy-500)' }}>
                  열람할 PDF 파일이 없습니다.
                </div>
              )}
            </div>
          </div>

          {/* Doctor Electronic Signature Approval Card */}
          <div className="card" style={{ border: '2px solid var(--teal-700)' }}>
            <div className="card-header" style={{ backgroundColor: 'var(--teal-50)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <span className="card-title" style={{ color: 'var(--teal-900)' }}>
                  <Key size={18} style={{ color: 'var(--teal-700)' }} />
                  의사 전자서명 승인
                </span>
                <InfoPopover
                  title="전자서명 1회용 토큰 및 멱등성 보장 규약"
                  buttonText="서명 규약"
                  description="발급된 서명 요청(Sign Request)은 10분의 유효기간 동안만 유효하며 일회용 넌스(Nonce) 및 고유 멱등키가 유지됩니다."
                  bullets={[
                    "1회성 원칙: 승인 완료 시 서명 토큰은 즉시 소진되어 재사용 불가",
                    "멱등성 보장: 네트워크 오류로 재시도하더라도 동일 서명키를 보존하여 중복 결제/서명 방지",
                    "불일치 방지: 열람 중인 버전 해시와 요청 해시가 다를 경우 Fail-closed 차단"
                  ]}
                  badge="전자서명 보안"
                />
              </div>
              <span className="dev-mock-badge" style={{ backgroundColor: 'var(--teal-700)', color: 'white' }}>
                DEV MOCK SIGN
              </span>
            </div>

            <div className="card-body">
              {/* Check if viewing older version */}
              {selectedVersion && !isLatestVersion ? (
                <div className="alert alert-warning">
                  <AlertTriangle size={18} />
                  <div>
                    <strong>구버전 열람 중:</strong> 현재 조회 중인 버전(v{selectedVersion.versionNumber})은
                    정정 이전의 구버전입니다. 전자서명은 최신 정정본(v{document.latestVersion.versionNumber})에만 가능합니다.
                  </div>
                </div>
              ) : !signRequest ? (
                /* No sign request yet */
                <div style={{ textAlign: 'center', padding: '20px' }}>
                  <p style={{ fontSize: '0.9rem', color: 'var(--navy-700)', marginBottom: '16px' }}>
                    현재 최신 버전에 대해 발급된 서명 요청이 없습니다.
                  </p>
                  <button
                    type="button"
                    onClick={handleCreateSignRequest}
                    className="btn btn-primary"
                    disabled={actionLoading || Boolean(pdfError)}
                  >
                    <Key size={16} />
                    {actionLoading ? '서명 요청 발급 중...' : '서명 요청 발급'}
                  </button>
                </div>
              ) : (
                /* Sign Request exists */
                <div>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '12px', marginBottom: '16px' }}>
                    <div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)' }}>서명 요청 상태</div>
                      <span
                        className={`badge ${
                          signRequest.status === 'COMPLETED'
                            ? 'badge-completed'
                            : signRequest.status === 'PENDING_APPROVAL' && !isExpired
                            ? 'badge-pending'
                            : 'badge-failed'
                        }`}
                        style={{ marginTop: '4px' }}
                      >
                        {signRequest.status === 'PENDING_APPROVAL' && !isExpired
                          ? '승인 대기 중'
                          : isExpired
                          ? '유효 기한 만료'
                          : signRequest.status}
                      </span>
                    </div>

                    <div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)' }}>서명 유효 기한</div>
                      <div style={{ fontSize: '0.8rem', fontWeight: 600, color: isExpired ? 'var(--red-600)' : 'inherit' }}>
                        {new Date(signRequest.expiresAt).toLocaleTimeString('ko-KR')} 까지 {isExpired && '(만료됨)'}
                      </div>
                    </div>

                    <div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)' }}>1회용 서명 토큰</div>
                      <div style={{ fontSize: '0.8rem', color: 'var(--teal-700)', fontWeight: 600 }}>
                        {/* Requirement 5: Hide raw nonce from user-facing UI */}
                        {signRequest.nonce ? '결속 완료 (보안 처리됨)' : '(사용 완료)'}
                      </div>
                    </div>
                  </div>

                  {/* Hash verification box */}
                  <div style={{ marginBottom: '16px' }}>
                    <div style={{ fontSize: '0.75rem', color: 'var(--navy-600)', marginBottom: '4px', fontWeight: 600 }}>
                      결속 문서 해시 (SHA-256):
                    </div>
                    <div className="code-block" style={{ fontSize: '0.75rem' }}>
                      {signRequest.documentSha256}
                    </div>
                  </div>

                  {/* Expiry Warning */}
                  {isExpired && signRequest.status === 'PENDING_APPROVAL' && (
                    <div className="alert alert-error" role="alert" style={{ marginBottom: '16px' }}>
                      <AlertTriangle size={18} />
                      <div>
                        <strong>서명 유효 시간 만료:</strong> 본 서명 요청의 유효 시간(10분)이 만료되었습니다.
                        기존 서명 요청은 무효화되었으므로 새 서명 요청을 발급받으십시오.
                      </div>
                    </div>
                  )}

                  {/* Signer Mismatch Warning */}
                  {isSignerMismatch && (
                    <div className="alert alert-warning" role="alert" style={{ marginBottom: '16px' }}>
                      <AlertTriangle size={18} />
                      <div>
                        <strong>지정 서명의 불일치:</strong> 본 판독문은 <code>{signRequest.signerId}</code> 전용으로
                        발급되었습니다. 현재 접속자(<code>{actorId}</code>)는 승인할 수 없습니다.
                      </div>
                    </div>
                  )}

                  {/* Signature outcome if completed */}
                  {signRequest.status === 'COMPLETED' && (
                    <div className="alert alert-info" style={{ backgroundColor: 'var(--green-50)', borderColor: '#bbf7d0' }}>
                      <CheckCircle2 size={20} style={{ color: '#16a34a' }} />
                      <div>
                        <strong style={{ color: '#15803d' }}>전자서명 완료 (MOCK COMPLETED)</strong>
                        <div style={{ fontSize: '0.8rem', marginTop: '2px' }}>
                          서명 ID: <code>{signRequest.signatureId}</code>
                          <br />
                          승인자: {signRequest.approvedBy} ({new Date(signRequest.approvedAt || '').toLocaleString('ko-KR')})
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Final Sign Button Area */}
                  {signRequest.status === 'PENDING_APPROVAL' && (
                    <div>
                      {/* Admin Restriction Rule: Admin cannot see/use sign button */}
                      {role === 'ADMIN' ? (
                        <div
                          className="alert alert-warning"
                          role="alert"
                          data-testid="admin-sign-restriction-alert"
                        >
                          <Lock size={18} />
                          <div>
                            <strong>서명 권한 없음 (관리자 계정):</strong> 관리자는 의사 판독문 서명 버튼을
                            사용할 수 없습니다. 상단에서 <code>doctor-demo</code> 계정으로 전환하십시오.
                          </div>
                        </div>
                      ) : (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', alignItems: 'center' }}>
                          <button
                            type="button"
                            onClick={handleApproveSign}
                            className="btn btn-sign"
                            disabled={!canApprove}
                            data-testid="doctor-sign-approve-btn"
                            style={{ width: '100%', maxWidth: '400px' }}
                          >
                            <ShieldCheck size={18} />
                            {actionLoading
                              ? '전자서명 승인 처리 중...'
                              : '최종 판독 및 전자서명 승인'}
                          </button>
                          <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)', textAlign: 'center' }}>
                            * 문서 무결성 확인 및 열람 확인 동의 후 1회 전자서명이 실행됩니다.
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Right Column: Immutable Version & Amendment History */}
        <div>
          <div className="card">
            <div className="card-header">
              <span className="card-title" style={{ fontSize: '0.95rem' }}>
                <Clock size={16} />
                버전 및 정정 이력
              </span>
            </div>
            <div className="card-body" style={{ padding: '12px' }}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                {document.versions.map((ver) => {
                  const isSelected = selectedVersion?.id === ver.id;
                  const isLatest = document.latestVersion.id === ver.id;
                  return (
                    <div
                      key={ver.id}
                      onClick={() => setSelectedVersion(ver)}
                      style={{
                        padding: '12px',
                        borderRadius: '6px',
                        border: isSelected
                          ? '2px solid var(--teal-600)'
                          : '1px solid var(--navy-200)',
                        backgroundColor: isSelected ? 'var(--teal-50)' : 'white',
                        cursor: 'pointer',
                        transition: 'all 0.15s',
                      }}
                      role="button"
                      tabIndex={0}
                      aria-label={`버전 ${ver.versionNumber} 선택`}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '6px' }}>
                        <span style={{ fontWeight: 700, fontSize: '0.85rem' }}>
                          버전 {ver.versionNumber}
                        </span>
                        {isLatest && (
                          <span className="badge badge-valid" style={{ fontSize: '0.65rem' }}>
                            최신본
                          </span>
                        )}
                      </div>

                      <div style={{ fontSize: '0.75rem', color: 'var(--navy-600)', marginBottom: '4px' }}>
                        등록자: {ver.createdBy} | {new Date(ver.createdAt).toLocaleDateString('ko-KR')}
                      </div>

                      <div style={{ fontSize: '0.7rem', color: 'var(--navy-500)', wordBreak: 'break-all' }}>
                        SHA: <code>{ver.sha256.substring(0, 16)}...</code>
                      </div>

                      {ver.amendmentReason && (
                        <div
                          style={{
                            marginTop: '6px',
                            padding: '6px',
                            backgroundColor: 'rgba(0,0,0,0.03)',
                            borderRadius: '4px',
                            fontSize: '0.75rem',
                            color: 'var(--amber-700)',
                          }}
                        >
                          <strong>정정 사유:</strong> {ver.amendmentReason}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>

              <div style={{ marginTop: '16px', fontSize: '0.75rem', color: 'var(--navy-500)', lineHeight: '1.4' }}>
                * <strong>버전 이력 관리:</strong> 등록된 버전은 덮어쓰이지 않고 이력으로 누적 관리되나, 현재 개발 프로파일 환경의 백엔드 저장소는 인메모리 방식이므로 <strong>서버 재기동 시 데이터가 초기화</strong>됩니다.
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Amendment Modal */}
      <DocumentAmendModal
        document={document}
        isOpen={isAmendModalOpen}
        onClose={() => setIsAmendModalOpen(false)}
        onSuccess={(updatedDoc) => {
          setDocument(updatedDoc);
          setSelectedVersion(updatedDoc.latestVersion);
          setSignRequest(null);
        }}
      />
    </div>
  );
};
