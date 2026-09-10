import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'app.aoide',
  appName: 'Aoide',
  webDir: 'dist-native',
  server: { androidScheme: 'https' },
  android: { allowMixedContent: false, backgroundColor: '#121212' },
  plugins: {
    SplashScreen: { launchShowDuration: 0, backgroundColor: '#121212', androidSplashResourceName: 'splash' },
  },
}

export default config
