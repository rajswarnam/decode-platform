import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Proxy ingestion API requests to ingestion-engine
      '/api/v1/ingestion': {
        target: 'http://localhost:8083',
        changeOrigin: true,
        secure: false,
      },
      // Proxy context orchestrator API requests
      '/api/v1/explore': {
        target: 'http://localhost:8082',
        changeOrigin: true,
        secure: false,
      },
    },
  },
})
