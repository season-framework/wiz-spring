import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';
import devProxy from './scripts/lib/dev-proxy.cjs';

const { createDevProxy } = devProxy;

export default defineConfig({
  root: 'frontend',
  plugins: [react()],
  build: {
    outDir: path.resolve('target/generated-resources/frontend'),
    emptyOutDir: true,
  },
  server: {
    proxy: createDevProxy(),
  },
});
