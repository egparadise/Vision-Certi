import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { AppProvider } from '../context/AppContext';
import { DoctorActivationView } from '../components/DoctorActivationView';
import { Doctor, DoctorActivationConfig, ActivationChallenge, DoctorCertificate, SignRequest } from '../types/api';

describe('DoctorActivationView Component Tests', () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    localStorage.clear();
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  const mockConfig: DoctorActivationConfig = {
    googleClientId: null, // intentionally null to test fallback banner and dev simulation
    googleConfigured: false,
    devSimulationEnabled: true,
    signingBackend: 'OPENBAO_TRANSIT',
    signingBackendAvailable: true,
    clinicalUseAllowed: false,
    mockGoogleEmail: 'doctor.test@example.com',
    mockGoogleSubject: 'sub-test-1234',
  };

  const mockChallenge: ActivationChallenge = {
    challengeId: 'chal-test-001',
    nonce: 'nonce-random-xyz-777',
    expiresAt: new Date(Date.now() + 600000).toISOString(),
    purpose: 'ACTIVATION',
  };

  const mockDoctorPending: Doctor = {
    id: 'doc-act-1',
    tenantId: 'demo-hospital-a',
    name: '김안과',
    licenseNumber: 'DOC-554433',
    centerName: 'A의료원 망막센터',
    googleEmail: 'doctor.test@example.com',
    status: 'IDENTITY_VERIFIED',
    identityMode: 'MOCK_IDENTITY',
    googleAccountLinked: true,
    invitationState: 'USED',
    invitationExpiresAt: new Date().toISOString(),
    identityVerifiedAt: new Date().toISOString(),
    consentedAt: null,
    consentVersion: null,
    activatedAt: null,
    statusReason: null,
    statusChangedAt: null,
    createdAt: new Date().toISOString(),
    createdBy: 'admin-demo',
    signingBackend: 'OPENBAO_TRANSIT',
    certificate: null,
  };

  const mockDoctorConsented: Doctor = {
    ...mockDoctorPending,
    status: 'CONSENTED',
    consentedAt: new Date().toISOString(),
    consentVersion: '2026-10-13-draft',
  };

  const mockCert: DoctorCertificate = {
    certificateId: 'cert-7788',
    kind: 'X509',
    backend: 'OPENBAO_TRANSIT',
    subject: 'CN=김안과, O=A의료원 망막센터, L=DOC-554433',
    issuer: 'CN=Vision-Certi Private CA, O=Inbiz Solution',
    serialNumber: 'SN-20261013-0001',
    notBefore: new Date().toISOString(),
    notAfter: new Date(Date.now() + 31536000000).toISOString(),
    publicKeySha256: 'sha256-dummy-pk-hash',
    keyName: 'doc-act-1-transit-key',
    keyVersion: 1,
    status: 'VALID',
    certificatePem: '-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----',
  };

  const mockDoctorActive: Doctor = {
    ...mockDoctorConsented,
    status: 'ACTIVE',
    activatedAt: new Date().toISOString(),
    certificate: mockCert,
  };

  it('completes activation challenge, verifies memory-only token, requires 3 consents, handles failure without marking active, and displays certificate on success', async () => {
    let activationAttempts = 0;

    globalThis.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      // 1. Config
      if (url.includes('/doctor-activation/config')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => mockConfig,
        } as Response);
      }
      // 2. Challenge
      if (url.includes('/doctor-activation/challenge') && init?.method === 'POST') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => mockChallenge,
        } as Response);
      }
      // 3. Dev identity
      if (url.includes('/doctor-activation/dev-identity') && init?.method === 'POST') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({
            activationToken: 'mem-secret-token-token-abc-999',
            expiresAt: new Date(Date.now() + 3600000).toISOString(),
            identityMode: 'MOCK_IDENTITY',
            doctor: mockDoctorPending,
          }),
        } as Response);
      }
      // 4. Consent
      if (url.includes('/doctor-activation/consent') && init?.method === 'POST') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => mockDoctorConsented,
        } as Response);
      }
      // 5. Activate
      if (url.includes('/doctor-activation/activate') && init?.method === 'POST') {
        activationAttempts++;
        if (activationAttempts === 1) {
          // First attempt fails to simulate provisioning error
          return Promise.resolve({
            ok: false,
            status: 500,
            json: async () => ({
              error: {
                code: 'SIGNING_BACKEND_UNAVAILABLE',
                message: 'OpenBao Transit key provisioning timed out.',
              },
            }),
          } as Response);
        }
        // Second attempt succeeds
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => mockDoctorActive,
        } as Response);
      }
      // 6. Sign requests when active
      if (url.includes('/doctor/sign-requests') && (!init || init.method === 'GET' || !init.method)) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({ items: [], total: 0 }),
        } as Response);
      }
      return Promise.reject(new Error(`Unhandled URL: ${url}`));
    });

    render(
      <AppProvider>
        <DoctorActivationView />
      </AppProvider>
    );

    // --- STEP 1: Invitation Code ---
    const invInput = await screen.findByTestId('invitation-code-input');
    const submitInvBtn = screen.getByTestId('submit-invitation-btn');
    fireEvent.change(invInput, { target: { value: 'INV-TEST-CODE-1' } });
    fireEvent.click(submitInvBtn);

    // --- STEP 2: Google unconfigured banner & Dev simulation ---
    const unconfiguredBanner = await screen.findByTestId('google-unconfigured-banner');
    expect(unconfiguredBanner).toBeInTheDocument();

    const devBox = screen.getByTestId('dev-identity-box');
    expect(devBox).toBeInTheDocument();
    expect(devBox).toHaveTextContent('개발용 Google 신원 시뮬레이션');

    const devVerifyBtn = screen.getByTestId('dev-verify-btn');
    fireEvent.click(devVerifyBtn);

    // --- SECURITY CHECK: Token in memory only ---
    await waitFor(() => {
      expect(screen.getByTestId('activation-step-3')).toBeInTheDocument();
    });
    // Check localStorage and sessionStorage have NOT stored the activation token
    expect(localStorage.getItem('activationToken')).toBeNull();
    expect(sessionStorage.getItem('activationToken')).toBeNull();
    expect(window.location.search).not.toContain('mem-secret-token');

    // --- STEP 3: Doctor details and 3 unchecked consents ---
    expect(screen.getByText('김안과')).toBeInTheDocument();
    expect(screen.getByText('DOC-554433')).toBeInTheDocument();

    const authCb = screen.getByTestId('consent-checkbox-auth') as HTMLInputElement;
    const sigCb = screen.getByTestId('consent-checkbox-sig') as HTMLInputElement;
    const privCb = screen.getByTestId('consent-checkbox-priv') as HTMLInputElement;
    const submitConsentBtn = screen.getByTestId('submit-consent-btn') as HTMLButtonElement;

    // Checkboxes MUST default to unchecked
    expect(authCb.checked).toBe(false);
    expect(sigCb.checked).toBe(false);
    expect(privCb.checked).toBe(false);
    expect(submitConsentBtn.disabled).toBe(true);

    // Checking only 2 must still keep submit disabled
    fireEvent.click(authCb);
    fireEvent.click(sigCb);
    expect(submitConsentBtn.disabled).toBe(true);

    // Check 3rd checkbox
    fireEvent.click(privCb);
    expect(submitConsentBtn.disabled).toBe(false);

    // Submit consents
    fireEvent.click(submitConsentBtn);

    // --- STEP 4: Key & Certificate Activation Failure & Retry ---
    const activateBtn = await screen.findByTestId('activate-btn');
    expect(activateBtn).toBeInTheDocument();

    // Trigger activation (1st attempt fails)
    fireEvent.click(activateBtn);

    const errorBanner = await screen.findByTestId('activation-error');
    expect(errorBanner).toBeInTheDocument();
    expect(errorBanner).toHaveTextContent('CONSENTED');
    // Ensure it NEVER claims ACTIVE on failure
    expect(screen.queryByTestId('activation-success')).not.toBeInTheDocument();

    // Trigger activation retry (2nd attempt succeeds)
    fireEvent.click(screen.getByTestId('activate-btn'));

    // Verify success & certificate card
    const successCard = await screen.findByTestId('activation-success');
    expect(successCard).toBeInTheDocument();
    expect(screen.getByTestId('certificate-card')).toBeInTheDocument();
    expect(screen.getByText('SN-20261013-0001')).toBeInTheDocument();
    expect(screen.getByText('doc-act-1-transit-key (v1)')).toBeInTheDocument();
  });

  it('supports returning doctor login, renders active sign requests, and performs informed document sign approval', async () => {
    const mockSignRequest: SignRequest = {
      id: 'req-active-001',
      tenantId: 'demo-hospital-a',
      documentId: 'doc-report-777',
      documentVersionId: 'ver-001',
      documentSha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      signerId: 'doc-act-1',
      nonce: 'sign-nonce-abc-123',
      status: 'PENDING_APPROVAL',
      signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE',
      createdAt: new Date().toISOString(),
      createdBy: 'admin-demo',
      expiresAt: new Date(Date.now() + 3600000).toISOString(),
      approvedAt: null,
      approvedBy: null,
      signatureId: null,
    };

    let approved = false;

    globalThis.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.includes('/doctor-activation/config')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => mockConfig,
        } as Response);
      }
      if (url.includes('/doctor-activation/login-challenges') && init?.method === 'POST') {
        return Promise.resolve({
          ok: true,
          status: 201,
          json: async () => ({
            challengeId: 'chal-login-999',
            nonce: 'login-nonce-555',
            expiresAt: new Date(Date.now() + 600000).toISOString(),
            purpose: 'LOGIN',
          }),
        } as Response);
      }
      if (url.includes('/doctor-activation/dev-identity') && init?.method === 'POST') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({
            activationToken: 'bearer-doctor-token-active-777',
            expiresAt: new Date(Date.now() + 3600000).toISOString(),
            identityMode: 'MOCK_IDENTITY',
            doctor: mockDoctorActive,
          }),
        } as Response);
      }
      if (url.includes('/doctor/sign-requests') && !url.includes('/approve') && (!init || init.method === 'GET' || !init.method)) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({
            items: approved ? [{ ...mockSignRequest, status: 'COMPLETED', signatureId: 'sig-ecdsa-999' }] : [mockSignRequest],
            total: 1,
          }),
        } as Response);
      }
      if (url.includes('/doctor/sign-requests/req-active-001/approve') && init?.method === 'POST') {
        approved = true;
        return Promise.resolve({
          ok: true,
          status: 202,
          json: async () => ({
            ...mockSignRequest,
            status: 'COMPLETED',
            signatureId: 'sig-ecdsa-999',
            completedAt: new Date().toISOString(),
          }),
        } as Response);
      }
      return Promise.reject(new Error(`Unhandled URL: ${url}`));
    });

    render(
      <AppProvider>
        <DoctorActivationView />
      </AppProvider>
    );

    // Switch to returning doctor login tab
    const loginTab = await screen.findByTestId('tab-login-mode');
    fireEvent.click(loginTab);

    // Start login
    const startLoginBtn = screen.getByTestId('start-doctor-login-btn');
    fireEvent.click(startLoginBtn);

    // Verify identity via dev simulation
    const devVerifyBtn = await screen.findByTestId('dev-verify-btn');
    fireEvent.click(devVerifyBtn);

    // Since doctor is ACTIVE, transitions directly to Step 4 Signing Portal
    const previewCard = await screen.findByTestId('document-preview-card');
    expect(previewCard).toBeInTheDocument();
    expect(screen.getByTestId('preview-document-sha256')).toHaveTextContent('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
    expect(screen.getByTestId('preview-request-nonce')).toHaveTextContent('sign-nonce-abc-123');

    // Confirm checkbox required
    const approveBtn = screen.getByTestId('doctor-sign-approve-btn') as HTMLButtonElement;
    const confirmCb = screen.getByTestId('doctor-sign-confirm-checkbox') as HTMLInputElement;
    expect(confirmCb.checked).toBe(false);
    expect(approveBtn.disabled).toBe(true);

    fireEvent.click(confirmCb);
    expect(approveBtn.disabled).toBe(false);

    // Click approve
    fireEvent.click(approveBtn);

    // Verify success message
    const successMsg = await screen.findByTestId('doctor-sign-success');
    expect(successMsg).toHaveTextContent('sig-ecdsa-999');
  });
});
