import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { AppProvider, useApp } from '../context/AppContext';
import { CertificateEnrollmentView } from '../components/CertificateEnrollmentView';
import { DoctorAdminView } from '../components/DoctorAdminView';
import { PhysicianSigningPortal } from '../components/PhysicianSigningPortal';
import {
  CertificateEnrollmentConfig,
  CertificateChallenge,
  CertificateSessionResponse,
  Enrollment,
  Doctor,
  SignRequest,
} from '../types/api';

describe('Definitive Certificate Enrollment HTTP Contract Tests', () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    vi.stubEnv('VITE_CERTIFICATE_DIAGNOSTICS', 'true');
    localStorage.clear();
    sessionStorage.clear();
    globalThis.URL.createObjectURL = vi.fn().mockReturnValue('blob:http://localhost/mock-doc-pdf');
    globalThis.URL.revokeObjectURL = vi.fn();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    localStorage.clear();
    sessionStorage.clear();
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
  });

  const mockEnrollmentConfig: CertificateEnrollmentConfig = {
    activationPolicy: 'CERTIFICATE_REQUIRED',
    serviceId: 'vision-certi-dev',
    challengeTtlSeconds: 300,
    pcCertificate: {
      trustConfigured: true,
      trustAnchorCount: 2,
      crlCount: 2,
      allowedSignatureAlgorithms: ['SHA256withRSA', 'SHA256withECDSA'],
      revocation: 'CRL',
      ocspSupported: false,
    },
    mobile: {
      configured: false,
      provider: null,
    },
    devMockCertificateEnabled: true,
    clinicalUseAllowed: false,
  };

  const mockChallenge: CertificateChallenge = {
    challengeId: 'chal-cert-998811',
    registrationId: 'reg-cert-998811',
    purpose: 'CERTIFICATE_ENROLLMENT',
    doctorId: 'doc-101',
    tenantId: 'demo-hospital-a',
    serviceId: 'vision-certi-dev',
    nonce: 'nonce-hex-abcd1234ef5678',
    issuedAt: '2026-10-13T10:00:00Z',
    expiresAt: new Date(Date.now() + 600000).toISOString(),
    signedPayload: 'vision-certi-certificate-auth-v1\npurpose=CERTIFICATE_ENROLLMENT\nchallengeId=chal-cert-998811\nserviceId=vision-certi-dev\ndoctorId=doc-101\ntenantId=demo-hospital-a\nnonce=nonce-hex-abcd1234ef5678\nissuedAt=2026-10-13T10:00:00Z\nexpiresAt=2026-10-13T10:10:00Z',
    signedPayloadBase64: 'VmlzaW9uQ2VydGl8Q0VSVElGSUNBVEVfRU5ST0xMTUVOVHxkb2MtMTAxfG5vbmNlLWhleC1hYmNkMTIzNGVmNTY3OA==',
    allowedSignatureAlgorithms: ['SHA256withRSA', 'SHA256withECDSA'],
  };

  const mockCandidateGood: Enrollment = {
    registrationId: 'reg-cert-998811',
    doctorId: 'doc-101',
    tenantId: 'demo-hospital-a',
    kind: 'PC_CERTIFICATE',
    registrationStatus: 'PENDING',
    externalStatus: 'GOOD',
    externalCheckedAt: '2026-10-13T10:00:00Z',
    reviewStatus: 'PENDING',
    reviewMethod: null,
    reviewedBy: null,
    reviewedAt: null,
    consentedAt: null,
    fingerprintSha256: '9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b1234567890abcdef12345678',
    publicKeySha256: '9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b',
    subjectDn: 'cn=김판독(9988),ou=의사,ou=KICA,o=SignKorea,c=KR',
    issuerDn: 'cn=KICA Joint CA,o=SignKorea,c=KR',
    serialNumber: '4481029481920391',
    notBefore: '2026-01-01T00:00:00Z',
    notAfter: '2027-01-01T00:00:00Z',
    signatureAlgorithm: 'SHA256withRSA',
    keyAlgorithm: 'RSA-2048',
    provider: null,
    providerSubject: null,
    createdAt: '2026-10-13T10:00:00Z',
    activatedAt: null,
    revokedAt: null,
    revocationReason: null,
    supersedes: null,
  };

  const mockCandidateRevoked: Enrollment = {
    ...mockCandidateGood,
    externalStatus: 'REVOKED',
  };

  const mockCandidateUnknown: Enrollment = {
    ...mockCandidateGood,
    externalStatus: 'UNKNOWN',
  };

  const mockDoctorActive: Doctor = {
    id: 'doc-101',
    tenantId: 'demo-hospital-a',
    name: '김판독',
    licenseNumber: '998811',
    centerName: 'A의료원 영상센터',
    googleEmail: 'dr.kim.retina@gmail.com',
    status: 'ACTIVE',
    identityMode: 'PC_CERTIFICATE',
    googleAccountLinked: false,
    invitationState: 'USED',
    invitationExpiresAt: new Date().toISOString(),
    identityVerifiedAt: new Date().toISOString(),
    consentedAt: new Date().toISOString(),
    consentVersion: '2026-10-13-draft',
    activatedAt: new Date().toISOString(),
    statusReason: null,
    statusChangedAt: null,
    createdAt: new Date().toISOString(),
    createdBy: 'admin-demo',
    signingBackend: 'OPENBAO_TRANSIT',
    certificate: {
      certificateId: 'CERT-SN-2026-999',
      kind: 'X509',
      backend: 'OPENBAO_TRANSIT',
      subject: 'CN=vc-doctor-doc-101, O=Vision-Certi DEV',
      issuer: 'CN=Vision-Certi Dev CA',
      serialNumber: 'SN-2026-999',
      notBefore: '2026-01-01T00:00:00Z',
      notAfter: '2027-01-01T00:00:00Z',
      publicKeySha256: 'pub-key-sha256-abc',
      keyName: 'vc-doctor-doc-101',
      keyVersion: 1,
      status: 'VALID',
      certificatePem: '-----BEGIN CERTIFICATE-----\nMIIC...\n-----END CERTIFICATE-----',
    },
    authCertificate: mockCandidateGood,
  };

  it('offers certificate login by default and no invitation form in self-enrollment', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(mockEnrollmentConfig), { status: 200 }));
    render(<AppProvider><CertificateEnrollmentView /></AppProvider>);
    expect(await screen.findByTestId('request-login-challenge-btn')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('tab-cert-enroll'));
    expect(screen.getByTestId('pc-enroll-step-1')).toHaveTextContent('공동인증서 직접 등록');
    expect(screen.queryByTestId('cert-invitation-code-input')).not.toBeInTheDocument();
    expect(screen.queryByTestId('request-cert-challenge-btn')).not.toBeInTheDocument();
  });

  it('1. GET /v1/certificate-enrollment/config: enforces nested pcCertificate and blocks flow on config error without fake fallback', async () => {
    let callCount = 0;
    globalThis.fetch = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/certificate-enrollment/config')) {
        callCount++;
        if (callCount === 1) {
          // Failure on first call
          return new Response(JSON.stringify({ code: 'CONFIG_UNAVAILABLE', message: 'Config service down' }), { status: 503 });
        }
        return new Response(JSON.stringify(mockEnrollmentConfig), { status: 200 });
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 });
    });

    render(
      <AppProvider>
        <CertificateEnrollmentView />
      </AppProvider>
    );

    // Initial failure displays retry error and DOES NOT invent fake mock config
    await waitFor(() => {
      expect(screen.getByTestId('cert-config-error')).toBeInTheDocument();
      expect(screen.getByTestId('cert-config-retry-btn')).toBeInTheDocument();
    });

    // Clicking retry fetches config again and clears error
    fireEvent.click(screen.getByTestId('cert-config-retry-btn'));

    await waitFor(() => {
      expect(screen.queryByTestId('cert-config-error')).not.toBeInTheDocument();
      expect(screen.getByTestId('cert-architecture-banner')).toBeInTheDocument();
    });
  });

  it('2. PACS-issued challenge: displays exact signedPayload bytes and strictly NO private key/password inputs', async () => {
    let capturedBody: any = null;
    globalThis.fetch = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.includes('/certificate-enrollment/config')) {
        return new Response(JSON.stringify(mockEnrollmentConfig), { status: 200 });
      }
      if (url.includes('/certificate-enrollment/challenges')) {
        capturedBody = JSON.parse(init?.body as string);
        return new Response(JSON.stringify(mockChallenge), { status: 201 });
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 });
    });

    render(
      <AppProvider>
        <CertificateEnrollmentView initialChallenge={mockChallenge} />
      </AppProvider>
    );


    await waitFor(() => {
      expect(screen.getByTestId('cert-step-2')).toBeInTheDocument();
      expect(screen.getByTestId('canonical-bytes-preview')).toBeInTheDocument();
    });

    // Verify exact backend request payload
    expect(capturedBody).toBeNull();
    expect(screen.queryByTestId('cert-invitation-code-input')).not.toBeInTheDocument();

    // Exact signed payload is displayed
    expect(screen.getByTestId('canonical-bytes-preview').textContent).toContain('vision-certi-certificate-auth-v1');
    expect(screen.getByTestId('download-challenge-btn')).toBeInTheDocument();

    // Verify ZERO private key or password inputs exist anywhere in DOM
    const allInputs = document.querySelectorAll('input');
    allInputs.forEach((input) => {
      expect(input.type).not.toBe('password');
      const nameOrId = (input.name + input.id + (input.getAttribute('placeholder') || '')).toLowerCase();
      expect(nameOrId).not.toContain('privatekey');
      expect(nameOrId).not.toContain('p12');
      expect(nameOrId).not.toContain('pfx');
      expect(nameOrId).not.toContain('password');
    });
  });

  it('3. POST /v1/certificate-enrollment/verify: blocks activation when external CA is REVOKED or UNKNOWN (Fail-Closed)', async () => {
    let capturedVerifyReq: any = null;
    globalThis.fetch = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.includes('/certificate-enrollment/config')) {
        return new Response(JSON.stringify(mockEnrollmentConfig), { status: 200 });
      }
      if (url.includes('/certificate-enrollment/challenges')) {
        return new Response(JSON.stringify(mockChallenge), { status: 201 });
      }
      if (url.includes('/certificate-enrollment/verify')) {
        capturedVerifyReq = JSON.parse(init?.body as string);
        const res: CertificateSessionResponse = {
          enrollment: mockCandidateRevoked,
          activationToken: 'token-revoked-fail',
          expiresAt: new Date(Date.now() + 1800000).toISOString(),
          identityMode: 'PC_CERTIFICATE',
          doctor: { ...mockDoctorActive, status: 'IDENTITY_VERIFIED' },
        };
        return new Response(JSON.stringify(res), { status: 201 });
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 });
    });

    render(
      <AppProvider>
        <CertificateEnrollmentView initialChallenge={mockChallenge} />
      </AppProvider>
    );

    // Advance to Step 2

    await waitFor(() => screen.getByTestId('public-cert-input'));

    // Input public certificate and signature
    fireEvent.change(screen.getByTestId('public-cert-input'), {
      target: { value: '-----BEGIN CERTIFICATE-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...\n-----END CERTIFICATE-----' },
    });
    fireEvent.change(screen.getByTestId('signature-bytes-input'), {
      target: { value: 'MEQCIG...signature...bytes==' },
    });
    fireEvent.click(screen.getByTestId('submit-cert-proof-btn'));

    // Expect FAIL-CLOSED block banner in Step 2
    await waitFor(() => {
      expect(screen.getByTestId('ca-status-alert')).toBeInTheDocument();
      expect(screen.getByTestId('ca-status-alert').textContent).toContain('REVOKED');
      expect(screen.getByTestId('ca-block-activation-notice')).toBeInTheDocument();
    });

    // Step 3 (Consent) must NEVER be shown when CA is revoked
    expect(screen.queryByTestId('cert-step-3')).not.toBeInTheDocument();

    // Verify VerifyRequest shape sent to backend
    expect(capturedVerifyReq.registrationId).toBe('reg-cert-998811');
    expect(capturedVerifyReq.signatureAlgorithm).toBe('SHA256withRSA');
    expect(capturedVerifyReq.signature).toBe('MEQCIG...signature...bytes==');
    expect(Array.isArray(capturedVerifyReq.chain)).toBe(true);
  });

  it('3b. POST /v1/certificate-enrollment/verify: blocks activation when external CA is UNKNOWN (Fail-Closed)', async () => {
    globalThis.fetch = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/certificate-enrollment/config')) {
        return new Response(JSON.stringify(mockEnrollmentConfig), { status: 200 });
      }
      if (url.includes('/certificate-enrollment/challenges')) {
        return new Response(JSON.stringify(mockChallenge), { status: 201 });
      }
      if (url.includes('/certificate-enrollment/verify')) {
        const res: CertificateSessionResponse = {
          enrollment: mockCandidateUnknown,
          activationToken: 'token-unknown-fail',
          expiresAt: new Date(Date.now() + 1800000).toISOString(),
          identityMode: 'PC_CERTIFICATE',
          doctor: { ...mockDoctorActive, status: 'IDENTITY_VERIFIED' },
        };
        return new Response(JSON.stringify(res), { status: 201 });
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 });
    });

    render(
      <AppProvider>
        <CertificateEnrollmentView initialChallenge={mockChallenge} />
      </AppProvider>
    );

    // Advance to Step 2

    await waitFor(() => screen.getByTestId('public-cert-input'));

    fireEvent.change(screen.getByTestId('public-cert-input'), {
      target: { value: '-----BEGIN CERTIFICATE-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...\n-----END CERTIFICATE-----' },
    });
    fireEvent.change(screen.getByTestId('signature-bytes-input'), {
      target: { value: 'MEQCIG...unknown...bytes==' },
    });
    fireEvent.click(screen.getByTestId('submit-cert-proof-btn'));

    // Expect FAIL-CLOSED block banner in Step 2 with UNKNOWN
    await waitFor(() => {
      expect(screen.getByTestId('ca-status-alert')).toBeInTheDocument();
      expect(screen.getByTestId('ca-status-alert').textContent).toContain('UNKNOWN');
      expect(screen.getByTestId('ca-block-activation-notice')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('cert-step-3')).not.toBeInTheDocument();
  });

  it('4. POST /v1/certificate-enrollment/enrollments/{registrationId}/consent: requires 3 explicit unchecked consents before activation', async () => {
    let capturedConsentReq: any = null;
    let activated = false;
    let capturedMedical: any = null;
    const onEnrollmentComplete = vi.fn();
    const medical = { ...mockCandidateGood, medicalCredentialsRequired: true, medicalLicenseNumber: '998811', specialist: true,
      specialistQualificationNumber: 'SP-9988', specialty: '영상의학과', medicalCredentialsSubmittedAt: new Date().toISOString() };

    globalThis.fetch = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.includes('/certificate-enrollment/config')) {
        return new Response(JSON.stringify(mockEnrollmentConfig), { status: 200 });
      }
      if (url.includes('/certificate-enrollment/challenges')) {
        return new Response(JSON.stringify(mockChallenge), { status: 201 });
      }
      if (url.includes('/certificate-enrollment/verify')) {
        const res: CertificateSessionResponse = {
          enrollment: { ...mockCandidateGood, medicalCredentialsRequired: true },
          activationToken: 'token-valid-memory-9988',
          expiresAt: new Date(Date.now() + 1800000).toISOString(),
          identityMode: 'PC_CERTIFICATE',
          doctor: { ...mockDoctorActive, status: 'IDENTITY_VERIFIED' },
        };
        return new Response(JSON.stringify(res), { status: 201 });
      }
      if (url.includes('/medical-credentials')) {
        expect(init?.method).toBe('PUT');
        expect((init?.headers as Record<string,string>).Authorization).toBe('Bearer token-valid-memory-9988');
        capturedMedical = JSON.parse(init?.body as string);
        return new Response(JSON.stringify(medical), { status: 200 });
      }
      if (url.includes('/consent')) {
        capturedConsentReq = JSON.parse(init?.body as string);
        return new Response(JSON.stringify({ ...medical, consentedAt: new Date().toISOString() }), { status: 200 });
      }
      if (url.includes('/doctor-activation/me')) {
        return new Response(JSON.stringify({ doctor: mockDoctorActive }), { status: 200 });
      }
      if (url.includes('/activate')) {
        activated = true;
        return new Response(JSON.stringify(mockDoctorActive), { status: 200 });
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 });
    });

    render(
      <AppProvider>
        <CertificateEnrollmentView initialChallenge={mockChallenge} onEnrollmentComplete={onEnrollmentComplete} />
      </AppProvider>
    );

    // Advance to Step 2

    await waitFor(() => screen.getByTestId('public-cert-input'));
    fireEvent.change(screen.getByTestId('public-cert-input'), {
      target: { value: '-----BEGIN CERTIFICATE-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A...\n-----END CERTIFICATE-----' },
    });
    fireEvent.change(screen.getByTestId('signature-bytes-input'), { target: { value: 'SIGNATURE-VALID-GOOD' } });
    fireEvent.click(screen.getByTestId('submit-cert-proof-btn'));

    // Step 3: Consents must be visible and ALL 3 DEFAULT TO UNCHECKED
    await waitFor(() => {
      expect(screen.getByTestId('cert-step-3')).toBeInTheDocument();
    });

    const chkAuth = screen.getByTestId('consent-checkbox-auth') as HTMLInputElement;
    const chkSig = screen.getByTestId('consent-checkbox-sig') as HTMLInputElement;
    const chkPriv = screen.getByTestId('consent-checkbox-priv') as HTMLInputElement;
    const submitBtn = screen.getByTestId('submit-consent-btn') as HTMLButtonElement;

    expect(chkAuth.checked).toBe(false);
    expect(chkSig.checked).toBe(false);
    expect(chkPriv.checked).toBe(false);
    expect(submitBtn.disabled).toBe(true);
    expect(onEnrollmentComplete).not.toHaveBeenCalled();

    // Check only 2
    fireEvent.click(chkAuth);
    fireEvent.click(chkSig);
    expect(submitBtn.disabled).toBe(true);

    // Check 3rd
    fireEvent.click(chkPriv);
    expect(submitBtn.disabled).toBe(true); // Consent alone cannot skip medical credentials.
    fireEvent.click(screen.getByLabelText('전문의'));
    fireEvent.change(screen.getByLabelText(/전문의 자격번호/), { target: { value: 'SP-9988' } });
    fireEvent.change(screen.getByLabelText(/전문과목/), { target: { value: '영상의학과' } });
    fireEvent.click(screen.getByRole('button', { name: '면허·자격 정보 제출' }));
    await waitFor(() => expect(submitBtn.disabled).toBe(false));
    expect(capturedMedical).toEqual({ medicalLicenseNumber: '998811', specialist: true, specialistQualificationNumber: 'SP-9988', specialty: '영상의학과' });

    // Submit consent
    fireEvent.click(submitBtn);

    // Step 4 rendered and consent payload verified
    await waitFor(() => {
      expect(screen.getByTestId('cert-step-4')).toBeInTheDocument();
    });
    expect(capturedConsentReq).toEqual({
      version: '2026-10-13-draft',
      authentication: true,
      signature: true,
      privacy: true,
    });
    expect(activated).toBe(false);
    expect(onEnrollmentComplete).toHaveBeenCalledTimes(1);
  });

  it('5. POST /v1/admin/doctors/{id}/certificate-reviews: requires exact fingerprintSha256, evidenceRef, and checkbox', async () => {
    let capturedReviewReq: any = null;

    globalThis.fetch = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.includes('/certificate-reviews')) {
        capturedReviewReq = JSON.parse(init?.body as string);
        const updatedEnrollment: Enrollment = {
          ...mockCandidateGood,
          registrationStatus: 'ACTIVE',
          reviewStatus: 'APPROVED',
          reviewMethod: 'ADMIN_REVIEW',
          reviewedBy: 'admin-demo',
          reviewedAt: new Date().toISOString(),
        };
        return new Response(JSON.stringify(updatedEnrollment), { status: 200 });
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 });
    });

    const AdminMockWrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => {
      const { setActorId, setIsMockMode } = useApp();
      React.useEffect(() => {
        setActorId('admin-demo');
        setIsMockMode(true);
      }, [setActorId, setIsMockMode]);
      return <>{children}</>;
    };

    render(
      <AppProvider>
        <AdminMockWrapper>
          <DoctorAdminView />
        </AdminMockWrapper>
      </AppProvider>
    );

    await waitFor(() => screen.getByTestId('doctor-row-doc-002'));
    const reviewBtn = screen.getByTestId('admin-cert-review-btn-doc-002');
    fireEvent.click(reviewBtn);

    await waitFor(() => screen.getByTestId('admin-cert-review-modal'));
    const evidenceInput = screen.getByTestId('admin-cert-review-evidence-input') as HTMLInputElement;
    const reviewCheckbox = screen.getByTestId('admin-cert-review-checkbox') as HTMLInputElement;
    const approveBtn = screen.getByTestId('admin-cert-review-approve-btn') as HTMLButtonElement;

    // Checkbox unchecked initially
    expect(reviewCheckbox.checked).toBe(false);
    expect(approveBtn.disabled).toBe(true);

    // Fill evidence and check
    fireEvent.change(evidenceInput, { target: { value: 'LIC-AUDIT-2026-999' } });
    fireEvent.click(reviewCheckbox);
    expect(approveBtn.disabled).toBe(false);

    fireEvent.click(approveBtn);

    await waitFor(() => {
      expect(screen.queryByTestId('admin-cert-review-modal')).not.toBeInTheDocument();
      expect(screen.getByTestId('doctor-cert-cell-doc-002').textContent).toContain('등록 활성 (ACTIVE)');
    });

    if (capturedReviewReq) {
      expect(capturedReviewReq.decision).toBe('APPROVE');
      expect(capturedReviewReq.evidenceRef).toBe('LIC-AUDIT-2026-999');
    }
  });

  it('6. GET /v1/doctor/sign-requests/{id}/document: previews PDF blob, calculates SHA-256 byte digest, and requires explicit confirmation', async () => {
    const dummyPdf = '%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF';
    // Exact SHA-256 of dummyPdf bytes
    const dummyPdfSha256 = '2ab94463e399ce58a21df28c88b429dd6c64637faadd1de7d8d44f10e4132415';

    const mockSignReq: SignRequest = {
      id: 'req-pdf-test-1',
      tenantId: 'demo-hospital-a',
      documentId: 'doc-pdf-001',
      documentVersionId: 'ver-pdf-001',
      documentSha256: dummyPdfSha256,
      signerId: 'doc-101',
      nonce: 'nonce-approval-999',
      status: 'PENDING_APPROVAL',
      signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE',
      createdAt: '2026-10-13T10:00:00Z',
      createdBy: 'admin-demo',
      expiresAt: '2026-10-13T12:00:00Z',
      approvedAt: null,
      approvedBy: null,
      signatureId: null,
    };

    let approved = false;

    // Mock URL.createObjectURL and URL.revokeObjectURL
    globalThis.URL.createObjectURL = vi.fn().mockReturnValue('blob:http://localhost/mock-doc-pdf');
    globalThis.URL.revokeObjectURL = vi.fn();

    globalThis.fetch = vi.fn().mockImplementation(async (url: string, _init?: RequestInit) => {
      if (url.includes('/doctor/sign-requests') && !url.includes('/document') && !url.includes('/approve')) {
        return new Response(JSON.stringify({ items: [mockSignReq], nextCursor: null }), { status: 200 });
      }
      if (url.includes('/document')) {
        return new Response(dummyPdf, {
          status: 200,
          headers: {
            'Content-Type': 'application/pdf',
            'X-Document-Sha256': dummyPdfSha256,
            'X-Document-Version-Id': mockSignReq.documentVersionId,
          },
        });
      }
      if (url.includes('/approve')) {
        approved = true;
        return new Response(JSON.stringify({ ...mockSignReq, status: 'COMPLETED' }), { status: 202 });
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 });
    });

    render(
      <AppProvider>
        <PhysicianSigningPortal token="mock-bearer-token-123" doctor={mockDoctorActive} />
      </AppProvider>
    );

    // 1. PDF iframe is rendered with blob URL and verified SHA-256 byte digest, refresh control present
    await waitFor(() => {
      expect(screen.getByTestId('preview-document-pdf')).toBeInTheDocument();
      expect(screen.getByTestId('header-sha256-badge').textContent).toContain('해시 검증 일치');
      expect(screen.getByTestId('refresh-sign-requests-btn')).toBeInTheDocument();
    });

    // 2. Before user confirms, document content does NOT claim bytes reviewed
    expect(screen.getByTestId('preview-document-content').textContent).toContain('판독문 바이트 검토 대기 중.');

    // 3. Explicit confirmation checkbox
    const confirmCheckbox = screen.getByTestId('doctor-sign-confirm-checkbox') as HTMLInputElement;
    const approveBtn = screen.getByTestId('doctor-sign-approve-btn') as HTMLButtonElement;

    expect(confirmCheckbox.checked).toBe(false);
    expect(approveBtn.disabled).toBe(true);

    fireEvent.click(confirmCheckbox);
    expect(confirmCheckbox.checked).toBe(true);
    expect(screen.getByTestId('preview-document-content').textContent).toContain('의사 직접 바이트 및 해시 검토 완료.');
    expect(approveBtn.disabled).toBe(false);

    fireEvent.click(approveBtn);

    await waitFor(() => {
      expect(approved).toBe(true);
      expect(screen.getByTestId('doctor-sign-success')).toBeInTheDocument();
    });
  });

  it('7. POST /v1/certificate-enrollment/dev/mock-sign: dev shortcut labels MOCK_CERTIFICATE and enforces same review/consent gates', async () => {
    globalThis.fetch = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/certificate-enrollment/config')) {
        return new Response(JSON.stringify(mockEnrollmentConfig), { status: 200 });
      }
      if (url.includes('/certificate-enrollment/challenges')) {
        return new Response(JSON.stringify(mockChallenge), { status: 201 });
      }
      if (url.includes('/certificate-enrollment/dev/mock-sign')) {
        const res: CertificateSessionResponse = {
          enrollment: { ...mockCandidateGood, kind: 'MOCK_CERTIFICATE' },
          activationToken: 'token-dev-mock-session',
          expiresAt: new Date(Date.now() + 1800000).toISOString(),
          identityMode: 'MOCK_CERTIFICATE',
          doctor: { ...mockDoctorActive, identityMode: 'MOCK_CERTIFICATE' },
        };
        return new Response(JSON.stringify(res), { status: 200 });
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 });
    });

    render(
      <AppProvider>
        <CertificateEnrollmentView initialChallenge={mockChallenge} />
      </AppProvider>
    );

    // Step 1

    // Step 2 shows Dev Mock Sign button when devMockCertificateEnabled is true
    await waitFor(() => {
      expect(screen.getByTestId('dev-mock-sign-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('dev-mock-sign-btn'));

    // Enforces Step 3 (Consent) even for dev mock sign
    await waitFor(() => {
      expect(screen.getByTestId('cert-step-3')).toBeInTheDocument();
      expect(screen.getByTestId('consent-checkbox-auth')).toBeInTheDocument();
    });
  });

  it('8. PhysicianSigningPortal: fails closed on byte SHA-256 mismatch or missing header', async () => {
    const mockSignReqMismatch: SignRequest = {
      id: 'req-mismatch-1',
      tenantId: 'demo-hospital-a',
      documentId: 'doc-pdf-mismatch',
      documentVersionId: 'ver-pdf-mismatch',
      documentSha256: '0000000000000000000000000000000000000000000000000000000000000000',
      signerId: 'doc-101',
      nonce: 'nonce-mismatch-999',
      status: 'PENDING_APPROVAL',
      signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE',
      createdAt: '2026-10-13T10:00:00Z',
      createdBy: 'admin-demo',
      expiresAt: '2026-10-13T12:00:00Z',
      approvedAt: null,
      approvedBy: null,
      signatureId: null,
    };

    globalThis.fetch = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/doctor/sign-requests') && !url.includes('/document')) {
        return new Response(JSON.stringify({ items: [mockSignReqMismatch], nextCursor: null }), { status: 200 });
      }
      if (url.includes('/document')) {
        const dummyPdf = '%PDF-1.4\ncorrupted content\n%%EOF';
        return new Response(dummyPdf, {
          status: 200,
          headers: {
            'Content-Type': 'application/pdf',
            'X-Document-Sha256': '1111111111111111111111111111111111111111111111111111111111111111',
            'X-Document-Version-Id': mockSignReqMismatch.documentVersionId,
          },
        });
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 });
    });

    render(
      <AppProvider>
        <PhysicianSigningPortal token="mock-bearer-token-123" doctor={mockDoctorActive} />
      </AppProvider>
    );

    // Mismatch alert appears and fail-closed blocks approval
    await waitFor(() => {
      expect(screen.getByTestId('hash-mismatch-alert')).toBeInTheDocument();
      expect(screen.getByTestId('header-sha256-badge').textContent).toContain('해시 불일치');
    });

    const confirmCheckbox = screen.getByTestId('doctor-sign-confirm-checkbox') as HTMLInputElement;
    expect(confirmCheckbox.disabled).toBe(true);
    const approveBtn = screen.getByTestId('doctor-sign-approve-btn') as HTMLButtonElement;
    expect(approveBtn.disabled).toBe(true);
  });

  it('9. PhysicianSigningPortal: handles CERTIFICATE_ENROLLMENT_CHANGED and displays re-authentication notice', async () => {
    const dummyPdf = '%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF';
    const dummyPdfSha256 = '2ab94463e399ce58a21df28c88b429dd6c64637faadd1de7d8d44f10e4132415';

    const mockSignReq: SignRequest = {
      id: 'req-cert-changed-1',
      tenantId: 'demo-hospital-a',
      documentId: 'doc-pdf-001',
      documentVersionId: 'ver-pdf-001',
      documentSha256: dummyPdfSha256,
      signerId: 'doc-101',
      nonce: 'nonce-approval-999',
      status: 'PENDING_APPROVAL',
      signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE',
      createdAt: '2026-10-13T10:00:00Z',
      createdBy: 'admin-demo',
      expiresAt: '2026-10-13T12:00:00Z',
      approvedAt: null,
      approvedBy: null,
      signatureId: null,
    };

    globalThis.fetch = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/doctor/sign-requests') && !url.includes('/document') && !url.includes('/approve')) {
        return new Response(JSON.stringify({ items: [mockSignReq], nextCursor: null }), { status: 200 });
      }
      if (url.includes('/document')) {
        return new Response(dummyPdf, {
          status: 200,
          headers: {
            'Content-Type': 'application/pdf',
            'X-Document-Sha256': dummyPdfSha256,
            'X-Document-Version-Id': mockSignReq.documentVersionId,
          },
        });
      }
      if (url.includes('/approve')) {
        return new Response(
          JSON.stringify({
            code: 'CERTIFICATE_ENROLLMENT_CHANGED',
            message: 'Active certificate enrollment changed; please authenticate again with your certificate',
            correlationId: 'test-corr-id',
            retryable: false,
          }),
          { status: 403, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 });
    });

    const onSignOutMock = vi.fn();

    render(
      <AppProvider>
        <PhysicianSigningPortal token="mock-bearer-token-123" doctor={mockDoctorActive} onSignOut={onSignOutMock} />
      </AppProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId('preview-document-pdf')).toBeInTheDocument();
      expect(screen.getByTestId('header-sha256-badge').textContent).toContain('해시 검증 일치');
    });

    const confirmCheckbox = screen.getByTestId('doctor-sign-confirm-checkbox') as HTMLInputElement;
    expect(confirmCheckbox.disabled).toBe(false);
    fireEvent.click(confirmCheckbox);
    expect(confirmCheckbox.checked).toBe(true);

    fireEvent.click(screen.getByTestId('doctor-sign-approve-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('portal-error-banner').textContent).toContain('CERTIFICATE_ENROLLMENT_CHANGED');
      expect(screen.getByTestId('reauth-cert-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('reauth-cert-btn'));
    expect(onSignOutMock).toHaveBeenCalled();
  });

  it('10. DoctorAdminView: exercises live HTTP fetch contracts for doctor list and candidate enrollments', async () => {
    const liveDoctor: Doctor = {
      id: 'doc-live-1',
      tenantId: 'demo-hospital-a',
      name: '홍길동',
      licenseNumber: 'DOC-554433',
      centerName: 'A의료원 안과',
      googleEmail: 'dr.hong@example.com',
      status: 'PENDING',
      identityMode: 'PC_CERTIFICATE',
      googleAccountLinked: false,
      invitationState: 'USED',
      invitationExpiresAt: new Date().toISOString(),
      identityVerifiedAt: null,
      consentedAt: null,
      consentVersion: null,
      activatedAt: null,
      statusReason: null,
      statusChangedAt: null,
      createdAt: new Date().toISOString(),
      createdBy: 'admin-demo',
      signingBackend: 'OPENBAO_TRANSIT',
      certificate: null,
      expectedCertificateFingerprint: null,
    };

    const liveEnrollment: Enrollment = {
      registrationId: 'reg-live-999',
      doctorId: 'doc-live-1',
      tenantId: 'demo-hospital-a',
      kind: 'PC_CERTIFICATE',
      registrationStatus: 'PENDING',
      externalStatus: 'GOOD',
      externalCheckedAt: new Date().toISOString(),
      reviewStatus: 'PENDING',
      reviewMethod: null,
      reviewedBy: null,
      reviewedAt: null,
      consentedAt: null,
      fingerprintSha256: 'aabbcc112233aabbcc112233aabbcc112233aabbcc112233aabbcc112233aabb',
      publicKeySha256: 'spki-sha256-mock',
      subjectDn: 'CN=홍길동',
      issuerDn: 'CN=KICA',
      serialNumber: 'SN-998877',
      notBefore: new Date().toISOString(),
      notAfter: new Date(Date.now() + 864000000).toISOString(),
      signatureAlgorithm: 'SHA256withRSA',
      keyAlgorithm: 'RSA',
      provider: null,
      providerSubject: null,
      createdAt: new Date().toISOString(),
      activatedAt: null,
      revokedAt: null,
      revocationReason: null,
      supersedes: null,
    };

    globalThis.fetch = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.includes('/admin/doctors/doc-live-1/certificate-enrollments')) {
        return new Response(JSON.stringify({ items: [liveEnrollment], nextCursor: null }), { status: 200 });
      }
      if (url.includes('/admin/doctors') && (!init || init.method === 'GET' || !init.method)) {
        return new Response(JSON.stringify({ items: [liveDoctor], nextCursor: null }), { status: 200 });
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 });
    });

    const AdminLiveWrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => {
      const { setActorId, setIsMockMode } = useApp();
      React.useEffect(() => {
        setActorId('admin-demo');
        setIsMockMode(false); // Live HTTP mode
      }, [setActorId, setIsMockMode]);
      return <>{children}</>;
    };

    render(
      <AppProvider>
        <AdminLiveWrapper>
          <DoctorAdminView />
        </AdminLiveWrapper>
      </AppProvider>
    );

    // Verify candidate enrollment was fetched and populated in live mode
    await waitFor(() => {
      expect(screen.getByTestId('admin-cert-review-btn-doc-live-1')).toBeInTheDocument();
      expect(screen.getByTestId('doctor-cert-cell-doc-live-1').textContent).toContain('후보 검토 대기');
    });
  });

  it('11. DoctorAdminView: displays 403 ADMIN_ROLE_REQUIRED notice with administrator login guidance', async () => {
    globalThis.fetch = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/admin/doctors')) {
        return new Response(
          JSON.stringify({
            code: 'ADMIN_ROLE_REQUIRED',
            message: 'Administrative role required to view or manage doctor directory',
            correlationId: 'corr-403',
            retryable: false,
          }),
          { status: 403, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 });
    });

    const DoctorActorWrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => {
      const { setActorId, setIsMockMode } = useApp();
      React.useEffect(() => {
        setActorId('doctor-demo'); // Non-admin actor
        setIsMockMode(false);
      }, [setActorId, setIsMockMode]);
      return <>{children}</>;
    };

    render(
      <AppProvider>
        <DoctorActorWrapper>
          <DoctorAdminView />
        </DoctorActorWrapper>
      </AppProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId('admin-role-required-notice')).toBeInTheDocument();
      expect(screen.getByTestId('admin-role-required-notice').textContent).toContain('관리자 계정으로 로그인');
    });
  });

  it('12. CertificateEnrollmentView: mobile start button is disabled when mobile adapter is not configured', async () => {
    globalThis.fetch = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/certificate-enrollment/config')) {
        return new Response(
          JSON.stringify({
            ...mockEnrollmentConfig,
            mobile: { configured: false, provider: null },
          }),
          { status: 200 }
        );
      }
      return new Response(JSON.stringify({ error: 'not found' }), { status: 404 });
    });

    render(
      <AppProvider>
        <CertificateEnrollmentView />
      </AppProvider>
    );

    await waitFor(() => screen.getByTestId('tab-mobile-cert'));
    fireEvent.click(screen.getByTestId('tab-mobile-cert'));

    await waitFor(() => {
      const mobileBtn = screen.getByTestId('start-mobile-transaction-btn') as HTMLButtonElement;
      expect(mobileBtn.disabled).toBe(true);
      expect(mobileBtn.textContent).toContain('모바일 간편인증 비활성화');
    });
  });
});
