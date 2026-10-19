// 센터 정보 타입
export interface CenterInfo {
  center_idx: number;
  center_name: string;
}

export interface User {
  user_id: string;
  user_idx: number;
  user_name: string;
  user_profile_path: string | null;
  group_idx: number;
  group_level: number;
  user_email: string | null;
  user_role: string | null;
  user_phone_number: string | null;
  user_fax_number: string | null;
  user_mobile_number: string | null;
  user_signature_path: string | null;
  // 타인 판독문 수정 허용(관리자 부여, 'Y'/'N') — 로그인 응답에 포함
  user_report_edit_all?: string;
  // 센터 정보 (로그인 시 할당된 센터 목록)
  center_info?: CenterInfo[];
  center_idx_list?: number[];
}

export type LoginFrom = {
  user_id: string;
  user_passwd: string;
  user_overwrite: boolean;
};

export interface UserResponse {
  user_data: User;
  token: string;
  refresh_token: string;
  overwrite: boolean;
  message: string;
  status: number;
}

export interface UserAuthProfile {
  user_id: string; user_passwd: string; user_passwd_encrypted?: string; checked: boolean;
}

// 라이센스 상태 타입 (config.types.ts에서 가져온 형태)
export interface LicenseStatus {
  valid: boolean;
  system_idx?: number;
  license_type?: string;
  license_end?: string;
  message: string;
}

import { VcSessionData, VcCertificateLoginResponse } from "./visionCerti.types";

export interface UserState{
  userData: User,
  token: string | null;
  refreshToken: string | null;
  userAuthProfile: UserAuthProfile;
  licenseStatus: LicenseStatus | null;
  vcSession?: VcSessionData | null;
}


export interface UserActions{
  userRemember: (userAuthProfile:UserAuthProfile) => void,
  login: (userData: UserResponse) => Promise<void>;
  loginWithCertificate: (data: VcCertificateLoginResponse) => Promise<void>;
  logout: () => void;
  setInitialState: (userData: Partial<UserState>) => void;
  refreshAccessToken: (newToken: string) => void;
  setLicenseStatus: (licenseStatus: LicenseStatus | null) => void;
}

export interface UserStore extends UserState{
  actions: UserActions;
}

export interface TokenRefreshResponse {
  token_access: string;
  message: string;
  status: number;
}


