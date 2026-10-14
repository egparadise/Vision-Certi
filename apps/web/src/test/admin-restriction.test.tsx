import React from 'react';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { AppProvider } from '../context/AppContext';
import { DocumentDetailView } from '../components/DocumentDetailView';
import { Document, SignRequest } from '../types/api';

const mockDoc: Document = {
  id: 'doc-test-1',
  tenantId: 'demo-hospital-a',
  externalDocumentId: 'EXT-101',
  externalStudyId: 'ST-202',
  title: '두부 CT 판독소견서 (테스트)',
  signerId: 'doctor-demo',
  createdAt: '2026-10-13T05:00:00Z',
  updatedAt: '2026-10-13T05:00:00Z',
  createdBy: 'admin-demo',
  latestVersion: {
    id: 'ver-test-1',
    documentId: 'doc-test-1',
    versionNumber: 1,
    sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    sizeBytes: 12000,
    contentType: 'application/pdf',
    createdAt: '2026-10-13T05:00:00Z',
    createdBy: 'admin-demo',
    previousVersionId: null,
    amendmentReason: null,
  },
  versions: [
    {
      id: 'ver-test-1',
      documentId: 'doc-test-1',
      versionNumber: 1,
      sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      sizeBytes: 12000,
      contentType: 'application/pdf',
      createdAt: '2026-10-13T05:00:00Z',
      createdBy: 'admin-demo',
      previousVersionId: null,
      amendmentReason: null,
    },
  ],
};

const mockSignReq: SignRequest = {
  id: 'req-test-1',
  tenantId: 'demo-hospital-a',
  documentId: 'doc-test-1',
  documentVersionId: 'ver-test-1',
  documentSha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
  signerId: 'doctor-demo',
  nonce: 'mock-nonce-12345',
  status: 'PENDING_APPROVAL',
  signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE',
  createdAt: '2026-10-13T05:05:00Z',
  createdBy: 'admin-demo',
  expiresAt: '2026-10-13T05:15:00Z',
  approvedAt: null,
  approvedBy: null,
  signatureId: null,
};

describe('Admin Signature Restriction Tests', () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    globalThis.fetch = async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/documents/doc-test-1/versions/ver-test-1/content')) {
        return new Response(new Blob(['%PDF-1.4 test bytes']), {
          status: 200,
          headers: { 'Content-Type': 'application/pdf' },
        });
      }
      if (url.includes('/documents/doc-test-1')) {
        return new Response(JSON.stringify(mockDoc), { status: 200 });
      }
      if (url.includes('/sign-requests')) {
        return new Response(JSON.stringify({ items: [mockSignReq], nextCursor: null }), {
          status: 200,
        });
      }
      return new Response('Not Found', { status: 404 });
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('hides/restricts the sign button when logged in as admin-demo and displays security warning', async () => {
    // Render in admin mode
    render(
      <AppProvider>
        <TestAdminWrapper />
      </AppProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('두부 CT 판독소견서 (테스트)')).toBeInTheDocument();
    });

    // Verify admin restriction alert is present
    expect(screen.getByTestId('admin-sign-restriction-alert')).toBeInTheDocument();
    expect(
      screen.getByText(/관리자는 의사 판독문 서명 버튼을 사용할 수 없습니다/i)
    ).toBeInTheDocument();

    // Verify final approve sign button is NOT rendered for admin
    expect(screen.queryByTestId('doctor-sign-approve-btn')).not.toBeInTheDocument();
  });

  it('renders sign approval button when logged in as doctor-demo', async () => {
    render(
      <AppProvider>
        <TestDoctorWrapper />
      </AppProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('두부 CT 판독소견서 (테스트)')).toBeInTheDocument();
    });

    // In doctor mode, sign button MUST be present
    await waitFor(() => {
      expect(screen.getByTestId('doctor-sign-approve-btn')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('admin-sign-restriction-alert')).not.toBeInTheDocument();
  });
});

// Helper component that sets actorId to admin-demo
const TestAdminWrapper: React.FC = () => {
  return <AdminSubComponent />;
};

import { useApp } from '../context/AppContext';

const AdminSubComponent: React.FC = () => {
  const { setActorId } = useApp();
  React.useEffect(() => {
    setActorId('admin-demo');
  }, [setActorId]);

  return <DocumentDetailView documentId="doc-test-1" onBack={() => {}} />;
};

const TestDoctorWrapper: React.FC = () => {
  const { setActorId } = useApp();
  React.useEffect(() => {
    setActorId('doctor-demo');
  }, [setActorId]);

  return <DocumentDetailView documentId="doc-test-1" onBack={() => {}} />;
};
