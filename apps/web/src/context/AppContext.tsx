import React, { createContext, useContext, useState, useMemo, useCallback, useEffect } from 'react';
import { ApiClient } from '../api/client';
import { DevActorId, DevRole, DevTenantId, RuntimeCapabilities, StaffMeResponse } from '../types/api';
import { createInitialMockStore, MockStore } from '../mock/mockData';

export type NavTab =
  | 'dashboard'
  | 'review'
  | 'documents'
  | 'signatures'
  | 'deliveries'
  | 'audit'
  | 'certificates';

interface AppContextType {
  tenantId: DevTenantId;
  setTenantId: (tenantId: DevTenantId) => void;
  actorId: DevActorId;
  setActorId: (actorId: DevActorId) => void;
  role: DevRole;
  isMockMode: boolean;
  setIsMockMode: (mock: boolean) => void;
  client: ApiClient;
  activeTab: NavTab;
  setActiveTab: (tab: NavTab) => void;
  selectedDocumentId: string | null;
  setSelectedDocumentId: (id: string | null) => void;
  mockStore: MockStore;
  setMockStore: React.Dispatch<React.SetStateAction<MockStore>>;
  resetTenantState: () => void;
  staffToken: string | null;
  setStaffToken: (token: string | null) => void;
  localAdminToken: string | null;
  setLocalAdminToken: (token: string | null) => void;
  staffProfile: StaffMeResponse | null;
  setStaffProfile: (profile: StaffMeResponse | null) => void;
  runtimeCapabilities: RuntimeCapabilities | null;
  setRuntimeCapabilities: (caps: RuntimeCapabilities | null) => void;
  capabilitiesLoading: boolean;
  capabilitiesError: string | null;
}

const AppContext = createContext<AppContextType | null>(null);

export const AppProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [tenantId, setTenantIdState] = useState<DevTenantId>('demo-hospital-a');
  const [actorId, setActorIdState] = useState<DevActorId>('doctor-demo');
  const [isMockMode, setIsMockMode] = useState<boolean>(false);
  const [activeTab, setActiveTab] = useState<NavTab>('dashboard');
  const [selectedDocumentId, setSelectedDocumentId] = useState<string | null>(null);
  const [mockStore, setMockStore] = useState<MockStore>(createInitialMockStore);
  
  // Production Staff OIDC Token (In-memory only, never persisted)
  const [staffToken, setStaffTokenState] = useState<string | null>(null);
  const [localAdminToken, setLocalAdminToken] = useState<string | null>(null);
  const [staffProfile, setStaffProfile] = useState<StaffMeResponse | null>(null);

  // Runtime Capabilities
  const [runtimeCapabilities, setRuntimeCapabilities] = useState<RuntimeCapabilities | null>(null);
  const [capabilitiesLoading, setCapabilitiesLoading] = useState<boolean>(false);
  const [capabilitiesError, setCapabilitiesError] = useState<string | null>(null);

  const role: DevRole = staffProfile
    ? (staffProfile.role as DevRole)
    : (actorId === 'doctor-demo' ? 'DOCTOR' : 'ADMIN');

  const client = useMemo(() => {
    return new ApiClient({
      tenantId,
      actorId,
      staffToken,
      localAdminToken,
    });
  }, [tenantId, actorId, staffToken, localAdminToken]);

  const setStaffToken = useCallback((token: string | null) => {
    setStaffTokenState(token);
    client.setStaffToken(token);
  }, [client]);

  // Load capabilities on mount or live mode switch
  useEffect(() => {
    if (isMockMode) {
      setRuntimeCapabilities(null);
      setCapabilitiesError(null);
      return;
    }

    let active = true;
    setCapabilitiesLoading(true);
    setCapabilitiesError(null);

    client
      .getRuntimeCapabilities()
      .then((caps) => {
        if (!active) return;
        setRuntimeCapabilities(caps);
      })
      .catch((err: unknown) => {
        if (!active) return;
        const msg = err instanceof Error ? err.message : '런타임 기능을 조회할 수 없습니다.';
        setCapabilitiesError(msg);
        setRuntimeCapabilities(null);
      })
      .finally(() => {
        if (active) {
          setCapabilitiesLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [client, isMockMode]);

  const resetTenantState = useCallback(() => {
    setSelectedDocumentId(null);
  }, []);

  const setTenantId = useCallback((newTenantId: DevTenantId) => {
    setTenantIdState(newTenantId);
    setSelectedDocumentId(null);
  }, []);

  const setActorId = useCallback((newActorId: DevActorId) => {
    setActorIdState(newActorId);
  }, []);

  const value = {
    tenantId,
    setTenantId,
    actorId,
    setActorId,
    role,
    isMockMode,
    setIsMockMode,
    client,
    activeTab,
    setActiveTab,
    selectedDocumentId,
    setSelectedDocumentId,
    mockStore,
    setMockStore,
    resetTenantState,
    staffToken,
    setStaffToken,
    localAdminToken,
    setLocalAdminToken,
    staffProfile,
    setStaffProfile,
    runtimeCapabilities,
    setRuntimeCapabilities,
    capabilitiesLoading,
    capabilitiesError,
  };

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
};

export function useApp(): AppContextType {
  const context = useContext(AppContext);
  if (!context) {
    throw new Error('useApp must be used within an AppProvider');
  }
  return context;
}
