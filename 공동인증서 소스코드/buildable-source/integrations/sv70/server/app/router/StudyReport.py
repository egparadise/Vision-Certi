from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Request
from fastapi.security import APIKeyHeader
from sqlalchemy.orm import Session
from sqlalchemy import func
from inspect import currentframe as frame
from datetime import datetime
import re
import time
from app.database.DataBase import db, manageDB
from app.utils.alimtalk_service import AlimTalkService, LogType
from app.database.schema import (
    StudyReportTable, StudyReportTemplateTable, StudyTable,
    UserTable, HospitalUserTable, PatientTable
)
from app.utils.response import success_response
from app.utils.logging_config import get_logger
from app.libs import orm_dict_to_json
from app.core.constants import SUPER_ADMIN_USER_IDX_LIST
from app.models import (
    TemplateDataModel, ReportUpdateDataModel,
    ReportAddDataModel
)
from app.integrations.vision_certi.runtime import legacy_report_write_guard, legacy_report_delete_guard


# 유틸리티 함수들
def is_valid_phone(number):
    """전화번호 형식 검증"""
    pattern = r'^01[016789]\d{7,8}$'
    return bool(re.match(pattern, number))


def safe_convert(value):
    """안전한 정수 변환"""
    if value == '' or value is None:
        return None
    try:
        return int(value)
    except ValueError:
        return None


def _can_read_others_studies(user_info) -> bool:
    """
    워크리스트 권한 모델(StudyServiceV2.apply_user_permissions)과 동일한 기준으로,
    '다른 사람에게 배정된 검사'를 열람/판독할 권한이 있는지 판별한다.

    - 병원/센터/최고 관리자(group_level >= 98) 및 슈퍼관리자(SUPER_ADMIN_USER_IDX_LIST):
      배정자와 무관하게 센터/병원 단위로 모든 검사를 열람·판독할 수 있다(예: 원장 계정).
    - 일반 판독의(group_level < 98): 본인 배정 또는 미배정(픽업)만 가능.

    따라서 '재배정 stale 가로채기' 차단(409)은 일반 판독의에게만 적용해야 하며,
    관리자급 사용자는 차단 대상에서 제외한다.
    """
    if not user_info:
        return False
    if (user_info.get('group_level') or 0) >= 98:
        return True
    if user_info.get('user_idx') in SUPER_ADMIN_USER_IDX_LIST:
        return True
    return False


def _report_edit_override(user_info, report_open_edit) -> bool:
    """판독 수정 권한 2종에 의한 재배정 가드(409) 예외:
    - user_report_edit_all='Y': 관리자가 부여한 '타인 판독문 수정' 권한
      (request.state.user 에 동봉 — crud/Token.py, 토큰 캐시로 최대 5분 지연 반영)
    - report_open_edit='Y': 판독문 자체의 '공동 수정 허용' (DB 저장값 — 클라 페이로드 아님)"""
    if user_info and user_info.get('user_report_edit_all') == 'Y':
        return True
    return report_open_edit == 'Y'


# API 키 헤더 설정
API_KEY_HEADER = APIKeyHeader(name="Authorization", auto_error=False)

# 두 개의 라우터 정의
# 1. 리포트 템플릿 라우터
template_router = APIRouter(
    prefix="/study/report",
    tags=["report_template"],
    dependencies=[Depends(API_KEY_HEADER)],
)

# 2. 스터디별 리포트 라우터
report_router = APIRouter(
    prefix="/study/{study_idx}/report",
    tags=["study_report"],
    dependencies=[Depends(API_KEY_HEADER)],
    responses={404: {"description": "Not found"}}
)


# === 리포트 템플릿 관련 엔드포인트 ===

@template_router.get('/template', description="템플릿 목록 조회")
def get_template(request: Request):
    try:
        # 사용자 정보 가져오기
        user_info = request.state.user
        user_idx = user_info['user_idx']

        # 템플릿 쿼리
        query = StudyReportTemplateTable.table()
        temp_columns = query.all_columns()
        query.select(*temp_columns)
        query.where(user_idx=user_idx)
        query = query.order_by('-temp_idx')

        # 결과 조회 및 변환
        result = query.all()
        template_data = orm_dict_to_json(result)

        # 응답 반환
        return success_response(
            data={"template_data": template_data},
            message="Successfully fetched all template"
        )

    except Exception as e:
        request.state.inspect = frame()
        raise e


