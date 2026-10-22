import type { PhysicianAccountConfig, PhysicianAccountRegistration, PhysicianAccountReceipt } from '../types/physicianAccount';
import type { MedicalCredentialsRequest } from '../types/certificate';
import {
  ActivationChallenge,
  ActivationSessionResponse,
  ApiError,
  AuditEvent,
  DashboardSummary,
  Delivery,
  DevActorId,
  DevTenantId,
  Doctor,
  DoctorActivationConfig,
  DoctorConsentRequest,
  DoctorMeResponse,
  DoctorStatusUpdateRequest,
  Document,
  DocumentMetadata,
  HealthInfo,
  ListResponse,
  PreregisterDoctorRequest,
  PreregisterDoctorResponse,
  SignRequest,
  Signature,
  Verification,
  CertificateEnrollmentConfig,
  CertificateChallenge,
  VerifyRequest,
  CertificateLoginRequest,
  CertificateSessionResponse,
  CertificateConsentRequest,
  Enrollment,
  MobileStartResponse,
  AdminCertificateReviewRequest,
  CertificateUnregisterRequest,
  PacsHandoffResponse,
  RuntimeCapabilities,
  StaffMeResponse,
} from '../types/api';
import type {
  CreateStaffPrincipalRequest,
  MedicalInstitution,
  ResetSystemSettingRequest,
  StaffPrincipal,
  SystemConfigurationResponse,
  SystemSettingsResponse,
  SystemSettingView,
  UpdateSystemSettingRequest,
} from '../types/admin';
import { isDemoEnabled } from '../utils/env';
import { apiBaseUrl } from './baseUrl';

export class ApiClientError extends Error {
  readonly code: string;
  readonly correlationId: string;
  readonly retryable: boolean;
  readonly status: number;

  constructor(status: number, error: ApiError) {
    super(error.message || `API Error: ${error.code}`);
    this.name = 'ApiClientError';
    this.status = status;
    this.code = error.code || 'UNKNOWN_ERROR';
    this.correlationId = error.correlationId || 'none';
    this.retryable = Boolean(error.retryable);
  }
}

export interface ApiClientConfig {
  baseUrl?: string;
  tenantId: DevTenantId;
  actorId: DevActorId;
  staffToken?: string | null;
  localAdminToken?: string | null;
}

export class ApiClient {
  private baseUrl: string;
  private tenantId: DevTenantId;
  private actorId: DevActorId;
  private staffToken: string | null = null;
  private localAdminToken: string | null = null;

  constructor(config: ApiClientConfig) {
    this.baseUrl = config.baseUrl || apiBaseUrl();
    this.tenantId = config.tenantId;
    this.actorId = config.actorId;
    this.staffToken = config.staffToken || null;
    this.localAdminToken = config.localAdminToken || null;
  }

  setTenantId(tenantId: DevTenantId) {
    this.tenantId = tenantId;
  }

  setActorId(actorId: DevActorId) {
    this.actorId = actorId;
  }

  setStaffToken(staffToken: string | null) {
    this.staffToken = staffToken;
  }

  getTenantId(): DevTenantId {
    return this.tenantId;
  }

  getActorId(): DevActorId {
    return this.actorId;
  }

  getStaffToken(): string | null {
    return this.staffToken;
  }

  private getHeaders(extraHeaders?: Record<string, string>): HeadersInit {
    const headers: Record<string, string> = {
      'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      ...extraHeaders,
    };
    if (this.staffToken) {
      headers['Authorization'] = `Bearer ${this.staffToken}`;
    } else if (isDemoEnabled()) {
      headers['X-Tenant-Id'] = this.tenantId;
      headers['X-Actor-Id'] = this.actorId;
      if (this.actorId === 'admin-demo' && this.localAdminToken) {
        headers['Authorization'] = `Bearer ${this.localAdminToken}`;
      }
    }
    return headers;
  }

  private async handleResponse<T>(response: Response): Promise<T> {
    if (!response.ok) {
      let errorData: ApiError;
      try {
        errorData = await response.json();
      } catch {
        errorData = {
          code: `HTTP_${response.status}`,
          message: response.statusText || '서버 통신 오류가 발생했습니다.',
          correlationId: response.headers.get('x-correlation-id') || 'none',
          retryable: response.status >= 500,
        };
      }
      throw new ApiClientError(response.status, errorData);
    }

    if (response.status === 204) {
      return {} as T;
    }

    return response.json();
  }

