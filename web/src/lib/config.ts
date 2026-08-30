// Runtime configuration, sourced from Vite env vars (see .env.example).
//
// The defaults deliberately derive from the host the page was served from rather than being
// hardcoded to localhost. That is what lets you open the web app from your phone at
// http://192.168.1.5:5173 and have it talk to the backend on that same machine -- a
// hardcoded "localhost" would resolve to the *phone* and silently fail. Set the env vars
// explicitly when the backend lives somewhere other than the web app's host.

const BACKEND_PORT = "8000";

function inferredHost(): string {
  if (typeof window === "undefined") return "localhost";
  return window.location.hostname || "localhost";
}

/** https when the page is https, so a TLS-served app never makes mixed-content calls. */
function inferredHttpBase(): string {
  const secure = typeof window !== "undefined" && window.location.protocol === "https:";
  return `${secure ? "https" : "http"}://${inferredHost()}:${BACKEND_PORT}`;
}

function inferredWsBase(): string {
  const secure = typeof window !== "undefined" && window.location.protocol === "https:";
  return `${secure ? "wss" : "ws"}://${inferredHost()}:${BACKEND_PORT}`;
}

export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? inferredHttpBase();
export const WS_BASE_URL: string = import.meta.env.VITE_WS_BASE_URL ?? inferredWsBase();

// Where the "Download Android APK" button points. Defaults to the backend's own APK
// endpoint, so a phone that can reach the backend can install the app straight from the
// login page. Override to serve the APK from a CDN or a GitHub Release instead.
export const APK_DOWNLOAD_URL: string = import.meta.env.VITE_APK_URL ?? `${API_BASE_URL}/download/apk`;
