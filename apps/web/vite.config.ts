/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { readFileSync } from 'node:fs';

// LAN mode is opt-in; backend and key-management ports remain on loopback.
const lanHost = process.env.VISIONCERTI_LAN_HOST;
const lanTls = lanHost ? {
  cert: readFileSync(process.env.VISIONCERTI_LAN_CERT_FILE!),
  key: readFileSync(process.env.VISIONCERTI_LAN_KEY_FILE!),
} : undefined;

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  publicDir: lanHost ? process.env.VISIONCERTI_LAN_PUBLIC_DIR : 'public',
  server: {
    host: lanHost || '127.0.0.1',
    port: lanHost ? 5184 : 5183,
    strictPort: true,
    https: lanTls,
    proxy: {
      '/v1': {
        target: lanHost ? 'http://127.0.0.1:18082' : 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    fileParallelism: false,
  },
});
