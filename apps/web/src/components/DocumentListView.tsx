import React, { useEffect, useState, useCallback } from 'react';
import {
  FileText,
  Upload,
  RefreshCw,
  ExternalLink,
  Search,
} from 'lucide-react';
import { useApp } from '../context/AppContext';
import { Document } from '../types/api';
import { DocumentUploadModal } from './DocumentUploadModal';
import { DocumentDetailView } from './DocumentDetailView';
import { ErrorBanner } from './ErrorBanner';
import { LoadingSpinner } from './LoadingSpinner';
import { EmptyState } from './EmptyState';

export const DocumentListView: React.FC = () => {
  const {
    tenantId,
    client,
    isMockMode,
    mockStore,
    selectedDocumentId,
    setSelectedDocumentId,
  } = useApp();

  const [documents, setDocuments] = useState<Document[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<unknown>(null);
  const [searchTerm, setSearchTerm] = useState<string>('');
  const [isUploadModalOpen, setIsUploadModalOpen] = useState<boolean>(false);

  const fetchGenRef = React.useRef<number>(0);

  const loadDocuments = useCallback(async () => {
    const currentGen = ++fetchGenRef.current;
    setLoading(true);
    setError(null);
    try {
      if (isMockMode) {
        const tenantDocs = mockStore.documents[tenantId] || [];
        if (currentGen !== fetchGenRef.current) return;
        setDocuments(tenantDocs);
      } else {
        const res = await client.getDocuments();
        if (currentGen !== fetchGenRef.current) return;
        setDocuments(res.items || []);
      }
    } catch (err) {
      if (currentGen === fetchGenRef.current) {
        setError(err);
      }
    } finally {
      if (currentGen === fetchGenRef.current) {
        setLoading(false);
      }
    }
  }, [client, isMockMode, mockStore, tenantId]);

  useEffect(() => {
    loadDocuments();
  }, [loadDocuments]);

  // If a document is selected, render DocumentDetailView
  if (selectedDocumentId) {
    return (
      <DocumentDetailView
        documentId={selectedDocumentId}
        onBack={() => setSelectedDocumentId(null)}
      />
    );
  }

  const filtered = documents.filter((doc) => {
    const q = searchTerm.toLowerCase();
    return (
      doc.title.toLowerCase().includes(q) ||
      doc.externalDocumentId.toLowerCase().includes(q) ||
      doc.externalStudyId.toLowerCase().includes(q) ||
      doc.signerId.toLowerCase().includes(q)
    );
  });

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
        <div>
          <h2 style={{ fontSize: '1.4rem', fontWeight: 800, color: 'var(--navy-900)' }}>
            판독문 목록 및 원본 등록
          </h2>
          <p style={{ fontSize: '0.85rem', color: 'var(--navy-600)' }}>
            기관 <strong>{tenantId}</strong> 에 등록된 의료영상 판독문 원본 및 정정 버전 내역
          </p>
        </div>

        <div style={{ display: 'flex', gap: '10px' }}>
          <button
            type="button"
            onClick={loadDocuments}
            className="btn btn-secondary"
            disabled={loading}
            aria-label="판독문 목록 새로고침"
          >
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
            새로고침
          </button>

          <button
            type="button"
            onClick={() => setIsUploadModalOpen(true)}
            className="btn btn-primary"
          >
            <Upload size={14} />
            새 판독문 등록 (Upload)
          </button>
        </div>
      </div>

      <ErrorBanner error={error as Error} onRetry={loadDocuments} />

      {/* Search Filter */}
      <div style={{ marginBottom: '16px', position: 'relative', maxWidth: '380px' }}>
        <input
          type="text"
          className="form-input"
          placeholder="제목, 문서ID, 검사번호 검색..."
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          style={{ paddingLeft: '32px' }}
        />
        <Search
          size={16}
          style={{
            position: 'absolute',
            left: '10px',
            top: '50%',
            transform: 'translateY(-50%)',
            color: 'var(--navy-400)',
          }}
        />
      </div>

      {loading && !documents.length ? (
        <LoadingSpinner message="판독문 목록을 불러오는 중입니다..." />
      ) : filtered.length === 0 ? (
        <EmptyState
          title="등록된 판독문이 없습니다"
          message={
            searchTerm
              ? '검색어와 일치하는 판독문이 없습니다.'
              : '현재 기관에 등록된 판독문이 없습니다. 우측 상단의 [새 판독문 등록] 버튼을 눌러 첫 판독문 PDF를 업로드하세요.'
          }
          actionText={!searchTerm ? '새 판독문 등록' : undefined}
          onAction={!searchTerm ? () => setIsUploadModalOpen(true) : undefined}
        />
      ) : (
        <div className="card">
          <div className="table-responsive">
            <table className="table">
              <thead>
                <tr>
                  <th>판독문 제목</th>
                  <th>외부 문서 ID</th>
                  <th>검사 번호</th>
                  <th>최신 버전</th>
                  <th>지정 전문의</th>
                  <th>등록 일시</th>
                  <th>작업</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((doc) => (
                  <tr key={doc.id}>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                        <FileText size={16} style={{ color: 'var(--teal-700)', flexShrink: 0 }} />
                        <span style={{ fontWeight: 600 }}>{doc.title}</span>
                      </div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)', marginTop: '2px' }}>
                        UUID: {doc.id}
                      </div>
                    </td>
                    <td>
                      <code>{doc.externalDocumentId}</code>
                    </td>
                    <td>
                      <code>{doc.externalStudyId}</code>
                    </td>
                    <td>
                      <span className="badge badge-planned">
                        v{doc.latestVersion.versionNumber} ({doc.versions.length}개 이력)
                      </span>
                    </td>
                    <td>
                      <span style={{ fontWeight: 600 }}>{doc.signerId}</span>
                    </td>
                    <td style={{ fontSize: '0.8rem', color: 'var(--navy-600)' }}>
                      {new Date(doc.createdAt).toLocaleString('ko-KR')}
                    </td>
                    <td>
                      <button
                        type="button"
                        onClick={() => setSelectedDocumentId(doc.id)}
                        className="btn btn-secondary"
                        style={{ padding: '6px 12px', fontSize: '0.75rem' }}
                      >
                        <ExternalLink size={14} />
                        상세 및 서명
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Upload Modal */}
      <DocumentUploadModal
        isOpen={isUploadModalOpen}
        onClose={() => setIsUploadModalOpen(false)}
        onSuccess={(newDoc) => {
          setDocuments((prev) => [newDoc, ...prev]);
          setSelectedDocumentId(newDoc.id); // Open newly uploaded document immediately
        }}
      />
    </div>
  );
};
