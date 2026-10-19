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

  it('disables administrator preregistration and invitation issuance', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [], total: 0 }), { status: 200 }));
    render(<AppProvider><AdminWrapper /></AppProvider>);
    expect(await screen.findByTestId('admin-invitations-disabled')).toBeInTheDocument();
    expect(screen.queryByTestId('prereg-name-input')).not.toBeInTheDocument();
    expect(screen.queryByTestId('submit-preregister-btn')).not.toBeInTheDocument();
    expect(screen.queryByTestId('one-time-invitation-card')).not.toBeInTheDocument();
    expect(vi.mocked(globalThis.fetch).mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
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
