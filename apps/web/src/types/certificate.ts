import { Doctor } from './api';

export type ExternalCaStatus = 'GOOD' | 'REVOKED' | 'UNKNOWN' | 'STALE';

export type CertificateRegistrationStatus = 'PENDING' | 'ACTIVE' | 'REVOKED';

export type EnrollmentKind = 'PC_CERTIFICATE' | 'MOBILE_CERTIFICATE' | 'MOCK_CERTIFICATE';

export type EnrollmentReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export type EnrollmentReviewMethod = 'ADMIN_REVIEW' | 'EXPECTED_FINGERPRINT' | null;

export type CertificateChallengePurpose =
  | 'CERTIFICATE_ENROLLMENT'
  | 'CERTIFICATE_LOGIN';

export interface PcCertificateConfig {
  trustConfigured: boolean;
  trustAnchorCount: number;
  crlCount: number;
  allowedSignatureAlgorithms: string[];
  revocation: string; // 'CRL'
  ocspSupported: boolean; // false
}

export interface MobileConfig {
  configured: boolean;
  provider: string | null;
}

export interface CertificateEnrollmentConfig {
  activationPolicy: 'CERTIFICATE_REQUIRED' | 'GOOGLE_PERMITTED_DEV' | string;
  serviceId: string;
  challengeTtlSeconds: number;
  pcCertificate: PcCertificateConfig;
  mobile: MobileConfig;
  devMockCertificateEnabled: boolean;
  clinicalUseAllowed: boolean;
}

export interface CertificateChallenge {
  challengeId: string;
  registrationId: string | null;
  purpose: CertificateChallengePurpose;
  doctorId: string | null;
  tenantId: string | null;
  serviceId: string;
  nonce: string;
  issuedAt: string;
  expiresAt: string;
  signedPayload: string;
  signedPayloadBase64: string;
  allowedSignatureAlgorithms: string[];
}

export interface Enrollment {
  registrationId: string;
  doctorId: string;
  tenantId: string;
  kind: EnrollmentKind;
  registrationStatus: CertificateRegistrationStatus;
  externalStatus: ExternalCaStatus;
  externalCheckedAt: string | null;
  reviewStatus: EnrollmentReviewStatus;
  reviewMethod: EnrollmentReviewMethod;
  reviewedBy: string | null;
  reviewedAt: string | null;
  consentedAt: string | null;
  fingerprintSha256: string; // Lowercase hex SHA-256 of the FULL DER certificate
  publicKeySha256: string;   // SHA-256 of SubjectPublicKeyInfo DER
  subjectDn: string;
  issuerDn: string;
  serialNumber: string;
  notBefore: string;
  notAfter: string;
  signatureAlgorithm: string;
  keyAlgorithm: string;
  provider: string | null;
  providerSubject: string | null;
  createdAt: string;
  activatedAt: string | null;
  revokedAt: string | null;
  revocationReason: string | null;
  supersedes: string | null;

  // Convenient optional aliases for view and legacy compatibility
  candidateId?: string;
  algorithm?: string;
  externalCaStatus?: ExternalCaStatus;
  caStatus?: ExternalCaStatus;
  evidenceRef?: string | null;
}

// Backward-compatible alias for views referencing ExternalCertCandidate
export type ExternalCertCandidate = Enrollment;

export interface CertificateSessionResponse {
  enrollment: Enrollment;
  activationToken: string;
  expiresAt: string;
  identityMode: 'PC_CERTIFICATE' | 'MOBILE_CERTIFICATE' | 'MOCK_CERTIFICATE' | string;
  doctor: Doctor;
}

export type CertificateVerificationRequest = VerifyRequest;
export type CertificateVerificationResponse = CertificateSessionResponse;

export interface VerifyRequest {
  registrationId: string;
  certificate: string; // Base64 DER
  chain: string[];     // Array of Base64 DER for intermediate CAs
  signature: string;   // Base64 DER
  signatureAlgorithm: 'SHA256withRSA' | 'SHA256withECDSA' | string;
}

export interface CertificateLoginRequest {
  challengeId: string;
  fingerprintSha256: string; // Lowercase hex SHA-256 of full DER certificate
  certificate: string;       // Base64 DER
  chain: string[];           // Base64 DER
  signature: string;         // Base64 DER
  signatureAlgorithm: 'SHA256withRSA' | 'SHA256withECDSA' | string;
}

export interface CertificateConsentRequest {
  version: string;
  authentication: boolean;
  signature: boolean;
  privacy: boolean;
}

export interface AdminCertificateReviewRequest {
  registrationId: string;
  decision: 'APPROVE' | 'REJECT';
  fingerprintSha256: string;
  evidenceRef: string;
  note?: string;
}

export interface CertificateUnregisterRequest {
  reason: string;
}

export interface DevMockSignRequest {
  challengeId: string;
  fingerprintSha256?: string | null;
}

export interface MobileStartRequest {
  purpose: 'CERTIFICATE_ENROLLMENT' | 'CERTIFICATE_LOGIN';
  invitationCode?: string;
}

export interface MobileStartResponse {
  transactionId: string;
  purpose: string;
  provider: string;
  expiresAt: string;
  transactionSecret: string;
  providerStart: Record<string, unknown>;
}

export interface MobileCallbackResponse {
  transactionId: string;
  status: string;
}

export interface MobileConsumeRequest {
  transactionSecret: string;
}

export interface MobileCertificateTransaction {
  transactionId: string;
  purpose?: string;
  provider: string;
  status?: string;
  expiresAt: string;
  transactionSecret?: string;
}
