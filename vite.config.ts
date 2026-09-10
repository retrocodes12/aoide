import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Two builds from one tree:
//   npm run build         -> dist/        served by GitHub Pages under /aoide/ (the web preview)
//   npm run build:native  -> dist-native/ served by Capacitor from the app root
export default defineConfig(({ command }) => {
  const native = process.env.AOIDE_TARGET === 'native'
  return {
    plugins: [react()],
    base: native ? '/' : command === 'build' ? '/aoide/' : '/',
    build: { target: 'es2022', sourcemap: false, outDir: native ? 'dist-native' : 'dist' },
  }
})
