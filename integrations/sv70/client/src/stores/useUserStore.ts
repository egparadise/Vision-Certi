import {createStore} from "@stores/factories";
import {UserState, UserStore} from "@shared/types/user.types";
import {setAuthorizationHeader} from "@libs/axios";


const initialUserState: UserState = {
  userData: null,
  token: null,
  refreshToken: null,
  userAuthProfile: {
    user_id: '', user_passwd: '', checked: false,
  },
  licenseStatus: null,
  vcSession: null,
}

export const useUserStore = createStore<UserStore>((set)=>({
  ...initialUserState,
  actions: {
    userRemember: (userAuthProfile) => {
      set({userAuthProfile});
      window.electron?.ipcRenderer?.send('user:remember-user', userAuthProfile);
    },
    login: async (userData) => {
      window.electron?.ipcRenderer?.send('user:set-user-info', {
        userInfo: userData.user_data,
        token: userData.token,
        refreshToken: userData.refresh_token
      });
      set({
        userData: userData.user_data,
        token: userData.token,
        refreshToken: userData.refresh_token,
        vcSession: null,
      });
    },
    loginWithCertificate: async (data) => {
      // NOTE: vc1 token is held in memory ONLY. Never persist to legacy encrypt-storage or disk.
      if (window.electron?.ipcRenderer) {
        window.electron.ipcRenderer.send('user:set-user-info', {
          userInfo: data.user_data,
          token: null,
          refreshToken: null,
        });
      }
      set({
        userData: data.user_data as any,
        token: data.token,
        refreshToken: null,
        vcSession: data.vc,
      });
      setAuthorizationHeader(data.token);
    },
    logout: () => {
      const hadVcSession = Boolean(useUserStore.getState().vcSession);
      if (hadVcSession) {
        import('@services/api/VisionCertiService').then(({ VisionCertiService }) => {
          VisionCertiService.logout().catch(() => {});
        }).catch(() => {});
      }
      if (window.electron?.ipcRenderer) {
        window.electron.ipcRenderer.send('user:set-user-info', {
          userInfo: null,
          token: null,
          refreshToken: null,
        });
      }
      set({
        userData: null,
        token: null,
        refreshToken: null,
        vcSession: null,
      });
      setAuthorizationHeader(null);
    },
    setInitialState: userData => {
      // Never allow legacy password session restore over an active certificate login
      if (useUserStore.getState().vcSession) {
        return;
      }
      set(state => ({
        ...state,
        ...userData,
      }))
    },
    refreshAccessToken: (newToken: string) => {
      set({ token: newToken });
      setAuthorizationHeader(newToken);
      const isVc = Boolean(useUserStore.getState().vcSession || newToken?.startsWith('vc1.'));
      if (window.electron?.ipcRenderer) {
        window.electron.ipcRenderer.send('user:set-user-info', {
          userInfo: useUserStore.getState().userData,
          token: isVc ? null : newToken,
          refreshToken: isVc ? null : useUserStore.getState().refreshToken
        });
      }
    },
    setLicenseStatus: (licenseStatus) => {
      set({ licenseStatus });
    }
  }
}))


useUserStore.subscribe(state => state.token, (token)=>{
  setAuthorizationHeader(token || null);
})
