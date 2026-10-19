from fastapi.params import Depends

from app.database import manageDB
from app.database.schema import TokenTable, UserTable, HospitalUserTable, CenterUserTable, DoctorTable, PacsUsersView
from sqlalchemy.orm import Session
from app.core import settings
from app.errors.exceptions import TokenExpiredEx
from app.libs import token_decode, create_access_token,orm_dict_to_json


def create_token(user_data, ip, session: Session):
    token_access = create_access_token(data=user_data, expires_delta=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    token_refresh = create_access_token(data=dict(user_idx=user_data['user_idx']),
                                        expires_delta=settings.REFRESH_TOKEN_EXPIRE_MINUTES)

    TokenTable.create(session, auto_commit=True, user_idx=user_data['user_idx'],
                      token_access=token_access,
                      token_refresh=token_refresh,
                      token_expire='N', token_insert_ip=ip)
    return dict(token=token_access, token_refresh=token_refresh)


def get_user(token: str, session):
    try:
        # Vision-Certi 인증서 세션 토큰(vc1.)은 pacs_token 이 아닌 pacs_vc_session(SHA-256)으로 매 요청 검증한다.
        if token.startswith('vc1.'):
            from app.integrations.vision_certi.runtime import resolve_vc_token_user_idx
            user_idx = resolve_vc_token_user_idx(token)
        else:
            # token_row = Token.get(token_access=token, token_expire='N')
            token_row = TokenTable.get(session, token_access=token)
            if token_row is None:
                raise TokenExpiredEx()
            token_decode(access_token=token)

            user_idx = token_row.user_idx

        user_data = dict()
        user_data['user_idx'] = user_idx
        user = PacsUsersView.table(session).where(user_idx=user_idx).first()
        if user:
            center_idx_list = [centers['center_idx'] for centers in user.centers]
            hospital_idx_list = [hospital['hospital_idx'] for hospital in user.hospitals]
            user_data['user_id'] = user.user_id
            user_data['group_idx'] = user.group_idx
            user_data['group_level'] = user.group_level
            user_data['user_name'] = user.user_name
            user_data['user_status'] = user.user_status
            user_data['user_profile_path'] = user.user_profile_path
            user_data['user_signature_path'] = user.user_signature_path
            user_data['hospital_info'] = user.hospitals
            user_data['hospital_idx_list'] = hospital_idx_list
            user_data['center_info'] = user.centers
            user_data['center_idx_list'] = center_idx_list
            user_data['doctor_idx'] = user.doctor_idx
            user_data['doctor_id'] = user.doctor_id
            user_data['doctor_major'] = user.doctor_major
            user_data['doctor_license_number'] = user.doctor_license_number
            # 타인 판독문 수정 허용(관리자 부여) — 뷰(pacs_users_view)에 없는 컬럼이라 원 테이블에서 조회.
            # 판독 저장/승인의 재배정 409 게이트(router/StudyReport)가 이 값을 존중한다.
            # (토큰 미들웨어 user 캐시로 최대 5분 지연 반영 — 허용)
            try:
                u_row = (UserTable.table(session)
                         .select('user_report_edit_all')
                         .where(user_idx=user_idx).first())
                user_data['user_report_edit_all'] = u_row.user_report_edit_all if u_row else 'N'
            except Exception:
                user_data['user_report_edit_all'] = 'N'
        return user_data
    except Exception as e:
        raise e

