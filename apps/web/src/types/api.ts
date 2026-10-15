/**
 * Vision-Certi Typed API Models & Enums
 * Target: OpenAPI 3.1.0 (contracts/openapi.dev.json) & apps/backend/docs/API.md
 * DEV MOCK NOT FOR CLINICAL USE
 */
import type { ExternalCertCandidate } from './certificate';

export type DevTenantId = 'demo-hospital-a' | 'demo-hospital-b';
export type DevActorId = 'doctor-demo' | 'admin-demo';
export type DevRole = 'DOCTOR' | 'ADMIN';

export interface ApiError {
  code: string;
  message: string;
  correlationId: string;
  retryable: boolean;
}

export interface ListResponse<T> {
  items: T[];
  nextCursor: string | null;
}

export interface DocumentMetadata {
  externalDocumentId: string;
  externalStudyId: string;
  title: string;
  signerId: string;
}

export interface DocumentVersion {
  id: string;
  documentId: string;
  versionNumber: number;
  sha256: string;
  sizeBytes: number;
  contentType: 'application/pdf';
  createdAt: string;
  createdBy: string;
  previousVersionId: string | null;
  amendmentReason: string | null;
}

export interface Document {
  id: string;
  tenantId: string;
  externalDocumentId: string;
  externalStudyId: string;
  title: string;
  signerId: string;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  latestVersion: DocumentVersion;
  versions: DocumentVersion[];
}

export type SignRequestStatus =
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'SIGNING'
  | 'EVIDENCE_PENDING'
  | 'COMPLETED'
  | 'EXPIRED'
  | 'REJECTED'
  | 'FAILED'
  | 'RECONCILIATION_REQUIRED';

export interface CreateSignRequest {
  documentVersionId: string;
  signerId: string;
}

export interface ApproveSignRequest {
  documentSha256: string;
  nonce: string;
}

export interface SignRequest {
  id: string;
  tenantId: string;
  documentId: string;
  documentVersionId: string;
  documentSha256: string;
  signerId: string;
  nonce: string | null;
  status: SignRequestStatus;
  signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE';
  createdAt: string;
  createdBy: string;
  expiresAt: string;
  approvedAt: string | null;
  approvedBy: string | null;
  signatureId: string | null;
}

export interface DoctorSignatureEvidence {
  doctorId: string;
  identityMode: string;
  identityIssuer: string;
  identitySubject: string;
  certificateId: string;
  certificateKind: string;
  keyName: string;
  keyVersion: number;
  approvalId: string;
  reportId: string;
  documentVersionId: string;
  documentSha256: string;
  signedAt: string;
  auditId: string;
  signatureFormat: string;
  signingBackend: string;
  cryptographicallyAttributable: boolean;
  revocationCheckedAtSigning: boolean;
  publicKeySpki: string;
  certificateEnrollmentId?: string | null;
  certificateEnrollmentFingerprint?: string | null;
}

export interface Signature {
  id: string;
  tenantId: string;
  signRequestId: string;
  documentId: string;
  documentVersionId: string;
  documentSha256: string;
  signerId: string;
  signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE' | string;
  algorithm: string;
  signatureValue: string;
  clinicalUseAllowed: false;
  createdAt: string;
  doctorEvidence?: DoctorSignatureEvidence | null;
}

export type VerificationCheckStatus = 'VALID' | 'INVALID' | 'INDETERMINATE';

export interface VerificationChecks {
  documentIntegrity: VerificationCheckStatus;
  signature: VerificationCheckStatus;
  trustChain: VerificationCheckStatus;
  timestamp: VerificationCheckStatus;
  revocationAtSigning: VerificationCheckStatus;
  authorizationAtSigning: VerificationCheckStatus;
}

export interface Verification {
  signatureId: string;
  status: 'INVALID' | 'INDETERMINATE';
  signatureMode: 'MOCK_NOT_VALID_FOR_CLINICAL_USE';
  clinicalUseAllowed: false;
  mockSignatureIntact: boolean;
  checks: VerificationChecks;
  reasonCodes: string[];
  verifiedAt: string;
}

