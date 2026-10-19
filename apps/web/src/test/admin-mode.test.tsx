import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { AppProvider, useApp } from '../context/AppContext';
import { AdminConsole, AdminSection, adminSectionFromHash } from '../components/admin/AdminConsole';
import { Navigation } from '../components/Navigation';
import type { SystemSettingView, SystemSettingsResponse, SystemConfigurationResponse } from '../types/admin';

const setting = (over: Partial<SystemSettingView> = {}): SystemSettingView => ({
  key: 'sign-requests.ttl',
  group: 'SIGNING',
  unit: 'SECONDS',
  label: '서명 요청 유효 시간',
  description: '의사 승인 대기 상태의 서명 요청이 만료되기까지의 시간입니다.',
  min: 60,
  max: 3600,
  defaultValue: 600,
  value: 600,
  overridden: false,
  revision: 0,
  reason: null,
  updatedAt: null,
  updatedBy: null,
  updatedByTenant: null,
  ...over,
});

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

type Handler = (url: string, init?: RequestInit) => Response | undefined;

function mockFetch(handler: Handler) {
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const handled = handler(url, init);
    if (handled) return handled;
    if (url.includes('/runtime/capabilities')) return json({ profile: 'dev', persistence: { mode: 'IN_MEMORY' }, staffAuth: { mode: 'DEV_HEADERS' }, doctorAuth: { pcCertificate: { configured: false, trustAnchors: 0, crlState: 'NOT_CONFIGURED' }, mobile: { configured: false, provider: null }, googleConfigured: false, devMockCertificate: false, devIdentity: false }, signing: { backend: 'DEV_SIMULATED', available: true }, clinicalUseAllowed: false, warnings: ['IN_MEMORY_PERSISTENCE_NOT_DURABLE'] });
    if (url.includes('/admin/certificate-operations') || url.includes('/admin/medical-institutions')) return json([]);
    if (url.includes('/dashboard')) return json({ tenantId: 'demo-hospital-a', generatedAt: '', signatureMode: '', persistence: 'IN_MEMORY', documents: 1, documentVersions: 1, signRequestsByStatus: {}, signatures: 0, deliveriesByStatus: {}, auditEvents: 3 });
    return json({ items: [] });
  });
  globalThis.fetch = fn as unknown as typeof globalThis.fetch;
  return fn;
}

const AsActor: React.FC<{ actor: 'admin-demo' | 'doctor-demo'; section: AdminSection }> = ({ actor, section }) => {
  const { setActorId, setLocalAdminToken, actorId } = useApp();
  React.useEffect(() => {
    setActorId(actor);
    setLocalAdminToken(actor === 'admin-demo' ? 'test-admin-token' : null);
  }, [actor, setActorId, setLocalAdminToken]);
  if (actorId !== actor) return null;
  return (
    <>
      <Navigation />
      <AdminConsole section={section} />
    </>
  );
};

const renderAs = (actor: 'admin-demo' | 'doctor-demo', section: AdminSection) =>
  render(
    <AppProvider>
      <AsActor actor={actor} section={section} />
    </AppProvider>
  );

