import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { AppProvider } from '../context/AppContext';
import { PhysicianSigningPortal } from '../components/PhysicianSigningPortal';
import { Header } from '../components/Header';
import { getLocalCertSdkAdapter } from '../services/localCertSdkAdapter';
import { SignRequest, Doctor, PacsReportSnapshot } from '../types/api';

const mockDoctor: Doctor = {
  id: 'doc-uuid-1',
  tenantId: 'demo-hospital-a',
  name: '홍길동',
  licenseNumber: 'DOC-12345',
  centerName: '본원 영상의학과',
  googleEmail: 'hong@hospital.org',
  status: 'ACTIVE',
  identityMode: 'PC_CERTIFICATE',
  googleAccountLinked: false,
  invitationState: 'USED',
  invitationExpiresAt: '2026-10-15T00:00:00Z',
  identityVerifiedAt: '2026-10-13T00:00:00Z',
  consentedAt: '2026-10-13T00:00:00Z',
  consentVersion: 'v1.0',
  activatedAt: '2026-10-13T00:00:00Z',
  statusReason: null,
  statusChangedAt: null,
  createdAt: '2026-10-13T00:00:00Z',
  createdBy: 'admin',
  signingBackend: 'OPENBAO_TRANSIT',
  certificate: null,
};

const samplePacsSnapshot: PacsReportSnapshot = {
  schema: 'saintview-pacs-report-snapshot/v1',
  tenantId: 'demo-hospital-a',
  doctorId: 'doc-uuid-1',
  pacs: {
    clientId: 'pacs-sv70',
    userIdx: 101,
    userId: 'doctor_hong',
    userName: '홍길동',
    doctorLicenseNumber: 'DOC-12345',
  },
  study: {
    externalStudyId: 'pacs-sv70:study:555',
    studyIdx: 555,
    accessionNo: 'ACC-20261013-001',
    patientId: 'P12345678',
    patientName: '김환자',
    modality: 'CT',
    bodyPart: 'CHEST',
    description: 'CT Chest Contrast',
    studyDate: '2026-10-13 14:30:00',
    hospitalIdx: 1,
    hospitalName: 'A 의료원 본원',
  },
  report: {
    externalReportId: 'pacs-sv70:report:999',
    revision: 1,
    approvalStatus: 'A',
    contentSha256: 'a1b2c3d4e5f60000111122223333444455556666777788889999aaaabbbbcccc',
    reading: '양측 폐야에 특이소견 관찰되지 않음. 종격동 림프절 비대 없음.',
    conclusion: 'No acute cardiopulmonary disease. Normal Chest CT.',
    studyComment: '조영제 부작용 없음',
    cvr: 'N',
  },
};