@template_router.post('/template', description="템플릿 생성")
def create_template(
        request: Request,
        template_data_post: TemplateDataModel,
        session: Session = Depends(db.session)
):
    try:
        # 사용자 정보 가져오기
        user_info = request.state.user

        # 모델 데이터 검증 및 변환
        template_data = TemplateDataModel.model_validate(
            template_data_post).model_dump(exclude_none=True)
        template_data['user_idx'] = user_info['user_idx']

        # 템플릿 생성
        StudyReportTemplateTable.create(session, auto_commit=True,
                                        **template_data)

        # 응답 반환
        return success_response(message="Successfully add template")

    except Exception as e:
        request.state.inspect = frame()
        raise e


@template_router.put('/template/{temp_idx}', description="템플릿 업데이트")
def update_template(request: Request, temp_idx: str,
                    template_data_post: TemplateDataModel):
    try:
        # 모델 데이터 검증 및 변환
        template_data = TemplateDataModel.model_validate(
            template_data_post).model_dump(exclude_none=True)

        # 템플릿 업데이트
        StudyReportTemplateTable.table().where(temp_idx=temp_idx).update(
            auto_commit=True, **template_data)

        # 응답 반환
        return success_response(message="Successfully update template")

    except Exception as e:
        request.state.inspect = frame()
        raise e


@template_router.delete('/template/{temp_idx}', description="템플릿 삭제")
def delete_template(request: Request, temp_idx: str):
    try:
        # 템플릿 삭제
        StudyReportTemplateTable.table().where(temp_idx=temp_idx).delete(
            auto_commit=True)

        # 응답 반환
        return success_response(message="Successfully delete template")

    except Exception as e:
        request.state.inspect = frame()
        raise e


# === 스터디별 리포트 관련 엔드포인트 ===

@report_router.get("/", description='리포트 조회')
def get_report(request: Request, study_idx: str,
               session: Session = Depends(manageDB.session)):
    try:
        # 리포트 쿼리 설정
        report_query = StudyReportTable.table()
        report_columns = report_query.all_columns()
        study_list = study_idx.split(sep=',')

        report_query.select(*report_columns)
        report_query.where(study_idx__in=study_list)
        reports = report_query.all()

        # 리포트가 없는 경우 404 에러
        if not reports:
            return success_response(
                data={"report_data": None},
                message="Successfully fetched report"
            )
            # request.state.inspect = frame()
            # raise HTTPException(status_code=404, detail="Report does not exist")

        # 리포트 데이터 변환
        report_data = orm_dict_to_json(reports)

        # 사용자 정보 추가
        if report_data:
            for item in report_data:
                # 작성자 정보 조회
                create_query = UserTable.table(session)
                create_query.select('user_id', 'user_name')
                create_query.where(user_idx=item['report_create_idx'])
                creator = create_query.first()

                # 승인자 정보 조회
                approver = None
                if item['report_approve_idx']:
                    approve_query = UserTable.table(session)
                    approve_query.select('user_id', 'user_name')
                    approve_query.where(user_idx=item['report_approve_idx'])
                    approver = approve_query.first()

                # 작성자 정보 추가
                if item['report_create_idx'] and creator is not None:
                    item['report_create_id'] = creator.user_id
                    item['report_create_name'] = creator.user_name
                else:
                    item['report_create_id'] = None
                    item['report_create_name'] = None

                # 승인자 정보 추가
                if item['report_approve_idx'] and approver is not None:
                    item['report_approve_id'] = approver.user_id
                    item['report_approve_name'] = approver.user_name
                else:
                    item['report_approve_id'] = None
                    item['report_approve_name'] = None

        # 단일 스터디 조회인 경우 첫 번째 항목만 반환
        if report_data and len(study_list) == 1:
            report_data = report_data[0]

        # 응답 반환
        return success_response(
            data={"report_data": report_data},
            message="Successfully fetched report"
        )

    except HTTPException as e:
        # HTTP 예외는 그대로 전달
        raise e
    except Exception as e:
        # 기타 예외 처리
        request.state.inspect = frame()
        raise e