export type DeliveryStatus =
  | 'QUEUED'
  | 'SENDING'
  | 'ACKED'
  | 'RETRY_WAIT'
  | 'DEAD_LETTER';

export interface Delivery {
  id: string;
  tenantId: string;
  signatureId: string;
  signRequestId: string;
  documentId: string;
  connectorId: string;
  eventType: string;
  destination: string;
  status: DeliveryStatus;
  attempts: number;
  createdAt: string;
  updatedAt: string;
}

export interface AuditEvent {
  id: string;
  tenantId: string;
  sequence: number;
  occurredAt: string;
  actorId: string;
  action: string;
  targetType: string;
  targetId: string;
  outcome: string;
  correlationId: string;
  details: Record<string, string>;
  previousHash: string;
  hash: string;
}

export interface DashboardSummary {
  tenantId: string;
  generatedAt: string;
  signatureMode: string;
  persistence: 'IN_MEMORY';
  documents: number;
  documentVersions: number;
  signRequestsByStatus: Record<string, number>;
  signatures: number;
  deliveriesByStatus: Record<string, number>;
  auditEvents: number;
}

export interface HealthInfo {
  status: string;
  service: string;
  activeProfiles: string[];
  persistence: 'IN_MEMORY';
  signatureMode: string;
  devSyntheticIdentities: boolean;
  clinicalUseAllowed: false;
}

// ==========================================
// Doctor Pre-registration & Activation Types
// ==========================================

export type DoctorStatus =
  | 'PENDING'
  | 'IDENTITY_VERIFIED'
  | 'CONSENTED'
  | 'ACTIVE'
  | 'SUSPENDED'
  | 'REVOKED';

export type DoctorIdentityMode =
  | 'GOOGLE'
  | 'MOCK_IDENTITY'
  | 'PC_CERTIFICATE'
  | 'MOBILE_CERTIFICATE'
  | 'MOCK_CERTIFICATE'
  | 'CERTIFICATE_PC'
  | 'CERTIFICATE_MOBILE'
  | null;

export * from './certificate';

export type InvitationState = 'ISSUED' | 'USED' | 'EXPIRED' | 'LOCKED' | 'VOID';

export type SigningBackend = 'NOT_CONFIGURED' | 'OPENBAO_TRANSIT' | 'DEV_SIMULATED';

export type CertificateKind = 'X509' | 'SIMULATED_NOT_X509';

export type CertificateStatus = 'VALID' | 'REVOKED' | 'EXPIRED';

export interface DoctorCertificate {
  certificateId: string;
  kind: CertificateKind;
  backend: SigningBackend;
  subject: string;
  issuer: string;
  serialNumber: string;
  notBefore: string;
  notAfter: string;
  publicKeySha256: string;
  keyName: string;
  keyVersion: number;
  status: CertificateStatus;
  certificatePem: string | null;
}

export interface Doctor {
  id: string;
  tenantId: string;
  name: string;
  licenseNumber: string;
  centerName: string;
  googleEmail: string | null;
  status: DoctorStatus;
  identityMode: DoctorIdentityMode;
  googleAccountLinked: boolean;
  invitationState: InvitationState;
  invitationExpiresAt: string;
  identityVerifiedAt: string | null;
  consentedAt: string | null;
  consentVersion: string | null;
  activatedAt: string | null;
  statusReason: string | null;
  statusChangedAt: string | null;
  createdAt: string;
  createdBy: string;
  signingBackend: SigningBackend;
  certificate: DoctorCertificate | null;
  expectedCertificateFingerprint?: string | null;
  identityEvidenceRef?: string | null;
  licenseEvidenceRef?: string | null;
  authCertificate?: ExternalCertCandidate | null;
  candidateCertificates?: ExternalCertCandidate[];
}

export interface DoctorActivationConfig {
  googleClientId: string | null;
  googleConfigured: boolean;
  devSimulationEnabled: boolean;
  signingBackend: SigningBackend;
  signingBackendAvailable: boolean;
  clinicalUseAllowed: false;
  activationPolicy?: string;
  mockGoogleEmail?: string;
  mockGoogleSubject?: string;
}

