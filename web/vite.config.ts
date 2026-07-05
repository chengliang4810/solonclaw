import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

const configuredBackendTarget = process.env.SOLONCLAW_SERVER_URL || ''
const backendTarget = configuredBackendTarget || 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [vue()],
  define: {
    __APP_VERSION__: JSON.stringify('0.0.0'),
    __SOLONCLAW_DEV_SERVER_URL__: JSON.stringify(configuredBackendTarget),
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 4000,
    rolldownOptions: {
      checks: {
        invalidAnnotation: false,
        pluginTimings: false,
      },
    },
  },
  optimizeDeps: {
    include: ['monaco-editor'],
  },
  server: {
    proxy: {
      '/api': backendTarget,
      '/health': backendTarget,
      '/upload': backendTarget,
    },
  },
})