  async getHealth(): Promise<HealthInfo> {
    const res = await fetch(`${this.baseUrl}/health`, {
      method: 'GET',
    });
    return this.handleResponse<HealthInfo>(res);
  }

  async getDashboard(): Promise<DashboardSummary> {
    const res = await fetch(`${this.baseUrl}/dashboard`, {
      method: 'GET',
      headers: this.getHeaders(),
    });
    return this.handleResponse<DashboardSummary>(res);
  }

  async getDocuments(): Promise<ListResponse<Document>> {
    const res = await fetch(`${this.baseUrl}/documents`, {
      method: 'GET',
      headers: this.getHeaders(),
    });
    return this.handleResponse<ListResponse<Document>>(res);
  }

  async getDocument(id: string): Promise<Document> {
    const res = await fetch(`${this.baseUrl}/documents/${id}`, {
      method: 'GET',
      headers: this.getHeaders(),
    });
    return this.handleResponse<Document>(res);
  }

  async getDocumentContentBlob(
    documentId: string,
    versionId: string
  ): Promise<Blob> {
    const res = await fetch(
      `${this.baseUrl}/documents/${documentId}/versions/${versionId}/content`,
      {
        method: 'GET',
        headers: this.getHeaders(),
      }
    );
    if (!res.ok) {
      let errorData: ApiError;
      try {
        errorData = await res.json();
      } catch {
        errorData = {
          code: `HTTP_${res.status}`,
          message: '문서 바이너리 열람 실패',
          correlationId: res.headers.get('x-correlation-id') || 'none',
          retryable: res.status >= 500,
        };
      }
      throw new ApiClientError(res.status, errorData);
    }
    return res.blob();
  }

  async registerDocument(
    file: File | Blob,
    metadata: DocumentMetadata,
    idempotencyKey?: string
  ): Promise<Document> {
    const formData = new FormData();
    formData.append(
      'metadata',
      new Blob([JSON.stringify(metadata)], { type: 'application/json' })
    );
    formData.append('file', file, 'document.pdf');

    const headers: Record<string, string> = {};
    if (idempotencyKey) {
      headers['Idempotency-Key'] = idempotencyKey;
    }

    const res = await fetch(`${this.baseUrl}/documents`, {
      method: 'POST',
      headers: this.getHeaders(headers),
      body: formData,
    });
    return this.handleResponse<Document>(res);
  }

  async amendDocument(
    documentId: string,
    file: File | Blob,
    previousVersionId: string,
    amendmentReason: string,
    idempotencyKey?: string
  ): Promise<Document> {
    const formData = new FormData();
    formData.append('file', file, 'amended.pdf');
    formData.append('previousVersionId', previousVersionId);
    formData.append('amendmentReason', amendmentReason);

    const headers: Record<string, string> = {};
    if (idempotencyKey) {
      headers['Idempotency-Key'] = idempotencyKey;
    }

    const res = await fetch(`${this.baseUrl}/documents/${documentId}/versions`, {
      method: 'POST',
      headers: this.getHeaders(headers),
      body: formData,
    });
    return this.handleResponse<Document>(res);
  }

  async getSignRequests(): Promise<ListResponse<SignRequest>> {
    const res = await fetch(`${this.baseUrl}/sign-requests`, {
      method: 'GET',
      headers: this.getHeaders(),
    });
    return this.handleResponse<ListResponse<SignRequest>>(res);
  }

  async getSignRequest(id: string): Promise<SignRequest> {
    const res = await fetch(`${this.baseUrl}/sign-requests/${id}`, {
      method: 'GET',
      headers: this.getHeaders(),
    });
    return this.handleResponse<SignRequest>(res);
  }

  async createSignRequest(
    documentVersionId: string,
    signerId: string,
    idempotencyKey?: string
  ): Promise<SignRequest> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (idempotencyKey) {
      headers['Idempotency-Key'] = idempotencyKey;
    }