describe('Admin mode (관리자 모드)', () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it('parses admin sections from the hash', () => {
    expect(adminSectionFromHash('#/admin')).toBe('overview');
    expect(adminSectionFromHash('#/admin/settings')).toBe('settings');
    expect(adminSectionFromHash('#/admin/unknown')).toBe('overview');
  });

  it('denies doctors and never calls admin APIs; the admin entry is hidden for them', async () => {
    const fetchMock = mockFetch(() => undefined);
    renderAs('doctor-demo', 'settings');
    expect(await screen.findByTestId('admin-access-denied')).toBeInTheDocument();
    expect(screen.queryByTestId('nav-admin-mode')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([u]) => String(u).includes('/admin/'))).toBe(false);
  });

  it('shows the admin entry and overview with configuration warnings for administrators', async () => {
    mockFetch(() => undefined);
    renderAs('admin-demo', 'overview');
    expect(await screen.findByTestId('nav-admin-mode')).toBeInTheDocument();
    expect(await screen.findByTestId('admin-overview')).toBeInTheDocument();
    expect(await screen.findByText(/표시할 실제 공동인증서가 없습니다/)).toBeInTheDocument();
  });

  it('changes a setting with reason and expected revision, then shows the audited result', async () => {
    let put: RequestInit | undefined;
    const fetchMock = mockFetch((url, init) => {
      if (url.endsWith('/admin/settings') && (!init?.method || init.method === 'GET')) {
        return json({ editable: true, editableReason: null, items: [setting()] } satisfies SystemSettingsResponse);
      }
      if (url.endsWith('/admin/settings/sign-requests.ttl') && init?.method === 'PUT') {
        put = init;
        return json(setting({ value: 120, overridden: true, revision: 1, reason: '승인 대기 단축', updatedBy: 'admin-demo' }));
      }
      return undefined;
    });
    renderAs('admin-demo', 'settings');
    expect(await screen.findByTestId('setting-value-sign-requests.ttl')).toHaveTextContent('10분');
    fireEvent.click(screen.getByTestId('setting-edit-sign-requests.ttl'));
    fireEvent.change(screen.getByTestId('setting-input-sign-requests.ttl'), { target: { value: '30' } });
    fireEvent.change(screen.getByTestId('setting-reason-sign-requests.ttl'), { target: { value: '승인 대기 단축' } });
    fireEvent.click(screen.getByTestId('setting-save-sign-requests.ttl'));
    // Out of range: refused client-side, nothing sent.
    expect(await screen.findByText(/60 ~ 3,600 범위/)).toBeInTheDocument();
    expect(put).toBeUndefined();

    fireEvent.change(screen.getByTestId('setting-input-sign-requests.ttl'), { target: { value: '120' } });
    fireEvent.click(screen.getByTestId('setting-save-sign-requests.ttl'));
    expect(await screen.findByTestId('admin-settings-notice')).toHaveTextContent('2분');
    expect(JSON.parse(String(put?.body))).toEqual({ value: 120, reason: '승인 대기 단축', expectedRevision: 0 });
    expect(screen.getByTestId('setting-value-sign-requests.ttl')).toHaveTextContent('2분');
    expect(screen.getByTestId('setting-reset-sign-requests.ttl')).toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([u]) => String(u).endsWith('/admin/settings')).length).toBe(1);
  });

  it('reloads after a concurrent change and tells the administrator', async () => {
    let gets = 0;
    mockFetch((url, init) => {
      if (url.endsWith('/admin/settings') && (!init?.method || init.method === 'GET')) {
        gets += 1;
        const current = gets === 1 ? setting() : setting({ value: 300, overridden: true, revision: 1 });
        return json({ editable: true, editableReason: null, items: [current] });
      }
      if (init?.method === 'PUT') {
        return json({ code: 'SETTING_CHANGED_CONCURRENTLY', message: 'changed', correlationId: 'c', retryable: false }, 409);
      }
      return undefined;
    });
    renderAs('admin-demo', 'settings');
    fireEvent.click(await screen.findByTestId('setting-edit-sign-requests.ttl'));
    fireEvent.change(screen.getByTestId('setting-input-sign-requests.ttl'), { target: { value: '120' } });
    fireEvent.change(screen.getByTestId('setting-reason-sign-requests.ttl'), { target: { value: '동시 변경 확인' } });
    fireEvent.click(screen.getByTestId('setting-save-sign-requests.ttl'));
    expect(await screen.findByText(/다른 관리자가 먼저 변경했습니다/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('setting-value-sign-requests.ttl')).toHaveTextContent('5분'));
  });

  it('shows read-only policies without edit controls for non-operating tenants', async () => {
    mockFetch((url) =>
      url.endsWith('/admin/settings')
        ? json({ editable: false, editableReason: '지정된 운영 기관의 관리자만 변경할 수 있습니다.', items: [setting()] })
        : undefined
    );
    renderAs('admin-demo', 'settings');
    expect(await screen.findByTestId('admin-settings-readonly')).toHaveTextContent('운영 기관');
    expect(screen.queryByTestId('setting-edit-sign-requests.ttl')).not.toBeInTheDocument();
  });

  it('shows deployment configuration read-only and never renders secret values', async () => {
    const config: SystemConfigurationResponse = {
      activeProfiles: ['dev'],
      persistenceMode: 'IN_MEMORY',
      settingsEditorTenants: ['demo-hospital-a'],
      settingsEditable: true,
      generatedAt: '2026-10-18T00:00:00Z',
      items: [
        { group: 'STAFF_AUTH', key: 'visioncerti.staff-auth.mode', envVar: 'VISIONCERTI_STAFF_AUTH_MODE', label: '관리자 인증 방식', value: 'DEV_HEADERS', configured: true, secret: false },
        { group: 'PACS', key: 'visioncerti.pacs.client-secret-file', envVar: 'VISIONCERTI_PACS_CLIENT_SECRET_FILE', label: 'PACS 연동 비밀키 파일', value: null, configured: true, secret: true },
      ],
    };
    mockFetch((url) => (url.endsWith('/admin/system/configuration') ? json(config) : undefined));
    renderAs('admin-demo', 'system');
    const secretRow = await screen.findByTestId('config-visioncerti.pacs.client-secret-file');
    expect(secretRow).toHaveTextContent('설정됨(값 비공개)');
    expect(screen.getByTestId('config-visioncerti.staff-auth.mode')).toHaveTextContent('DEV_HEADERS');
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('requires OIDC mode before staff accounts can be registered', async () => {
    mockFetch((url) => (url.endsWith('/admin/staff') ? json({ items: [] }) : undefined));
    renderAs('admin-demo', 'staff');
    expect(await screen.findByTestId('admin-staff-oidc-required')).toBeInTheDocument();
    expect(screen.queryByTestId('staff-create-btn')).not.toBeInTheDocument();
  });
});
