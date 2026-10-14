import {ReactComponent as Logo} from "@assets/img/logo/saintview_logo.svg";
import {useTranslation} from "react-i18next";
import {useForm} from "react-hook-form";
import {useEffect, useState} from 'react';
import {encryptLocalStorage} from "@libs/encryptStorage";
import { useNavigate } from "react-router-dom";
import {useUserStore} from "@stores/useUserStore";
import {useUserLogin} from "@queries/useUsers";
import {useSettingStore} from "@stores/useSettingStore";
import {measureAsync} from "@libs/PerformanceLogger";
import ReleaseNotesModal from "@components/modal/ReleaseNotesModal";
import { VisionCertiService } from "@services/api/VisionCertiService";
import { requestCertificateHandoff } from "@services/api/certificateHandoff";
import { VcConfig } from "@shared/types/visionCerti.types";

export default function LoginForm() {
  const {t} = useTranslation();
  const [remember, setRemember] = useState(false);
  const [showReleaseNotes, setShowReleaseNotes] = useState(false);
  const userActions = useUserStore(state => state.actions);
  const userAuthProfile = useUserStore(state => state.userAuthProfile);
  const userData = useUserStore(state => state.userData);
  const userToken = useUserStore(state => state.token);
  const {appVersion, actions: {
    setInitialConfig,
  }} = useSettingStore()
  const {register, handleSubmit, getValues, reset, setValue} = useForm({
    mode: "onChange",
    defaultValues: {
      user_id: userAuthProfile?.user_id || '',
      user_passwd: userAuthProfile?.user_passwd || '',
      user_overwrite: true,
    },
  });
  // 로그인 페이지는 ConfigProvider 밖이므로 직접 IPC로 remember info 로드
  useEffect(() => {
    (async () => {
      console.log('[LoginForm] IPC 로드 시작');
      const [appVersion, rememberInfo] = await Promise.all([
        measureAsync('window.api.getAppVersion', () => window.api.getAppVersion()),
        window.api.getUserRemember().catch((err: any) => { console.error('[LoginForm] getUserRemember 실패:', err); return null; }),
      ]);
      console.log('[LoginForm] IPC 로드 완료:', { appVersion, rememberInfo: rememberInfo ? { checked: rememberInfo.checked, user_id: rememberInfo.user_id, hasPasswd: !!rememberInfo.user_passwd } : null });
      setInitialConfig({ appVersion });
      if (rememberInfo) {
        console.log('[LoginForm] userRemember 호출:', { checked: rememberInfo.checked, user_id: rememberInfo.user_id, passwd_length: rememberInfo.user_passwd?.length });
        userActions.userRemember(rememberInfo);
      }
    })();
  }, []);
  // userAuthProfile이 IPC에서 비동기 로드된 후 폼에 반영
  useEffect(() => {
    console.log('[LoginForm] userAuthProfile 변경:', { checked: userAuthProfile?.checked, user_id: userAuthProfile?.user_id, hasPasswd: !!userAuthProfile?.user_passwd, passwd_length: userAuthProfile?.user_passwd?.length });
    setRemember(userAuthProfile?.checked ?? false);
    if (userAuthProfile?.user_id || userAuthProfile?.user_passwd) {
      const uid = userAuthProfile.user_id || '';
      const pwd = userAuthProfile.user_passwd || '';
      console.log('[LoginForm] setValue 호출:', { user_id: uid, passwd_length: pwd.length });
      // reset()은 uncontrolled input에서 DOM 반영이 불안정할 수 있으므로 setValue로 직접 설정
      setValue('user_id', uid, { shouldDirty: false });
      setValue('user_passwd', pwd, { shouldDirty: false });
    }
  }, [userAuthProfile, reset]);

  const rememberChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const checked = e.currentTarget.checked;
    setRemember(checked);
    if (!checked) {
      encryptLocalStorage.removeItem("rememberedUser");
    }
  };

  const navigate = useNavigate()
  useEffect(() => {
    // 이미 로그인된 경우
    if (userData && userToken) {
      navigate("/", { replace: true }); // 메인으로 리다이렉트
    }
  }, [userData, userToken]);

  const { mutate: userLogin } = useUserLogin();

  const [vcConfig, setVcConfig] = useState<VcConfig | null>(null);
  const [vcError, setVcError] = useState<string | null>(null);
  const [vcLoading, setVcLoading] = useState(false);

  useEffect(() => {
    let active = true;
    VisionCertiService.getConfig()
      .then((cfg) => {
        if (active) setVcConfig(cfg);
      })
      .catch((err) => {
        console.warn('[LoginForm] Failed to load VC config:', err);
      });
    return () => {
      active = false;
    };
  }, []);

  const handleCertificateLogin = async () => {
    if (!vcConfig || vcLoading) return;
    setVcError(null);
    setVcLoading(true);
    try {
      const code = await requestCertificateHandoff(vcConfig);
      const loginRes = await VisionCertiService.certificateLogin(code);
      await userActions.loginWithCertificate(loginRes);
      navigate('/', { replace: true });
    } catch (err: unknown) {
      setVcError(err instanceof Error ? err.message : '공동인증서 로그인에 실패했습니다.');
    } finally {
      setVcLoading(false);
    }
  };

  const onSubmit = handleSubmit((data)=>{
    if (remember) {
      const rememberedUser = {
        userId: data.user_id,
        userPasswd: data.user_passwd,
        timestamp: new Date().getTime(),
      };
      encryptLocalStorage.setItem(
        "rememberedUser",
        JSON.stringify(rememberedUser)
      );
    } else {
      encryptLocalStorage.removeItem("rememberedUser");
    }
    const rememberInfo = {
      checked: remember,
      user_id: remember ? getValues().user_id : "",
      user_passwd: remember ? getValues().user_passwd : "",
    };
    // 로그인 정보 electron store 저장
    userActions.userRemember(rememberInfo);
    userLogin(data);
  },() => {
  });

  return(
    <div id="login_form">
      <form className="login_box" onSubmit={onSubmit}>
        <div>
          <Logo/>
        </div>
        <h1>{t("login.title")}</h1>
        <input
          {...register("user_id", {
            required: t("login.userIdRequired"),
            minLength: {value: 3, message: t("login.userIdLength")},
          })}
          placeholder={t("login.userIdPlaceholder")}
        />
        <input
          {...register("user_passwd", {
            required: t("login.userPasswdPlaceholder"),
          })}
          type="password"
          placeholder={t("login.userPasswdPlaceholder")}
        />
        <input
          type="checkbox"
          id="rmb"
          name="rmb"
          onChange={rememberChange}
          checked={remember}
        />
        <label htmlFor="rmb">Remember</label>
        <button className="btn_login">{t("login.submitButton")}</button>

        {vcConfig?.enabled && (
          <div style={{ marginTop: '16px', width: '100%' }}>
            <div style={{ display: 'flex', alignItems: 'center', margin: '12px 0' }}>
              <div style={{ flex: 1, height: '1px', background: 'rgba(255,255,255,0.2)' }} />
              <span style={{ margin: '0 10px', fontSize: '12px', color: '#888' }}>또는</span>
              <div style={{ flex: 1, height: '1px', background: 'rgba(255,255,255,0.2)' }} />
            </div>
            <button
              type="button"
              className="btn_login"
              data-testid="pacs-certificate-login-btn"
              onClick={handleCertificateLogin}
              disabled={vcLoading}
              style={{
                backgroundColor: '#1e3a5f',
                border: '1px solid #3b82f6',
                color: '#93c5fd',
                cursor: vcLoading ? 'not-allowed' : 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '8px',
                marginTop: 0,
              }}
            >
              {vcLoading ? '인증 진행 중...' : '🛡️ 공동인증서 로그인 (Vision-Certi)'}
            </button>
            {vcError && (
              <div
                data-testid="pacs-certificate-login-error"
                style={{ marginTop: '8px', color: '#f87171', fontSize: '12px', textAlign: 'center' }}
              >
                {vcError}
              </div>
            )}
          </div>
        )}
        <div
          style={{
            marginTop: 20,
            width: "100%",
            textAlign: "center",
          }}
        >
          <div style={{ marginBottom: 8 }}>
            Version: {appVersion.oldVersion}
          </div>
          <button
            type="button"
            onClick={() => setShowReleaseNotes(true)}
            style={{
              background: 'transparent',
              border: '1px solid rgba(255,255,255,0.3)',
              color: '#ccc',
              padding: '6px 16px',
              borderRadius: '4px',
              cursor: 'pointer',
              fontSize: '12px',
              transition: 'all 0.2s',
            }}
            onMouseOver={(e) => {
              e.currentTarget.style.borderColor = '#9164b8';
              e.currentTarget.style.color = '#fff';
            }}
            onMouseOut={(e) => {
              e.currentTarget.style.borderColor = 'rgba(255,255,255,0.3)';
              e.currentTarget.style.color = '#ccc';
            }}
          >
            릴리즈 노트
          </button>
        </div>
      </form>
      {showReleaseNotes && (
        <ReleaseNotesModal onClose={() => setShowReleaseNotes(false)} />
      )}
    </div>
  )
}
