import React from 'react';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { AppProvider, useApp } from '../context/AppContext';
import { DocumentDetailView } from '../components/DocumentDetailView';
import { Document, SignRequest } from '../types/api';

const baseDoc: Document = {
  id: 'doc-pre-1',
  tenantId: 'demo-hospital-a',
  externalDocumentId: 'EXT-PRE-1',
  externalStudyId: 'ST-PRE-1',
  title: '조건 검증 테스트 판독문',
  signerId: 'doctor-demo',
  createdAt: '2026-10-13T05:00:00Z',
  updatedAt: '2026-10-13T05:00:00Z',
  createdBy: 'admin-demo',
  latestVersion: {
    id: 'ver-pre-1',
    documentId: 'doc-pre-1',
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
      id: 'ver-pre-1',
      documentId: 'doc-pre-1',
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

const DoctorWrapper: React.FC<{ docId: string; actorOverride?: 'doctor-demo' | 'admin-demo' }> = ({
  docId,
  actorOverride = 'doctor-demo',
}) => {
  const { setActorId } = useApp();
  React.useEffect(() => {
    setActorId(actorOverride);
  }, [setActorId, actorOverride]);

  return <DocumentDetailView documentId={docId} onBack={() => {}} />;
};

describe('Approval Preconditions, Retry Key, and Reconciliation Tests', () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('requires explicit confirmation checkbox before enabling approval button', async () => {
    const unexpiredReq: SignRequest = {
      id: 'req-check-1',
      tenantId: 'demo-hospital-a',
      documentId: 'doc-pre-1',
      documentVersionId: 'ver-pre-1',
      documentSha256: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
      signerId: 'doctor-demo',
      nonce: 'nonce-valid',
      status: 'PENDING_APPROVAL',
      signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE',
      createdAt: new Date().toISOString(),
      createdBy: 'admin-demo',
      expiresAt: new Date(Date.now() + 600000).toISOString(),
      approvedAt: null,
      approvedBy: null,
      signatureId: null,
    };

    globalThis.fetch = async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/content')) {
        return new Response(new Blob(['%PDF-1.4']), {
          status: 200,
          headers: { 'Content-Type': 'application/pdf' },
        });
      }
      if (url.includes('/documents/doc-pre-1')) {
        return new Response(JSON.stringify(baseDoc), { status: 200 });
      }
      if (url.includes('/sign-requests')) {
        return new Response(JSON.stringify({ items: [unexpiredReq], nextCursor: null }), {
          status: 200,
        });
      }
      return new Response('Not Found', { status: 404 });
    };

    render(
      <AppProvider>
        <DoctorWrapper docId="doc-pre-1" />
      </AppProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId('pdf-view-confirm-checkbox')).toBeInTheDocument();
    });

    const iframe = screen.getByTitle('판독문 PDF 뷰어');
    const approveBtn = screen.getByTestId('doctor-sign-approve-btn');
    const confirmCheckbox = screen.getByTestId('pdf-view-confirm-checkbox');

    // Before iframe load completes, button MUST be disabled
    expect(approveBtn).toBeDisabled();

    // Fire iframe load event
    fireEvent.load(iframe);

    // Without checkbox checked, button MUST still be disabled
    expect(confirmCheckbox).not.toBeChecked();
    expect(approveBtn).toBeDisabled();

    // Check the confirmation checkbox
    fireEvent.click(confirmCheckbox);
    expect(confirmCheckbox).toBeChecked();
    expect(approveBtn).not.toBeDisabled();
  });

  it('disables approval and shows error alert when sign request is expired', async () => {
    const expiredReq: SignRequest = {
      id: 'req-expired-1',
      tenantId: 'demo-hospital-a',
      documentId: 'doc-pre-1',
      documentVersionId: 'ver-pre-1',
      documentSha256: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
      signerId: 'doctor-demo',
      nonce: 'nonce-valid',
      status: 'PENDING_APPROVAL',
      signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE',
      createdAt: '2026-10-13T04:00:00Z',
      createdBy: 'admin-demo',
      expiresAt: '2026-10-13T04:10:00Z', // In the past!
      approvedAt: null,
      approvedBy: null,
      signatureId: null,
    };

    globalThis.fetch = async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/content')) {
        return new Response(new Blob(['%PDF-1.4']), {
          status: 200,
          headers: { 'Content-Type': 'application/pdf' },
        });
      }
      if (url.includes('/documents/doc-pre-1')) {
        return new Response(JSON.stringify(baseDoc), { status: 200 });
      }
      if (url.includes('/sign-requests')) {
        return new Response(JSON.stringify({ items: [expiredReq], nextCursor: null }), {
          status: 200,
        });
      }
      return new Response('Not Found', { status: 404 });
    };

    render(
      <AppProvider>
        <DoctorWrapper docId="doc-pre-1" />
      </AppProvider>
    );

    await waitFor(() => {
      expect(screen.getByText(/서명 유효 시간 만료/)).toBeInTheDocument();
    });

    const approveBtn = screen.getByTestId('doctor-sign-approve-btn');
    expect(approveBtn).toBeDisabled();
  });

  it('shows signer mismatch alert and disables approval when signerId differs', async () => {
    const mismatchReq: SignRequest = {
      id: 'req-mismatch-1',
      tenantId: 'demo-hospital-a',
      documentId: 'doc-pre-1',
      documentVersionId: 'ver-pre-1',
      documentSha256: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
      signerId: 'doctor-specialist-99', // Different doctor!
      nonce: 'nonce-valid',
      status: 'PENDING_APPROVAL',
      signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE',
      createdAt: new Date().toISOString(),
      createdBy: 'admin-demo',
      expiresAt: new Date(Date.now() + 600000).toISOString(),
      approvedAt: null,
      approvedBy: null,
      signatureId: null,
    };

    globalThis.fetch = async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/content')) {
        return new Response(new Blob(['%PDF-1.4']), {
          status: 200,
          headers: { 'Content-Type': 'application/pdf' },
        });
      }
      if (url.includes('/documents/doc-pre-1')) {
        return new Response(JSON.stringify(baseDoc), { status: 200 });
      }
      if (url.includes('/sign-requests')) {
        return new Response(JSON.stringify({ items: [mismatchReq], nextCursor: null }), {
          status: 200,
        });
      }
      return new Response('Not Found', { status: 404 });
    };

    render(
      <AppProvider>
        <DoctorWrapper docId="doc-pre-1" />
      </AppProvider>
    );

    await waitFor(() => {
      expect(screen.getByText(/지정 서명의 불일치/)).toBeInTheDocument();
    });

    const approveBtn = screen.getByTestId('doctor-sign-approve-btn');
    expect(approveBtn).toBeDisabled();
  });

  it('displays request-list errors rather than swallowing them', async () => {
    globalThis.fetch = async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/content')) {
        return new Response(new Blob(['%PDF-1.4']), {
          status: 200,
          headers: { 'Content-Type': 'application/pdf' },
        });
      }
      if (url.includes('/documents/doc-pre-1')) {
        return new Response(JSON.stringify(baseDoc), { status: 200 });
      }
      if (url.includes('/sign-requests')) {
        return new Response(
          JSON.stringify({
            code: 'SIGN_REQUESTS_FETCH_ERROR',
            message: '서명 요청 조회 실패 테스트',
            correlationId: 'corr-fetch-fail',
            retryable: true,
          }),
          { status: 500 }
        );
      }
      return new Response('Not Found', { status: 404 });
    };

    render(
      <AppProvider>
        <DoctorWrapper docId="doc-pre-1" />
      </AppProvider>
    );

    // Assert: Error MUST be displayed in the ErrorBanner
    await waitFor(() => {
      expect(screen.getByText(/SIGN_REQUESTS_FETCH_ERROR/)).toBeInTheDocument();
      expect(screen.getByText(/서명 요청 조회 실패 테스트/)).toBeInTheDocument();
    });
  });

  it('preserves the SAME Idempotency-Key across approval retries after failure', async () => {
    const unexpiredReq: SignRequest = {
      id: 'req-retry-key-1',
      tenantId: 'demo-hospital-a',
      documentId: 'doc-pre-1',
      documentVersionId: 'ver-pre-1',
      documentSha256: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
      signerId: 'doctor-demo',
      nonce: 'nonce-valid',
      status: 'PENDING_APPROVAL',
      signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE',
      createdAt: new Date().toISOString(),
      createdBy: 'admin-demo',
      expiresAt: new Date(Date.now() + 600000).toISOString(),
      approvedAt: null,
      approvedBy: null,
      signatureId: null,
    };

    const sentKeys: string[] = [];

    globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString();
      if (url.includes('/content')) {
        return new Response(new Blob(['%PDF-1.4']), {
          status: 200,
          headers: { 'Content-Type': 'application/pdf' },
        });
      }
      if (url.includes('/documents/doc-pre-1')) {
        return new Response(JSON.stringify(baseDoc), { status: 200 });
      }
      if (url.includes('/sign-requests') && !url.includes('/approve')) {
        return new Response(JSON.stringify({ items: [unexpiredReq], nextCursor: null }), {
          status: 200,
        });
      }
      if (url.includes('/approve')) {
        const headers = init?.headers as Record<string, string>;
        sentKeys.push(headers['Idempotency-Key']);

        if (sentKeys.length === 1) {
          // First attempt fails with 503
          return new Response(
            JSON.stringify({
              code: 'SERVER_UNAVAILABLE',
              message: '일시 장애',
              correlationId: 'corr-503',
              retryable: true,
            }),
            { status: 503 }
          );
        }

        // Second attempt succeeds
        return new Response(
          JSON.stringify({
            ...unexpiredReq,
            status: 'COMPLETED',
            approvedAt: new Date().toISOString(),
            approvedBy: 'doctor-demo',
            signatureId: 'sig-ok-1',
          }),
          { status: 202 }
        );
      }
      return new Response('Not Found', { status: 404 });
    };

    render(
      <AppProvider>
        <DoctorWrapper docId="doc-pre-1" />
      </AppProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId('pdf-view-confirm-checkbox')).toBeInTheDocument();
    });

    fireEvent.load(screen.getByTitle('판독문 PDF 뷰어'));
    fireEvent.click(screen.getByTestId('pdf-view-confirm-checkbox'));

    const approveBtn = screen.getByTestId('doctor-sign-approve-btn');
    await waitFor(() => {
      expect(approveBtn).not.toBeDisabled();
    });

    // Click 1 (fails)
    fireEvent.click(approveBtn);

    await waitFor(() => {
      expect(screen.getByText(/SERVER_UNAVAILABLE/)).toBeInTheDocument();
    });

    // Click 2 (retry)
    fireEvent.click(approveBtn);

    await waitFor(() => {
      expect(screen.getByText(/전자서명 완료/)).toBeInTheDocument();
    });

    // Check that BOTH attempts sent the EXACT SAME Idempotency-Key
    expect(sentKeys).toHaveLength(2);
    expect(sentKeys[0]).toBeDefined();
    expect(sentKeys[0]).toBe(sentKeys[1]);
  });

  it('reconciles status on network ambiguity if server already completed the sign request', async () => {
    let approveCallCount = 0;
    const reqObj: SignRequest = {
      id: 'req-reconcile-1',
      tenantId: 'demo-hospital-a',
      documentId: 'doc-pre-1',
      documentVersionId: 'ver-pre-1',
      documentSha256: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
      signerId: 'doctor-demo',
      nonce: 'nonce-valid',
      status: 'PENDING_APPROVAL',
      signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE',
      createdAt: new Date().toISOString(),
      createdBy: 'admin-demo',
      expiresAt: new Date(Date.now() + 600000).toISOString(),
      approvedAt: null,
      approvedBy: null,
      signatureId: null,
    };

    globalThis.fetch = async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/content')) {
        return new Response(new Blob(['%PDF-1.4']), {
          status: 200,
          headers: { 'Content-Type': 'application/pdf' },
        });
      }
      if (url.includes('/documents/doc-pre-1')) {
        return new Response(JSON.stringify(baseDoc), { status: 200 });
      }
      if (url.includes('/sign-requests/req-reconcile-1/approve')) {
        approveCallCount++;
        // Simulate dropped response / connection reset
        throw new TypeError('Failed to fetch (network response dropped)');
      }
      if (url.endsWith('/sign-requests/req-reconcile-1')) {
        // Status query reveals the server actually completed the approval in-process!
        return new Response(
          JSON.stringify({
            ...reqObj,
            status: 'COMPLETED',
            approvedAt: new Date().toISOString(),
            approvedBy: 'doctor-demo',
            signatureId: 'sig-reconciled-99',
          }),
          { status: 200 }
        );
      }
      if (url.includes('/sign-requests')) {
        return new Response(JSON.stringify({ items: [reqObj], nextCursor: null }), {
          status: 200,
        });
      }
      return new Response('Not Found', { status: 404 });
    };

    render(
      <AppProvider>
        <DoctorWrapper docId="doc-pre-1" />
      </AppProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId('pdf-view-confirm-checkbox')).toBeInTheDocument();
    });

    fireEvent.load(screen.getByTitle('판독문 PDF 뷰어'));
    fireEvent.click(screen.getByTestId('pdf-view-confirm-checkbox'));
    const approveBtn = screen.getByTestId('doctor-sign-approve-btn');

    await waitFor(() => {
      expect(approveBtn).not.toBeDisabled();
    });

    fireEvent.click(approveBtn);

    // Assert: UI reconciles via status query and displays COMPLETED rather than failing
    await waitFor(() => {
      expect(screen.getByText(/전자서명 완료/)).toBeInTheDocument();
      expect(screen.getByText(/sig-reconciled-99/)).toBeInTheDocument();
    });

    expect(approveCallCount).toBe(1);
  });
});
