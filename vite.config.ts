import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// GitHub Pages serves the site from /sleeve/. `npm run dev` stays at /.
export default defineConfig(({ command }) => ({
  plugins: [react()],
  base: command === 'build' ? '/aoide/' : '/',
  build: {
    target: 'es2022',
    sourcemap: false,
  },
}))