    const res = await fetch(`${this.baseUrl}/sign-requests`, {
      method: 'POST',
      headers: this.getHeaders(headers),
      body: JSON.stringify({ documentVersionId, signerId }),
    });
    return this.handleResponse<SignRequest>(res);
  }

  async approveSignRequest(
    id: string,
    documentSha256: string,
    nonce: string,
    idempotencyKey: string
  ): Promise<SignRequest> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    };

    const res = await fetch(`${this.baseUrl}/sign-requests/${id}/approve`, {
      method: 'POST',
      headers: this.getHeaders(headers),
      body: JSON.stringify({ documentSha256, nonce }),
    });
    return this.handleResponse<SignRequest>(res);
  }

  async getSignatures(): Promise<ListResponse<Signature>> {
    const res = await fetch(`${this.baseUrl}/signatures`, {
      method: 'GET',
      headers: this.getHeaders(),
    });
    return this.handleResponse<ListResponse<Signature>>(res);
  }

  async verifySignature(signatureId: string): Promise<Verification> {
    const res = await fetch(`${this.baseUrl}/signatures/${signatureId}/verify`, {
      method: 'POST',
      headers: this.getHeaders(),
    });
    return this.handleResponse<Verification>(res);
  }

  async getDeliveries(): Promise<ListResponse<Delivery>> {
    const res = await fetch(`${this.baseUrl}/deliveries`, {
      method: 'GET',
      headers: this.getHeaders(),
    });
    return this.handleResponse<ListResponse<Delivery>>(res);
  }

  async getDelivery(id: string): Promise<Delivery> {
    const res = await fetch(`${this.baseUrl}/deliveries/${id}`, {
      method: 'GET',
      headers: this.getHeaders(),
    });
    return this.handleResponse<Delivery>(res);
  }

  async getAuditEvents(): Promise<ListResponse<AuditEvent>> {
    const res = await fetch(`${this.baseUrl}/audit-events`, {
      method: 'GET',
      headers: this.getHeaders(),
    });
    return this.handleResponse<ListResponse<AuditEvent>>(res);
  }

  async getCertificateLogins(): Promise<ListResponse<AuditEvent>> {
    const res = await fetch(`${this.baseUrl}/audit-events/certificate-logins`, {
      method: 'GET',
      headers: this.getHeaders(),
    });
    return this.handleResponse<ListResponse<AuditEvent>>(res);
  }

  async getCertificateOperations(): Promise<Array<{
    doctorId: string; name: string; pacsLoginId: string | null; licenseNumber: string | null;
    specialistQualificationNumber: string | null; issuer: string | null; notAfter: string | null;
    fingerprintSha256: string; registrationStatus: string; loginAttempts: number;
    loginApproved: number; reportApprovalRequests: number; signedReports: number; lastLoginAt: string | null;
    pacsSessionId: string | null; connectionExpiresAt: string | null; extensionCount: number;
    institutionNames: string[];
  }>> {
    const res = await fetch(`${this.baseUrl}/admin/certificate-operations`, {
      method: 'GET', headers: this.getHeaders(),
    });
    return this.handleResponse(res);
  }

  // --- Admin mode (관리자 모드): operational settings, deployment configuration, staff accounts ---

  async getPacsCertificateActivity(q = '', offset = 0): Promise<import('../types/pacsActivity').PacsActivityPage> {
    return this.adminJson(`/admin/pacs-certificate-activity?${new URLSearchParams({ q, offset: String(offset), limit: '50' })}`, 'GET');
  }

  private async adminJson<T>(path: string, method: string, body?: unknown): Promise<T> {
    const res = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers: this.getHeaders(body === undefined ? undefined : { 'Content-Type': 'application/json' }),
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return this.handleResponse<T>(res);
  }

  async getSystemSettings(): Promise<SystemSettingsResponse> {
    return this.adminJson<SystemSettingsResponse>('/admin/settings', 'GET');
  }

  async updateSystemSetting(key: string, data: UpdateSystemSettingRequest): Promise<SystemSettingView> {
    return this.adminJson<SystemSettingView>(`/admin/settings/${encodeURIComponent(key)}`, 'PUT', data);
  }

  async resetSystemSetting(key: string, data: ResetSystemSettingRequest): Promise<SystemSettingView> {
    return this.adminJson<SystemSettingView>(`/admin/settings/${encodeURIComponent(key)}/reset`, 'POST', data);
  }

  async getSystemConfiguration(): Promise<SystemConfigurationResponse> {
    return this.adminJson<SystemConfigurationResponse>('/admin/system/configuration', 'GET');
  }

  async getStaffPrincipals(): Promise<ListResponse<StaffPrincipal>> {
    return this.adminJson<ListResponse<StaffPrincipal>>('/admin/staff', 'GET');
  }

  async createStaffPrincipal(data: CreateStaffPrincipalRequest): Promise<StaffPrincipal> {
    return this.adminJson<StaffPrincipal>('/admin/staff', 'POST', data);
  }

  async disableStaffPrincipal(principalId: string, reason: string): Promise<StaffPrincipal> {
    return this.adminJson<StaffPrincipal>(`/admin/staff/${encodeURIComponent(principalId)}/disable`, 'POST', { reason });
  }

  // --- Doctor Activation & Admin Preregistration Routes ---

  async getDoctorActivationConfig(): Promise<DoctorActivationConfig> {
    const res = await fetch(`${this.baseUrl}/doctor-activation/config`, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
    });
    return this.handleResponse<DoctorActivationConfig>(res);
  }

  async preregisterDoctor(data: PreregisterDoctorRequest): Promise<PreregisterDoctorResponse> {
    const res = await fetch(`${this.baseUrl}/admin/doctors`, {
      method: 'POST',
      headers: this.getHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(data),
    });
    return this.handleResponse<PreregisterDoctorResponse>(res);
  }

  async getAdminDoctors(): Promise<ListResponse<Doctor>> {
    const res = await fetch(`${this.baseUrl}/admin/doctors`, {
      method: 'GET',
      headers: this.getHeaders(),
    });
    return this.handleResponse<ListResponse<Doctor>>(res);
  }

  async getAdminDoctor(id: string): Promise<Doctor> {
    const res = await fetch(`${this.baseUrl}/admin/doctors/${id}`, {
      method: 'GET',
      headers: this.getHeaders(),
    });
    return this.handleResponse<Doctor>(res);
  }

  async reissueDoctorInvitation(id: string): Promise<PreregisterDoctorResponse> {
    const res = await fetch(`${this.baseUrl}/admin/doctors/${id}/invitation`, {
      method: 'POST',
      headers: this.getHeaders(),
    });
    return this.handleResponse<PreregisterDoctorResponse>(res);
  }

  async updateDoctorStatus(id: string, data: DoctorStatusUpdateRequest): Promise<Doctor> {
    const res = await fetch(`${this.baseUrl}/admin/doctors/${id}/status`, {
      method: 'PATCH',
      headers: this.getHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(data),
    });
    return this.handleResponse<Doctor>(res);
  }

  async createActivationChallenge(invitationCode: string): Promise<ActivationChallenge> {
    const res = await fetch(`${this.baseUrl}/doctor-activation/challenges`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
      body: JSON.stringify({ invitationCode }),
    });
    return this.handleResponse<ActivationChallenge>(res);
  }

  async verifyGoogleIdentity(challengeId: string, idToken: string): Promise<ActivationSessionResponse> {
    const res = await fetch(`${this.baseUrl}/doctor-activation/google`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
      body: JSON.stringify({ challengeId, idToken }),
    });
    return this.handleResponse<ActivationSessionResponse>(res);
  }

  async verifyDevIdentity(challengeId: string, email: string, subject = 'dev-sub'): Promise<ActivationSessionResponse> {
    const res = await fetch(`${this.baseUrl}/doctor-activation/dev-identity`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
      body: JSON.stringify({ challengeId, email, subject }),
    });
    return this.handleResponse<ActivationSessionResponse>(res);
  }

  async submitDoctorConsent(activationToken: string, consents: DoctorConsentRequest): Promise<Doctor> {
    const res = await fetch(`${this.baseUrl}/doctor-activation/consent`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${activationToken}`,
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
      body: JSON.stringify(consents),
    });
    return this.handleResponse<Doctor>(res);
  }

  async activateDoctor(activationToken: string): Promise<Doctor> {
    const res = await fetch(`${this.baseUrl}/doctor-activation/activate`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${activationToken}`,
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
      body: JSON.stringify({}),
    });
    return this.handleResponse<Doctor>(res);
  }

  async getDoctorMe(activationToken: string): Promise<DoctorMeResponse> {
    const res = await fetch(`${this.baseUrl}/doctor-activation/me`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${activationToken}`,
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
    });
    return this.handleResponse<DoctorMeResponse>(res);
  }

  async createLoginChallenge(): Promise<ActivationChallenge> {
    const res = await fetch(`${this.baseUrl}/doctor-activation/login-challenges`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
      body: JSON.stringify({}),
    });
    return this.handleResponse<ActivationChallenge>(res);
  }

  async getDoctorSignRequests(token: string): Promise<ListResponse<SignRequest>> {
    const res = await fetch(`${this.baseUrl}/doctor/sign-requests`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`,
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
    });
    return this.handleResponse<ListResponse<SignRequest>>(res);
  }

  async getDoctorSignRequest(token: string, id: string): Promise<SignRequest> {
    const res = await fetch(`${this.baseUrl}/doctor/sign-requests/${id}`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`,
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
    });
    return this.handleResponse<SignRequest>(res);
  }

  async approveDoctorSignRequest(
    token: string,
    id: string,
    data: { documentSha256: string; nonce: string; reviewAttestation?: string;
      certificateDerBase64?: string; externalSignatureBase64?: string; externalSignatureAlgorithm?: string }
  ): Promise<SignRequest> {
    const res = await fetch(`${this.baseUrl}/doctor/sign-requests/${id}/approve`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
      body: JSON.stringify(data),
    });
    return this.handleResponse<SignRequest>(res);
  }

  async getReportCertificatePayload(token: string, id: string): Promise<{ signedPayloadBase64: string }> {
    const res = await fetch(`${this.baseUrl}/doctor/sign-requests/${id}/certificate-payload`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${token}`, 'X-Correlation-Id': `web-${Date.now()}` },
    });
    return this.handleResponse<{ signedPayloadBase64: string }>(res);
  }

  // --- Certificate Enrollment & Verification APIs ---
  async getCertificateEnrollmentConfig(): Promise<CertificateEnrollmentConfig> {
    const res = await fetch(`${this.baseUrl}/certificate-enrollment/config`, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
    });
    return this.handleResponse<CertificateEnrollmentConfig>(res);
  }

  async getPhysicianAccountConfig(): Promise<PhysicianAccountConfig> {
    return this.handleResponse(await fetch(`${this.baseUrl}/physician-accounts/config`, { cache: 'no-store' }));
  }

  async registerPhysicianAccount(body: PhysicianAccountRegistration): Promise<PhysicianAccountReceipt> {
    return this.handleResponse(await fetch(`${this.baseUrl}/physician-accounts/registrations`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    }));
  }

  async resumePhysicianAccount(username: string, password: string): Promise<PhysicianAccountReceipt> {
    return this.handleResponse(await fetch(`${this.baseUrl}/physician-accounts/sessions`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }),
    }));
  }

  async createPhysicianAccountChallenge(token: string): Promise<CertificateChallenge> {
    return this.handleResponse(await fetch(`${this.baseUrl}/physician-accounts/certificate-challenges`, {
      method: 'POST', headers: { Authorization: `Bearer ${token}` },
    }));
  }

  async logoutPhysicianAccount(token: string): Promise<void> {
    return this.handleResponse(await fetch(`${this.baseUrl}/physician-accounts/logout`, {
      method: 'POST', headers: { Authorization: `Bearer ${token}` },
    }));
  }

  async createCertificateChallenge(
    invitationCode?: string,
    token?: string
  ): Promise<CertificateChallenge> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
    };
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    const body = invitationCode ? JSON.stringify({ invitationCode }) : JSON.stringify({});
    const res = await fetch(`${this.baseUrl}/certificate-enrollment/challenges`, {
      method: 'POST',
      headers,
      body,
    });
    return this.handleResponse<CertificateChallenge>(res);
  }

  async verifyCertificateProof(
    req: VerifyRequest
  ): Promise<CertificateSessionResponse> {
    const res = await fetch(`${this.baseUrl}/certificate-enrollment/verify`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
      body: JSON.stringify(req),
    });
    return this.handleResponse<CertificateSessionResponse>(res);
  }

  async createCertificateLoginChallenge(): Promise<CertificateChallenge> {
    const res = await fetch(`${this.baseUrl}/certificate-enrollment/login-challenges`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
      body: JSON.stringify({}),
    });
    return this.handleResponse<CertificateChallenge>(res);
  }

  async loginWithCertificate(
    req: CertificateLoginRequest
  ): Promise<CertificateSessionResponse> {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 30000);
    try {
      const res = await fetch(`${this.baseUrl}/certificate-enrollment/login`, {
        method: 'POST',
        signal: controller.signal,
        headers: {
          'Content-Type': 'application/json',
          'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
        },
        body: JSON.stringify(req),
      });
      return await this.handleResponse<CertificateSessionResponse>(res);
    } catch (error) {
      if (controller.signal.aborted) throw new Error('인증 서버 응답 시간이 초과되었습니다. PACS에서 다시 로그인해 주세요.');
      throw error;
    } finally { window.clearTimeout(timeout); }
  }

  /** PACS-only certificate possession check; no Vision-Certi doctor account is required. */
  async createPacsCertificateProofChallenge(audience: string): Promise<CertificateChallenge> {
    const res = await fetch(`${this.baseUrl}/pacs-certificate-proofs/challenges`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ audience }),
    });
    return this.handleResponse<CertificateChallenge>(res);
  }

  async verifyPacsCertificateProof(req: CertificateLoginRequest): Promise<{
    verificationCode: string; audience: string; expiresAt: string;
  }> {
    const res = await fetch(`${this.baseUrl}/pacs-certificate-proofs/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    });
    return this.handleResponse(res);
  }

  async getMedicalInstitutions(): Promise<MedicalInstitution[]> {
    return this.adminJson<MedicalInstitution[]>('/admin/medical-institutions', 'GET');
  }

  async createMedicalInstitution(code: string, name: string): Promise<MedicalInstitution> {
    return this.adminJson<MedicalInstitution>('/admin/medical-institutions', 'POST', { code, name });
  }

  async assignMedicalInstitutionDoctor(institutionId: string, doctorId: string): Promise<MedicalInstitution> {
    return this.adminJson<MedicalInstitution>(
      `/admin/medical-institutions/${encodeURIComponent(institutionId)}/doctors/${encodeURIComponent(doctorId)}`, 'POST');
  }

  async unassignMedicalInstitutionDoctor(institutionId: string, doctorId: string): Promise<MedicalInstitution> {
    return this.adminJson<MedicalInstitution>(
      `/admin/medical-institutions/${encodeURIComponent(institutionId)}/doctors/${encodeURIComponent(doctorId)}`, 'DELETE');
  }

  async getCertificateEnrollments(token: string): Promise<ListResponse<Enrollment>> {
    const res = await fetch(`${this.baseUrl}/certificate-enrollment/enrollments`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`,
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
    });
    return this.handleResponse<ListResponse<Enrollment>>(res);
  }

  async submitMedicalCredentials(token: string, registrationId: string, body: MedicalCredentialsRequest): Promise<Enrollment> {
    const res = await fetch(`${this.baseUrl}/certificate-enrollment/enrollments/${registrationId}/medical-credentials`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
      body: JSON.stringify(body),
    });
    return this.handleResponse<Enrollment>(res);
  }

  async submitCertificateConsent(
    token: string,
    registrationId: string,
    consent: CertificateConsentRequest = {
      version: '2026-10-13-draft',
      authentication: true,
      signature: true,
      privacy: true,
    }
  ): Promise<Enrollment> {
    const res = await fetch(`${this.baseUrl}/certificate-enrollment/enrollments/${registrationId}/consent`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
      body: JSON.stringify(consent),
    });
    return this.handleResponse<Enrollment>(res);
  }

  async unregisterDoctorCertificate(
    token: string,
    registrationId: string,
    req?: CertificateUnregisterRequest
  ): Promise<Enrollment> {
    const res = await fetch(`${this.baseUrl}/certificate-enrollment/enrollments/${registrationId}/unregister`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
      body: JSON.stringify(req || { reason: 'Physician unregistration' }),
    });
    return this.handleResponse<Enrollment>(res);
  }

  async devMockSign(
    challengeId: string,
    fingerprintSha256?: string | null
  ): Promise<CertificateSessionResponse> {
    const res = await fetch(`${this.baseUrl}/certificate-enrollment/dev/mock-sign`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
      body: JSON.stringify({ challengeId, fingerprintSha256: fingerprintSha256 || null }),
    });
    return this.handleResponse<CertificateSessionResponse>(res);
  }

  async createMobileCertificateTransaction(
    purpose: 'CERTIFICATE_ENROLLMENT' | 'CERTIFICATE_LOGIN' = 'CERTIFICATE_ENROLLMENT',
    invitationCode?: string
  ): Promise<MobileStartResponse> {
    const res = await fetch(`${this.baseUrl}/certificate-enrollment/mobile/transactions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
      body: JSON.stringify({
        purpose,
        invitationCode: invitationCode || undefined,
      }),
    });
    return this.handleResponse<MobileStartResponse>(res);
  }

  async consumeMobileCertificateTransaction(
    transactionId: string,
    transactionSecret: string
  ): Promise<CertificateSessionResponse> {
    const res = await fetch(`${this.baseUrl}/certificate-enrollment/mobile/transactions/${transactionId}/consume`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
      body: JSON.stringify({ transactionSecret }),
    });
    return this.handleResponse<CertificateSessionResponse>(res);
  }

  async getAdminDoctorCertificateEnrollments(doctorId: string): Promise<ListResponse<Enrollment>> {
    const res = await fetch(`${this.baseUrl}/admin/doctors/${doctorId}/certificate-enrollments`, {
      method: 'GET',
      headers: this.getHeaders(),
    });
    return this.handleResponse<ListResponse<Enrollment>>(res);
  }

  async reviewAdminDoctorCertificate(
    doctorId: string,
    review: AdminCertificateReviewRequest
  ): Promise<Enrollment> {
    const res = await fetch(`${this.baseUrl}/admin/doctors/${doctorId}/certificate-reviews`, {
      method: 'POST',
      headers: this.getHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(review),
    });
    return this.handleResponse<Enrollment>(res);
  }

  // Alias for backward compatibility if needed
  async reviewDoctorCertificate(
    doctorId: string,
    review: AdminCertificateReviewRequest
  ): Promise<Enrollment> {
    return this.reviewAdminDoctorCertificate(doctorId, review);
  }

  async renewDoctorCertificate(
    token: string,
    invitationCode?: string
  ): Promise<CertificateChallenge> {
    return this.createCertificateChallenge(invitationCode, token);
  }

  async getDoctorSignRequestDocument(
    token: string,
    requestId: string
  ): Promise<{ blob: Blob; sha256: string | null; versionId: string | null; contentType: string | null }> {
    const res = await fetch(`${this.baseUrl}/doctor/sign-requests/${requestId}/document`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`,
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
    });
    if (!res.ok) {
      let errData: ApiError;
      try {
        errData = await res.json();
      } catch {
        errData = {
          code: `HTTP_${res.status}`,
          message: '판독문 문서를 불러올 수 없습니다.',
          correlationId: res.headers.get('x-correlation-id') || 'none',
          retryable: res.status >= 500,
        };
      }
      throw new ApiClientError(res.status, errData);
    }
    const sha256 = res.headers.get('x-document-sha256');
    const versionId = res.headers.get('x-document-version-id');
    const contentType = res.headers.get('content-type');
    const blob = await res.blob();
    return { blob, sha256, versionId, contentType };
  }

  // Backward-compatible method forwarding to getDoctorSignRequestDocument
  async getDoctorSignRequestDocumentContent(
    token: string,
    requestId: string
  ): Promise<{ content: string; blob: Blob }> {
    const doc = await this.getDoctorSignRequestDocument(token, requestId);
    const content = await doc.blob.text();
    return { content, blob: doc.blob };
  }

  async createPacsHandoff(
    token: string,
    audience: string
  ): Promise<PacsHandoffResponse> {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 15000);
    try {
      const res = await fetch(`${this.baseUrl}/doctor/pacs-handoffs`, {
        method: 'POST',
        signal: controller.signal,
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`,
          'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
        },
        body: JSON.stringify({ audience }),
      });
      return await this.handleResponse<PacsHandoffResponse>(res);
    } catch (error) {
      if (controller.signal.aborted) throw new Error('PACS 로그인 연결 시간이 초과되었습니다. 다시 시도해 주세요.');
      throw error;
    } finally { window.clearTimeout(timeout); }
  }

  async getRuntimeCapabilities(): Promise<RuntimeCapabilities> {
    const res = await fetch(`${this.baseUrl}/runtime/capabilities`, {
      method: 'GET',
      headers: {
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
    });
    return this.handleResponse<RuntimeCapabilities>(res);
  }

  async getStaffMe(token: string): Promise<StaffMeResponse> {
    const res = await fetch(`${this.baseUrl}/staff/me`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`,
        'X-Correlation-Id': `web-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`,
      },
    });
    return this.handleResponse<StaffMeResponse>(res);
  }
}
