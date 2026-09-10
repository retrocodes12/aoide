import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The web preview of Aoide, served by GitHub Pages under /aoide/ (dev and preview use the same base).
export default defineConfig(() => {
    return {
    plugins: [react()],
    base: '/aoide/',
    build: { target: 'es2022', sourcemap: false },
  }
})