#
# @report_router.post("/", description='리포트 추가 또는 업데이트')
# def add_report(
#         request: Request,
#         report_model_data: ReportAddDataModel,
#         session: Session = Depends(db.session),
#         session_manage: Session = Depends(manageDB.session)
# ):
#     user_info = request.state.user
#     # SMS 서비스 초기화
#     sms = SMS('211.172.232.124', 9201, 'f3380eee05b278def2c1dfa0fc66ed2a')
#
#     try:
#         # 모델 데이터 검증 및 변환
#         report_data = ReportAddDataModel.model_validate(
#             report_model_data).model_dump(exclude_none=True)
#
#         # 스터디 정보 조회
#         study_row = (StudyTable.table()
#                      .select('center_name', 'hospital_idx', 'hospital_name',
#                              'patient_id', 'study_description', 'hospital_name',
#                              'study_body_part', 'study_modality',
#                              'study_accession_no')
#                      .select('patient_name', search_table=PatientTable)
#                      .join(PatientTable, 'patient_idx', 'patient_idx')
#                      .where(study_idx=report_data['study_idx']).first())
#
#         # 병원 사용자 정보 조회 (SMS 전송용)
#         hospital_user_row = (HospitalUserTable.table(session=session_manage)
#                              .select('hospital_idx', 'user_type')
#                              .select('user_idx', search_table=UserTable)
#                              .select(
#             func.inviz_aes_decrypt(UserTable.user_mobile_number).label(
#                 'user_mobile_number'), search_table=UserTable)
#                              .join(UserTable, HospitalUserTable.user_idx,
#                                    UserTable.user_idx)
#                              .where(user_type='H',
#                                     hospital_idx=study_row.hospital_idx,
#                                     search_table=HospitalUserTable).all())
#
#         user_phone_number = []
#         for hospital_user in hospital_user_row:
#             if hospital_user.user_mobile_number:
#                 cleaned_number = re.sub(r'\D', '',
#                                         hospital_user.user_mobile_number)
#                 if is_valid_phone(cleaned_number):
#                     user_phone_number.append(cleaned_number)
#
#         # 생성자 정보가 없으면 현재 사용자로 설정
#         if 'report_create_idx' not in report_data or report_data[
#             'report_create_idx'] is None:
#             report_data['report_create_idx'] = user_info['user_idx']
#
#         # 승인자 정보가 없고 상태가 승인인 경우 현재 사용자로 설정
#         if ('report_approve_idx' not in report_data or report_data[
#             'report_approve_idx'] is None) and report_data['report_status'] in [
#             'A', 'RA']:
#             report_data['report_approve_idx'] = user_info['user_idx']
#
#         # 리포트 ID 처리
#         report_idx = None
#         if report_data.get('report_idx'):
#             report_idx = report_data['report_idx']
#             del report_data['report_idx']
#
#         # 기존 리포트 업데이트 또는 새 리포트 생성
#         if report_idx:
#             # 리포트 수정 시 SMS 알림
#             if report_data['report_status'] in ['R', 'RR']:
#                 content = f'{study_row.patient_id}({study_row.patient_name}) : {study_row.study_body_part} / {study_row.study_modality} / {study_row.study_accession_no} \n 의 리포트 내용이 수정되었습니다.'
#                 sms.add(user_phone_number, '0260837788', content, '', '')
#
#             # 리포트 업데이트
#             StudyReportTable.table().where(report_idx=report_idx).update(
#                 auto_commit=True, **report_data)
#         else:
#             # 새 리포트 생성
#             StudyReportTable.create(session, auto_commit=True, **report_data)
#
#         # 스터디 상태 업데이트
#         StudyTable.table().where(study_idx=report_data['study_idx']).update(
#             auto_commit=True,
#             study_status=report_data['report_status'])
#
#         # CVR 알림 처리
#         if report_data['report_cvr_send'] == 'Y':
#             current_time = datetime.now()
#             formatted_time = current_time.strftime("%Y년%m월%d일 %H시 %M분")
#             content = f"{study_row.center_name}입니다.\n{study_row.hospital_name} / {study_row.patient_id} / {study_row.patient_name} / {study_row.study_description} / {formatted_time} / CVR / 판독완료되었습니다."
#             sms.add(user_phone_number, '0260837788', content, '', '')
#
#         # SMS 전송
#         if len(sms.Data) > 0 and sms.send():
#             print(sms.Result)
#
#         # 응답 반환
#         return success_response(
#             data={"report_data": report_data},
#             message="report add success"
#         )
#
#     except Exception as e:
#         request.state.inspect = frame()
#         raise e

