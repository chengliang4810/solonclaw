/// <reference types="vite/client" />

declare const __APP_VERSION__: string
declare const __SOLONCLAW_DEV_SERVER_URL__: string

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
