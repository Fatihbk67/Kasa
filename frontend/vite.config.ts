import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  test: {
    // The dictionary test lists source files with import.meta.glob; a cached transform would miss files added since.
    fsModuleCache: false,
  },
  server: {
    port: 5173,
    // The browser sees one origin, so the backend needs no CORS rules and its cookies-free API stays simple.
    proxy: { '/api': 'http://localhost:8080' },
  },
});