@report_router.post("/", description='리포트 추가 또는 업데이트')
def add_report(
        request: Request,
        report_model_data: ReportAddDataModel,
        background_tasks: BackgroundTasks,
        session: Session = Depends(db.session),
        session_manage: Session = Depends(manageDB.session)
):
    user_info = request.state.user
    client_ip = request.client.host if request.client else ''
    logger = get_logger('study_report', 'study_report.log')
    t0 = time.time()
    logger.info(f"[TIMING] add_report START | ip={client_ip} | user={user_info.get('user_id','')} | study_idx={report_model_data.study_idx} | status={report_model_data.report_status}")

    # 알림톡 서비스 초기화
    alimtalk_service = AlimTalkService(session_manage)

    # 템플릿 코드 설정
    template_report_update = 'UA_1289'  # 리포트 수정 템플릿
    template_cvr_complete = 'UA_1296'  # CVR 완료 템플릿

    try:
        # 모델 데이터 검증 및 변환
        report_data = ReportAddDataModel.model_validate(
            report_model_data).model_dump(exclude_none=True)
        # Vision-Certi 연동 활성(또는 설정 오류) 시 A/RA 는 인증서 서명 승인으로만 가능 (409 VC_SIGNATURE_REQUIRED),
        # 클라이언트가 보낸 승인자/승인일시는 무시한다. 연동 비활성(기본)이면 기존 동작 그대로.
        legacy_report_write_guard(report_data)

        logger.info(f"[TIMING] model_validate: {time.time()-t0:.3f}s")

        # 스터디 정보 조회 (배정자/상태 포함 — 권한 검증용)
        study_row = (StudyTable.table()
                     .select('center_name', 'hospital_idx', 'hospital_name',
                             'patient_id', 'study_description', 'hospital_name',
                             'study_body_part', 'study_modality',
                             'study_accession_no', 'patient_name',
                             'user_idx', 'study_status')
                     .where(study_idx=report_data['study_idx']).first())

        logger.info(f"[TIMING] study_query: {time.time()-t0:.3f}s")

        # ── 판독 저장/승인 권한 검증 ────────────────────────────────────
        # study.user_idx = 현재 배정자. 재배정되면 이전 배정자의 stale 목록에서
        # 저장/승인이 들어올 수 있어, 판독을 가로채는 사고가 발생함.
        # → 일반 판독의는 현재 배정자 본인(또는 미배정 픽업)만 허용하고,
        #   다른 사람에게 재배정된 경우 409로 차단한다.
        # 단, 관리자급(원장 등 group_level >= 98 / 슈퍼관리자)은 워크리스트 권한
        # 모델상 배정자와 무관하게 열람·판독이 허용되므로 차단 대상에서 제외한다.
        if study_row is None:
            request.state.inspect = frame()
            raise HTTPException(status_code=404, detail="study does not exist")
        _requester = user_info.get('user_idx') if user_info else None
        # 판독 수정 권한 2종 예외용 — 기존 판독문의 공동 수정 허용값(DB, 클라 페이로드 아님).
        # 컬럼 부재(마이그레이션 미적용) 등 조회 실패는 예외 없이 종전 동작으로 폴백.
        _existing_open_edit = None
        try:
            _r = (StudyReportTable.table()
                  .select('report_open_edit')
                  .where(study_idx=report_data['study_idx']).first())
            _existing_open_edit = _r.report_open_edit if _r else None
        except Exception:
            _existing_open_edit = None
        if (study_row.user_idx is not None
                and study_row.user_idx != _requester
                and not _can_read_others_studies(user_info)
                and not _report_edit_override(user_info, _existing_open_edit)):
            logger.warning(
                f"[REPORT_OWNER_REJECT] study_idx={report_data['study_idx']} "
                f"assignee={study_row.user_idx} requester={_requester} "
                f"user={user_info.get('user_id', '') if user_info else ''} ip={client_ip} "
                f"study_status={study_row.study_status} "
                f"report_status={report_data.get('report_status')} "
                f"— 재배정된 검사에 이전 배정자가 판독 저장 시도 (차단)"
            )
            request.state.inspect = frame()
            raise HTTPException(
                status_code=409,
                detail="이 검사는 다른 판독의에게 재배정되었습니다. 목록을 새로고침해주세요."
            )

        # 병원 사용자 정보 조회 (알림톡 전송용)
        hospital_user_row = (HospitalUserTable.table(session=session_manage)
                             .select('hospital_idx', 'user_type')
                             .select('user_idx', search_table=UserTable)
                             .select(
            func.inviz_aes_decrypt(UserTable.user_mobile_number).label(
                'user_mobile_number'), search_table=UserTable)
                             .join(UserTable, HospitalUserTable.user_idx,
                                   UserTable.user_idx)
                             .where(user_type='H',
                                    hospital_idx=study_row.hospital_idx,
                                    search_table=HospitalUserTable).all())

        logger.info(f"[TIMING] hospital_user_query: {time.time()-t0:.3f}s | receivers={len(hospital_user_row)}")

        # 유효한 전화번호 수집
        valid_receivers = []
        for hospital_user in hospital_user_row:
            if hospital_user.user_mobile_number:
                cleaned_number = re.sub(r'\D', '',
                                        hospital_user.user_mobile_number)
                if is_valid_phone(cleaned_number):
                    valid_receivers.append({
                        'phone': cleaned_number,
                        'user_idx': hospital_user.user_idx
                    })

        # 생성자 정보가 없으면 현재 사용자로 설정
        if 'report_create_idx' not in report_data or report_data['report_create_idx'] is None or report_data['report_create_idx'] == 0:
            report_data['report_create_idx'] = user_info['user_idx']

        # 승인자 정보가 없고 상태가 승인인 경우 현재 사용자로 설정
        if ('report_approve_idx' not in report_data or report_data['report_approve_idx'] is None) and report_data['report_status'] in ['A', 'RA']:
            report_data['report_approve_idx'] = user_info['user_idx']
            report_data['report_approve_datetime'] = datetime.now().isoformat()

        # 리포트 ID 처리
        report_idx = None
        if report_data.get('report_idx'):
            report_idx = report_data['report_idx']
            del report_data['report_idx']

        # 기존 리포트 업데이트 또는 새 리포트 생성
        if report_idx:
            # 리포트 업데이트
            StudyReportTable.table().where(report_idx=report_idx).update(
                auto_commit=True, **report_data)

            logger.info(f"[TIMING] report_update: {time.time()-t0:.3f}s")

            # 알림톡은 백그라운드에서 발송 (API 응답 블로킹 제거)
            if report_data['report_status'] in ['R', 'RR', 'A', 'RA'] and valid_receivers:
                alimtalk_message = f"{study_row.patient_id}({study_row.patient_name}) : {study_row.study_body_part} / {study_row.study_modality} / {study_row.study_accession_no} 의 리포트 내용이 수정되었습니다."
                background_tasks.add_task(
                    _send_report_alimtalk,
                    alimtalk_service, template_report_update, valid_receivers,
                    alimtalk_message, report_data['study_idx'], report_idx,
                    user_info['user_idx'], study_row.hospital_idx, logger
                )
        else:
            # 새 리포트 생성
            StudyReportTable.create(session, auto_commit=True, **report_data)

            logger.info(f"[TIMING] report_create: {time.time()-t0:.3f}s")

        # study_status UPDATE 제거 — DB 트리거(report_insert_trigger / report_update_trigger)에서 이미 처리
        # StudyTable.table().where(...).update(study_status=...) 는 트리거와 이중 실행이었음

        # CVR 알림도 백그라운드에서 처리
        if report_data['report_cvr_send'] == 'Y' and valid_receivers:
            current_time = datetime.now()
            formatted_time = current_time.strftime("%Y년%m월%d일 %H시 %M분")
            cvr_message = (f"{study_row.center_name}입니다.\n"
                       f"{study_row.hospital_name} / {study_row.patient_id} / "
                       f"{study_row.patient_name} / {study_row.study_description} / "
                       f"{formatted_time} / CVR / 판독완료되었습니다.")

            receivers_info = [
                {
                    'receiver': r['phone'],
                    'subject': 'CVR 판독 완료',
                    'message': cvr_message,
                    'receiver_user_idx': r['user_idx'],
                }
                for r in valid_receivers
            ]
            if receivers_info:
                background_tasks.add_task(
                    _send_cvr_alimtalk,
                    alimtalk_service, template_cvr_complete, receivers_info,
                    report_data['study_idx'], report_idx, user_info['user_idx'],
                    study_row.hospital_idx, logger
                )

        logger.info(f"[TIMING] add_report DONE | total: {time.time()-t0:.3f}s")

        # 응답 반환
        return success_response(
            data={"report_data": report_data},
            message="report add success"
        )

    except HTTPException:
        # 권한/상태 검증 등 의도된 4xx 는 그대로 전달 (500 으로 감싸지 않음)
        raise
    except Exception as e:
        logger.error(f"[TIMING] add_report ERROR at {time.time()-t0:.3f}s: {e}")
        request.state.inspect = frame()
        raise e


