import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  ShieldAlert,
  UserPlus,
  Copy,
  Check,
  AlertTriangle,
  RefreshCw,
  Building2,
  Lock,
  Ban,
  ArrowRight,
  ArrowLeft,
  FileCheck2,
  Users,
  Award,
} from 'lucide-react';
import { useApp } from '../context/AppContext';
import { ApiClientError } from '../api/client';
import {
  Doctor,
  DoctorStatus,
  PreregisterDoctorRequest,
  PreregisterDoctorResponse,
  ExternalCertCandidate,
  ExternalCaStatus,
} from '../types/api';
import { ErrorBanner } from './ErrorBanner';
import { LoadingSpinner } from './LoadingSpinner';
import { InfoPopover } from './InfoPopover';
import { isSyntheticDoctor } from '../utils/realData';

// Administrator invitation issuance is temporarily disabled; review and suspension remain available.
const adminInvitationsEnabled = false;

export const DoctorAdminView: React.FC = () => {
  const { client, tenantId, isMockMode, mockStore, setMockStore } = useApp();

  const [doctors, setDoctors] = useState<Doctor[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<Error | null>(null);

  // Form State
  const [formData, setFormData] = useState<PreregisterDoctorRequest>({
    name: '',
    licenseNumber: '',
    centerName: '',
    googleEmail: '',
    identityEvidenceRef: '',
    licenseEvidenceRef: '',
    expectedCertificateFingerprint: '',
  });
  const [evidenceReviewed, setEvidenceReviewed] = useState<boolean>(false);
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [formError, setFormError] = useState<string | null>(null);

  // One-time Invitation Modal State
  const [latestInvitation, setLatestInvitation] = useState<PreregisterDoctorResponse | null>(null);
  const [copied, setCopied] = useState<boolean>(false);

  // Status Action Modal State (SUSPEND / REVOKE)
  const [selectedDoctorForAction, setSelectedDoctorForAction] = useState<Doctor | null>(null);
  const [targetActionStatus, setTargetActionStatus] = useState<'SUSPENDED' | 'REVOKED' | null>(null);
  const [statusReason, setStatusReason] = useState<string>('');
  const [actionProcessing, setActionProcessing] = useState<boolean>(false);
  const [actionError, setActionError] = useState<string | null>(null);

  // Candidate Certificate Review Modal State
  const [reviewCandidateDoctor, setReviewCandidateDoctor] = useState<Doctor | null>(null);
  const [selectedCandidate, setSelectedCandidate] = useState<ExternalCertCandidate | null>(null);
  const [reviewEvidenceRef, setReviewEvidenceRef] = useState<string>('');
  const [reviewNote, setReviewNote] = useState<string>('');
  const [reviewConfirmed, setReviewConfirmed] = useState<boolean>(false);
  const [reviewProcessing, setReviewProcessing] = useState<boolean>(false);
  const [reviewError, setReviewError] = useState<string | null>(null);

  const fetchGenRef = useRef<number>(0);

  // Explicit refresh of certificate enrollments for a specific doctor
  const handleRefreshDoctorEnrollments = useCallback(async (doctorId: string) => {
    try {
      const enrollmentsRes = await client.getAdminDoctorCertificateEnrollments(doctorId);
      const candidates = (enrollmentsRes.items || [])
        .filter((cand: any) => cand && (cand.registrationId || cand.fingerprintSha256 || cand.kind))
        .map((cand) => ({
          ...cand,
          externalCaStatus: cand.externalCaStatus || cand.externalStatus,
        }));
      const activeCert = candidates.find((c) => c.registrationStatus === 'ACTIVE') || null;
      setDoctors((prev) =>
        prev.map((d) =>
          d.id === doctorId
            ? {
                ...d,
                candidateCertificates: candidates,
                authCertificate: activeCert || d.authCertificate || null,
              }
            : d
        )
      );
    } catch (err) {
      console.error(`Failed to refresh enrollments for doctor ${doctorId}:`, err);
    }
  }, [client]);

  // Load Doctor List
  const loadDoctors = useCallback(async () => {
    const currentGen = ++fetchGenRef.current;
    setLoading(true);
    setError(null);

    try {
      if (isMockMode) {
        const tenantDoctors = mockStore.doctors[tenantId] || [];
        if (currentGen === fetchGenRef.current) {
          setDoctors(tenantDoctors.filter(d => !isSyntheticDoctor(d)));
        }
      } else {
        const response = await client.getAdminDoctors();
        if (currentGen === fetchGenRef.current) {
          const baseDocs = (response.items || []).filter(d => !isSyntheticDoctor(d));
          // Concurrently fetch certificate enrollments per doctor so real candidate certificates appear
          const docsWithEnrollments = await Promise.all(
            baseDocs.map(async (doc) => {
              try {
                const enrollmentsRes = await client.getAdminDoctorCertificateEnrollments(doc.id);
                const candidates = (enrollmentsRes.items || [])
                  .filter((cand: any) => cand && (cand.registrationId || cand.fingerprintSha256 || cand.kind))
                  .map((cand) => ({
                    ...cand,
                    externalCaStatus: cand.externalCaStatus || cand.externalStatus,
                  }));
                const activeCert = candidates.find((c) => c.registrationStatus === 'ACTIVE') || null;
                return {
                  ...doc,
                  candidateCertificates: candidates,
                  authCertificate: activeCert || doc.authCertificate || null,
                };
              } catch (enrollErr) {
                console.error(`Failed to load certificate enrollments for doctor ${doc.id}:`, enrollErr);
                return {
                  ...doc,
                  candidateCertificates: doc.candidateCertificates || [],
                };
              }
            })
          );
          if (currentGen === fetchGenRef.current) {
            setDoctors(docsWithEnrollments);
          }
        }
      }
    } catch (err) {
      if (currentGen === fetchGenRef.current) {
        setError(err instanceof Error ? err : new Error('의사 목록 조회에 실패했습니다.'));
      }
    } finally {
      if (currentGen === fetchGenRef.current) {
        setLoading(false);
      }
    }
  }, [client, isMockMode, mockStore.doctors, tenantId]);

  useEffect(() => {
    loadDoctors();
  }, [loadDoctors]);

  // Handle Form Submit (Pre-registration)
  const handlePreregister = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!adminInvitationsEnabled) return;
    if (!evidenceReviewed) {
      setFormError('관리자의 신원 및 의사 면허 증빙 대조·검토 확인(체크박스)이 필수입니다.');
      return;
    }
    setSubmitting(true);
    setFormError(null);

    try {
      if (isMockMode) {
        const newDocId = `doc-mock-${Date.now()}`;
        const newCode = `INV-MOCK-${Math.random().toString(36).substring(2, 7).toUpperCase()}-${Math.random().toString(36).substring(2, 7).toUpperCase()}-${Math.random().toString(36).substring(2, 7).toUpperCase()}`;
        const expiresAt = new Date(Date.now() + 7 * 86400000).toISOString();

        const createdDoctor: Doctor = {
          id: newDocId,
          tenantId,
          name: formData.name.trim(),
          licenseNumber: formData.licenseNumber.trim(),
          centerName: formData.centerName.trim(),
          googleEmail: formData.googleEmail.trim().toLowerCase(),
          status: 'PENDING',
          identityMode: null,
          googleAccountLinked: false,
          invitationState: 'ISSUED',
          invitationExpiresAt: expiresAt,
          identityVerifiedAt: null,
          consentedAt: null,
          consentVersion: null,
          activatedAt: null,
          statusReason: null,
          statusChangedAt: null,
          createdAt: new Date().toISOString(),
          createdBy: 'admin-demo',
          signingBackend: 'NOT_CONFIGURED',
          certificate: null,
        };

        const result: PreregisterDoctorResponse = {
          doctor: createdDoctor,
          invitationCode: newCode,
          invitationExpiresAt: expiresAt,
        };

        setMockStore((prev) => ({
          ...prev,
          doctors: {
            ...prev.doctors,
            [tenantId]: [createdDoctor, ...(prev.doctors[tenantId] || [])],
          },
          invitationCodes: {
            ...prev.invitationCodes,
            [newCode]: {
              doctorId: newDocId,
              tenantId,
              code: newCode,
              expiresAt,
              used: false,
            },
          },
        }));

        setLatestInvitation(result);
        setDoctors((prev) => [createdDoctor, ...prev]);
        setFormData({
          name: '',
          licenseNumber: '',
          centerName: '',
          googleEmail: '',
          identityEvidenceRef: '',
          licenseEvidenceRef: '',
          expectedCertificateFingerprint: '',
        });
        setEvidenceReviewed(false);
      } else {
        const response = await client.preregisterDoctor({
          name: formData.name.trim(),
          licenseNumber: formData.licenseNumber.trim(),
          centerName: formData.centerName.trim(),
          googleEmail: formData.googleEmail.trim().toLowerCase(),
          identityEvidenceRef: formData.identityEvidenceRef.trim(),
          licenseEvidenceRef: formData.licenseEvidenceRef.trim(),
          expectedCertificateFingerprint: formData.expectedCertificateFingerprint?.trim() || undefined,
        });

        setLatestInvitation(response);
        setDoctors((prev) => [response.doctor, ...prev.filter((d) => d.id !== response.doctor.id)]);
        setFormData({
          name: '',
          licenseNumber: '',
          centerName: '',
          googleEmail: '',
          identityEvidenceRef: '',
          licenseEvidenceRef: '',
          expectedCertificateFingerprint: '',
        });
        setEvidenceReviewed(false);
      }
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '의사 사전등록 처리에 실패했습니다.');
    } finally {
      setSubmitting(false);
    }
  };

  // Reissue Invitation
  const handleReissue = async (doc: Doctor) => {
    if (!adminInvitationsEnabled || doc.status !== 'PENDING') return;
    if (!window.confirm(`${doc.name} 의사에게 새 초대 코드를 재발급하시겠습니까? 기존 초대 코드는 즉시 무효화(VOID)됩니다.`)) {
      return;
    }

    try {
      if (isMockMode) {
        const newCode = `INV-REISSUE-${Math.random().toString(36).substring(2, 7).toUpperCase()}-${Math.random().toString(36).substring(2, 7).toUpperCase()}`;
        const expiresAt = new Date(Date.now() + 7 * 86400000).toISOString();
        const updatedDoc: Doctor = {
          ...doc,
          invitationState: 'ISSUED',
          invitationExpiresAt: expiresAt,
        };
        const result: PreregisterDoctorResponse = {
          doctor: updatedDoc,
          invitationCode: newCode,
          invitationExpiresAt: expiresAt,
        };

        setMockStore((prev) => ({
          ...prev,
          doctors: {
            ...prev.doctors,
            [tenantId]: prev.doctors[tenantId].map((d) => (d.id === doc.id ? updatedDoc : d)),
          },
          invitationCodes: {
            ...prev.invitationCodes,
            [newCode]: {
              doctorId: doc.id,
              tenantId,
              code: newCode,
              expiresAt,
              used: false,
            },
          },
        }));

        setLatestInvitation(result);
        setDoctors((prev) => prev.map((d) => (d.id === doc.id ? updatedDoc : d)));
      } else {
        const response = await client.reissueDoctorInvitation(doc.id);
        setLatestInvitation(response);
        setDoctors((prev) => prev.map((d) => (d.id === doc.id ? response.doctor : d)));
      }
    } catch (err) {
      alert(err instanceof Error ? err.message : '초대 코드 재발급에 실패했습니다.');
    }
  };

  // Execute Status Update (SUSPEND or REVOKE)
  const handleExecuteStatusUpdate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedDoctorForAction || !targetActionStatus) return;
    if (statusReason.trim().length < 2) {
      setActionError('사유를 최소 2자 이상 입력해주세요.');
      return;
    }

    setActionProcessing(true);
    setActionError(null);

    try {
      if (isMockMode) {
        const updatedDoc: Doctor = {
          ...selectedDoctorForAction,
          status: targetActionStatus,
          statusReason: statusReason.trim(),
          statusChangedAt: new Date().toISOString(),
        };

        setMockStore((prev) => ({
          ...prev,
          doctors: {
            ...prev.doctors,
            [tenantId]: prev.doctors[tenantId].map((d) =>
              d.id === selectedDoctorForAction.id ? updatedDoc : d
            ),
          },
        }));

        setDoctors((prev) =>
          prev.map((d) => (d.id === selectedDoctorForAction.id ? updatedDoc : d))
        );
        setSelectedDoctorForAction(null);
        setTargetActionStatus(null);
        setStatusReason('');
      } else {
        const updatedDoctor = await client.updateDoctorStatus(selectedDoctorForAction.id, {
          status: targetActionStatus,
          reason: statusReason.trim(),
        });

        setDoctors((prev) =>
          prev.map((d) => (d.id === selectedDoctorForAction.id ? updatedDoctor : d))
        );
        setSelectedDoctorForAction(null);
        setTargetActionStatus(null);
        setStatusReason('');
      }
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '상태 변경에 실패했습니다.');
    } finally {
      setActionProcessing(false);
    }
  };

  const copyInvitationCode = () => {
    if (!latestInvitation) return;
    navigator.clipboard.writeText(latestInvitation.invitationCode);
    setCopied(true);
    setTimeout(() => setCopied(false), 2500);
  };

  const renderStatusBadge = (status: DoctorStatus) => {
    switch (status) {
      case 'PENDING':
        return <span className="badge badge-pending" data-testid="status-chip-pending">등록 대기 (PENDING)</span>;
      case 'IDENTITY_VERIFIED':
        return <span className="badge" style={{ backgroundColor: '#e0f2fe', color: '#0369a1' }}>구글 신원 검증 (VERIFIED)</span>;
      case 'CONSENTED':
        return <span className="badge" style={{ backgroundColor: '#dbeafe', color: '#1d4ed8' }}>의사 동의 완료 (CONSENTED)</span>;
      case 'ACTIVE':
        return <span className="badge badge-completed" data-testid="status-chip-active">서명 활성 (ACTIVE)</span>;
      case 'SUSPENDED':
        return <span className="badge" style={{ backgroundColor: '#ffedd5', color: '#c2410c' }} data-testid="status-chip-suspended">이용 정지 (SUSPENDED)</span>;
      case 'REVOKED':
        return <span className="badge badge-failed" data-testid="status-chip-revoked">권한 파기 (REVOKED)</span>;
      default:
        return <span className="badge">{status}</span>;
    }
  };

  const renderCaBadge = (status?: ExternalCaStatus) => {
    switch (status) {
      case 'GOOD':
        return <span className="badge badge-completed" style={{ fontSize: '0.7rem' }}>CA 정상 (GOOD)</span>;
      case 'REVOKED':
        return <span className="badge badge-failed" style={{ fontSize: '0.7rem' }}>CA 폐기 (REVOKED)</span>;
      case 'UNKNOWN':
        return <span className="badge badge-pending" style={{ fontSize: '0.7rem' }}>CA 미확인 (UNKNOWN)</span>;
      case 'STALE':
        return <span className="badge" style={{ backgroundColor: '#fef08a', color: '#854d0e', fontSize: '0.7rem' }}>CRL 지연 (STALE)</span>;
      default:
        return <span className="badge">{status || '미확인'}</span>;
    }
  };

  const handleOpenCertReview = (doc: Doctor, cand: ExternalCertCandidate) => {
    setReviewCandidateDoctor(doc);
    setSelectedCandidate(cand);
    setReviewEvidenceRef(doc.licenseNumber ? `LIC-${doc.licenseNumber}` : '');
    setReviewNote('');
    setReviewConfirmed(false);
    setReviewError(null);
  };

  const handleReviewCandidateSubmit = async (decision: 'APPROVED' | 'REJECTED') => {
    if (!reviewCandidateDoctor || !selectedCandidate) return;
    if (decision === 'APPROVED' && selectedCandidate.medicalCredentialsRequired && !selectedCandidate.medicalCredentialsSubmittedAt) {
      setReviewError('의사가 면허·자격 정보를 먼저 제출해야 합니다.');
      return;
    }
    if (decision === 'APPROVED' && !reviewConfirmed) {
      setReviewError('관리자의 신원 및 의사 면허 대조 검증 확인(체크박스)이 필수입니다.');
      return;
    }
    if (decision === 'APPROVED' && !reviewEvidenceRef.trim()) {
      setReviewError('면허 및 신원 대조 증빙 번호(Evidence Ref)를 입력해주세요.');
      return;
    }

    setReviewProcessing(true);
    setReviewError(null);

    try {
      if (isMockMode) {
        const updatedEnrollment: ExternalCertCandidate = {
          ...selectedCandidate,
          registrationStatus: decision === 'APPROVED' ? 'ACTIVE' : 'REVOKED',
          reviewStatus: decision === 'APPROVED' ? 'APPROVED' : 'REJECTED',
          reviewMethod: 'ADMIN_REVIEW',
          reviewedBy: 'admin-demo',
          reviewedAt: new Date().toISOString(),
          activatedAt: decision === 'APPROVED' ? new Date().toISOString() : null,
        };

        const updatedCandidates = (reviewCandidateDoctor.candidateCertificates || []).map((c) =>
          (c.registrationId === selectedCandidate.registrationId || c.fingerprintSha256 === selectedCandidate.fingerprintSha256)
            ? updatedEnrollment
            : c
        );

        const updatedDoc: Doctor = {
          ...reviewCandidateDoctor,
          authCertificate: decision === 'APPROVED' ? updatedEnrollment : reviewCandidateDoctor.authCertificate,
          candidateCertificates: updatedCandidates,
        };

        setMockStore((prev) => ({
          ...prev,
          doctors: {
            ...prev.doctors,
            [tenantId]: prev.doctors[tenantId].map((d) => (d.id === updatedDoc.id ? updatedDoc : d)),
          },
        }));

        setDoctors((prev) => prev.map((d) => (d.id === updatedDoc.id ? updatedDoc : d)));
        setReviewCandidateDoctor(null);
        setSelectedCandidate(null);
      } else {
        const updatedEnrollment = await client.reviewAdminDoctorCertificate(reviewCandidateDoctor.id, {
          registrationId: selectedCandidate.registrationId,
          decision: decision === 'APPROVED' ? 'APPROVE' : 'REJECT',
          fingerprintSha256: selectedCandidate.fingerprintSha256,
          evidenceRef: reviewEvidenceRef.trim(),
          medicalCredentialsSubmittedAt: selectedCandidate.medicalCredentialsSubmittedAt || undefined,
          note: reviewNote.trim() || undefined,
        });

        const updatedCandidates = (reviewCandidateDoctor.candidateCertificates || []).map((c) =>
          (c.registrationId === updatedEnrollment.registrationId || c.fingerprintSha256 === updatedEnrollment.fingerprintSha256)
            ? updatedEnrollment
            : c
        );

        const updatedDoc: Doctor = {
          ...reviewCandidateDoctor,
          authCertificate: updatedEnrollment.registrationStatus === 'ACTIVE' ? updatedEnrollment : reviewCandidateDoctor.authCertificate,
          candidateCertificates: updatedCandidates,
        };

        setDoctors((prev) => prev.map((d) => (d.id === updatedDoc.id ? updatedDoc : d)));
        setReviewCandidateDoctor(null);
        setSelectedCandidate(null);
      }
    } catch (err) {
      setReviewError(err instanceof Error ? err.message : '인증서 검토 처리에 실패했습니다.');
    } finally {
      setReviewProcessing(false);
    }
  };

  return (
    <div style={{ maxWidth: '1200px', margin: '0 auto', padding: '16px' }}>
      {/* Top Navigation Bar */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px', flexWrap: 'wrap', gap: '8px' }}>
        <a href="#/" className="btn btn-secondary" style={{ textDecoration: 'none' }}>
          <ArrowLeft size={14} /> 메인 콘솔로 복귀
        </a>
        <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
          <a href="#/activate" className="btn btn-outline" style={{ textDecoration: 'none', color: 'var(--teal-700)' }}>
            의사 직접 등록 안내 <ArrowRight size={14} />
          </a>
          <button type="button" onClick={loadDoctors} className="btn btn-secondary" disabled={loading}>
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} /> 새로고침
          </button>
        </div>
      </div>

      {/* Dev Header Authentication Warning (Mandatory Notice) */}
      <div
        className="alert alert-warning"
        role="alert"
        style={{ marginBottom: '20px' }}
        data-testid="dev-auth-notice"
      >
        <ShieldAlert size={20} />
        <div>
          <strong>개발/검증 환경 가이드 (Dev-Header Authentication):</strong>
          <div style={{ fontSize: '0.8rem', marginTop: '2px', lineHeight: '1.4' }}>
            개발·검증 환경에서는 실제 발급기관 인증서로 확인된 의사만 운영 목록에 표시합니다.
            합성 시험 기록은 화면에서 제외되지만 감사 기록에는 보존됩니다. 현재 관리자 인증 방식과 서버 운영 상태는 상단 경고를 확인해 주세요.
          </div>
        </div>
      </div>

      <ErrorBanner error={error} />

      {error && ((error instanceof ApiClientError && (error.status === 403 || error.code === 'ADMIN_ROLE_REQUIRED')) || (error.message && (error.message.includes('403') || error.message.includes('ADMIN_ROLE_REQUIRED')))) && (
        <div
          data-testid="admin-role-required-notice"
          className="alert alert-warning"
          role="alert"
          style={{
            marginBottom: '20px',
            padding: '16px',
            backgroundColor: '#fffbeb',
            border: '1px solid #f59e0b',
            borderRadius: '8px',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontWeight: 600, color: '#b45309' }}>
            <AlertTriangle size={18} />
            관리자 권한 필요 (ADMIN_ROLE_REQUIRED / 403 Forbidden)
          </div>
          <div style={{ marginTop: '8px', fontSize: '0.875rem', color: '#92400e', lineHeight: 1.5 }}>
            의사 관리 및 인증서 심사는 <strong>관리자(ADMIN)</strong> 역할만 접근할 수 있습니다.
            관리자 계정으로 로그인해 주세요.
          </div>
        </div>
      )}

      {/* Grid: Pre-registration Form (Left) & Summary (Right) */}
      <div className="detail-grid" style={{ marginBottom: '24px' }}>
        {/* Left: Pre-registration Form */}
        {adminInvitationsEnabled && <div className="card">
          <div className="card-header" style={{ backgroundColor: 'var(--teal-50)' }}>
            <span className="card-title" style={{ color: 'var(--teal-900)' }}>
              <UserPlus size={18} style={{ color: 'var(--teal-700)' }} />
              신규 의사 사전등록 (Admin Pre-registration)
            </span>
          </div>
          <div className="card-body">
            <p style={{ fontSize: '0.85rem', color: 'var(--navy-600)', marginBottom: '16px', lineHeight: '1.5' }}>
              관리자가 의사의 신원 확인 서류와 의사 면허증 사본을 직접 대조·검토한 후 등록합니다.
              <strong> 사전등록 시점에는 서명키나 인증서가 생성되지 않으며</strong>, 의사 본인의 구글 인증 및 3대 동의를 거쳐 발급됩니다.
            </p>

            <form onSubmit={handlePreregister}>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '12px', marginBottom: '12px' }}>
                <div>
                  <label htmlFor="doc-name" style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, marginBottom: '4px' }}>
                    의사 성명 <span style={{ color: 'var(--red-600)' }}>*</span>
                  </label>
                  <input
                    id="doc-name"
                    type="text"
                    required
                    minLength={2}
                    maxLength={50}
                    placeholder="예: 홍길동"
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                    data-testid="prereg-name-input"
                    style={{ width: '100%', padding: '8px 10px', borderRadius: '4px', border: '1px solid var(--navy-300)' }}
                  />
                </div>

                <div>
                  <label htmlFor="doc-license" style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, marginBottom: '4px' }}>
                    의사 면허번호 <span style={{ color: 'var(--red-600)' }}>*</span>
                  </label>
                  <input
                    id="doc-license"
                    type="text"
                    required
                    pattern="^[0-9]{1,20}$"
                    maxLength={20}
                    placeholder="예: 123456 (숫자만)"
                    value={formData.licenseNumber}
                    onChange={(e) => setFormData({ ...formData, licenseNumber: e.target.value.replace(/\D/g, '') })}
                    data-testid="prereg-license-input"
                    style={{ width: '100%', padding: '8px 10px', borderRadius: '4px', border: '1px solid var(--navy-300)' }}
                  />
                </div>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '12px', marginBottom: '12px' }}>
                <div>
                  <label htmlFor="doc-center" style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, marginBottom: '4px' }}>
                    소속 판독센터 / 병원명 <span style={{ color: 'var(--red-600)' }}>*</span>
                  </label>
                  <input
                    id="doc-center"
                    type="text"
                    required
                    minLength={2}
                    maxLength={100}
                    placeholder="예: 서울영상판독센터"
                    value={formData.centerName}
                    onChange={(e) => setFormData({ ...formData, centerName: e.target.value })}
                    data-testid="prereg-center-input"
                    style={{ width: '100%', padding: '8px 10px', borderRadius: '4px', border: '1px solid var(--navy-300)' }}
                  />
                </div>

                <div>
                  <label htmlFor="doc-google-email" style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, marginBottom: '4px' }}>
                    구글 계정 이메일 <span style={{ color: 'var(--red-600)' }}>*</span>
                  </label>
                  <input
                    id="doc-google-email"
                    type="email"
                    required
                    maxLength={254}
                    placeholder="예: dr.hong@example.com"
                    value={formData.googleEmail}
                    onChange={(e) => setFormData({ ...formData, googleEmail: e.target.value })}
                    data-testid="prereg-email-input"
                    style={{ width: '100%', padding: '8px 10px', borderRadius: '4px', border: '1px solid var(--navy-300)' }}
                  />
                  <span style={{ fontSize: '0.7rem', color: 'var(--navy-500)' }}>
                    * 초대 후 구글 로그인 시 일치해야 하는 이메일 주소입니다.
                  </span>
                </div>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '12px', marginBottom: '16px' }}>
                <div>
                  <label htmlFor="doc-identity-ref" style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, marginBottom: '4px' }}>
                    신원 확인 증빙 참조 (ID Evidence Ref) <span style={{ color: 'var(--red-600)' }}>*</span>
                  </label>
                  <input
                    id="doc-identity-ref"
                    type="text"
                    required
                    minLength={1}
                    maxLength={200}
                    placeholder="예: ID-DOC-2026-001"
                    value={formData.identityEvidenceRef}
                    onChange={(e) => setFormData({ ...formData, identityEvidenceRef: e.target.value })}
                    data-testid="prereg-id-evidence-input"
                    style={{ width: '100%', padding: '8px 10px', borderRadius: '4px', border: '1px solid var(--navy-300)' }}
                  />
                </div>

                <div>
                  <label htmlFor="doc-license-ref" style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, marginBottom: '4px' }}>
                    면허 확인 증빙 참조 (License Evidence Ref) <span style={{ color: 'var(--red-600)' }}>*</span>
                  </label>
                  <input
                    id="doc-license-ref"
                    type="text"
                    required
                    minLength={1}
                    maxLength={200}
                    placeholder="예: LIC-VERIF-2026-089"
                    value={formData.licenseEvidenceRef}
                    onChange={(e) => setFormData({ ...formData, licenseEvidenceRef: e.target.value })}
                    data-testid="prereg-license-evidence-input"
                    style={{ width: '100%', padding: '8px 10px', borderRadius: '4px', border: '1px solid var(--navy-300)' }}
                  />
                </div>
              </div>

              <div style={{ marginBottom: '16px' }}>
                <label htmlFor="doc-expected-fingerprint" style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, marginBottom: '4px' }}>
                  사전 등록 인증서 지문 (Expected Certificate Fingerprint, 선택)
                </label>
                <input
                  id="doc-expected-fingerprint"
                  type="text"
                  maxLength={100}
                  placeholder="예: 64자 16진수 SHA-256 지문 (일치 시 심사 자동 승인)"
                  value={formData.expectedCertificateFingerprint || ''}
                  onChange={(e) => setFormData({ ...formData, expectedCertificateFingerprint: e.target.value })}
                  data-testid="admin-doctor-expected-fingerprint-input"
                  style={{ width: '100%', padding: '8px 10px', borderRadius: '4px', border: '1px solid var(--navy-300)', fontFamily: 'monospace', fontSize: '0.8rem' }}
                />
                <span style={{ fontSize: '0.7rem', color: 'var(--navy-500)' }}>
                  * 외부 인증서 전체 DER의 SHA-256 지문(64자 16진수). 의사가 등록한 인증서와 일치하면 관리자 심사가 자동 승인됩니다.
                </span>
              </div>

              {/* Mandatory Admin Verification Checkbox */}
              <div
                style={{
                  padding: '12px',
                  backgroundColor: 'var(--navy-50)',
                  borderRadius: '6px',
                  border: '1px solid var(--navy-300)',
                  marginBottom: '16px',
                }}
              >
                <label style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={evidenceReviewed}
                    onChange={(e) => setEvidenceReviewed(e.target.checked)}
                    data-testid="admin-reviewed-evidence-checkbox"
                    style={{ marginTop: '3px', cursor: 'pointer' }}
                  />
                  <span style={{ fontSize: '0.85rem', color: 'var(--navy-900)', fontWeight: 600 }}>
                    <FileCheck2 size={16} style={{ display: 'inline', verticalAlign: '-3px', marginRight: '4px', color: 'var(--teal-700)' }} />
                    관리자 본인은 위 의료인의 실명 신원 확인 서류 및 의사 면허 증빙을 직접 대조·검토하였음을 확인합니다.
                  </span>
                </label>
              </div>

              {formError && (
                <div className="alert alert-error" role="alert" style={{ marginBottom: '16px' }}>
                  <AlertTriangle size={16} />
                  <span>{formError}</span>
                </div>
              )}

              <button
                type="submit"
                className="btn btn-primary"
                disabled={submitting || !evidenceReviewed}
                data-testid="submit-preregister-btn"
                style={{ width: '100%' }}
              >
                {submitting ? '사전등록 처리 중...' : '신규 의사 사전등록 및 1회용 초대 코드 발급'}
              </button>
            </form>
          </div>
        </div>}

        {/* Right: Architecture Notice & Summary */}
        <div className="card">
          <div className="card-header">
            <span className="card-title">
              <Building2 size={18} />
              기관별 의사 등록 현황
              <InfoPopover
                title="의사 등록·인증서 보안 원칙"
                description="판독의사가 PACS에서 본인의 공동인증서를 등록하고 로그인합니다."
                bullets={[
                  '관리자 사전등록·초대코드 발급은 비활성화되어 있습니다.',
                  '의사 서명키는 인증서 검증, 본인 동의 및 신원·면허 연결 검토 후 생성됩니다.',
                ]}
              />
            </span>
          </div>
          <div className="card-body">
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '12px', marginBottom: '16px' }}>
              <div style={{ padding: '12px', backgroundColor: 'var(--navy-50)', borderRadius: '6px' }}>
                <div style={{ fontSize: '0.75rem', color: 'var(--navy-600)' }}>등록된 의사 수</div>
                <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--teal-700)' }}>
                  {doctors.length}명
                </div>
              </div>
              <div style={{ padding: '12px', backgroundColor: 'var(--navy-50)', borderRadius: '6px' }}>
                <div style={{ fontSize: '0.75rem', color: 'var(--navy-600)' }}>서명 활성 (ACTIVE)</div>
                <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#16a34a' }}>
                  {doctors.filter((d) => d.status === 'ACTIVE').length}명
                </div>
              </div>
            </div>

            <div data-testid="admin-invitations-disabled" style={{ fontSize: '0.8rem', color: 'var(--navy-600)' }}>
              관리자 초대코드 발급 비활성화
            </div>
          </div>
        </div>
      </div>

      {/* One-time Invitation Code Modal / Banner */}
      {adminInvitationsEnabled && latestInvitation && (
        <div
          className="card"
          style={{
            marginBottom: '24px',
            border: '2px solid var(--teal-600)',
            backgroundColor: 'var(--teal-50)',
          }}
          data-testid="one-time-invitation-card"
        >
          <div className="card-header" style={{ backgroundColor: 'var(--teal-100)' }}>
            <span className="card-title" style={{ color: 'var(--teal-900)' }}>
              🎉 1회용 의사 초대 코드 생성 완료 (One-time Invitation)
            </span>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => setLatestInvitation(null)}
              style={{ fontSize: '0.75rem', padding: '4px 8px' }}
            >
              닫기
            </button>
          </div>
          <div className="card-body">
            <p style={{ fontSize: '0.9rem', color: 'var(--teal-950)', marginBottom: '12px' }}>
              <strong>{latestInvitation.doctor.name}</strong> 의사 (면허번호: {latestInvitation.doctor.licenseNumber})에게 전달할 1회용 초대 코드입니다:
            </p>

            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                padding: '12px 16px',
                backgroundColor: 'white',
                borderRadius: '6px',
                border: '1px solid var(--teal-400)',
                marginBottom: '12px',
              }}
            >
              <code
                style={{
                  fontSize: '1.1rem',
                  fontWeight: 700,
                  letterSpacing: '1px',
                  color: 'var(--navy-900)',
                  flex: 1,
                  wordBreak: 'break-all',
                }}
                data-testid="generated-invitation-code"
              >
                {latestInvitation.invitationCode}
              </code>
              <button
                type="button"
                className="btn btn-primary"
                onClick={copyInvitationCode}
                data-testid="copy-invitation-btn"
                style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}
              >
                {copied ? <Check size={16} /> : <Copy size={16} />}
                {copied ? '복사 완료!' : '코드 복사'}
              </button>
            </div>

            <div className="alert alert-info" style={{ backgroundColor: 'white', borderColor: 'var(--teal-300)' }}>
              <div>
                <strong>안전 전달 및 이메일 미발송 고지:</strong>
                <div style={{ fontSize: '0.8rem', marginTop: '2px', lineHeight: '1.4' }}>
                  * 시스템은 <strong>이메일 자동 발송을 지원하지 않습니다</strong>. 관리자는 위 초대 코드를 원내 공문 또는 보안 메신저 등 인가된 안전한 경로로 의사에게 직접 전달해야 합니다.
                  <br />
                  * 본 코드는 화면을 닫으면 <strong>다시 조회할 수 없으므로</strong> 반드시 복사하여 안전하게 보관 및 전달하십시오.
                  <br />
                  * 유효 기한: {new Date(latestInvitation.invitationExpiresAt).toLocaleString('ko-KR')} 까지 (유효 기한 경과 시 재발급 필요)
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Doctor List Section */}
      <div className="card">
        <div className="card-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span className="card-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Users size={18} />
            등록 의사 목록 (Doctor Directory)
            <InfoPopover
              title="의사 계정 및 인증서 관리 규정"
              badge="관리자 심사 안내"
              description="판독의사의 본인 확인 서류 및 공동인증서 지문을 대조 심사하고 서명 권한 상태를 관리합니다."
              bulletPoints={[
                '후보 검토 대기(PENDING): 의사가 제출한 인증서 지문과 면허 정보를 대조 심사합니다.',
                '서명 활성(ACTIVE): 관리자 심사 승인 및 의사 3대 동의가 완료되어 원격 판독 서명이 가능합니다.',
                '이용 정지(SUSPENDED): 자격 정지 또는 보안 이슈 발생 시 즉시 서명 권한을 일시 중단합니다.',
                '권한 파기(REVOKED): 퇴사 또는 면허 취소 시 영구 폐기하며 복구가 불가합니다.',
              ]}
            />
          </span>
          <span className="badge badge-planned">
            기관: {tenantId}
          </span>
        </div>

        <div className="card-body">
          {loading ? (
            <LoadingSpinner message="의사 목록을 불러오는 중입니다..." />
          ) : doctors.length === 0 ? (
            <div style={{ padding: '40px', textAlign: 'center', color: 'var(--navy-500)' }}>
              등록된 의사가 없습니다. 판독의사가 PACS에서 공동인증서 등록을 시작하면 표시됩니다.
            </div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left', fontSize: '0.85rem' }}>
                <thead>
                  <tr style={{ borderBottom: '2px solid var(--navy-200)', backgroundColor: 'var(--navy-50)' }}>
                    <th style={{ padding: '10px 12px' }}>성명 / 면허번호</th>
                    <th style={{ padding: '10px 12px' }}>소속 판독센터</th>
                    <th style={{ padding: '10px 12px' }}>지정 구글 이메일</th>
                    <th style={{ padding: '10px 12px' }}>외부 인증서 (External Auth)</th>
                    <th style={{ padding: '10px 12px' }}>계정 상태 (Status)</th>
                    <th style={{ padding: '10px 12px' }}>인증 모드</th>
                    <th style={{ padding: '10px 12px' }}>등록 일시</th>
                    <th style={{ padding: '10px 12px' }}>관리 작업</th>
                  </tr>
                </thead>
                <tbody>
                  {doctors.map((doc) => (
                    <tr key={doc.id} style={{ borderBottom: '1px solid var(--navy-100)' }} data-testid={`doctor-row-${doc.id}`}>
                      <td style={{ padding: '10px 12px' }}>
                        <strong>{doc.name}</strong>
                        <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)' }}>
                          면허: {doc.licenseNumber}
                        </div>
                      </td>
                      <td style={{ padding: '10px 12px' }}>{doc.centerName}</td>
                      <td style={{ padding: '10px 12px' }}>
                        <code>{doc.googleEmail || 'PACS 계정 등록'}</code>
                      </td>
                      <td style={{ padding: '10px 12px' }} data-testid={`doctor-cert-cell-${doc.id}`}>
                        <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '2px' }}>
                          <button
                            type="button"
                            onClick={() => handleRefreshDoctorEnrollments(doc.id)}
                            style={{
                              background: 'transparent',
                              border: 'none',
                              padding: '1px 3px',
                              cursor: 'pointer',
                              color: 'var(--navy-500)',
                              fontSize: '0.7rem',
                            }}
                            title="인증서 등록 목록 새로고침"
                            data-testid={`refresh-cert-btn-${doc.id}`}
                          >
                            <RefreshCw size={11} />
                          </button>
                        </div>
                        {doc.authCertificate ? (
                          <div>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '4px', flexWrap: 'wrap' }}>
                              <span className="badge badge-completed" style={{ fontSize: '0.65rem' }}>등록 활성 (ACTIVE)</span>
                              {renderCaBadge(doc.authCertificate.externalCaStatus)}
                            </div>
                            <div style={{ fontSize: '0.75rem', color: 'var(--navy-700)', marginTop: '2px', wordBreak: 'break-all' }}>
                              SN: <code>{doc.authCertificate.serialNumber}</code>
                            </div>
                            <div style={{ fontSize: '0.7rem', color: 'var(--navy-500)' }}>
                              증빙: {doc.authCertificate.evidenceRef}
                            </div>
                          </div>
                        ) : doc.candidateCertificates && doc.candidateCertificates.length > 0 ? (
                          <div>
                            {doc.candidateCertificates.map((cand) => (
                              <div key={cand.registrationId || cand.fingerprintSha256} style={{ marginBottom: '4px' }}>
                                <div style={{ display: 'flex', alignItems: 'center', gap: '4px', flexWrap: 'wrap' }}>
                                  <span className="badge badge-pending" style={{ fontSize: '0.65rem' }}>후보 검토 대기</span>
                                  {renderCaBadge(cand.externalCaStatus)}
                                </div>
                                <div style={{ fontSize: '0.7rem', color: 'var(--navy-600)', marginTop: '2px' }}>
                                  지문: <code>{cand.fingerprintSha256 ? `${cand.fingerprintSha256.substring(0, 12)}...` : '-'}</code>
                                </div>
                                {cand.registrationStatus === 'PENDING' && (
                                  <button
                                    type="button"
                                    className="btn btn-primary"
                                    onClick={() => handleOpenCertReview(doc, cand)}
                                    style={{ fontSize: '0.7rem', padding: '2px 6px', marginTop: '4px' }}
                                    data-testid={`admin-cert-review-btn-${doc.id}`}
                                  >
                                    인증서 대조·검토
                                  </button>
                                )}
                              </div>
                            ))}
                          </div>
                        ) : (
                          <span style={{ color: 'var(--navy-400)', fontSize: '0.75rem' }}>(미제출)</span>
                        )}
                      </td>
                      <td style={{ padding: '10px 12px' }}>
                        <span data-testid={`status-chip-${doc.id}`}>
                          {renderStatusBadge(doc.status)}
                        </span>
                        {doc.statusReason && (
                          <div style={{ fontSize: '0.7rem', color: 'var(--red-600)', marginTop: '2px' }}>
                            사유: {doc.statusReason}
                          </div>
                        )}
                      </td>
                      <td style={{ padding: '10px 12px' }}>
                        {doc.identityMode || '(미인증)'}
                      </td>
                      <td style={{ padding: '10px 12px', color: 'var(--navy-600)' }}>
                        {new Date(doc.createdAt).toLocaleDateString('ko-KR')}
                      </td>
                      <td style={{ padding: '10px 12px' }}>
                        <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                          {adminInvitationsEnabled && doc.status === 'PENDING' && (
                            <button
                              type="button"
                              className="btn btn-secondary"
                              onClick={() => handleReissue(doc)}
                              style={{ fontSize: '0.75rem', padding: '3px 8px' }}
                              data-testid={`reissue-btn-${doc.id}`}
                            >
                              초대 재발급
                            </button>
                          )}

                          {doc.status !== 'REVOKED' && doc.status !== 'SUSPENDED' && (
                            <button
                              type="button"
                              className="btn btn-outline"
                              onClick={() => {
                                setSelectedDoctorForAction(doc);
                                setTargetActionStatus('SUSPENDED');
                                setStatusReason('');
                                setActionError(null);
                              }}
                              style={{ fontSize: '0.75rem', padding: '3px 8px', color: '#c2410c' }}
                              data-testid={`suspend-btn-${doc.id}`}
                            >
                              이용 정지
                            </button>
                          )}

                          {doc.status !== 'REVOKED' && (
                            <button
                              type="button"
                              className="btn btn-outline"
                              onClick={() => {
                                setSelectedDoctorForAction(doc);
                                setTargetActionStatus('REVOKED');
                                setStatusReason('');
                                setActionError(null);
                              }}
                              style={{ fontSize: '0.75rem', padding: '3px 8px', color: 'var(--red-600)' }}
                              data-testid={`revoke-btn-${doc.id}`}
                            >
                              권한 파기
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Suspend / Revoke Modal */}
      {selectedDoctorForAction && targetActionStatus && (
        <div
          style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            backgroundColor: 'rgba(0, 0, 0, 0.5)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
            padding: '16px',
          }}
          data-testid="status-action-modal"
        >
          <div
            className="card"
            style={{
              width: '100%',
              maxWidth: '480px',
              backgroundColor: 'white',
              boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.1)',
            }}
          >
            <div className="card-header" style={{ backgroundColor: targetActionStatus === 'REVOKED' ? '#fee2e2' : '#ffedd5' }}>
              <span className="card-title" style={{ color: targetActionStatus === 'REVOKED' ? '#991b1b' : '#9a3412' }}>
                {targetActionStatus === 'REVOKED' ? <Ban size={18} /> : <Lock size={18} />}
                {selectedDoctorForAction.name} 의사 계정 {targetActionStatus === 'REVOKED' ? '권한 영구 파기 (REVOKE)' : '일시 이용 정지 (SUSPEND)'}
              </span>
            </div>
            <div className="card-body">
              <p style={{ fontSize: '0.85rem', color: 'var(--navy-700)', marginBottom: '12px' }}>
                {targetActionStatus === 'REVOKED'
                  ? '권한 파기 시 모든 활성 세션이 즉시 폐기되고 발급된 인증서가 파기되며 복구할 수 없습니다.'
                  : '이용 정지 시 의사는 정지가 해제될 때까지 전자서명을 수행할 수 없습니다.'}
              </p>

              <form onSubmit={handleExecuteStatusUpdate}>
                <div style={{ marginBottom: '16px' }}>
                  <label htmlFor="action-reason" style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, marginBottom: '4px' }}>
                    변경 사유 (Reason) <span style={{ color: 'var(--red-600)' }}>*</span>
                  </label>
                  <textarea
                    id="action-reason"
                    required
                    minLength={2}
                    maxLength={200}
                    rows={3}
                    placeholder="사유를 입력해주세요 (2~200자)"
                    value={statusReason}
                    onChange={(e) => setStatusReason(e.target.value)}
                    style={{ width: '100%', padding: '8px', borderRadius: '4px', border: '1px solid var(--navy-300)', fontSize: '0.85rem' }}
                    data-testid="status-change-reason-input"
                  />
                </div>

                {actionError && (
                  <div className="alert alert-error" role="alert" style={{ marginBottom: '16px' }}>
                    <AlertTriangle size={16} />
                    <span>{actionError}</span>
                  </div>
                )}

                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
                  <button
                    type="button"
                    className="btn btn-secondary"
                    onClick={() => {
                      setSelectedDoctorForAction(null);
                      setTargetActionStatus(null);
                    }}
                    disabled={actionProcessing}
                  >
                    취소
                  </button>
                  <button
                    type="submit"
                    className="btn"
                    style={{
                      backgroundColor: targetActionStatus === 'REVOKED' ? 'var(--red-600)' : '#ea580c',
                      color: 'white',
                    }}
                    disabled={actionProcessing || statusReason.trim().length < 2}
                    data-testid="modal-confirm-btn"
                  >
                    {actionProcessing ? '처리 중...' : targetActionStatus === 'REVOKED' ? '권한 영구 파기' : '이용 정지 처리'}
                  </button>
                </div>
              </form>
            </div>
          </div>
        </div>
      )}
      {/* Candidate Certificate Review Modal */}
      {reviewCandidateDoctor && selectedCandidate && (
        <div
          style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            backgroundColor: 'rgba(0, 0, 0, 0.5)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
            padding: '16px',
          }}
          data-testid="admin-cert-review-modal"
        >
          <div
            className="card"
            style={{
              width: '100%',
              maxWidth: '640px',
              backgroundColor: 'white',
              boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.1)',
              maxHeight: '90vh',
              overflowY: 'auto',
            }}
          >
            <div className="card-header" style={{ backgroundColor: 'var(--teal-50)' }}>
              <span className="card-title" style={{ color: 'var(--teal-900)' }}>
                <Award size={18} style={{ color: 'var(--teal-700)' }} />
                후보 인증서 면허·신원 대조 검토 (Candidate Certificate Review)
              </span>
            </div>
            <div className="card-body">
              <div
                style={{
                  padding: '10px 12px',
                  backgroundColor: 'var(--navy-50)',
                  borderRadius: '6px',
                  marginBottom: '12px',
                  fontSize: '0.85rem',
                  lineHeight: '1.5',
                }}
              >
                <div><strong>대상 의사:</strong> {reviewCandidateDoctor.name} (면허번호: {reviewCandidateDoctor.licenseNumber})</div>
                <div><strong>소속 센터:</strong> {reviewCandidateDoctor.centerName}</div>
                <div><strong>이메일:</strong> {reviewCandidateDoctor.googleEmail || 'PACS 계정 등록'}</div>
              </div>

              <div style={{ marginBottom: '12px', fontSize: '0.8rem', border: '1px solid var(--navy-200)', borderRadius: '6px', padding: '10px 12px' }}>
                <h5 style={{ margin: '0 0 8px 0', fontSize: '0.85rem', fontWeight: 600, color: 'var(--navy-900)' }}>
                  검증된 후보 인증서 공개 메타데이터:
                </h5>
                <div style={{ display: 'grid', gridTemplateColumns: '110px 1fr', gap: '6px' }}>
                  <span style={{ color: 'var(--navy-500)' }}>Subject DN:</span>
                  <code style={{ wordBreak: 'break-all' }}>{selectedCandidate.subjectDn}</code>

                  <span style={{ color: 'var(--navy-500)' }}>Issuer DN:</span>
                  <code style={{ wordBreak: 'break-all' }}>{selectedCandidate.issuerDn}</code>

                  <span style={{ color: 'var(--navy-500)' }}>Serial Number:</span>
                  <code>{selectedCandidate.serialNumber}</code>

                  <span style={{ color: 'var(--navy-500)' }}>SHA-256 지문:</span>
                  <code style={{ wordBreak: 'break-all' }}>{selectedCandidate.fingerprintSha256}</code>

                  <span style={{ color: 'var(--navy-500)' }}>알고리즘 / 기간:</span>
                  <span>{selectedCandidate.keyAlgorithm || selectedCandidate.algorithm} ({selectedCandidate.notBefore.slice(0, 10)} ~ {selectedCandidate.notAfter.slice(0, 10)})</span>

                  <span style={{ color: 'var(--navy-500)' }}>외부 CA 폐기상태:</span>
                  <span>{renderCaBadge(selectedCandidate.caStatus || selectedCandidate.externalCaStatus || 'GOOD')} (CRL/OCSP 실시간 검증 결과)</span>
                </div>
              </div>

              <div className="alert alert-info" style={{ marginBottom: '12px', fontSize: '0.8rem' }}>
                <div>
                  <strong>보안 원칙:</strong> 인증서의 서명 및 인증기관 체인이 유효하더라도,
                  관리자가 실제 등록된 의사 면허 정보 및 신원 확인 서류와의 일치 여부를 직접 대조·승인해야만 인증서가 활성화(ACTIVE)됩니다.
                </div>
              </div>

              {selectedCandidate.medicalCredentialsRequired && <section className="alert alert-info" data-testid="admin-medical-credentials">
                <strong>의료인 자격 대조</strong>
                <p>의사면허번호: {selectedCandidate.medicalLicenseNumber || '미제출'}</p>
                <p>전문의 자격: {selectedCandidate.specialist == null ? '미제출' : selectedCandidate.specialist
                  ? `${selectedCandidate.specialty} / ${selectedCandidate.specialistQualificationNumber}` : '해당 없음'}</p>
                <p>제출 상태: {selectedCandidate.medicalCredentialsSubmittedAt ? '제출 완료 · 증빙 대조 필요' : '제출 대기 · 승인 불가'}</p>
              </section>}
              <form onSubmit={(e) => { e.preventDefault(); handleReviewCandidateSubmit('APPROVED'); }}>
                <div style={{ marginBottom: '12px' }}>
                  <label htmlFor="review-evidence" style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, marginBottom: '4px' }}>
                    대조 증빙 참조 (Evidence Reference) <span style={{ color: 'var(--red-600)' }}>*</span>
                  </label>
                  <input
                    id="review-evidence"
                    type="text"
                    required
                    placeholder="예: LIC-VERIF-2026-089 또는 원내 대조 공문 번호"
                    value={reviewEvidenceRef}
                    onChange={(e) => setReviewEvidenceRef(e.target.value)}
                    style={{ width: '100%', padding: '8px', borderRadius: '4px', border: '1px solid var(--navy-300)', fontSize: '0.85rem' }}
                    data-testid="admin-cert-review-evidence-input"
                  />
                </div>

                <div style={{ marginBottom: '12px' }}>
                  <label htmlFor="review-note" style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, marginBottom: '4px' }}>
                    검토 의견 / 메모 (선택사항)
                  </label>
                  <input
                    id="review-note"
                    type="text"
                    placeholder="특이사항 또는 검토 의견"
                    value={reviewNote}
                    onChange={(e) => setReviewNote(e.target.value)}
                    style={{ width: '100%', padding: '8px', borderRadius: '4px', border: '1px solid var(--navy-300)', fontSize: '0.85rem' }}
                    data-testid="admin-cert-review-note-input"
                  />
                </div>

                <div
                  style={{
                    padding: '10px 12px',
                    backgroundColor: 'var(--navy-50)',
                    borderRadius: '6px',
                    border: '1px solid var(--navy-300)',
                    marginBottom: '14px',
                  }}
                >
                  <label style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', cursor: 'pointer' }}>
                    <input
                      type="checkbox"
                      checked={reviewConfirmed}
                      onChange={(e) => setReviewConfirmed(e.target.checked)}
                      data-testid="admin-cert-review-checkbox"
                      style={{ marginTop: '2px', cursor: 'pointer' }}
                    />
                    <span style={{ fontSize: '0.82rem', color: 'var(--navy-900)', fontWeight: 600 }}>
                      <FileCheck2 size={16} style={{ display: 'inline', verticalAlign: '-3px', marginRight: '4px', color: 'var(--teal-700)' }} />
                      위 인증서 소유자의 신원·의사면허 및 제출된 전문의 자격번호·전문과목을 증빙과 대조하여 확인했습니다.
                    </span>
                  </label>
                </div>

                {reviewError && (
                  <div className="alert alert-error" role="alert" style={{ marginBottom: '14px' }}>
                    <AlertTriangle size={16} />
                    <span>{reviewError}</span>
                  </div>
                )}

                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
                  <button
                    type="button"
                    className="btn btn-secondary"
                    onClick={() => {
                      setReviewCandidateDoctor(null);
                      setSelectedCandidate(null);
                    }}
                    disabled={reviewProcessing}
                  >
                    닫기
                  </button>
                  <button
                    type="button"
                    className="btn btn-outline"
                    onClick={() => handleReviewCandidateSubmit('REJECTED')}
                    style={{ color: 'var(--red-600)', borderColor: 'var(--red-300)' }}
                    disabled={reviewProcessing}
                    data-testid="admin-cert-review-reject-btn"
                  >
                    {reviewProcessing ? '처리 중...' : '검토 반려 (REJECT)'}
                  </button>
                  <button
                    type="submit"
                    className="btn btn-primary"
                    disabled={reviewProcessing || !reviewConfirmed || !reviewEvidenceRef.trim() || Boolean(selectedCandidate.medicalCredentialsRequired && !selectedCandidate.medicalCredentialsSubmittedAt)}
                    data-testid="admin-cert-review-approve-btn"
                  >
                    {reviewProcessing ? '처리 중...' : '신원·면허 일치 승인 (APPROVE)'}
                  </button>
                </div>
              </form>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
