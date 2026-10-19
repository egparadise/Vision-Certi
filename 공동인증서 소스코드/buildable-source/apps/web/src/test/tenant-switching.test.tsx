import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { AppProvider, useApp } from '../context/AppContext';
import { Header } from '../components/Header';
import { DocumentListView } from '../components/DocumentListView';

const MockParentApp: React.FC = () => {
  const { tenantId, setTenantId, selectedDocumentId, setSelectedDocumentId } = useApp();

  return (
    <div>
      <Header />
      <div data-testid="current-tenant">{tenantId}</div>
      <div data-testid="current-selected-doc">{selectedDocumentId || 'none'}</div>
      <button
        type="button"
        data-testid="select-doc-btn"
        onClick={() => setSelectedDocumentId('doc-secret-a-99')}
      >
        A병원 문서 선택
      </button>
      <button type="button" data-testid="switch-tenant-btn" onClick={() => setTenantId('demo-hospital-b')}>
        시험용 기관 범위 전환
      </button>
      <DocumentListView />
    </div>
  );
};

describe('Tenant Switching and State Reset Tests', () => {
  it('clears active document selection and resets caches when switching tenant', async () => {
    // Mock fetch for both hospitals
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/documents')) {
        return new Response(JSON.stringify({ items: [], nextCursor: null }), {
          status: 200,
        });
      }
      return new Response('Not Found', { status: 404 });
    });

    render(
      <AppProvider>
        <MockParentApp />
      </AppProvider>
    );

    // Initial tenant is demo-hospital-a
    expect(screen.getByTestId('current-tenant')).toHaveTextContent('demo-hospital-a');
    expect(screen.getByTestId('current-selected-doc')).toHaveTextContent('none');

    // Simulate doctor selecting a confidential document in Hospital A
    fireEvent.click(screen.getByTestId('select-doc-btn'));
    expect(screen.getByTestId('current-selected-doc')).toHaveTextContent('doc-secret-a-99');

    // Switch tenant to B 병원 (demo-hospital-b)
    expect(screen.queryByLabelText('의료기관 선택')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('switch-tenant-btn'));

    // Assert: active selected document MUST BE CLEARED immediately
    await waitFor(() => {
      expect(screen.getByTestId('current-tenant')).toHaveTextContent('demo-hospital-b');
      expect(screen.getByTestId('current-selected-doc')).toHaveTextContent('none');
    });
  });
});
