import { useState, useCallback, useMemo, useEffect, useRef, forwardRef, useImperativeHandle } from "react";
import { useFormContext } from "react-hook-form";
import { useNavigate, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Toast } from "@libs/Toast";
import { CustomConfirm } from "@libs/CustomConfirm";
import { setBroadcastReportUpdate } from "@libs/IPCSend";
import { useAddReportMutation, useStudyStatusChange } from "@queries/useReport";
import { useReportStore } from "@stores/useReportStore";
import { useSettingStore } from "@stores/useSettingStore";
import { useUserStore } from "@stores/useUserStore";
import { ReportFormType } from "@userTypes/report.types";
import StudyService from "@services/api/StudyService";
import { MdNavigateBefore, MdNavigateNext } from "react-icons/md";
import { useUserAllUiQuery, useUpdateUserUIMutation } from "@queries/useUI";
import { UpdateUiRequest } from "@userTypes/ui.types";
import { VisionCertiService } from "@services/api/VisionCertiService";
import { VcConfig, VcApproval } from "@shared/types/visionCerti.types";

import { RendererLogger } from '@libs/RendererLogger';

const logger = new RendererLogger('ReportViewer');
// 상태 변경이 불필요한 상태 목록 (이미 저장/승인/참조된 상태)
const EXCLUDED_STATUSES = ['R', 'RR', 'REF', 'A', 'RA'];

// 권한 검증이 필요한 상태 목록 (저장/승인된 상태)
const PERMISSION_CHECK_STATUSES = ['R', 'RR', 'A', 'RA'];

export interface ReportViewerHandle {
  handleSave: () => Promise<void>;
  handleApprove: () => Promise<void>;
  handleReset: () => void;
  getTextareaRefs: () => {
    readingRef: HTMLTextAreaElement | null;
    conclusionRef: HTMLTextAreaElement | null;
  };
}

