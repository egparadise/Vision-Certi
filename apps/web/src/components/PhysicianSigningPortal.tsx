import React, { useState, useEffect, useCallback } from 'react';
import { useApp } from '../context/AppContext';
import { ApiClientError } from '../api/client';
import { Doctor, SignRequest, PacsReportSnapshot, PacsHandoffResponse } from '../types/api';

interface PhysicianSigningPortalProps {
  token: string;
  doctor: Doctor;
  onSignOut?: () => void;
}

function getQueryOrHashParam(name: string): string | null {
  if (typeof window === 'undefined') return null;
  const searchParams = new URLSearchParams(window.location.search);
  if (searchParams.has(name)) {
    return searchParams.get(name);
  }
  const hash = window.location.hash || '';
  const qIdx = hash.indexOf('?');
  if (qIdx !== -1) {
    const hashParams = new URLSearchParams(hash.substring(qIdx + 1));
    if (hashParams.has(name)) {
      return hashParams.get(name);
    }
  }
  return null;
}

function getTargetSignRequestId(): string | null {
  const fromParam = getQueryOrHashParam('signRequestId');
  if (fromParam) return fromParam;
  const hash = window.location.hash || '';
  const match = hash.match(/#\/doctor\/sign-requests\/([^?]+)/);
  if (match && match[1]) {
    return decodeURIComponent(match[1]);
  }
  return null;
}

function getValidatedTargetOrigin(): string {
  const allowedOriginsStr = (import.meta.env?.VITE_PACS_ALLOWED_ORIGINS as string | undefined) ||
    'http://localhost:5173,http://localhost:3000,http://127.0.0.1:5173,http://127.0.0.1:3000,http://localhost:8080,http://127.0.0.1:8080';
  const allowed = allowedOriginsStr.split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
  const paramOrigin = getQueryOrHashParam('origin');
  if (paramOrigin) {
    try {
      const parsed = new URL(paramOrigin).origin.toLowerCase();
      if (allowed.includes(parsed) || parsed === window.location.origin.toLowerCase()) {
        return parsed;
      }
    } catch (e) {}
  }
  return window.location.origin;
}

export const PhysicianSigningPortal: React.FC<PhysicianSigningPortalProps> = ({
  token,
  doctor,
  onSignOut,
}) => {
  const { client } = useApp();

  const [signRequests, setSignRequests] = useState<SignRequest[]>([]);
  const [selectedSignRequest, setSelectedSignRequest] = useState<SignRequest | null>(null);
  const [loadingRequests, setLoadingRequests] = useState(false);

  // Document state (PDF or JSON PACS Report)
  const [pdfBlobUrl, setPdfBlobUrl] = useState<string | null>(null);
  const [docSha256, setDocSha256] = useState<string | null>(null);
  const [docContentType, setDocContentType] = useState<string | null>(null);
  const [parsedPacsReport, setParsedPacsReport] = useState<PacsReportSnapshot | null>(null);
  const [hashVerified, setHashVerified] = useState<boolean>(false);
  const [hashMismatchError, setHashMismatchError] = useState<string | null>(null);
  const [docLoading, setDocLoading] = useState(false);
  const [docError, setDocError] = useState<string | null>(null);

  // Approval state
  const [signConfirmed, setSignConfirmed] = useState(false);
  const [approving, setApproving] = useState(false);
  const [signingSuccessMsg, setSigningSuccessMsg] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  // PACS Handoff state
  const [pacsAudience, setPacsAudience] = useState<string>('pacs-sv70');
  const [pacsHandoffResult, setPacsHandoffResult] = useState<PacsHandoffResponse | null>(null);
  const [pacsHandoffLoading, setPacsHandoffLoading] = useState(false);
  const [pacsHandoffMsg, setPacsHandoffMsg] = useState<string | null>(null);

  // Load sign requests
  const loadRequests = useCallback(async () => {
    setLoadingRequests(true);
    setActionError(null);
    try {
      const res = await client.getDoctorSignRequests(token);
      setSignRequests(res.items);
      const targetId = getTargetSignRequestId();
      if (targetId) {
        const found = res.items.find(r => r.id === targetId);
        if (found) {
          setSelectedSignRequest(found);
          return;
        }
      }
      if (res.items.length > 0 && !selectedSignRequest) {
        setSelectedSignRequest(res.items[0]);
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '판독문 목록을 불러오지 못했습니다.';
      setActionError(msg);
    } finally {
      setLoadingRequests(false);
    }
  }, [client, token, selectedSignRequest]);

  useEffect(() => {
    loadRequests();
  }, [loadRequests]);

  // Handle cross-window postMessage correlation from SV70 PACS popup
  useEffect(() => {
    const handleMessage = (e: MessageEvent) => {
      if (window.opener && e.source !== window.opener) {
        return;
      }
      const allowedOriginsStr = (import.meta.env?.VITE_PACS_ALLOWED_ORIGINS as string | undefined) ||
        'http://localhost:5173,http://localhost:3000,http://127.0.0.1:5173,http://127.0.0.1:3000,http://localhost:8080,http://127.0.0.1:8080';
      const allowed = allowedOriginsStr.split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
      if (!allowed.includes(e.origin.toLowerCase()) && e.origin.toLowerCase() !== window.location.origin.toLowerCase()) {
        return;
      }
      if (e.data && e.data.type === 'VC_REQUEST_HANDOFF') {
        if (e.data.audience) {
          setPacsAudience(e.data.audience);
        }
      }
    };
    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
  }, []);

  const handleCreatePacsHandoff = async () => {
    setPacsHandoffLoading(true);
    setPacsHandoffMsg(null);
    try {
      const res = await client.createPacsHandoff(token, pacsAudience);
      setPacsHandoffResult(res);
      setPacsHandoffMsg(`PACS 인계 코드(handoffCode)가 발급되었습니다. (유효시간: 60초)`);

      // If opened as a popup by SV70 PACS, deliver code back safely using origin check & nonce
      if (window.opener && !window.opener.closed) {
        const targetOrigin = getValidatedTargetOrigin();
        const nonce = getQueryOrHashParam('nonce') || '';
        const payload = {
          handoffCode: res.handoffCode,
          audience: res.audience,
          tenantId: res.tenantId,
          expiresAt: res.expiresAt,
          nonce,
        };
        window.opener.postMessage(
          {
            type: 'VC_HANDOFF_CODE',
            ...payload,
          },
          targetOrigin
        );
        window.opener.postMessage(
          {
            type: 'VC_PACS_HANDOFF_SUCCESS',
            ...payload,
          },
          targetOrigin
        );
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'PACS 인계 코드 발급 실패';
      setPacsHandoffMsg(`오류: ${msg}`);
    } finally {
      setPacsHandoffLoading(false);
    }
  };

  // Load document (PDF or JSON PACS Report) and verify hash with real byte SHA-256 calculation
  useEffect(() => {
    if (!selectedSignRequest) {
      setPdfBlobUrl(null);
      setParsedPacsReport(null);
      setDocContentType(null);
      return;
    }

    let active = true;
    let currentBlobUrl: string | null = null;
    setHashVerified(false);
    setDocLoading(true);
    setDocError(null);
    setHashMismatchError(null);
    setSignConfirmed(false);
    setSigningSuccessMsg(null);
    setParsedPacsReport(null);

    client
      .getDoctorSignRequestDocument(token, selectedSignRequest.id)
      .then(async (res) => {
        if (!active) return;
        currentBlobUrl = URL.createObjectURL(res.blob);
        setPdfBlobUrl(currentBlobUrl);
        setDocSha256(res.sha256);
        setDocContentType(res.contentType);

        // Fail-closed if header or request hash is missing
        if (!res.sha256 || !selectedSignRequest.documentSha256) {
          setHashVerified(false);
          setHashMismatchError('보안 오류: 문서 SHA-256 헤더(X-Document-Sha256) 또는 요청 해시가 누락되어 검증에 실패했습니다. (Fail-closed)');
          setDocLoading(false);
          return;
        }

        try {
          // Compute actual SHA-256 of exact blob bytes using crypto.subtle
          const arrayBuffer = await res.blob.arrayBuffer();
          const hashBuffer = await crypto.subtle.digest('SHA-256', arrayBuffer);
          const hashArray = Array.from(new Uint8Array(hashBuffer));
          const computedHex = hashArray.map((b) => b.toString(16).padStart(2, '0')).join('').toLowerCase();
          const reqSha256 = selectedSignRequest.documentSha256.toLowerCase();
          const headerSha256 = res.sha256.toLowerCase();

          const matchesDigest = computedHex === reqSha256;
          const matchesHeader = headerSha256 === reqSha256;

          // Check if it's a structured PACS report snapshot
          const isJsonType = Boolean(
            res.contentType?.includes('application/vnd.saintview.pacs-report+json') ||
            res.contentType?.includes('application/json')
          );
          if (isJsonType) {
            try {
              const text = new TextDecoder('utf-8').decode(arrayBuffer);
              const json = JSON.parse(text) as PacsReportSnapshot;
              if (json && json.schema === 'saintview-pacs-report-snapshot/v1') {
                setParsedPacsReport(json);
              }
            } catch {
              // Not strict JSON or fallback
            }
          }

          if (matchesDigest && matchesHeader) {
            setHashVerified(true);
            setHashMismatchError(null);
          } else {
            setHashVerified(false);
            setHashMismatchError(
              `보안 경고: 계산된 문서 바이트 해시(${computedHex.slice(0, 16)}...) 또는 헤더 해시(${headerSha256.slice(0, 16)}...)가 서명 요청 해시(${reqSha256.slice(0, 16)}...)와 일치하지 않습니다. 서명이 차단됩니다.`
            );
          }
        } catch {
          setHashVerified(false);
          setHashMismatchError('문서 바이트 해시 계산 중 암호화 오류가 발생하여 서명이 차단되었습니다. (Fail-closed)');
        } finally {
          if (active) {
            setDocLoading(false);
          }
        }
      })
      .catch((err: unknown) => {
        if (!active) return;
        const msg = err instanceof Error ? err.message : '판독문 원본 문서를 불러올 수 없습니다.';
        setDocError(msg);
        setHashVerified(false);
        setDocLoading(false);
      });

    return () => {
      active = false;
      if (currentBlobUrl) {
        URL.revokeObjectURL(currentBlobUrl);
      }
    };
  }, [client, selectedSignRequest, token]);

  const handleApprove = async () => {
    if (!selectedSignRequest || !signConfirmed || !hashVerified || docLoading || docError || !pdfBlobUrl) {
      return;
    }
    setApproving(true);
    setActionError(null);
    try {
      const isPacsReport = Boolean(
        docContentType?.includes('pacs-report') || parsedPacsReport !== null
      );
      await client.approveDoctorSignRequest(token, selectedSignRequest.id, {
        documentSha256: selectedSignRequest.documentSha256,
        nonce: selectedSignRequest.nonce || '',
        reviewAttestation: isPacsReport ? 'REVIEWED_EXACT_REPORT_CONTENT' : undefined,
      });
      setSigningSuccessMsg(`요청 #${selectedSignRequest.id.slice(0, 8)} 전자서명이 안전하게 완료되었습니다.`);
      setSignConfirmed(false);

      if (window.opener && !window.opener.closed) {
        const targetOrigin = getValidatedTargetOrigin();
        window.opener.postMessage(
          {
            type: 'VC_SIGN_COMPLETED',
            signRequestId: selectedSignRequest.id,
          },
          targetOrigin
        );
      }

      await loadRequests();
    } catch (err: unknown) {
      if (err instanceof ApiClientError && (err.code === 'CERTIFICATE_ENROLLMENT_CHANGED' || err.message.includes('CERTIFICATE_ENROLLMENT_CHANGED'))) {
        setActionError('등록된 인증서 상태가 변경되었습니다. 새 인증서로 다시 로그인하여 서명 세션을 갱신하십시오. (CERTIFICATE_ENROLLMENT_CHANGED)');
      } else {
        const msg = err instanceof Error ? err.message : '전자서명 승인 처리에 실패했습니다.';
        setActionError(msg);
      }
    } finally {
      setApproving(false);
    }
  };

  return (
    <div
      data-testid="physician-signing-portal"
      style={{
        border: '1px solid var(--border-color, #cbd5e1)',
        borderRadius: '8px',
        background: '#ffffff',
        padding: '1.25rem',
        marginTop: '1.5rem',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem', borderBottom: '1px solid #f1f5f9', paddingBottom: '0.75rem' }}>
        <div>
          <h3 style={{ margin: 0, fontSize: '1.1rem', fontWeight: 600, color: 'var(--navy-900, #0f172a)' }}>
            의사 전자서명 포털 (Physician Signing Portal)
          </h3>
          <span style={{ fontSize: '0.8rem', color: 'var(--text-muted, #64748b)' }}>
            인증 의사: <strong>{doctor.name}</strong> ({doctor.licenseNumber}) | 세션: <code>{doctor.identityMode}</code>
          </span>
        </div>
        {onSignOut && (
          <button
            type="button"
            data-testid="portal-signout-btn"
            onClick={onSignOut}
            style={{
              padding: '4px 10px',
              fontSize: '0.8rem',
              color: '#dc2626',
              border: '1px solid #fca5a5',
              background: '#fff',
              borderRadius: '4px',
              cursor: 'pointer',
            }}
          >
            서명 세션 종료 (Sign Out)
          </button>
        )}
      </div>

      {actionError && (
        <div
          data-testid="portal-error-banner"
          style={{
            padding: '0.75rem 1rem',
            background: 'rgba(239, 68, 68, 0.1)',
            border: '1px solid #ef4444',
            borderRadius: '6px',
            color: '#b91c1c',
            marginBottom: '1rem',
            fontSize: '0.85rem',
          }}
        >
          <div>{actionError}</div>
          {actionError.includes('CERTIFICATE_ENROLLMENT_CHANGED') && onSignOut && (
            <button
              type="button"
              data-testid="reauth-cert-btn"
              onClick={onSignOut}
              style={{
                marginTop: '0.5rem',
                padding: '4px 10px',
                fontSize: '0.75rem',
                backgroundColor: '#b91c1c',
                color: '#fff',
                border: 'none',
                borderRadius: '4px',
                cursor: 'pointer',
                fontWeight: 600,
              }}
            >
              인증서로 다시 로그인
            </button>
          )}
        </div>
      )}

      {signingSuccessMsg && (
        <div
          data-testid="doctor-sign-success"
          style={{
            padding: '0.75rem 1rem',
            background: 'rgba(34, 197, 94, 0.1)',
            border: '1px solid #22c55e',
            borderRadius: '6px',
            color: '#15803d',
            marginBottom: '1rem',
            fontSize: '0.85rem',
          }}
        >
          {signingSuccessMsg}
        </div>
      )}

      <div data-testid="doctor-sign-requests-section">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.75rem' }}>
          <h4 style={{ margin: 0, fontSize: '0.95rem', fontWeight: 600 }}>
            판독문 서명 승인 대기 목록 (Sign Requests)
          </h4>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <span style={{ fontSize: '0.8rem', color: 'var(--text-muted, #64748b)' }}>
              대기: {signRequests.filter(r => r.status === 'PENDING_APPROVAL').length}건
            </span>
            <button
              type="button"
              data-testid="refresh-sign-requests-btn"
              onClick={loadRequests}
              disabled={loadingRequests}
              style={{
                padding: '3px 8px',
                fontSize: '0.75rem',
                border: '1px solid #cbd5e1',
                borderRadius: '4px',
                background: '#f8fafc',
                cursor: loadingRequests ? 'not-allowed' : 'pointer',
              }}
            >
              새로고침
            </button>
          </div>
        </div>

        {loadingRequests ? (
          <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>서명 요청 목록을 조회 중...</p>
        ) : signRequests.length === 0 ? (
          <div style={{ textAlign: 'center', padding: '1.5rem', background: '#f8fafc', borderRadius: '6px' }}>
            <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)', margin: '0 0 0.5rem 0' }}>
              현재 배정된 승인 대기 판독문이 없습니다.
            </p>
            <button
              type="button"
              data-testid="refresh-sign-requests-btn-empty"
              onClick={loadRequests}
              disabled={loadingRequests}
              style={{
                padding: '4px 10px',
                fontSize: '0.75rem',
                border: '1px solid #cbd5e1',
                borderRadius: '4px',
                background: '#fff',
                cursor: 'pointer',
              }}
            >
              목록 새로고침
            </button>
          </div>
        ) : (
          <div>
            <div style={{ display: 'flex', gap: '8px', marginBottom: '1rem', overflowX: 'auto', paddingBottom: '4px' }}>
              {signRequests.map(req => (
                <button
                  key={req.id}
                  type="button"
                  data-testid="doctor-sign-request-item"
                  onClick={() => {
                    setSelectedSignRequest(req);
                    setSignConfirmed(false);
                    setSigningSuccessMsg(null);
                  }}
                  style={{
                    padding: '6px 12px',
                    borderRadius: '6px',
                    fontSize: '0.8rem',
                    fontWeight: 500,
                    border: selectedSignRequest?.id === req.id ? '2px solid var(--primary-color, #0284c7)' : '1px solid var(--border-color, #cbd5e1)',
                    background: selectedSignRequest?.id === req.id ? 'rgba(2, 132, 199, 0.08)' : '#f8fafc',
                    cursor: 'pointer',
                    whiteSpace: 'nowrap',
                  }}
                >
                  요청 #{req.id.slice(0, 8)} ({req.status})
                </button>
              ))}
            </div>

            {selectedSignRequest && (
              <div
                data-testid="document-preview-card"
                style={{
                  padding: '1rem',
                  border: '1px solid var(--border-color, #cbd5e1)',
                  borderRadius: '6px',
                  background: '#f8fafc',
                  marginBottom: '1rem',
                }}
              >
                <h5 style={{ margin: '0 0 0.5rem 0', fontSize: '0.9rem', fontWeight: 600 }}>
                  판독문 상세 사전검토 (Document Preview & Nonce)
                </h5>

                <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: '0.4rem', fontSize: '0.8rem', marginBottom: '0.75rem' }}>
                  <div>
                    <span style={{ color: 'var(--text-muted, #64748b)' }}>요청 ID: </span>
                    <code style={{ fontFamily: 'monospace' }}>{selectedSignRequest.id}</code>
                  </div>
                  <div>
                    <span style={{ color: 'var(--text-muted, #64748b)' }}>문서 SHA-256 해시: </span>
                    <code data-testid="preview-document-sha256" style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>
                      {selectedSignRequest.documentSha256}
                    </code>
                  </div>
                  <div>
                    <span style={{ color: 'var(--text-muted, #64748b)' }}>서명 챌린지 Nonce: </span>
                    <code data-testid="preview-request-nonce" style={{ fontFamily: 'monospace' }}>
                      {selectedSignRequest.nonce || '(미발급/완료됨)'}
                    </code>
                  </div>
                  <div>
                    <span style={{ color: 'var(--text-muted, #64748b)' }}>현재 처리 상태: </span>
                    <strong style={{ color: selectedSignRequest.status === 'COMPLETED' ? '#16a34a' : '#d97706' }}>
                      {selectedSignRequest.status}
                    </strong>
                  </div>
                </div>

                {/* Document PDF Preview Area */}
                <div style={{ marginTop: '0.75rem', borderTop: '1px solid var(--border-color, #cbd5e1)', paddingTop: '0.75rem' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                    <span style={{ fontWeight: 600, fontSize: '0.82rem', color: 'var(--navy-900)' }}>
                      {parsedPacsReport ? '📋 PACS 판독문 JSON 원본 검토 (Structured PACS Report Preview):' : '📄 판독문 원본 PDF 검토 (Bearer Authorized Document Preview):'}
                    </span>
                    {docSha256 && (
                      <span
                        data-testid="header-sha256-badge"
                        style={{
                          fontSize: '0.7rem',
                          background: hashVerified ? '#dcfce7' : '#fee2e2',
                          color: hashVerified ? '#15803d' : '#b91c1c',
                          padding: '2px 6px',
                          borderRadius: '4px',
                          fontWeight: 600,
                        }}
                      >
                        {hashVerified ? '✓ 해시 검증 일치' : '✗ 해시 불일치'}
                      </span>
                    )}
                  </div>

                  {hashMismatchError && (
                    <div
                      data-testid="hash-mismatch-alert"
                      style={{
                        padding: '0.5rem',
                        background: '#fee2e2',
                        border: '1px solid #ef4444',
                        borderRadius: '4px',
                        color: '#991b1b',
                        fontSize: '0.78rem',
                        marginBottom: '0.5rem',
                      }}
                    >
                      {hashMismatchError}
                    </div>
                  )}

                  {docLoading ? (
                    <div style={{ padding: '1rem', textAlign: 'center', fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                      판독문 문서를 안전하게 불러오는 중입니다...
                    </div>
                  ) : docError ? (
                    <div style={{ padding: '0.75rem', background: '#fee2e2', color: '#b91c1c', borderRadius: '4px', fontSize: '0.8rem' }}>
                      {docError}
                    </div>
                  ) : parsedPacsReport ? (
                    <div
                      data-testid="pacs-report-structured-preview"
                      style={{
                        border: '1px solid #cbd5e1',
                        borderRadius: '6px',
                        background: '#f8fafc',
                        padding: '0.75rem',
                        fontSize: '0.82rem',
                        color: '#1e293b',
                      }}
                    >
                      {/* Patient & Study Info Header */}
                      <div style={{ background: '#fff', border: '1px solid #e2e8f0', borderRadius: '4px', padding: '0.6rem', marginBottom: '0.6rem' }}>
                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '0.4rem' }}>
                          <div><strong>환자명:</strong> <span data-testid="pacs-patient-name">{parsedPacsReport.study?.patientName || '(미상)'}</span> ({parsedPacsReport.study?.patientId})</div>
                          <div><strong>검사일시:</strong> {parsedPacsReport.study?.studyDate || '-'}</div>
                          <div><strong>검사/부위:</strong> {parsedPacsReport.study?.modality} / {parsedPacsReport.study?.bodyPart || '-'} ({parsedPacsReport.study?.description || '-'})</div>
                          <div><strong>의료기관:</strong> {parsedPacsReport.study?.hospitalName || '-'} [Acc: {parsedPacsReport.study?.accessionNo || '-'}]</div>
                        </div>
                      </div>

                      {/* Findings: Reading & Conclusion */}
                      <div style={{ marginBottom: '0.6rem' }}>
                        <div style={{ fontWeight: 600, color: '#0f172a', marginBottom: '0.2rem' }}>[판독 소견 (Findings / Reading)]</div>
                        <div
                          data-testid="pacs-report-reading"
                          style={{
                            background: '#fff',
                            border: '1px solid #e2e8f0',
                            borderRadius: '4px',
                            padding: '0.6rem',
                            whiteSpace: 'pre-wrap',
                            fontFamily: 'sans-serif',
                            minHeight: '60px',
                          }}
                        >
                          {parsedPacsReport.report?.reading || '(판독 소견 없음)'}
                        </div>
                      </div>

                      <div style={{ marginBottom: '0.6rem' }}>
                        <div style={{ fontWeight: 600, color: '#0f172a', marginBottom: '0.2rem' }}>[결론 (Impression / Conclusion)]</div>
                        <div
                          data-testid="pacs-report-conclusion"
                          style={{
                            background: '#fff',
                            border: '1px solid #e2e8f0',
                            borderRadius: '4px',
                            padding: '0.6rem',
                            whiteSpace: 'pre-wrap',
                            fontFamily: 'sans-serif',
                            minHeight: '40px',
                          }}
                        >
                          {parsedPacsReport.report?.conclusion || '(결론 없음)'}
                        </div>
                      </div>

                      {(parsedPacsReport.report?.studyComment || parsedPacsReport.report?.referComment) && (
                        <div style={{ background: '#fff', border: '1px solid #e2e8f0', borderRadius: '4px', padding: '0.5rem', marginBottom: '0.6rem', fontSize: '0.78rem' }}>
                          {parsedPacsReport.report?.studyComment && <div><strong>검사 코멘트:</strong> {parsedPacsReport.report.studyComment}</div>}
                          {parsedPacsReport.report?.referComment && <div><strong>참고 코멘트:</strong> {parsedPacsReport.report.referComment}</div>}
                        </div>
                      )}

                      {/* Metadata row */}
                      <div style={{ display: 'flex', justifyContent: 'space-between', flexWrap: 'wrap', gap: '0.5rem', fontSize: '0.75rem', color: '#64748b' }}>
                        <span>판독의: <strong>{parsedPacsReport.pacs?.userName || parsedPacsReport.pacs?.userId}</strong> (면허: {parsedPacsReport.pacs?.doctorLicenseNumber || '-'})</span>
                        <span>개정(Rev): <strong>{parsedPacsReport.report?.revision}</strong> ({parsedPacsReport.report?.approvalStatus})</span>
                        <span>CVR 긴급통보: <strong>{parsedPacsReport.report?.cvr === 'Y' ? '요청됨' : '미요청'}</strong></span>
                      </div>

                      {/* Preserved metadata container for test compatibility */}
                      <pre
                        data-testid="preview-document-content"
                        style={{
                          marginTop: '0.5rem',
                          padding: '0.5rem',
                          background: '#fff',
                          border: '1px solid #e2e8f0',
                          borderRadius: '4px',
                          fontSize: '0.75rem',
                          maxHeight: '80px',
                          overflowY: 'auto',
                          whiteSpace: 'pre-wrap',
                          fontFamily: 'monospace',
                        }}
                      >
                        {`[PACS 리포트 식별자: ${selectedSignRequest.documentId} | Rev: ${parsedPacsReport.report?.revision} | 해시: ${selectedSignRequest.documentSha256}] ${signConfirmed ? '의사 직접 바이트 및 해시 검토 완료.' : '판독문 바이트 검토 대기 중.'}`}
                      </pre>
                    </div>
                  ) : pdfBlobUrl ? (
                    <div>
                      <iframe
                        src={pdfBlobUrl}
                        data-testid="preview-document-pdf"
                        title="판독문 PDF 문서"
                        style={{
                          width: '100%',
                          height: '240px',
                          border: '1px solid #cbd5e1',
                          borderRadius: '4px',
                          backgroundColor: '#f8fafc',
                        }}
                      />
                      {/* Document metadata element for test inspection & screen readers */}
                      <pre
                        data-testid="preview-document-content"
                        style={{
                          marginTop: '0.5rem',
                          padding: '0.5rem',
                          background: '#fff',
                          border: '1px solid #e2e8f0',
                          borderRadius: '4px',
                          fontSize: '0.75rem',
                          maxHeight: '80px',
                          overflowY: 'auto',
                          whiteSpace: 'pre-wrap',
                          fontFamily: 'monospace',
                        }}
                      >
                        {`[문서 식별자: ${selectedSignRequest.documentId} | 해시: ${selectedSignRequest.documentSha256}] ${signConfirmed ? '의사 직접 바이트 및 해시 검토 완료.' : '판독문 바이트 검토 대기 중.'}`}
                      </pre>
                    </div>
                  ) : null}
                </div>

                {/* Informed Approval Confirmation */}
                {selectedSignRequest.status === 'PENDING_APPROVAL' && (
                  <div style={{ marginTop: '1rem', borderTop: '1px solid var(--border-color, #cbd5e1)', paddingTop: '0.75rem' }}>
                    <label
                      style={{
                        display: 'flex',
                        alignItems: 'flex-start',
                        gap: '0.5rem',
                        fontSize: '0.85rem',
                        cursor: (!hashVerified || docLoading) ? 'not-allowed' : 'pointer',
                        marginBottom: '0.75rem',
                      }}
                    >
                      <input
                        type="checkbox"
                        data-testid="doctor-sign-confirm-checkbox"
                        disabled={!hashVerified || docLoading}
                        checked={signConfirmed}
                        onChange={e => setSignConfirmed(e.target.checked)}
                        style={{ marginTop: '0.15rem' }}
                      />
                      <span>
                        <strong>의사 서명 직접 승인:</strong> {parsedPacsReport ? '본인은 상기 PACS 판독문(환자 정보, 판독 소견, 결론, 의사 면허 정보 등) 및 암호학적 SHA-256 해시를 직접 검토하였으며, 위탁된 본인의 전자서명 키로 최종 전자서명을 승인합니다.' : '본인은 상기 판독문 PDF 및 SHA-256 해시를 직접 검토하였으며, 위탁된 본인의 전자서명 키로 최종 전자서명을 승인합니다.'}
                      </span>
                    </label>

                    <button
                      type="button"
                      data-testid="doctor-sign-approve-btn"
                      disabled={approving || !signConfirmed || !hashVerified || docLoading}
                      onClick={handleApprove}
                      style={{
                        width: '100%',
                        padding: '0.625rem',
                        fontSize: '0.9rem',
                        fontWeight: 600,
                        background: (!signConfirmed || !hashVerified) ? 'var(--text-muted, #94a3b8)' : '#16a34a',
                        color: '#fff',
                        border: 'none',
                        borderRadius: '6px',
                        cursor: (!signConfirmed || !hashVerified || approving) ? 'not-allowed' : 'pointer',
                      }}
                    >
                      {approving ? '서명 승인 처리 중...' : '최종 전자서명 승인 (Bearer Authorized Approval)'}
                    </button>
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {/* PACS Session Handoff Bridge Section */}
        <div
          data-testid="pacs-handoff-section"
          style={{
            marginTop: '1.5rem',
            padding: '1rem',
            background: '#f8fafc',
            border: '1px solid #e2e8f0',
            borderRadius: '6px',
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '0.5rem' }}>
            <div>
              <strong style={{ fontSize: '0.9rem', color: '#0f172a' }}>SV70 PACS 인증 세션 인계 (PACS Handoff Bridge)</strong>
              <div style={{ fontSize: '0.78rem', color: '#64748b' }}>
                현재 의사 전자서명 세션을 SV70 PACS 클라이언트에 1회용 handoffCode로 안전하게 인계합니다. (Audience 검증 및 60초 TTL)
              </div>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <input
                type="text"
                value={pacsAudience}
                onChange={e => setPacsAudience(e.target.value)}
                placeholder="PACS Client ID"
                style={{
                  fontSize: '0.8rem',
                  padding: '4px 8px',
                  border: '1px solid #cbd5e1',
                  borderRadius: '4px',
                  width: '120px',
                }}
                data-testid="pacs-audience-input"
              />
              <button
                type="button"
                data-testid="pacs-handoff-btn"
                disabled={pacsHandoffLoading}
                onClick={handleCreatePacsHandoff}
                style={{
                  padding: '6px 12px',
                  fontSize: '0.8rem',
                  fontWeight: 600,
                  background: '#0284c7',
                  color: '#fff',
                  border: 'none',
                  borderRadius: '4px',
                  cursor: pacsHandoffLoading ? 'not-allowed' : 'pointer',
                }}
              >
                {pacsHandoffLoading ? '생성 중...' : 'PACS 인계 코드 발급'}
              </button>
            </div>
          </div>

          {pacsHandoffMsg && (
            <div style={{ marginTop: '0.5rem', fontSize: '0.8rem', color: pacsHandoffResult ? '#15803d' : '#b91c1c' }}>
              {pacsHandoffMsg}
            </div>
          )}

          {pacsHandoffResult && (
            <div style={{ marginTop: '0.5rem', padding: '0.5rem', background: '#fff', border: '1px dashed #cbd5e1', borderRadius: '4px', fontSize: '0.78rem' }}>
              <div><strong>Handoff Code:</strong> <code data-testid="pacs-handoff-code" style={{ wordBreak: 'break-all', color: '#0369a1' }}>{pacsHandoffResult.handoffCode}</code></div>
              <div style={{ color: '#64748b', marginTop: '2px' }}>Audience: {pacsHandoffResult.audience} | 만료: {pacsHandoffResult.expiresAt}</div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
