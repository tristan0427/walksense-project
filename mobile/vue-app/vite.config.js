import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path';

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  optimizeDeps: {
    exclude: [
      '@capacitor-community/background-geolocation',
      '@capacitor/core',
      '@capacitor/geolocation'
    ]
  },
  ssr: {
    external: ['@capacitor-community/background-geolocation']
  },
  build: {
    rollupOptions: {
      external: ['@capacitor-community/background-geolocation'],
      output: {
        globals: {
          '@capacitor-community/background-geolocation': 'BackgroundGeolocation'
        }
      }
    }
  }
})