describe('SV70 PACS & Production Runtime Integration Tests', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    globalThis.URL.createObjectURL = vi.fn().mockReturnValue('blob:mock-url');
    globalThis.URL.revokeObjectURL = vi.fn();
  });

  it('renders structured JSON PACS report preview and computes exact byte SHA-256', async () => {
    const rawJsonText = JSON.stringify(samplePacsSnapshot);
    const encoder = new TextEncoder();
    const rawBytes = encoder.encode(rawJsonText);

    // Compute expected SHA-256
    const hashBuf = await crypto.subtle.digest('SHA-256', rawBytes);
    const expectedSha256 = Array.from(new Uint8Array(hashBuf))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');

    const mockSignRequest: SignRequest = {
      id: 'req-pacs-1',
      tenantId: 'demo-hospital-a',
      documentId: 'doc-pacs-1',
      documentVersionId: 'ver-pacs-1',
      documentSha256: expectedSha256,
      signerId: 'doc-uuid-1',
      status: 'PENDING_APPROVAL',
      signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE',
      createdBy: 'system',
      approvedAt: null,
      approvedBy: null,
      signatureId: null,
      nonce: 'test-nonce-1234567890',
      createdAt: '2026-10-13T10:00:00Z',
      expiresAt: '2026-10-13T10:15:00Z',
    };

    // Mock URL.createObjectURL and URL.revokeObjectURL
    globalThis.URL.createObjectURL = vi.fn().mockReturnValue('blob:mock-url');
    globalThis.URL.revokeObjectURL = vi.fn();

    // Mock fetch for sign requests and document
    globalThis.fetch = vi.fn().mockImplementation((url: string, _opts?: RequestInit) => {
      if (url.includes('/doctor/sign-requests/req-pacs-1/document')) {
        return Promise.resolve(
          new Response(rawJsonText, {
            status: 200,
            headers: {
              'Content-Type': 'application/vnd.saintview.pacs-report+json;version=1',
              'X-Document-Sha256': expectedSha256,
              'X-Document-Version-Id': 'ver-pacs-1',
            },
          })
        );
      }
      if (url.includes('/doctor/sign-requests')) {
        return Promise.resolve(new Response(JSON.stringify({ items: [mockSignRequest], total: 1 }), { status: 200 }));
      }
      return Promise.resolve(new Response(JSON.stringify({}), { status: 200 }));
    });

    render(
      <AppProvider>
        <PhysicianSigningPortal token="mock-doctor-bearer-token" doctor={mockDoctor} />
      </AppProvider>
    );

    // Verify structured PACS report fields are displayed
    await waitFor(() => {
      expect(screen.getByTestId('pacs-report-structured-preview')).toBeInTheDocument();
    });

    expect(screen.getByTestId('pacs-patient-name')).toHaveTextContent('김환자');
    expect(screen.getByTestId('pacs-report-reading')).toHaveTextContent('양측 폐야에 특이소견 관찰되지 않음');
    expect(screen.getByTestId('pacs-report-conclusion')).toHaveTextContent('No acute cardiopulmonary disease');
    expect(screen.getByTestId('header-sha256-badge')).toHaveTextContent('✓ 해시 검증 일치');
  });

  it('requires explicit review attestation and sends REVIEWED_EXACT_REPORT_CONTENT on approval', async () => {
    const rawJsonText = JSON.stringify(samplePacsSnapshot);
    const rawBytes = new TextEncoder().encode(rawJsonText);
    const hashBuf = await crypto.subtle.digest('SHA-256', rawBytes);
    const expectedSha256 = Array.from(new Uint8Array(hashBuf))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');

    const mockSignRequest: SignRequest = {
      id: 'req-pacs-2',
      tenantId: 'demo-hospital-a',
      documentId: 'doc-pacs-2',
      documentVersionId: 'ver-pacs-2',
      documentSha256: expectedSha256,
      signerId: 'doc-uuid-1',
      status: 'PENDING_APPROVAL',
      signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE',
      createdBy: 'system',
      approvedAt: null,
      approvedBy: null,
      signatureId: null,
      nonce: 'nonce-for-approval',
      createdAt: '2026-10-13T10:00:00Z',
      expiresAt: '2026-10-13T10:15:00Z',
    };

    let approvePayload: any = null;

    globalThis.fetch = vi.fn().mockImplementation((url: string, opts?: RequestInit) => {
      if (url.includes('/doctor/sign-requests/req-pacs-2/approve')) {
        approvePayload = JSON.parse(opts?.body as string);
        return Promise.resolve(new Response(JSON.stringify({ ...mockSignRequest, status: 'COMPLETED' }), { status: 200 }));
      }
      if (url.includes('/doctor/sign-requests/req-pacs-2/document')) {
        return Promise.resolve(
          new Response(rawJsonText, {
            status: 200,
            headers: {
              'Content-Type': 'application/vnd.saintview.pacs-report+json;version=1',
              'X-Document-Sha256': expectedSha256,
            },
          })
        );
      }
      if (url.includes('/doctor/sign-requests')) {
        return Promise.resolve(new Response(JSON.stringify({ items: [mockSignRequest], total: 1 }), { status: 200 }));
      }
      return Promise.resolve(new Response(JSON.stringify({}), { status: 200 }));
    });

    render(
      <AppProvider>
        <PhysicianSigningPortal token="mock-doctor-bearer-token" doctor={mockDoctor} />
      </AppProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId('pacs-report-structured-preview')).toBeInTheDocument();
    });

    const checkbox = screen.getByTestId('doctor-sign-confirm-checkbox');
    const approveBtn = screen.getByTestId('doctor-sign-approve-btn');

    expect(approveBtn).toBeDisabled();

    // Check confirmation checkbox
    fireEvent.click(checkbox);
    expect(approveBtn).not.toBeDisabled();

    // Click approve
    fireEvent.click(approveBtn);

    await waitFor(() => {
      expect(approvePayload).not.toBeNull();
    });

    expect(approvePayload.documentSha256).toBe(expectedSha256);
    expect(approvePayload.nonce).toBe('nonce-for-approval');
    expect(approvePayload.reviewAttestation).toBe('REVIEWED_EXACT_REPORT_CONTENT');
  });

  it('generates PACS handoff code and delivers to popup opener via postMessage with nonce correlation', async () => {
    let handoffRequested = false;

    globalThis.fetch = vi.fn().mockImplementation((url: string, opts?: RequestInit) => {
      if (url.includes('/doctor/pacs-handoffs')) {
        handoffRequested = true;
        const body = JSON.parse(opts?.body as string);
        expect(body.audience).toBe('pacs-sv70');
        return Promise.resolve({
          ok: true,
          json: () =>
            Promise.resolve({
              handoffCode: 'handoff-code-xyz-1234567890',
              expiresAt: '2026-10-13T12:01:00Z',
              audience: 'pacs-sv70',
              tenantId: 'demo-hospital-a',
            }),
        });
      }
      if (url.includes('/doctor/sign-requests')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ items: [], total: 0 }),
        });
      }
      return Promise.resolve({ ok: true, json: () => Promise.resolve({}) });
    });

    const postMessageSpy = vi.fn();
    window.opener = {
      postMessage: postMessageSpy,
      closed: false,
    } as any;

    render(
      <AppProvider>
        <PhysicianSigningPortal token="mock-doctor-bearer-token" doctor={mockDoctor} />
      </AppProvider>
    );

    const handoffBtn = screen.getByTestId('pacs-handoff-btn');
    fireEvent.click(handoffBtn);

    await waitFor(() => {
      expect(handoffRequested).toBe(true);
      expect(screen.getByTestId('pacs-handoff-code')).toHaveTextContent('handoff-code-xyz-1234567890');
    });

    expect(postMessageSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'VC_PACS_HANDOFF_SUCCESS',
        handoffCode: 'handoff-code-xyz-1234567890',
        audience: 'pacs-sv70',
        tenantId: 'demo-hospital-a',
      }),
      expect.any(String)
    );
  });

  it('derives runtime banner accurately from /v1/runtime/capabilities', async () => {
    globalThis.fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/runtime/capabilities')) {
        return Promise.resolve({
          ok: true,
          json: () =>
            Promise.resolve({
              profile: 'prod',
              persistence: { mode: 'POSTGRESQL', schemaVersion: 5, state: 'READY' },
              staffAuth: { mode: 'OIDC', issuer: 'https://idp.hospital.org' },
              doctorAuth: {
                pcCertificate: { configured: true, trustAnchors: 1, crlState: 'FRESH' },
                mobile: { configured: false, provider: null },
                googleConfigured: false,
                devMockCertificate: false,
                devIdentity: false,
              },
              signing: {
                backend: 'OPENBAO_TRANSIT',
                available: true,
                verificationLabel: 'PRIVATE_PKI_NOT_TIMESTAMPED',
              },
              clinicalUseAllowed: false,
              clinicalUseReason: 'LEGAL_APPROVAL_NOT_ASSERTED',
              warnings: ['PRIVATE_PKI_NOT_ACCREDITED', 'NO_TRUSTED_TIMESTAMP'],
            }),
        });
      }
      return Promise.resolve({ ok: true, json: () => Promise.resolve({}) });
    });

    render(
      <AppProvider>
        <Header />
      </AppProvider>
    );

    await waitFor(() => {
      const bannerText = screen.getByTestId('runtime-banner-text');
      expect(bannerText).toHaveTextContent('NON-CLINICAL VALIDATION: OPENBAO');
      expect(bannerText).toHaveTextContent('LEGAL_APPROVAL_NOT_ASSERTED');
    });

    expect(screen.getByTestId('runtime-banner-badge')).toHaveTextContent('PROD | POSTGRESQL');
    expect(screen.getByTestId('runtime-warnings-banner')).toHaveTextContent('PRIVATE_PKI_NOT_ACCREDITED | NO_TRUSTED_TIMESTAMP');
  });

  it('handles absent / failed runtime capabilities by failing closed to UNAVAILABLE', async () => {
    globalThis.fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/runtime/capabilities')) {
        return Promise.resolve({
          ok: false,
          status: 503,
          statusText: 'Service Unavailable',
        });
      }
      return Promise.resolve({ ok: true, json: () => Promise.resolve({}) });
    });

    render(
      <AppProvider>
        <Header />
      </AppProvider>
    );

    await waitFor(() => {
      const bannerText = screen.getByTestId('runtime-banner-text');
      expect(bannerText).toHaveTextContent('RUNTIME UNAVAILABLE: 백엔드 런타임 구성 확인 불가');
    });

    expect(screen.getByTestId('runtime-banner-badge')).toHaveTextContent('UNCONFIGURED / OFFLINE');
  });

  it('defines local certificate SDK adapter contract and reports unavailable when uninstalled', async () => {
    const adapter = getLocalCertSdkAdapter();
    expect(adapter).toBeDefined();
    expect(adapter.id).toBeDefined();

    const isAvailable = await adapter.isAvailable();
    expect(isAvailable).toBe(false);

    await expect(
      adapter.signChallenge({
        challengeId: 'test-challenge',
        challengeNonce: 'test-nonce',
      })
    ).rejects.toThrow('PC 인증서 보안프로그램에 연결할 수 없습니다. 설치·실행 후 다시 확인해 주세요.');
  });
});
