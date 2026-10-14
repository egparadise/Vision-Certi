import { useTranslation } from "react-i18next";
import { RendererLogger } from '@libs/RendererLogger';

const logger = new RendererLogger('GlobalSetting');
import {
  lazy,
  LazyExoticComponent,
  Suspense,
  useCallback,
  useEffect,
  useMemo,
  useState
} from 'react';
import { useSearchParams } from 'react-router-dom';
import classNames from "classnames";
import Spinner from "@components/loading/Spinner";
import "@assets/css/setting/globalSetting.scss";
import {VscChromeClose} from "react-icons/vsc";
import {useWindowActivity} from "@hooks/useWindowActivity";
import {useSettingStore} from "@stores/useSettingStore";
import {useUserStore} from "@stores/useUserStore";

interface TabConfig {
  key: string;
  title: string;
  component?: LazyExoticComponent<any>;
  complete?: boolean;
}

const TABS = {
  BASIC: {
    key: "basic",
  },
  BASIC_LAYOUT:{
    key: "basicLayoutManager",
  },
  WWL_LIST:{
    key: "wwlManager",
  },
  WORK_LIST:{
    key: "workList",
  },
  HANGING:{
    key: "hanging",
  },
  DISPLAY: {
    key: "display",
  },
  REPORT: {
    key: "report",
  },
  VIEWER: {
    key: "viewer",
  },
  ADMIN: {
    key: "admin",
  },
  REPORT_PERMISSION: {
    key: "reportPermission",
  },
  VC_DOCTOR_LINKS: {
    key: "vcDoctorLinks",
  }
} as const;

const RAIL_GROUPS: { id: string; label: string; tabKeys: string[] }[] = [
  { id: 'env',    label: '환경설정', tabKeys: ['basic', 'workList', 'viewer', 'report', 'display'] },
  { id: 'preset', label: '프리셋',   tabKeys: ['basicLayoutManager', 'wwlManager', 'hanging'] },
  { id: 'system', label: '시스템',   tabKeys: ['admin', 'reportPermission', 'vcDoctorLinks'] },
];

// 관리자 전용 탭 — 관리자 그룹(group_idx==1) 또는 관리자급(group_level>=98)에게만 노출.
// (서버 엔드포인트도 동일 조건 + 슈퍼관리자로 게이트하므로 노출은 편의, 차단은 서버가 보장)
const ADMIN_ONLY_TAB_KEYS = new Set<string>(['reportPermission', 'vcDoctorLinks']);


