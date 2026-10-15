import client from "@libs/axios";
import {
  VcConfig,
  VcCertificateLoginResponse,
  VcPrepareResponse,
  VcFinalizeResponse,
  VcApproval,
  VcDoctorLink,
  CreateDoctorLinkRequest,
  VcCurrentReportResponse,
} from "@shared/types/visionCerti.types";

export default class VisionCertiService {
  public static async getEnrollmentStatus(): Promise<{
    registrationStatus: 'NOT_REQUIRED' | 'NOT_REGISTERED' | 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'REVOKED';
    doctorId: string | null;
  }> {
    return (await client.get('/vc/auth/enrollment-status')).data;
  }

  public static async startEnrollment(): Promise<{ doctorId: string; challenge: Record<string, unknown> }> {
    return (await client.post('/vc/auth/enrollment-start', {})).data;
  }

  /**
   * Vision-Certi 연동 설정 조회 (공개 엔드포인트)
   */
  public static async getConfig(): Promise<VcConfig> {
    try {
      const response = await client.get("/vc/config");
      return response.data;
    } catch (error: any) {
      throw error.response ? { status: error.response.status, ...(error.response.data ?? {}) } : error.message;
    }
  }

  /**
   * 현재 저장된 판독문 내용 및 contentSha256 조회
   */
  public static async getCurrentReport(studyIdx: number): Promise<VcCurrentReportResponse> {
    try {
      const response = await client.get(`/vc/study/${studyIdx}/report/current`);
      return response.data;
    } catch (error: any) {
      throw error.response ? { status: error.response.status, ...(error.response.data ?? {}) } : error.message;
    }
  }

  /**
   * VC Handoff 코드를 통한 의사 인증서 로그인
   */
  public static async certificateStepUp(handoffCode: string): Promise<VcCertificateLoginResponse> {
    const response = await client.post('/vc/auth/certificate-step-up', { handoffCode });
    return response.data;
  }

  public static async certificateLogin(handoffCode: string): Promise<VcCertificateLoginResponse> {
    try {
      const response = await client.post("/vc/auth/certificate-login", { handoffCode });
      return response.data;
    } catch (error: any) {
      throw error.response ? { status: error.response.status, ...(error.response.data ?? {}) } : error.message;
    }
  }

  /**
   * 인증서 세션 정보 조회
   */
  public static async getSession(): Promise<any> {
    try {
      const response = await client.get("/vc/auth/session");
      return response.data;
    } catch (error: any) {
      throw error.response ? { status: error.response.status, ...(error.response.data ?? {}) } : error.message;
    }
  }

  /**
   * 인증서 세션 로그아웃
   */
  public static async logout(): Promise<{ loggedOut: boolean }> {
    try {
      const response = await client.post("/vc/auth/logout");
      return response.data;
    } catch (error: any) {
      throw error.response ? { status: error.response.status, ...(error.response.data ?? {}) } : error.message;
    }
  }

  /**
   * 판독문 전자서명 스냅샷 생성 및 서명 요청 준비 (Prepare)
   */
  public static async prepareReport(
    studyIdx: number,
    expectedContentSha256?: string | null
  ): Promise<VcPrepareResponse> {
    try {
      const response = await client.post(`/vc/study/${studyIdx}/report/prepare`, {
        expected_content_sha256: expectedContentSha256 || undefined,
      });
      return response.data;
    } catch (error: any) {
      throw error.response ? { status: error.response.status, ...(error.response.data ?? {}) } : error.message;
    }
  }

  /**
   * Vision-Certi 서명 증적 수집 및 판독문 최종 승인 확정 (Finalize)
   */
  public static async finalizeReport(approvalId: string): Promise<VcFinalizeResponse> {
    try {
      const response = await client.post(`/vc/report-approvals/${approvalId}/finalize`);
      return response.data;
    } catch (error: any) {
      throw error.response ? { status: error.response.status, ...(error.response.data ?? {}) } : error.message;
    }
  }

  /**
   * 서명 승인 상태 단건 조회
   */
  public static async getApproval(approvalId: string): Promise<VcApproval> {
    try {
      const response = await client.get(`/vc/report-approvals/${approvalId}`);
      return response.data;
    } catch (error: any) {
      throw error.response ? { status: error.response.status, ...(error.response.data ?? {}) } : error.message;
    }
  }

  /**
   * 해당 검사의 전자서명 승인 이력 목록 조회
   */
  public static async listStudyApprovals(studyIdx: number): Promise<{ items: VcApproval[] }> {
    try {
      const response = await client.get(`/vc/study/${studyIdx}/report/approvals`);
      return response.data;
    } catch (error: any) {
      throw error.response ? { status: error.response.status, ...(error.response.data ?? {}) } : error.message;
    }
  }

  /**
   * 관리자: 의사-VisionCerti 계정 연동 매핑 목록 조회
   */
  public static async listDoctorLinks(): Promise<{ items: VcDoctorLink[] }> {
    try {
      const response = await client.get("/vc/admin/doctor-links");
      return response.data;
    } catch (error: any) {
      throw error.response ? { status: error.response.status, ...(error.response.data ?? {}) } : error.message;
    }
  }

  /**
   * 관리자: 의사-VisionCerti 계정 연동 생성
   */
  public static async createDoctorLink(body: CreateDoctorLinkRequest): Promise<VcDoctorLink> {
    try {
      const response = await client.post("/vc/admin/doctor-links", body);
      return response.data;
    } catch (error: any) {
      throw error.response ? { status: error.response.status, ...(error.response.data ?? {}) } : error.message;
    }
  }

  /**
   * 관리자: 의사-VisionCerti 계정 연동 철회(Revoke)
   */
  public static async revokeDoctorLink(
    linkIdx: number,
    reason: string
  ): Promise<VcDoctorLink> {
    try {
      const response = await client.post(`/vc/admin/doctor-links/${linkIdx}/revoke`, { reason });
      return response.data;
    } catch (error: any) {
      throw error.response ? { status: error.response.status, ...(error.response.data ?? {}) } : error.message;
    }
  }
}

export { VisionCertiService };
