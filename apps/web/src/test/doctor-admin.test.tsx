import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { AppProvider, useApp } from '../context/AppContext';
import { DoctorAdminView } from '../components/DoctorAdminView';
import { Doctor } from '../types/api';

const AdminWrapper: React.FC = () => {
  const { setActorId } = useApp();
  React.useEffect(() => {
    setActorId('admin-demo');
  }, [setActorId]);

  return <DoctorAdminView />;
};

describe('DoctorAdminView Component Tests', () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it('renders pre-registration form and keeps submit button disabled until evidence review checkbox is checked', async () => {
    const mockDoctors: Doctor[] = [];
    globalThis.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.includes('/admin/doctors') && (!init || init.method === 'GET' || !init.method)) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({ items: mockDoctors, total: 0 }),
        } as Response);
      }
      return Promise.reject(new Error(`Unhandled URL: ${url}`));
    });

    render(
      <AppProvider>
        <AdminWrapper />
      </AppProvider>
    );

    // Verify presence of form inputs
    const nameInput = await screen.findByTestId('prereg-name-input');
    const licenseInput = screen.getByTestId('prereg-license-input');
    const centerInput = screen.getByTestId('prereg-center-input');
    const emailInput = screen.getByTestId('prereg-email-input');
    const idEvidenceInput = screen.getByTestId('prereg-id-evidence-input');
    const licEvidenceInput = screen.getByTestId('prereg-license-evidence-input');
    const evidenceCheckbox = screen.getByTestId('admin-reviewed-evidence-checkbox') as HTMLInputElement;
    const submitBtn = screen.getByTestId('submit-preregister-btn') as HTMLButtonElement;

    // Fill form
    fireEvent.change(nameInput, { target: { value: '이진단' } });
    fireEvent.change(licenseInput, { target: { value: 'DOC-998877' } });
    fireEvent.change(centerInput, { target: { value: 'A의료원 안과' } });
    fireEvent.change(emailInput, { target: { value: 'dr.lee@example.com' } });
    fireEvent.change(idEvidenceInput, { target: { value: 'ID-CARD-SCAN-01' } });
    fireEvent.change(licEvidenceInput, { target: { value: 'MED-LICENSE-SCAN-01' } });

    // Submit button should be disabled because evidenceCheckbox is unchecked
    expect(evidenceCheckbox.checked).toBe(false);
    expect(submitBtn.disabled).toBe(true);

    // Check evidence review confirmation checkbox
    fireEvent.click(evidenceCheckbox);
    expect(evidenceCheckbox.checked).toBe(true);
    expect(submitBtn.disabled).toBe(false);
  });

  it('submits pre-registration and renders one-time invitation card with safe copy and non-automatic email warning', async () => {
    const createdDoctor: Doctor = {
      id: 'doc-new-1',
      tenantId: 'demo-hospital-a',
      name: '이진단',
      licenseNumber: 'DOC-998877',
      centerName: 'A의료원 안과',
      googleEmail: 'dr.lee@example.com',
      status: 'PENDING',
      identityMode: null,
      googleAccountLinked: false,
      invitationState: 'ISSUED',
      invitationExpiresAt: new Date(Date.now() + 86400000).toISOString(),
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
    };

    globalThis.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.includes('/admin/doctors') && (!init || init.method === 'GET' || !init.method)) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({ items: [], total: 0 }),
        } as Response);
      }
      if (url.endsWith('/admin/doctors') && init?.method === 'POST') {
        return Promise.resolve({
          ok: true,
          status: 201,
          json: async () => ({
            doctor: createdDoctor,
            invitationCode: 'INV-TEST-9988-SAFE',
            invitationExpiresAt: createdDoctor.invitationExpiresAt,
          }),
        } as Response);
      }
      return Promise.reject(new Error(`Unhandled URL: ${url}`));
    });

    render(
      <AppProvider>
        <AdminWrapper />
      </AppProvider>
    );

    const nameInput = await screen.findByTestId('prereg-name-input');
    fireEvent.change(nameInput, { target: { value: '이진단' } });
    fireEvent.change(screen.getByTestId('prereg-license-input'), { target: { value: 'DOC-998877' } });
    fireEvent.change(screen.getByTestId('prereg-center-input'), { target: { value: 'A의료원 안과' } });
    fireEvent.change(screen.getByTestId('prereg-email-input'), { target: { value: 'dr.lee@example.com' } });
    fireEvent.change(screen.getByTestId('prereg-id-evidence-input'), { target: { value: 'ID-01' } });
    fireEvent.change(screen.getByTestId('prereg-license-evidence-input'), { target: { value: 'LIC-01' } });
    fireEvent.click(screen.getByTestId('admin-reviewed-evidence-checkbox'));

    fireEvent.click(screen.getByTestId('submit-preregister-btn'));

    // Wait for one-time invitation card to appear
    const invitationCard = await screen.findByTestId('one-time-invitation-card');
    expect(invitationCard).toBeInTheDocument();
    expect(invitationCard).toHaveTextContent('INV-TEST-9988-SAFE');
    // Verify non-automatic email disclaimer
    expect(invitationCard).toHaveTextContent('이메일 자동 발송을 지원하지 않습니다');

    // Safe copy button exists
    const copyBtn = screen.getByTestId('copy-invitation-btn');
    expect(copyBtn).toBeInTheDocument();
  });

  it('renders directory with status chips and handles suspend action with required reason', async () => {
    const existingDoctor: Doctor = {
      id: 'doc-active-1',
      tenantId: 'demo-hospital-a',
      name: '정안과',
      licenseNumber: 'DOC-123456',
      centerName: 'A의료원 본관',
      googleEmail: 'dr.jung@example.com',
      status: 'ACTIVE',
      identityMode: 'GOOGLE',
      googleAccountLinked: true,
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
      certificate: null,
    };

    let updatedDoctor: Doctor = { ...existingDoctor };

    globalThis.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url.includes('/admin/doctors') && (!init || init.method === 'GET' || !init.method)) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({ items: [updatedDoctor], total: 1 }),
        } as Response);
      }
      if (url.includes('/admin/doctors/doc-active-1/status') && init?.method === 'PATCH') {
        const body = JSON.parse(init.body as string);
        updatedDoctor = {
          ...updatedDoctor,
          status: body.status,
          statusReason: body.reason,
        };
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => updatedDoctor,
        } as Response);
      }
      return Promise.reject(new Error(`Unhandled URL: ${url}`));
    });

    render(
      <AppProvider>
        <AdminWrapper />
      </AppProvider>
    );

    // Verify doctor in directory
    await waitFor(() => {
      expect(screen.getByText('정안과')).toBeInTheDocument();
    });

    // Status chip should display ACTIVE
    expect(screen.getByTestId('status-chip-doc-active-1')).toHaveTextContent('ACTIVE');

    // Click suspend button to open modal
    const suspendBtn = screen.getByTestId('suspend-btn-doc-active-1');
    fireEvent.click(suspendBtn);

    // Modal opens
    const modalConfirmBtn = screen.getByTestId('modal-confirm-btn') as HTMLButtonElement;
    const reasonInput = screen.getByTestId('status-change-reason-input');

    // Confirm button disabled when reason is empty or too short (< 2 chars)
    expect(modalConfirmBtn.disabled).toBe(true);

    fireEvent.change(reasonInput, { target: { value: '의사 요청으로 인한 일시 서명 중지' } });
    expect(modalConfirmBtn.disabled).toBe(false);

    // Submit suspension
    fireEvent.click(modalConfirmBtn);

    // Verify status chip updates to SUSPENDED
    await waitFor(() => {
      expect(screen.getByTestId('status-chip-doc-active-1')).toHaveTextContent('SUSPENDED');
    });
  });

  it('displays clear dev authentication notice', async () => {
    globalThis.fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/admin/doctors')) {
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
        <AdminWrapper />
      </AppProvider>
    );

    const devNotice = await screen.findByTestId('dev-auth-notice');
    expect(devNotice).toBeInTheDocument();
    expect(devNotice).toHaveTextContent('개발/검증 환경 가이드');
  });
});