def _send_report_alimtalk(alimtalk_service, tpl_code, receivers, message,
                          study_idx, report_idx, user_idx, hospital_idx, logger):
    """백그라운드: 리포트 수정 알림톡 발송"""
    try:
        for i, receiver_info in enumerate(receivers):
            t = time.time()
            alimtalk_service.send_and_log(
                log_type=LogType.REPORT_UPDATE,
                tpl_code=tpl_code,
                receiver=receiver_info['phone'],
                subject='리포트 수정 알림',
                message=message,
                study_idx=study_idx,
                report_idx=report_idx,
                user_idx=user_idx,
                receiver_user_idx=receiver_info['user_idx'],
                hospital_idx=hospital_idx,
            )
            logger.info(f"[TIMING:BG] alimtalk_send[{i}]: {time.time()-t:.3f}s")
    except Exception as e:
        logger.error(f"[TIMING:BG] alimtalk_send ERROR: {e}")


def _send_cvr_alimtalk(alimtalk_service, tpl_code, receivers_info,
                       study_idx, report_idx, user_idx, hospital_idx, logger):
    """백그라운드: CVR 알림톡 발송"""
    try:
        t = time.time()
        alimtalk_service.send_multiple_and_log(
            log_type=LogType.CVR_COMPLETE,
            tpl_code=tpl_code,
            receivers=receivers_info,
            study_idx=study_idx,
            report_idx=report_idx,
            user_idx=user_idx,
            hospital_idx=hospital_idx,
        )
        logger.info(f"[TIMING:BG] cvr_alimtalk: {time.time()-t:.3f}s")
    except Exception as e:
        logger.error(f"[TIMING:BG] cvr_alimtalk ERROR: {e}")