const ReportViewer = forwardRef<ReportViewerHandle>(
  (_props, ref) => {
  const { t } = useTranslation('report');
  const navigate = useNavigate();
  const { studyIdx: paramStudyIdx } = useParams();
  const currentStudyIdx = Number(paramStudyIdx); // URL에서 직접 가져온 값 (항상 최신)

  const studyData = useReportStore(state => state.studyData);
  const reportData = useReportStore(state => state.reportData);
  const basic = useSettingStore(state => state.basic);
  const workList = useSettingStore(state => state.workList);
  const userData = useUserStore(state => state.userData);
  const vcSession = useUserStore(state => state.vcSession);

  // Vision-Certi state
  const [vcConfig, setVcConfig] = useState<VcConfig | null>(null);
  const [vcApprovals, setVcApprovals] = useState<VcApproval[]>([]);
  const [showApprovalsModal, setShowApprovalsModal] = useState(false);
  const [isFinalizing, setIsFinalizing] = useState(false);
  const [activeApprovalId, setActiveApprovalId] = useState<string | null>(null);
  const [snapshotPreview, setSnapshotPreview] = useState<{
    snapshot: string;
    snapshotSha256: string;
    approvalId: string;
    signRequestId: string;
  } | null>(null);

  useEffect(() => {
    let unmounted = false;
    VisionCertiService.getConfig()
      .then((cfg) => {
        if (!unmounted) setVcConfig(cfg);
      })
      .catch((err) => {
        console.warn('[ReportViewer] Failed to load VC config:', err);
      });
    return () => {
      unmounted = true;
    };
  }, []);

  const loadStudyApprovals = useCallback(async () => {
    if (!currentStudyIdx) return;
    try {
      const res = await VisionCertiService.listStudyApprovals(currentStudyIdx);
      setVcApprovals(res.items || []);
    } catch (err) {
      console.warn('[ReportViewer] Failed to load study approvals:', err);
    }
  }, [currentStudyIdx]);

  useEffect(() => {
    loadStudyApprovals();
  }, [loadStudyApprovals]);

  // 설정값
  const autoCommitEnabled = basic?.autoCommitEnabled || false;
  const reportSize = basic?.reportSize || null;
  const reportAlterUse = basic?.reportAlterUse !== undefined ? basic.reportAlterUse : true;
  const reportUseNext = basic?.reportUseNext !== undefined ? basic.reportUseNext : true;
  const reportAutoClose = basic?.reportAutoClose !== undefined ? basic.reportAutoClose : true;
  const useReportUpdateRefresh = workList?.useReportUpdateRefresh !== undefined ? workList.useReportUpdateRefresh : false;
  const comparisonAutoInsert = basic?.comparisonAutoInsert ?? false;

  const { mutate: useAddReport, mutateAsync: addReportAsync } = useAddReportMutation();

  // DB에서 UI 설정 불러오기
  const { data: uiData } = useUserAllUiQuery({ ui_page: "report" });
  const { mutate: updateUI } = useUpdateUserUIMutation();

  // textarea refs
  const readingRef = useRef<HTMLTextAreaElement>(null);
  const conclusionRef = useRef<HTMLTextAreaElement>(null);

  // textarea 리사이저 상태
  const [isTextareaResizing, setIsTextareaResizing] = useState(false);
  const textareaRafRef = useRef<number | null>(null);
  const textareaStartYRef = useRef(0);
  const textareaStartReadingHeightRef = useRef(0);
  const textareaStartConclusionHeightRef = useRef(0);

  // 상태 변경 추적을 위한 refs
  const originalStatusRef = useRef<string | null>(null);
  const hasCalledRIRef = useRef(false);

  // 상태 변경 mutation
  const { mutate: changeStatus } = useStudyStatusChange();

  // DB에서 불러온 높이 및 폰트 사이즈 적용
  useEffect(() => {
    if (uiData?.ui_data && Array.isArray(uiData.ui_data)) {
      const readingHeightData = uiData.ui_data.find(item => item.ui_type === "readingHeight");
      const conclusionHeightData = uiData.ui_data.find(item => item.ui_type === "conclusionHeight");

      if (readingHeightData?.ui_content && readingRef.current) {
        readingRef.current.style.height = `${readingHeightData.ui_content}px`;
      }
      if (conclusionHeightData?.ui_content && conclusionRef.current) {
        conclusionRef.current.style.height = `${conclusionHeightData.ui_content}px`;
      }
    }

    // reportSize 적용
    if (reportSize) {
      if (readingRef.current) {
        readingRef.current.style.fontSize = `${reportSize}px`;
      }
      if (conclusionRef.current) {
        conclusionRef.current.style.fontSize =  `${reportSize}px`;
      }
    }
  }, [uiData, reportSize]);

  // 외부에서 textarea 포커스 요청 처리
  useEffect(() => {
    const handleFocusTextarea = (_event: Electron.IpcRendererEvent) => {
      setTimeout(() => {
        if (readingRef.current) {
          readingRef.current.focus();
        }
      }, 100);
    };

    const unsubscribe = window.electron.ipcRenderer.on('report:focus-textarea', handleFocusTextarea);
    return () => {
      unsubscribe();
    };
  }, []);

  // Main 프로세스에서 study:report-sync 이벤트 수신 (다른 윈도우에서 이전/다음 네비게이션 시)
  useEffect(() => {
    const handleReportSync = (_event: Electron.IpcRendererEvent, newStudyIdx: number) => {
      navigate(`/report/${newStudyIdx}`, { replace: true });
    };

    const unsubscribe = window.electron.ipcRenderer.on('study:report-sync', handleReportSync);
    return () => {
      unsubscribe();
    };
  }, [navigate]);

  // 네비게이션 경계 알림 (처음/마지막 검사)
  useEffect(() => {
    const handleNavigateBoundary = (_event: Electron.IpcRendererEvent, boundaryType: 'first' | 'last') => {
      if (boundaryType === 'first') {
        Toast(t('toast.noPreviousStudy'), "warning", "top-right", 2000, "dark", true, true, false, "navigate-boundary");
      } else {
        Toast(t('toast.noNextStudy'), "warning", "top-right", 2000, "dark", true, true, false, "navigate-boundary");
      }
    };

    const unsubscribe = window.electron.ipcRenderer.on('study:navigate-boundary', handleNavigateBoundary);
    return () => {
      unsubscribe();
    };
  }, [t]);

  // 데이터 로드 완료 후 focusWindowAfterOpen이 'report'면 textarea 자동 포커스
  useEffect(() => {
    if (studyData && basic?.focusWindowAfterOpen === 'report') {
      // 약간의 딜레이 후 포커스 (렌더링 완료 대기)
      const timer = setTimeout(() => {
        if (readingRef.current) {
          readingRef.current.focus();
        }
      }, 100);
      return () => clearTimeout(timer);
    }
  }, [studyData, basic?.focusWindowAfterOpen]);

  // studyData 로드 시 원래 상태 저장 및 refs 초기화
  useEffect(() => {
    if (studyData?.study_status) {
      originalStatusRef.current = studyData.study_status;
      hasCalledRIRef.current = false;
    }
  }, [studyData?.study_idx]); // study_idx가 변경될 때만 초기화

  // 텍스트 입력 시 RI 상태로 변경하는 핸들러
  const handleTextInput = useCallback(() => {
    // 이미 RI 호출했으면 스킵
    if (hasCalledRIRef.current) return;

    // 제외할 상태이면 스킵 (이미 저장/승인/참조된 상태)
    const currentStatus = studyData?.study_status;
    if (!currentStatus || EXCLUDED_STATUSES.includes(currentStatus)) return;

    // studyIdx가 없으면 스킵
    if (!currentStudyIdx) return;

    // RI 상태로 변경
    hasCalledRIRef.current = true;
    changeStatus(
      { studyIdx: currentStudyIdx, reportType: 'report' }
    );
  }, [studyData?.study_status, currentStudyIdx, changeStatus, t, basic?.focusWindowAfterOpen]);

  // 상태 원복 함수 (RI로 변경했을 때만 원래 상태로 복구)
  const revertStatus = useCallback(() => {
    // RI를 호출한 적이 없으면 스킵
    if (!hasCalledRIRef.current) return;

    // studyIdx가 없으면 스킵
    if (!currentStudyIdx) return;

    // 원래 상태로 복구 (reportType: 'end'는 E 상태로 변경)
    changeStatus(
      { studyIdx: currentStudyIdx, reportType: 'end' },
      {
        onSuccess: () => {
          hasCalledRIRef.current = false;
        }
      }
    );
  }, [currentStudyIdx, changeStatus]);

  // textarea 높이 변경 핸들러
  const handleReadingResize = useCallback(() => {
    if (readingRef.current) {
      const height = readingRef.current.offsetHeight;
      const updateBody: UpdateUiRequest = {
        ui_page: "report",
        ui_content: height.toString(),
        ui_type: "readingHeight",
      };
      updateUI(updateBody);
    }
  }, [updateUI]);

  const handleConclusionResize = useCallback(() => {
    if (conclusionRef.current) {
      const height = conclusionRef.current.offsetHeight;
      const updateBody: UpdateUiRequest = {
        ui_page: "report",
        ui_content: height.toString(),
        ui_type: "conclusionHeight",
      };
      updateUI(updateBody);
    }
  }, [updateUI]);

  // textarea 드래그 리사이저 핸들러
  const handleTextareaResizerMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    textareaStartYRef.current = e.clientY;
    textareaStartReadingHeightRef.current = readingRef.current?.offsetHeight || 240;
    textareaStartConclusionHeightRef.current = conclusionRef.current?.offsetHeight || 240;
    setIsTextareaResizing(true);
  }, []);

  useEffect(() => {
    if (!isTextareaResizing) return;

    const handleMouseMove = (e: MouseEvent) => {
      const delta = e.clientY - textareaStartYRef.current;
      const newReadingHeight = Math.max(80, textareaStartReadingHeightRef.current + delta);
      const newConclusionHeight = Math.max(80, textareaStartConclusionHeightRef.current - delta);

      if (textareaRafRef.current) return;
      textareaRafRef.current = requestAnimationFrame(() => {
        if (readingRef.current) {
          readingRef.current.style.height = `${newReadingHeight}px`;
        }
        if (conclusionRef.current) {
          conclusionRef.current.style.height = `${newConclusionHeight}px`;
        }
        textareaRafRef.current = null;
      });
    };

    const handleMouseUp = () => {
      if (textareaRafRef.current) {
        cancelAnimationFrame(textareaRafRef.current);
        textareaRafRef.current = null;
      }

      // DB에 높이 저장
      if (readingRef.current) {
        updateUI({
          ui_page: "report",
          ui_content: readingRef.current.offsetHeight.toString(),
          ui_type: "readingHeight",
        });
      }
      if (conclusionRef.current) {
        updateUI({
          ui_page: "report",
          ui_content: conclusionRef.current.offsetHeight.toString(),
          ui_type: "conclusionHeight",
        });
      }

      setIsTextareaResizing(false);
    };

    document.addEventListener('mousemove', handleMouseMove, { passive: true });
    document.addEventListener('mouseup', handleMouseUp);
    document.body.style.cursor = 'row-resize';
    document.body.style.userSelect = 'none';

    return () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      if (textareaRafRef.current) {
        cancelAnimationFrame(textareaRafRef.current);
        textareaRafRef.current = null;
      }
    };
  }, [isTextareaResizing, updateUI]);

  // 읽기 전용 판단
  const isRefStudy = useMemo(() => {
    return studyData?.study_status === 'REF' || studyData?.study_is_ref === 'Y';
  }, [studyData]);

  // 다른 사용자가 작성/승인한 리포트인지 확인 (수정 권한 검증)
  const isOtherUserReport = useMemo(() => {
    const reportStatus = reportData?.report_status;
    const currentUserIdx = userData?.user_idx;

    // 권한 검증이 필요한 상태가 아니면 false
    if (!reportStatus || !PERMISSION_CHECK_STATUSES.includes(reportStatus)) {
      return false;
    }

    // 로그인 정보가 없으면 false (비정상 상태)
    if (!currentUserIdx) {
      return false;
    }

    // 판독문이 공동 수정 허용('Y')이면 누구나 수정 가능
    if (reportData?.report_open_edit === 'Y') {
      return false;
    }
    // 내 계정에 타인 판독문 수정 권한(관리자 부여)이 있으면 수정 가능
    if (userData?.user_report_edit_all === 'Y') {
      return false;
    }

    const createIdx = reportData?.report_create_idx;
    const approveIdx = reportData?.report_approve_idx;

    // report_create_idx 또는 report_approve_idx가 있고, 현재 사용자와 다르면 true
    if (createIdx && createIdx !== currentUserIdx) {
      return true;
    }
    if (approveIdx && approveIdx !== currentUserIdx) {
      return true;
    }

    return false;
  }, [reportData, userData]);

  // 편집 불가 상태 (참조 검사 또는 다른 사용자 리포트)
  const isEditDisabled = useMemo(() => {
    return isRefStudy || isOtherUserReport;
  }, [isRefStudy, isOtherUserReport]);

  const { getValues, setValue, handleSubmit, register } = useFormContext<ReportFormType>();

  // 이전 검사 비교 정보 자동 삽입
  useEffect(() => {
    if (!comparisonAutoInsert || !studyData?.study_idx || !studyData?.study_modality) return;

    // 이미 reading에 내용이 있으면 삽입하지 않음
    const currentReading = getValues("report_reading");
    if (currentReading) return;

    // 편집 불가 상태이면 삽입하지 않음
    if (isEditDisabled) return;

    const fetchComparison = async () => {
      try {
        const result = await StudyService.getAdjacentStudy({
          studyIdx: studyData.study_idx,
          studyPosition: 'compare1',
          studyModality: studyData.study_modality,
          studyBodyPart: studyData.study_body_part,
          studySearchType: 'equal',
        });

        if (result?.study_idx && result.study_datetime) {
          const dateStr = result.study_datetime.split('T')[0];
          const modality = result.study_modality || studyData.study_modality || '';
          const bodyPart = result.study_body_part || studyData.study_body_part || '';
          const description = result.study_description || '';
          const parts = [dateStr, modality, bodyPart, description].filter(Boolean);
          const comparisonText = `Comparison: ${parts.join(' / ')}`;

          // reading이 여전히 비어있는지 재확인
          const latestReading = getValues("report_reading");
          if (!latestReading) {
            setValue("report_reading", comparisonText + '\n');
          }
        }
      } catch {
        // 이전 검사가 없으면 무시
      }
    };

    fetchComparison();
  }, [studyData?.study_idx, comparisonAutoInsert, isEditDisabled]);

  // 제출 핸들러
  const onSubmit = handleSubmit(
    (data) => {
      if (!data.report_reading && !data.report_conclusion) {
        Toast(t('toast.enterReadingOrConclusion'), "warning");
        return;
      }
      // report_cvr_send/report_open_edit 를 boolean에서 'Y'/'N'으로 변환
      const submitData = {
        ...data,
        report_cvr_send: data.report_cvr_send ? "Y" : "N",
        report_open_edit: data.report_open_edit ? "Y" : "N"
      };
      const study_status = data.report_status
      // [APPROVAL_TIMING] 측정 시작 — 승인→다음 검사 표시까지 단계별 시간 추적
      const approvalStart = performance.now();
      console.log(`[APPROVAL_TIMING] submit start status=${study_status} study_idx=${data.study_idx}`);
      useAddReport(submitData, {
        onSuccess: async () => {
          console.log(`[APPROVAL_TIMING] save api done at ${(performance.now() - approvalStart).toFixed(0)}ms`);
          Toast(t('toast.saved'), "success");

          // 저장 성공 시 상태 플래그 리셋 (창 닫혀도 원복 안함)
          hasCalledRIRef.current = false;

          // 브로드캐스트 업데이트
          if (useReportUpdateRefresh) {
            setBroadcastReportUpdate();
          }

          // 다음 검사로 이동
          const navigatedToNext = reportUseNext && (study_status === "A" || study_status === "RA");
          if (navigatedToNext) {
            console.log(`[APPROVAL_TIMING] navigate request at ${(performance.now() - approvalStart).toFixed(0)}ms`);
            await handleChangeReport('next');
          }
          // 다음 검사로 이동한 경우에는 창을 닫지 않음 (다음 리포트가 열려야 하므로)
          if(reportAutoClose && !navigatedToNext){
            window.electron.ipcRenderer.send('win:invoke', 'close')
          }
        },
        onError: (error) => {
          // 백엔드가 재배정/권한 위반을 409 로 거부 → generic "저장 실패" 대신
          // 명확한 안내(재배정됨·목록 새로고침) 표시.
          // ReportService 가 status + 본문(detail) 을 보존해 던짐.
          const status = (error as any)?.status;
          const detail = (error as any)?.detail;
          if (status === 409 && detail) {
            Toast(detail, "error");
          } else {
            Toast(t('toast.saveFailed'), "error");
          }
          logger.error("Report save error:", error);
        }
      });
    },
    (errors) => {
      for (const item of Object.values(errors)) {
        if (item) {
          Toast((item as any)?.message, "warning");
          ((item as any)?.ref as HTMLInputElement)?.focus();
          break;
        }
      }
    }
  );

  // {t('viewer.reset')}
  const handleReset = useCallback(() => {
    if (isRefStudy) {
      Toast(t('toast.cannotResetRef'), "warning");
      return;
    }
    if (isOtherUserReport) {
      Toast(t('toast.cannotEditOtherUserReport'), "warning");
      return;
    }

    CustomConfirm({
      title: t('confirm.resetTitle'),
      text: t('confirm.reset'),
      icon: 'warning',
      useCancel: true,
    }, (result) => {
      if (result.isConfirmed) {
        setValue("report_reading", "");
        setValue("report_conclusion", "");
        setValue("report_refer_comment", "");
        setValue("report_cvr_send", false);
        setValue("report_open_edit", false);

        // 상태 원복 (RI로 변경했을 경우 원래 상태로)
        revertStatus();

        // 초기화 후 Reading textarea에 포커스 복원
        setTimeout(() => {
          if (readingRef.current) {
            readingRef.current.focus();
          }
        }, 100);
      }
    });
  }, [isRefStudy, isOtherUserReport, setValue, t, revertStatus]);

  // 이전/다음 검사 이동 (Main 프로세스에 위임)
  const handleChangeReport = useCallback(async (direction: 'prev' | 'next') => {
    if (!currentStudyIdx) {
      Toast(t('toast.currentStudyNotFound'), "warning");
      return;
    }

    // Main 프로세스에 네비게이션 요청 (URL params에서 직접 가져온 최신 값 사용)
    window.electron.ipcRenderer.send('study:navigate-prev-next', {
      direction,
      currentStudyIdx, // studyData 대신 URL params 사용 (항상 최신)
      reportAutoOpen: true, // 레포트 윈도우는 이미 열려 있으므로 true
      focusWindowAfterOpen: basic?.focusWindowAfterOpen || 'report',
    });
  }, [currentStudyIdx, basic, t]);

  // 로컬 초안 저장 (VC 전자서명 준비 전 DB 상태 동기화)
  const saveDraftLocally = useCallback(async (): Promise<boolean> => {
    const study_status = getValues("study_status");
    const reading = getValues("report_reading");
    const conclusion = getValues("report_conclusion");
    if (!reading && !conclusion) {
      Toast(t('toast.enterReadingOrConclusion'), "warning");
      return false;
    }
    const draftStatus = (study_status === "RR" || study_status === "RE" || study_status === "RA") ? "RR" : "R";
    setValue("report_status", draftStatus);

    const formData = getValues();
    const submitData = {
      ...formData,
      report_status: draftStatus,
      report_cvr_send: formData.report_cvr_send ? "Y" : "N",
      report_open_edit: formData.report_open_edit ? "Y" : "N",
    };

    try {
      await addReportAsync(submitData as any);
      hasCalledRIRef.current = false;
      if (useReportUpdateRefresh) setBroadcastReportUpdate();
      return true;
    } catch (err: any) {
      const detail = err?.detail || err?.response?.data?.detail;
      Toast(detail || t('toast.saveFailed'), "error");
      return false;
    }
  }, [getValues, setValue, addReportAsync, useReportUpdateRefresh, t]);

  // Vision-Certi 서명 팝업 시작 및 권한 있는 최종 확정(Finalize) 처리
  const startSigningPopup = useCallback((approvalId: string, signRequestId: string) => {
    if (!vcConfig) return;
    setSnapshotPreview(null);
    setIsFinalizing(true);

    const origin = window.location.origin;
    const popupUrl = `${vcConfig.visionCertiBaseUrl}/#/doctor/sign-requests/${signRequestId}?audience=sv70-pacs&origin=${encodeURIComponent(origin)}`;
    const popup = window.open(popupUrl, 'VisionCertiSignPopup', 'width=700,height=880');
    if (!popup) {
      Toast("서명 팝업이 차단되었습니다. 브라우저 팝업을 허용해주세요.", "warning");
      setIsFinalizing(false);
      return;
    }

    let finalized = false;
    const doFinalize = async () => {
      if (finalized) return;
      finalized = true;
      try {
        const finalRes = await VisionCertiService.finalizeReport(approvalId);
        if (finalRes.approval.state === 'APPROVED') {
          const study_status = getValues("study_status");
          const finalStatus = (study_status === "RR" || study_status === "RE" || study_status === "RA") ? "RA" : "A";
          setValue("report_status", finalStatus);
          setValue("study_status", finalStatus);
          const hashShort = (finalRes.approval.contentSha256 || finalRes.approval.snapshotSha256)?.substring(0, 10) || '';
          Toast(`공동인증서 전자서명 및 최종 승인이 완료되었습니다. (해시: ${hashShort}...)`, "success");
          hasCalledRIRef.current = false;
          if (useReportUpdateRefresh) setBroadcastReportUpdate();
          await loadStudyApprovals();

          const navigatedToNext = reportUseNext;
          if (navigatedToNext) {
            await handleChangeReport('next');
          }
          if (reportAutoClose && !navigatedToNext) {
            window.electron.ipcRenderer.send('win:invoke', 'close');
          }
        } else {
          Toast(`전자서명이 완료되지 않았습니다 (상태: ${finalRes.approval.state}). 리포트는 임시저장(R) 상태로 유지됩니다.`, "warning");
          await loadStudyApprovals();
        }
      } catch (err: any) {
        Toast(err?.message || "최종 서명 검증 및 확정에 실패했습니다. 리포트는 임시저장 상태로 유지됩니다.", "error");
        await loadStudyApprovals();
      } finally {
        setIsFinalizing(false);
        setActiveApprovalId(null);
      }
    };

    const handleMsg = async (event: MessageEvent) => {
      let expectedOrigin = '';
      try {
        expectedOrigin = new URL(vcConfig.visionCertiBaseUrl).origin.toLowerCase();
      } catch (e) {}
      if (expectedOrigin && event.origin.toLowerCase() !== expectedOrigin) {
        return;
      }
      if (event.source !== popup) {
        return;
      }
      if (event.data?.type === 'VC_SIGN_COMPLETED' && event.data?.signRequestId === signRequestId) {
        window.removeEventListener('message', handleMsg);
        clearInterval(pollClosed);
        try { if (popup && !popup.closed) popup.close(); } catch(e){}
        await doFinalize();
      }
    };
    window.addEventListener('message', handleMsg);

    const pollClosed = setInterval(() => {
      if (popup.closed) {
        clearInterval(pollClosed);
        window.removeEventListener('message', handleMsg);
        doFinalize();
      }
    }, 1000);
  }, [vcConfig, getValues, setValue, useReportUpdateRefresh, loadStudyApprovals, reportUseNext, handleChangeReport, reportAutoClose]);

  // {t('viewer.approve')}
  const handleApprove = useCallback(async () => {
    if (isRefStudy) {
      Toast(t('toast.cannotApproveRef'), "warning");
      return;
    }
    if (isOtherUserReport) {
      Toast(t('toast.cannotEditOtherUserReport'), "warning");
      return;
    }

    const study_status = getValues("study_status");
    const reading = getValues("report_reading");
    const conclusion = getValues("report_conclusion");

    if (!reading && !conclusion) {
      Toast(t('toast.enterBothReadingAndConclusion'), "warning");
      return;
    }

    // Vision-Certi 공동인증서 전자서명 게이트
    if (vcConfig?.enabled) {
      if (!vcSession) {
        Toast("공동인증서 세션이 필요합니다. 먼저 Vision-Certi 공동인증서 로그인을 진행해주세요.", "warning");
        return;
      }

      Toast("판독문 초안을 저장하고 공동인증서 서명 스냅샷을 생성합니다...", "info");
      const saved = await saveDraftLocally();
      if (!saved) {
        return;
      }

      let currentRep;
      try {
        currentRep = await VisionCertiService.getCurrentReport(currentStudyIdx);
      } catch (err: any) {
        Toast(err?.message || "현재 판독문 해시 조회에 실패했습니다.", "error");
        return;
      }

      let prepRes;
      try {
        prepRes = await VisionCertiService.prepareReport(currentStudyIdx, currentRep.contentSha256);
      } catch (err: any) {
        Toast(err?.message || "전자서명 준비 요청에 실패했습니다.", "error");
        await loadStudyApprovals();
        return;
      }

      setActiveApprovalId(prepRes.approval.approvalId);
      // 의사가 실제 서명될 스냅샷 내용과 해시를 직접 확인하도록 모달 팝업 표시
      setSnapshotPreview({
        snapshot: prepRes.snapshot,
        snapshotSha256: prepRes.snapshotSha256,
        approvalId: prepRes.approval.approvalId,
        signRequestId: prepRes.visionCertiSignRequestId,
      });
      return;
    }

    const doApprove = async () => {
      setValue("report_status", (study_status === "RR" || study_status === "RE") ? "RA" : "A");
      await onSubmit();
    };

    if (reportAlterUse) {
      CustomConfirm(
        {
          title: t('confirm.approve.title'),
          text: t('confirm.approve.text'),
          icon: "info",
        },
        async (result) => {
          if (result.isConfirmed) {
            await doApprove();
          }
        }
      );
    } else {
      await doApprove();
    }
  }, [isRefStudy, isOtherUserReport, getValues, setValue, onSubmit, reportAlterUse, t, vcConfig, vcSession, saveDraftLocally, currentStudyIdx, loadStudyApprovals]);

  // {t('viewer.save')}
  const handleSave = useCallback(async () => {
    if (isRefStudy) {
      Toast(t('toast.cannotSaveRef'), "warning");
      return;
    }
    if (isOtherUserReport) {
      Toast(t('toast.cannotEditOtherUserReport'), "warning");
      return;
    }

    const study_status = getValues("study_status");
    const reading = getValues("report_reading");
    const conclusion = getValues("report_conclusion");

    if (!reading && !conclusion) {
      Toast(t('toast.enterReadingOrConclusion'), "warning");
      return;
    }

    const doSave = async () => {
      // autoCommitEnabled가 true이고 VC가 활성화된 경우: 암묵적 우회 금지 -> VC 전자서명 승인으로 전달
      if (autoCommitEnabled && vcConfig?.enabled) {
        await handleApprove();
        return;
      }
      if (autoCommitEnabled) {
        setValue("report_status", (study_status === "RR" || study_status === "RE") ? "RA" : "A");
      } else {
        setValue("report_status", (study_status === "RE" || study_status === "RR" || study_status === "RA") ? "RR" : "R");
      }
      await onSubmit();
    };

    const isAlreadySaved = PERMISSION_CHECK_STATUSES.includes(study_status);
    if (isAlreadySaved) {
      CustomConfirm(
        {
          title: t('confirm.overwrite.title'),
          text: t('confirm.overwrite.text'),
          icon: "warning",
        },
        async (result) => {
          if (result.isConfirmed) {
            await doSave();
          }
        }
      );
    } else {
      await doSave();
    }
  }, [isRefStudy, isOtherUserReport, getValues, setValue, onSubmit, autoCommitEnabled, vcConfig, handleApprove, t]);

  // Expose functions to parent component via ref
  useImperativeHandle(ref, () => ({
    handleSave,
    handleApprove,
    handleReset,
    getTextareaRefs: () => ({
      readingRef: readingRef.current,
      conclusionRef: conclusionRef.current,
    }),
  }), [handleSave, handleApprove, handleReset]);

  return (
    <div className="report_viewer">
      <div className="patient_info_header">
        <div className="patient_info_content">
          <div className="patient_main_info">
            <h1>{studyData?.patient_name} ({studyData?.patient_sex}/{studyData?.patient_age})</h1>
            <div className="patient_id">ID: {studyData?.patient_id}</div>
            <div className="study_date">{studyData?.study_datetime}</div>
            <h4 className="study_info">
              {studyData?.study_modality} / {studyData?.study_body_part} / {studyData?.study_description}
            </h4>
          </div>
          <div className="patient_actions">
            <div className="checkbox_group">
              <input
                type="checkbox"
                id="cvr_notice_header"
                {...register("report_cvr_send")}
                disabled={isEditDisabled}
              />
              <label htmlFor="cvr_notice_header">{t('viewer.cvrNotice')}</label>
            </div>
            {/* 공동 수정 허용 — 저장/승인 시 함께 전송(Y 면 작성자 외 누구나 수정 가능) */}
            <div className="checkbox_group" title={t('viewer.openEditTooltip')}>
              <input
                type="checkbox"
                id="open_edit_header"
                {...register("report_open_edit")}
                disabled={isEditDisabled}
              />
              <label htmlFor="open_edit_header">{t('viewer.openEdit')}</label>
            </div>
            <div className="navigation_buttons">
              <button
                className="btn_nav btn_prev"
                onClick={() => handleChangeReport('prev')}
                title="이전 검사"
              >
                <MdNavigateBefore />
              </button>
              <button
                className="btn_nav btn_next"
                onClick={() => handleChangeReport('next')}
                title="다음 검사"
              >
                <MdNavigateNext />
              </button>
            </div>
            <div className="action_buttons">
              <button
                className="btn_reset"
                onClick={handleReset}
                disabled={isEditDisabled}
              >
                {t('viewer.reset')}
              </button>
              <button
                className="btn_save"
                onClick={handleSave}
                disabled={isEditDisabled || isFinalizing}
              >
                {t('viewer.save')}
              </button>
              {vcConfig?.enabled && (
                <button
                  type="button"
                  className="btn_save"
                  data-testid="pacs-report-approvals-history-btn"
                  onClick={() => setShowApprovalsModal(true)}
                  style={{
                    backgroundColor: '#1e293b',
                    borderColor: '#38bdf8',
                    color: '#38bdf8',
                    cursor: 'pointer',
                    fontSize: '12px',
                    padding: '6px 12px',
                  }}
                  title="Vision-Certi 전자서명 및 승인 이력 조회"
                >
                  📜 서명 이력 ({vcApprovals.length})
                </button>
              )}
              {!autoCommitEnabled && (
                <button
                  className="btn_approve"
                  onClick={handleApprove}
                  disabled={isEditDisabled || isFinalizing}
                  style={isFinalizing ? { opacity: 0.6, cursor: 'wait' } : undefined}
                >
                  {isFinalizing ? '서명 검증 중...' : t('viewer.approve')}
                </button>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* 재판독 사유 배너 */}
      {studyData?.re_request_reason && (
        <div className="re_request_banner">
          <span className="re_request_label">{t('viewer.reRequestReason')}</span>
          <span className="re_request_content">{studyData.re_request_reason}</span>
        </div>
      )}

      <div className="report_form_container">
        <div className="report_form">
          <div className="form_row info_row_inline">
            <div className="form_group inline">
              <label htmlFor="patient_id">{t('viewer.id')}:</label>
              <input
                type="text"
                id="patient_id"
                value={studyData?.patient_id || ''}
                disabled
              />
            </div>
            <div className="form_group inline">
              <label htmlFor="reporter">{t('viewer.reporter')}:</label>
              <input
                type="text"
                id="reporter"
                value={reportData?.report_create_name || ''}
                disabled
              />
            </div>
            <div className="form_group inline">
              <label htmlFor="report_date">{t('viewer.reportDay')}:</label>
              <input
                type="text"
                id="report_date"
                value={studyData?.study_datetime || ''}
                disabled
              />
            </div>
          </div>

          <div className="form_row">
            <div className="form_group full_width">
              <label htmlFor="hospital_comment">{t('viewer.hospitalComment')}</label>
              <input
                type="text"
                id="hospital_comment"
                value={studyData?.original_comments || ''}
                disabled
              />
            </div>
          </div>

          <div className="form_row">
            <div className="form_group full_width">
              <label htmlFor="study_comment">{t('viewer.studyComment')}</label>
              <input
                type="text"
                id="study_comment"
                {...register("report_study_comment")}
                disabled={isEditDisabled}
              />
            </div>
          </div>

          <div className="form_row">
            <div className="form_group full_width">
              <label htmlFor="refer_comment">{t('viewer.referComment')}</label>
              <input
                type="text"
                id="refer_comment"
                {...register("report_refer_comment")}
                disabled={isEditDisabled}
              />
            </div>
          </div>

          <div className="form_row">
            <div className="form_group full_width textarea_group">
              <div className="textarea_label_row">
                <label htmlFor="reading">{t('viewer.reading')}</label>
                {isRefStudy && (
                  <span className="prohibition_notice">
                    {t('viewer.prohibition')}
                  </span>
                )}
                {isOtherUserReport && (
                  <span className="prohibition_notice">
                    {t('viewer.otherUserReport')}
                  </span>
                )}
              </div>
              <textarea
                id="reading"
                placeholder={t('viewer.readingPlaceholder')}
                {...register("report_reading", {
                  setValueAs: (value) => value,
                })}
                ref={(e) => {
                  register("report_reading").ref(e);
                  readingRef.current = e;
                }}
                disabled={isEditDisabled}
                onMouseUp={handleReadingResize}
                onInput={handleTextInput}
              />
            </div>
          </div>

          <div
            className="textarea-resizer"
            onMouseDown={handleTextareaResizerMouseDown}
          />

          <div className="form_row">
            <div className="form_group full_width textarea_group">
              <div className="textarea_label_row">
                <label htmlFor="conclusion">{t('viewer.conclusion')}</label>
              </div>
              <textarea
                id="conclusion"
                placeholder={t('viewer.conclusionPlaceholder')}
                {...register("report_conclusion", {
                  setValueAs: (value) => value,
                })}
                ref={(e) => {
                  register("report_conclusion").ref(e);
                  conclusionRef.current = e;
                }}
                disabled={isEditDisabled}
                onMouseUp={handleConclusionResize}
                onInput={handleTextInput}
              />
            </div>
          </div>
        </div>
      </div>
      {/* Vision-Certi 판독문 전자서명 스냅샷 사전 검토 모달 (Defect 1) */}
      {snapshotPreview && (
        <div
          data-testid="pacs-report-snapshot-preview-modal"
          style={{
            position: 'fixed',
            inset: 0,
            backgroundColor: 'rgba(0,0,0,0.75)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 9999,
          }}
        >
          <div
            style={{
              background: '#0f172a',
              border: '1px solid #0284c7',
              borderRadius: '8px',
              padding: '24px',
              width: '800px',
              maxHeight: '85vh',
              overflowY: 'auto',
              color: '#f8fafc',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <div>
                <h3 style={{ fontSize: '18px', fontWeight: 600, color: '#38bdf8', margin: 0 }}>
                  🔍 전자서명 원본 스냅샷 검토 (Signing Snapshot Verification)
                </h3>
                <p style={{ fontSize: '12px', color: '#94a3b8', marginTop: '4px', marginBottom: 0 }}>
                  승인 전 생성된 암호학적 판독문 스냅샷과 SHA-256 해시를 확인한 후 의사 전자서명을 진행하십시오.
                </p>
              </div>
              <button
                type="button"
                onClick={() => setSnapshotPreview(null)}
                style={{
                  background: 'none',
                  border: 'none',
                  color: '#94a3b8',
                  fontSize: '20px',
                  cursor: 'pointer',
                }}
              >
                ✕
              </button>
            </div>

            <div style={{ marginBottom: '12px', padding: '10px 14px', background: '#1e293b', borderRadius: '6px', fontSize: '12px' }}>
              <div><strong>스냅샷 SHA-256 해시:</strong> <code style={{ color: '#38bdf8', wordBreak: 'break-all' }}>{snapshotPreview.snapshotSha256}</code></div>
              <div style={{ marginTop: '4px', color: '#94a3b8' }}>
                승인 ID: <code>{snapshotPreview.approvalId}</code> | 서명 요청 ID: <code>{snapshotPreview.signRequestId}</code>
              </div>
            </div>

            <div style={{ marginBottom: '16px' }}>
              <label style={{ display: 'block', fontSize: '12px', fontWeight: 600, color: '#cbd5e1', marginBottom: '6px' }}>
                서명 대상 스냅샷 본문 (Authoritative Snapshot Body):
              </label>
              <pre
                style={{
                  background: '#020617',
                  border: '1px solid #334155',
                  borderRadius: '6px',
                  padding: '12px',
                  fontSize: '12px',
                  fontFamily: 'monospace',
                  whiteSpace: 'pre-wrap',
                  maxHeight: '320px',
                  overflowY: 'auto',
                  color: '#e2e8f0',
                }}
              >
                {snapshotPreview.snapshot}
              </pre>
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px' }}>
              <button
                type="button"
                onClick={() => setSnapshotPreview(null)}
                style={{
                  padding: '8px 18px',
                  background: '#334155',
                  border: 'none',
                  borderRadius: '4px',
                  color: '#cbd5e1',
                  cursor: 'pointer',
                  fontSize: '13px',
                }}
              >
                취소
              </button>
              <button
                type="button"
                data-testid="pacs-confirm-sign-popup-btn"
                onClick={() => startSigningPopup(snapshotPreview.approvalId, snapshotPreview.signRequestId)}
                style={{
                  padding: '8px 20px',
                  background: '#0284c7',
                  border: 'none',
                  borderRadius: '4px',
                  color: '#fff',
                  fontWeight: 600,
                  cursor: 'pointer',
                  fontSize: '13px',
                }}
              >
                확인 및 전자서명 진행 (Sign via Vision-Certi)
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Vision-Certi Approvals History Modal */}
      {showApprovalsModal && (
        <div
          data-testid="pacs-report-approvals-modal"
          style={{
            position: 'fixed',
            inset: 0,
            backgroundColor: 'rgba(0,0,0,0.7)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 9999,
          }}
        >
          <div
            style={{
              background: '#0f172a',
              border: '1px solid #334155',
              borderRadius: '8px',
              padding: '24px',
              width: '850px',
              maxHeight: '85vh',
              overflowY: 'auto',
              color: '#f8fafc',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <div>
                <h3 style={{ fontSize: '18px', fontWeight: 600, color: '#38bdf8', marginBottom: '4px' }}>
                  📜 공동인증서 전자서명 및 승인 이력 (Vision-Certi Approvals)
                </h3>
                <p style={{ fontSize: '12px', color: '#94a3b8' }}>
                  검사 번호: #{currentStudyIdx} | 환자: {studyData?.patient_name} ({studyData?.patient_id})
                </p>
              </div>
              <button
                type="button"
                onClick={() => setShowApprovalsModal(false)}
                style={{
                  background: 'none',
                  border: 'none',
                  color: '#94a3b8',
                  fontSize: '20px',
                  cursor: 'pointer',
                }}
              >
                ✕
              </button>
            </div>

            {vcApprovals.length === 0 ? (
              <div style={{ padding: '36px', textAlign: 'center', color: '#64748b' }}>
                기록된 공동인증서 전자서명 승인 이력이 없습니다.
              </div>
            ) : (
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
                <thead>
                  <tr style={{ borderBottom: '1px solid #334155', color: '#94a3b8', textAlign: 'left' }}>
                    <th style={{ padding: '8px' }}>개정 차수</th>
                    <th style={{ padding: '8px' }}>서명 상태</th>
                    <th style={{ padding: '8px' }}>문서 해시 (SHA-256)</th>
                    <th style={{ padding: '8px' }}>서명의사 식별자</th>
                    <th style={{ padding: '8px' }}>등록/서명일시</th>
                    <th style={{ padding: '8px', textAlign: 'right' }}>작업</th>
                  </tr>
                </thead>
                <tbody>
                  {vcApprovals.map((appr) => {
                    const stateColor =
                      appr.state === 'APPROVED'
                        ? '#22c55e'
                        : (appr.state === 'PENDING_SIGNATURE' || appr.state === 'PREPARING')
                        ? '#eab308'
                        : (appr.state === 'REMOTE_FAILED' || appr.state === 'COMMIT_REFUSED')
                        ? '#ef4444'
                        : '#94a3b8';
                    const hashVal = appr.contentSha256 || appr.snapshotSha256 || '';
                    return (
                      <tr key={appr.approvalId} style={{ borderBottom: '1px solid #1e293b' }}>
                        <td style={{ padding: '8px', fontWeight: 600 }}>Rev #{appr.revision}</td>
                        <td style={{ padding: '8px' }}>
                          <span
                            style={{
                              padding: '2px 8px',
                              borderRadius: '4px',
                              fontSize: '11px',
                              fontWeight: 600,
                              color: stateColor,
                              border: `1px solid ${stateColor}`,
                            }}
                          >
                            {appr.state}
                          </span>
                        </td>
                        <td style={{ padding: '8px', fontFamily: 'monospace', fontSize: '11px', color: '#cbd5e1' }} title={hashVal}>
                          {hashVal ? `${hashVal.substring(0, 16)}...` : '-'}
                        </td>
                        <td style={{ padding: '8px' }}>
                          {appr.vcDoctorId ? `Doctor: ${appr.vcDoctorId}` : (appr.signerUserIdx ? `User #${appr.signerUserIdx}` : '-')}
                        </td>
                        <td style={{ padding: '8px', color: '#94a3b8' }}>
                          {appr.createdAt ? new Date(appr.createdAt).toLocaleString() : '-'}
                        </td>
                        <td style={{ padding: '8px', textAlign: 'right' }}>
                          {appr.state === 'PENDING_SIGNATURE' && appr.visionCertiSignRequestId && (
                            <button
                              type="button"
                              onClick={() => {
                                setShowApprovalsModal(false);
                                startSigningPopup(appr.approvalId, appr.visionCertiSignRequestId);
                              }}
                              style={{
                                padding: '3px 8px',
                                background: '#0284c7',
                                border: 'none',
                                borderRadius: '4px',
                                color: '#fff',
                                cursor: 'pointer',
                                fontSize: '11px',
                              }}
                            >
                              서명 재시도
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}

            <div style={{ marginTop: '20px', display: 'flex', justifyContent: 'flex-end' }}>
              <button
                type="button"
                onClick={() => setShowApprovalsModal(false)}
                style={{
                  padding: '6px 16px',
                  background: '#334155',
                  border: 'none',
                  borderRadius: '4px',
                  color: '#fff',
                  cursor: 'pointer',
                  fontSize: '12px',
                }}
              >
                닫기
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
});

ReportViewer.displayName = 'ReportViewer';

export default ReportViewer;
