import React from 'react';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { AppProvider, useApp } from '../context/AppContext';
import { DocumentDetailView } from '../components/DocumentDetailView';
import { Document, SignRequest } from '../types/api';

const mockDoc: Document = {
  id: 'doc-dup-1',
  tenantId: 'demo-hospital-a',
  externalDocumentId: 'EXT-DUP-1',
  externalStudyId: 'ST-DUP-1',
  title: '중복 승인 방지 테스트 판독문',
  signerId: 'doctor-demo',
  createdAt: '2026-10-13T05:00:00Z',
  updatedAt: '2026-10-13T05:00:00Z',
  createdBy: 'admin-demo',
  latestVersion: {
    id: 'ver-dup-1',
    documentId: 'doc-dup-1',
    versionNumber: 1,
    sha256: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
    sizeBytes: 15000,
    contentType: 'application/pdf',
    createdAt: '2026-10-13T05:00:00Z',
    createdBy: 'admin-demo',
    previousVersionId: null,
    amendmentReason: null,
  },
  versions: [
    {
      id: 'ver-dup-1',
      documentId: 'doc-dup-1',
      versionNumber: 1,
      sha256: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
      sizeBytes: 15000,
      contentType: 'application/pdf',
      createdAt: '2026-10-13T05:00:00Z',
      createdBy: 'admin-demo',
      previousVersionId: null,
      amendmentReason: null,
    },
  ],
};

const mockSignReq: SignRequest = {
  id: 'req-dup-1',
  tenantId: 'demo-hospital-a',
  documentId: 'doc-dup-1',
  documentVersionId: 'ver-dup-1',
  documentSha256: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
  signerId: 'doctor-demo',
  nonce: 'mock-nonce-valid-once',
  status: 'PENDING_APPROVAL',
  signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE',
  createdAt: '2026-10-13T05:00:00Z',
  createdBy: 'admin-demo',
  expiresAt: new Date(Date.now() + 600000).toISOString(),
  approvedAt: null,
  approvedBy: null,
  signatureId: null,
};

const DoctorWrapper: React.FC = () => {
  const { setActorId } = useApp();
  React.useEffect(() => {
    setActorId('doctor-demo');
  }, [setActorId]);

  return <DocumentDetailView documentId="doc-dup-1" onBack={() => {}} />;
};

describe('Duplicate Approval Prevention and Idempotency Tests', () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('disables the approval button immediately during in-flight sign request and sends idempotency key', async () => {
    let approveCallCount = 0;
    let capturedHeaders: HeadersInit | undefined;

    globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString();

      if (url.includes('/content')) {
        return new Response(new Blob(['%PDF-1.4']), {
          status: 200,
          headers: { 'Content-Type': 'application/pdf' },
        });
      }
      if (url.includes('/documents/doc-dup-1')) {
        return new Response(JSON.stringify(mockDoc), { status: 200 });
      }
      if (url.includes('/sign-requests') && !url.includes('/approve')) {
        return new Response(JSON.stringify({ items: [mockSignReq], nextCursor: null }), {
          status: 200,
        });
      }
      if (url.includes('/approve')) {
        approveCallCount++;
        capturedHeaders = init?.headers;
        // Introduce a slight artificial delay so we can inspect button disabled state
        await new Promise((resolve) => setTimeout(resolve, 150));
        return new Response(
          JSON.stringify({
            ...mockSignReq,
            status: 'COMPLETED',
            approvedAt: new Date().toISOString(),
            approvedBy: 'doctor-demo',
            signatureId: 'sig-dup-1',
          }),
          { status: 202 }
        );
      }
      return new Response('Not Found', { status: 404 });
    };

    render(
      <AppProvider>
        <DoctorWrapper />
      </AppProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId('pdf-view-confirm-checkbox')).toBeInTheDocument();
    });

    const iframe = screen.getByTitle('판독문 PDF 뷰어');
    fireEvent.load(iframe);

    const confirmCheck = screen.getByTestId('pdf-view-confirm-checkbox');
    fireEvent.click(confirmCheck);

    const approveBtn = screen.getByTestId('doctor-sign-approve-btn');
    await waitFor(() => {
      expect(approveBtn).not.toBeDisabled();
    });

    // Click the button
    fireEvent.click(approveBtn);

    // Button MUST immediately become disabled with in-progress message to prevent multi-click
    expect(approveBtn).toBeDisabled();
    expect(screen.getByText(/전자서명 승인 처리 중/)).toBeInTheDocument();

    // Rapid double click attempt
    fireEvent.click(approveBtn);
    fireEvent.click(approveBtn);

    // Wait for the asynchronous approval to finish
    await waitFor(() => {
      expect(screen.getByText(/전자서명 완료/i)).toBeInTheDocument();
    });

    // Verify exactly 1 call was dispatched despite multiple rapid clicks
    expect(approveCallCount).toBe(1);

    // Verify Idempotency-Key header was sent
    expect(capturedHeaders).toBeDefined();
    const headersRecord = capturedHeaders as Record<string, string>;
    expect(headersRecord['Idempotency-Key']).toBeDefined();
    expect(headersRecord['Idempotency-Key']).toMatch(/^sign-appr-/);
  });
});