export interface PreregisterDoctorRequest {
  name: string;
  licenseNumber: string;
  centerName: string;
  googleEmail: string;
  identityEvidenceRef: string;
  licenseEvidenceRef: string;
  expectedCertificateFingerprint?: string | null;
}

export interface PreregisterDoctorResponse {
  doctor: Doctor;
  invitationCode: string;
  invitationExpiresAt: string;
}

export interface DoctorStatusUpdateRequest {
  status: 'SUSPENDED' | 'REVOKED';
  reason: string;
}

export interface ActivationChallenge {
  challengeId: string;
  nonce: string;
  expiresAt: string;
  purpose: 'ACTIVATION' | 'LOGIN';
}

export interface VerifyGoogleRequest {
  challengeId: string;
  idToken: string;
}

export interface DevIdentityRequest {
  challengeId: string;
  email: string;
  subject: string;
}

export interface ActivationSessionResponse {
  activationToken: string;
  expiresAt: string;
  identityMode: 'GOOGLE' | 'MOCK_IDENTITY';
  doctor: Doctor;
}

export interface DoctorConsentRequest {
  version: '2026-10-13-draft';
  authentication: true;
  signature: true;
  privacy: true;
}

export interface DoctorMeResponse {
  doctor: Doctor;
  certificate: DoctorCertificate | null;
  identityMode: 'GOOGLE' | 'MOCK_IDENTITY';
  sessionExpiresAt?: string;
}

export interface PacsHandoffResponse {
  handoffCode: string;
  expiresAt: string;
  audience: string;
  tenantId: string;
}

export interface RuntimeCapabilities {
  profile: 'prod' | 'dev';
  persistence: {
    mode: 'POSTGRESQL' | 'IN_MEMORY';
    schemaVersion?: number;
    state?: string;
  };
  staffAuth: {
    mode: 'OIDC' | 'DEV_HEADERS';
    issuer?: string;
    clientId?: string;
    audience?: string;
    scopes?: string[];
    acceptedTokens?: string;
  };
  doctorAuth: {
    pcCertificate: {
      configured: boolean;
      trustAnchors: number;
      crlState: string;
    };
    mobile: {
      configured: boolean;
      provider: string | null;
    };
    googleConfigured: boolean;
    devMockCertificate: boolean;
    devIdentity: boolean;
  };
  signing: {
    backend: 'OPENBAO_TRANSIT' | 'DEV_SIMULATED' | 'UNAVAILABLE';
    available: boolean;
    signatureMode?: string;
    pki?: string;
    timestamp?: string;
    verificationLabel?: string;
  };
  personalSignup?: {
    available: boolean;
    reason?: string;
  };
  pacsIntegration?: {
    available: boolean;
    reason?: string;
  };
  clinicalUseAllowed: boolean;
  clinicalUseReason?: string;
  warnings?: string[];
}

export interface StaffMeResponse {
  tenantId: string;
  actorId: string;
  role: string;
  displayName: string;
  issuer: string;
  principalId: string;
}

export interface PacsReportSnapshot {
  schema: string;
  tenantId: string;
  doctorId: string;
  pacs?: {
    clientId: string;
    userIdx: number;
    userId: string;
    userName?: string;
    doctorLicenseNumber?: string;
  };
  study?: {
    externalStudyId: string;
    studyIdx: number;
    accessionNo?: string;
    patientId?: string;
    patientName?: string;
    modality?: string;
    bodyPart?: string;
    description?: string;
    studyDate?: string;
    hospitalIdx?: number;
    hospitalName?: string;
  };
  report?: {
    externalReportId: string;
    revision: number;
    approvalStatus: string;
    contentSha256: string;
    supersedesApprovalId?: string | null;
    priorSignedSnapshotSha256?: string | null;
    reportIdx?: number;
    reading?: string;
    conclusion?: string;
    studyComment?: string;
    referComment?: string;
    cvr?: string;
    createdByUserIdx?: number;
  };
}

export * from './certificate';
