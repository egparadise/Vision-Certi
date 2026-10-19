import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { AppProvider } from '../context/AppContext';
import { DashboardView } from '../components/DashboardView';

describe('dashboard help and runtime status', () => {
  it('uses the reported backend mode and keeps technical details behind the i button', async () => {
    globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      const body = url.includes('/runtime/capabilities')
        ? {
            profile: 'dev',
            persistence: { mode: 'POSTGRESQL' },
            staffAuth: { mode: 'DEV_HEADERS' },
            doctorAuth: { pcCertificate: { configured: true, trustAnchors: 1, crlState: 'FRESH' }, mobile: { configured: false, provider: null }, googleConfigured: false, devMockCertificate: false, devIdentity: false },
            signing: { backend: 'OPENBAO_TRANSIT', available: true },
            clinicalUseAllowed: false,
            clinicalUseReason: 'LEGAL_APPROVAL_NOT_ASSERTED',
          }
        : {
            tenantId: 'hospital-1', generatedAt: '', signatureMode: 'OPENBAO_TRANSIT', persistence: 'POSTGRESQL',
            documents: 2, documentVersions: 3, signRequestsByStatus: {}, signatures: 1,
            deliveriesByStatus: {}, auditEvents: 4,
          };
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof globalThis.fetch;

    render(<AppProvider><DashboardView /></AppProvider>);
    await waitFor(() => expect(screen.getByText('판독·서명 현황')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('임상 사용 전 검증 중'));
    expect(screen.queryByText(/현재 백엔드는 IN_MEMORY/)).not.toBeInTheDocument();
    expect(screen.queryByText(/MOCK-HMAC-SHA256/)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '현재 서버 구성 상세 보기' }));
    const details = screen.getByRole('dialog', { name: '현재 서버 구성' });
    expect(details).toHaveTextContent('POSTGRESQL');
    expect(details).toHaveTextContent('OPENBAO_TRANSIT');
    expect(details).toHaveTextContent('LEGAL_APPROVAL_NOT_ASSERTED');
  });
});
