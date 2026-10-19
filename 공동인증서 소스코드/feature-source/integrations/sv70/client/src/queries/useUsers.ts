import {useTranslation} from "react-i18next";
import {
  useMutation, UseMutationOptions,
  useQuery,
  useQueryClient,
  UseQueryOptions
} from "@tanstack/react-query";
import UserService from "@services/api/UserService";
import {CustomConfirm} from "@libs/CustomConfirm";
import {Toast} from "@libs/Toast";
import {useNavigate} from "react-router-dom";
import {AxiosError} from "axios";
import {useUserStore} from "@stores/useUserStore";
import {LoginFrom} from "@shared/types/user.types";
import {
  EditUserConfigType,
  UserAllConfigResponseType,
  UserConfigChangeResponseType
} from "@userTypes/ui.types";
import {useSettingStore} from "@stores/useSettingStore";

export interface ErrorInterface {
  code: string;
  detail: string;
  msg: string;
  status: number;
}


export const useUserLogin = () => {
  const {t: user_t} = useTranslation(null, {keyPrefix: "confirm.user"});
  const userActions = useUserStore(state => state.actions);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const mutationResults = useMutation({
    mutationFn: UserService.getUser,
    onSuccess: async (data, variables) => {
      if (data.status === 202) {
        CustomConfirm({
            title: user_t("loginOverWriteTitle"),
            text: user_t("loginOverWrite"),
            icon: "warning",
          },
          (result) => {
            if (result.isConfirmed) {
              mutationResults.mutate({...variables, user_overwrite: true});
            }
          }
        );
      } else if (data.status === 200) {
        navigate("/certificate-enrollment", { replace: true });
        await userActions.login(data);
        await queryClient.invalidateQueries({queryKey: ["setting"]});

        // 시스템 등록은 Layout.tsx에서 토큰이 확실히 설정된 후에 처리
      } else {
        Toast(user_t("loginError"), "error", "top-right", 5000);
      }
    },
    onError: (error: ErrorInterface) => {
      switch (error.code) {
        case "4000009":
        default:
          Toast(user_t("loginUnknownError"), "warning");
          break;
      }
    },
  });
  return {
    ...mutationResults,
    mutate: (params: LoginFrom) => {
      mutationResults.mutate(params);
    },
  };
}

export const useGetConfigAllDataQuery = (
  options?: Omit<
    UseQueryOptions<
      UserAllConfigResponseType,
      AxiosError,
      UserAllConfigResponseType,
      [string]
    >,
    "queryKey" | "queryFn"
  >,
) =>
  useQuery({
    ...options,
    queryKey: ["USER_ALL_CONFIG"],
    queryFn: () => UserService.getAllUserConfig(),
  });

export const useChangeConfigMutation = (
  options?: UseMutationOptions<
    UserConfigChangeResponseType,
    AxiosError,
    EditUserConfigType
  >,
) => {
  const queryClient = useQueryClient();
  const {setWorkList} = useSettingStore((state) => state.actions);
  return useMutation({
    ...options,
    mutationFn: UserService.changeUserConfig,
    onSuccess: async (data) => {
      await queryClient.invalidateQueries({queryKey: ["USER_CONFIG"]});
      if (data.config_data.config_name === "worklistConfig") {
        setWorkList(JSON.parse(data.config_data.config_content));
      }
    },
  });
};
