import { useEffect, useState, type FormEvent } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { fetchApkInfo, type ApkInfo } from "../lib/api";
import { APK_DOWNLOAD_URL } from "../lib/config";

function formatMb(bytes: number): string {
  return `${(bytes / (1024 * 1024)).toFixed(0)} MB`;
}

export function LoginPage() {
  const { isAuthenticated, isLoggingIn, loginError, login } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [apkInfo, setApkInfo] = useState<ApkInfo | null>(null);

  useEffect(() => {
    void fetchApkInfo().then(setApkInfo);
  }, []);

  if (isAuthenticated) {
    return <Navigate to="/connect" replace />;
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    const success = await login(username, password);
    if (success) {
      navigate("/connect", { replace: true });
    }
  };

  return (
    <div className="centered-page">
      <div className="card card--login">
        <div className="brand">
          <span className="brand__icon">📡</span>
          <h1 className="brand__name">WatchWire</h1>
        </div>
        <p className="muted">Remote motion monitoring for your Android camera.</p>

        <form onSubmit={handleSubmit} className="form">
          <label className="form__field">
            <span>Username</span>
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              required
              autoFocus
            />
          </label>

          <label className="form__field">
            <span>Password</span>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </label>

          {loginError && (
            <div className="form__error" role="alert">
              {loginError}
            </div>
          )}

          <button type="submit" className="btn btn--primary" disabled={isLoggingIn}>
            {isLoggingIn ? "Logging in…" : "Log In"}
          </button>
        </form>

        <div className="divider" />

        {apkInfo?.available === false ? (
          <p className="muted apk-note">
            No Android APK is available on this server yet. Build one with
            {" "}<code>./gradlew :app:assembleRelease</code>{" "}
            and put it in the repository's <code>dist/</code> folder.
          </p>
        ) : (
          <>
            <a className="btn btn--secondary" href={APK_DOWNLOAD_URL}>
              ⬇ Download Android APK
              {apkInfo?.size_bytes ? ` (${formatMb(apkInfo.size_bytes)})` : ""}
            </a>
            <p className="muted apk-note">
              Open this page on your Android phone to install the camera app.
            </p>
          </>
        )}
      </div>
    </div>
  );
}
