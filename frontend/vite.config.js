import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:8080'
    }
  },
  build: {
    rollupOptions: {
      output: {
        // Split the heavy, rarely-changing dependencies into their own chunks so
        // they cache independently of the app code and of each other. Paths are
        // normalised to forward slashes first: Rollup ids are posix-style, but
        // this config also runs on Windows.
        manualChunks(id) {
          const path = id.replace(/\\/g, '/')
          if (!path.includes('/node_modules/')) return
          if (path.includes('/highlight.js/')) return 'highlight'
          if (path.includes('/marked/') || path.includes('/dompurify/')) return 'markdown'
          if (path.includes('/vue/') || path.includes('/vue-router/') || path.includes('/@vue/')) return 'vendor'
        }
      }
    }
  }
})
