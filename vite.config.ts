import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Two builds from one tree:
//   npm run build         -> dist/        served by GitHub Pages under /aoide/ (dev and preview use the same base)
//   npm run build:native  -> dist-native/ served by Capacitor from the app root
export default defineConfig(() => {
  const native = process.env.AOIDE_TARGET === 'native'
  return {
    plugins: [react()],
    base: native ? '/' : '/aoide/',
    build: { target: 'es2022', sourcemap: false, outDir: native ? 'dist-native' : 'dist' },
  }
})