@report_router.put("/{report_idx}", description='리포트 업데이트')
def update_report(request: Request, report_idx: str,
                  report_model_data: ReportUpdateDataModel):
    try:
        # 모델 데이터 검증 및 변환
        report_data = ReportUpdateDataModel.model_validate(
            report_model_data).model_dump(exclude_none=True)
        legacy_report_write_guard(report_data)  # Vision-Certi: A/RA 직접 기록 차단 (add_report 와 동일)

        # 리포트 + 소속 study 조회 (배정자 권한 검증용, 공동 수정 허용값 포함)
        report_row = (StudyReportTable.table()
                      .select('report_idx', 'study_idx', 'report_open_edit')
                      .where(report_idx=report_idx).first())
        if not report_row:
            request.state.inspect = frame()
            raise HTTPException(status_code=404, detail="report does not exist")

        # ── 판독 업데이트 권한 검증 (add_report 와 동일 규칙) ──────────────
        # 일반 판독의만 차단하고, 관리자급(원장 등)·판독 수정 권한 2종은 제외한다.
        user_info = request.state.user
        client_ip = request.client.host if request.client else ''
        study_row = (StudyTable.table()
                     .select('user_idx', 'study_status')
                     .where(study_idx=report_row.study_idx).first())
        _requester = user_info.get('user_idx') if user_info else None
        if (study_row and study_row.user_idx is not None
                and study_row.user_idx != _requester
                and not _can_read_others_studies(user_info)
                and not _report_edit_override(user_info, getattr(report_row, 'report_open_edit', None))):
            get_logger('study_report', 'study_report.log').warning(
                f"[REPORT_OWNER_REJECT:PUT] report_idx={report_idx} "
                f"study_idx={report_row.study_idx} assignee={study_row.user_idx} "
                f"requester={_requester} ip={client_ip} "
                f"— 재배정된 검사에 이전 배정자가 판독 업데이트 시도 (차단)"
            )
            request.state.inspect = frame()
            raise HTTPException(
                status_code=409,
                detail="이 검사는 다른 판독의에게 재배정되었습니다. 목록을 새로고침해주세요."
            )

        # 리포트 업데이트
        StudyReportTable.table().where(report_idx=report_idx).update(
            auto_commit=True, **report_data)

        # 응답 반환
        return success_response(message="Successfully updated report")

    except HTTPException as e:
        # HTTP 예외는 그대로 전달
        raise e
    except Exception as e:
        # 기타 예외 처리
        request.state.inspect = frame()
        raise e


