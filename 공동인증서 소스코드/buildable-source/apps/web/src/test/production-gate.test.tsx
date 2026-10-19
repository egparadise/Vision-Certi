import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import * as envUtils from '../utils/env';
import { App, AppContent } from '../App';
import { AppProvider } from '../context/AppContext';
import { ApiClient } from '../api/client';

describe('Production Gate and Security Screen Tests', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('blocks the public signup route in the default production build', () => {
    vi.spyOn(envUtils, 'isDemoEnabled').mockReturnValue(false);
    const previousHash = window.location.hash;
    window.location.hash = '#/signup';
    try {
      render(<AppProvider><App /></AppProvider>);
      expect(screen.getByTestId('production-disabled-screen')).toBeInTheDocument();
      expect(screen.queryByRole('radiogroup', { name: '가입 유형' })).not.toBeInTheDocument();
    } finally {
      window.location.hash = previousHash;
    }
  });

  it('renders ProductionDisabledScreen and blocks demo UI when demo mode is false', () => {
    vi.spyOn(envUtils, 'isDemoEnabled').mockReturnValue(false);

    render(
      <AppProvider>
        <AppContent />
      </AppProvider>
    );

    expect(screen.getByTestId('production-disabled-screen')).toBeInTheDocument();
    expect(screen.getByText(/운영 환경 데모 모드 차단 안내/)).toBeInTheDocument();
    // Headers or demo controls must NOT be in the document
    expect(screen.queryByLabelText('의료기관 선택')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('사용자 역할 선택')).not.toBeInTheDocument();
  });

  it('does not attach X-Tenant-Id or X-Actor-Id headers when demo mode is false', async () => {
    vi.spyOn(envUtils, 'isDemoEnabled').mockReturnValue(false);

    let capturedHeaders: HeadersInit | undefined;
    globalThis.fetch = vi.fn().mockImplementation(async (_input, init) => {
      capturedHeaders = init?.headers;
      return new Response(JSON.stringify({ status: 'UP' }), { status: 200 });
    });

    const client = new ApiClient({
      tenantId: 'demo-hospital-a',
      actorId: 'doctor-demo',
    });

    await client.getDashboard();

    expect(capturedHeaders).toBeDefined();
    const headers = capturedHeaders as Record<string, string>;
    expect(headers['X-Tenant-Id']).toBeUndefined();
    expect(headers['X-Actor-Id']).toBeUndefined();
    expect(headers['X-Correlation-Id']).toBeDefined();
  });
});
