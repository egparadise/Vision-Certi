/**
 * Environment & Demo Gate Configuration
 * Default production build disables dev headers and demo role switching.
 */

export const isDemoEnabled = (): boolean => {
  return Boolean(
    import.meta.env.DEV || import.meta.env.VITE_ENABLE_DEMO === 'true'
  );
};