@report_router.delete("/{report_idx}", description='리포트 삭제')
def delete_report(request: Request, report_idx: str, session: Session = Depends(db.session)):
    try:
        # Vision-Certi: 인증서 승인 이력(서명 증거)이 있는 판독문은 삭제 불가 (409 VC_SIGNED_REPORT_IMMUTABLE)
        legacy_report_delete_guard(session, report_idx)
        # 리포트 조회
        report = StudyReportTable.table().where(report_idx=report_idx).first()
        if not report:
            request.state.inspect = frame()
            raise HTTPException(status_code=404, detail="report does not exist")

        # 연관된 스터디 상태 업데이트
        StudyTable.table().where(study_idx=report.study_idx).update(
            auto_commit=True, study_status='E')

        # 리포트 삭제
        StudyReportTable.table().where(report_idx=report_idx).delete(
            auto_commit=True)

        # 응답 반환
        return success_response(message="Successfully deleted report")

    except HTTPException as e:
        # HTTP 예외는 그대로 전달
        raise e
    except Exception as e:
        # 기타 예외 처리
        request.state.inspect = frame()
        raise e


@report_router.get('/change_status/{report_type}', description="레포트 작성중 토글")
def change_status(request: Request, study_idx: str, report_type: str,
                  session: Session = Depends(db.session)):
    try:
        current_user_idx = request.state.user['user_idx']

        # SELECT ... FOR UPDATE — 동시 선점 방지 (트랜잭션 내 행 잠금)
        study_row = (
            session.query(StudyTable)
            .filter(StudyTable.study_idx == study_idx)
            .with_for_update()
            .first()
        )
        if not study_row:
            request.state.inspect = frame()
            raise HTTPException(status_code=404, detail="study does not exist")

        # 이미 작성중인 경우 — 본인이면 허용, 다른 판독의면 차단
        if study_row.study_status == 'RI':
            if study_row.user_idx != current_user_idx:
                request.state.inspect = frame()
                raise HTTPException(status_code=409, detail="이미 다른 판독의가 작성 중입니다")
            # 본인이 이미 RI 상태 → 중복 업데이트 불필요
            return success_response(message="Already in progress")

        # 작성, 승인, 참조는 제외
        if study_row.study_status in ['R', 'RR', 'REF', 'A', 'RA']:
            request.state.inspect = frame()
            raise HTTPException(status_code=409, detail="이미 판독이 완료된 검사입니다")

        # 상태 업데이트
        study_status = 'RI' if report_type == 'report' else 'E'
        study_row.study_status = study_status

        # 판독 선점 시 user_idx 기록 (기존 값 보존)
        if report_type == 'report':
            study_row.original_user_idx = study_row.user_idx
            study_row.user_idx = current_user_idx

        session.commit()
        return success_response(message="Successfully updated study status")
    except HTTPException as e:
        session.rollback()
        raise e
    except Exception as e:
        session.rollback()
        request.state.inspect = frame()
        raise e
