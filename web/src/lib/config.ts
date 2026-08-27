// Runtime configuration, sourced from Vite env vars (see .env.example) with sensible
// local-development fallbacks so the app runs out of the box with `npm run dev` + the
// backend on its default port.

export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8000";
export const WS_BASE_URL: string = import.meta.env.VITE_WS_BASE_URL ?? "ws://localhost:8000";

// Where the "Download Android APK" button points. Configurable so a real deployment can
// point this at a release asset (e.g. a GitHub Releases URL or a static file on the
// backend/CDN) without a code change.
export const APK_DOWNLOAD_URL: string = import.meta.env.VITE_APK_URL ?? "/watchwire.apk";