export default function GlobalSetting() {
  const { t } = useTranslation("globalSetting", { keyPrefix: "tabText" });
  const {t: globalSettingT} = useTranslation('globalSetting', {
    keyPrefix: 'setting',
  });
  const configPageFontSize = useSettingStore(state => state.basic?.configPageFontSize) || 12;

  useWindowActivity({
    onAutoLogout: useCallback(() => {
      try {
        // 윈도우 닫기
        if (window.electron?.ipcRenderer) {
          window.electron.ipcRenderer.send('win:invoke', 'close');
        } else {
          window.close();
        }
      } catch (error) {
        logger.error('Error during viewer logout cleanup:', error);
        window.close();
      }
    }, []),
    enableLocalDetection: true,
    onWarning: useCallback(() => {
    }, [])
  });

  const [selectedTab, setSelectedTab] = useState<string>(TABS.BASIC.key);

  const tabConfigs = useMemo<TabConfig[]>(
    () => [
      {
        key: TABS.BASIC.key,
        title: t("basic"),
        component: lazy(() => import("@components/setting/basic/General")),
        complete: true,
      },
      {
        key: TABS.BASIC_LAYOUT.key,
        title: 'basicLayout',
        component: lazy(() => import("@components/setting/basicLayout/BasicLayoutManager")),
        complete: true,
      },
      {
        key: TABS.WWL_LIST.key,
        title: 'wwlManager',
        component: lazy(() => import("@components/setting/wwl/WwlPresetManager")),
        complete: true,
      },
      {
        key: TABS.WORK_LIST.key,
        title: t("workList"),
        component: lazy(() => import("@components/workList/setting/WorkListConfig")),
        complete: true,
      },
      {
        key: TABS.REPORT.key,
        title: 'Report',
        component: lazy(()=> import('@components/report/setting/ReportSetting')),
        complete: true,
      },
      {
        key: TABS.VIEWER.key,
        title: 'Viewer',
        component: lazy(() => import("@components/viewer/setting/ViewerSetting")),
        complete: true,
      },
      {
        key: TABS.HANGING.key,
        title: 'Hanging Protocol',
        component: lazy(() => import("@components/setting/hanging/index")),
        complete: true,
      },
      {
        key: TABS.DISPLAY.key,
        title: globalSettingT('display'),
        component: lazy(() => import("@components/setting/monitor")),
        complete: true,
      },
      {
        key: TABS.ADMIN.key,
        title: globalSettingT('admin'),
        component: lazy(() => import("@components/setting/admin")),
        complete: true,
      },
      {
        key: TABS.REPORT_PERMISSION.key,
        title: globalSettingT('reportPermission'),
        component: lazy(() => import("@components/setting/reportPermission")),
        complete: true,
      },
      {
        key: TABS.VC_DOCTOR_LINKS.key,
        title: '의사 계정 연동 (Vision-Certi)',
        component: lazy(() => import("@components/setting/admin/VcDoctorLinks")),
        complete: true,
      },
    ],
    [t, globalSettingT],
  );

  // 관리자 전용 탭 노출 판정 (판독 권한 관리 등)
  const userData = useUserStore(state => state.userData);
  const isPermissionAdmin = userData?.group_idx === 1 || (userData?.group_level ?? 0) >= 98;

  const [searchParams] = useSearchParams();

  // win:open-setting 의 tab 파라미터(#/setting?tab=<key>)로 초기/변경 시 탭 선택.
  // HashRouter 가 해시 뒤 쿼리를 search 로 파싱하므로 hashchange 에도 반응.
  useEffect(() => {
    const requested = searchParams.get("tab");
    const valid = tabConfigs.some((tc) => tc.key === requested);
    // 관리자 전용 탭은 비관리자의 직접 접근(tab 파라미터)도 차단
    if (requested && valid && !(ADMIN_ONLY_TAB_KEYS.has(requested) && !isPermissionAdmin)) {
      setSelectedTab(requested);
    }
    // 파라미터 없거나 무효면 기존 선택 유지(초기값 BASIC).
    // deps 에 tabConfigs 제외: param 이 바뀔 때만 적용해, 언어 변경 등 재렌더가 사용자의 수동 탭 선택을 되돌리지 않게.
  }, [searchParams]);

  const ActiveComponent = useMemo(
    () => tabConfigs.find((t) => t.key === selectedTab)?.component,
    [selectedTab, tabConfigs],
  );

  return (
      <div
        className="global_config"
        style={{ '--config-font-size': `${configPageFontSize}px` } as React.CSSProperties}
      >
        <div className="modal_header" data-tauri-drag-region>
          <p data-tauri-drag-region>{globalSettingT('title')}</p>
          <div className="header_button">
            <button onClick={async (e)=>{
              e.preventDefault();
              window.electron.ipcRenderer.send('win:invoke', 'close')
            }} ><VscChromeClose /></button>
          </div>
        </div>
        <div className="config_wrap">
          <nav className="settings_rail">
            {RAIL_GROUPS.map((g) => (
              <div className="rail_group" key={g.id}>
                <div className="rail_group_label">{g.label}</div>
                {g.tabKeys.map((key) => {
                  if (ADMIN_ONLY_TAB_KEYS.has(key) && !isPermissionAdmin) return null;
                  const tc = tabConfigs.find((t) => t.key === key);
                  if (!tc) return null;
                  return (
                    <button
                      key={key}
                      className={classNames('rail_item', { active: selectedTab === key })}
                      onClick={() => setSelectedTab(key)}
                    >
                      {tc.title}
                    </button>
                  );
                })}
              </div>
            ))}
          </nav>

          <div className="setting_pane">
            <Suspense fallback={<Spinner />}>
              {ActiveComponent && <ActiveComponent />}
            </Suspense>
          </div>
        </div>
      </div>
  );
}
