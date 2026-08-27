import { useState, type FormEvent } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { useClientSocket } from "../context/ClientSocketContext";

export function ConnectPage() {
  const { logout } = useAuth();
  const { pairingStatus, pairError, socketStatus, pair } = useClientSocket();
  const navigate = useNavigate();
  const [code, setCode] = useState("");
  const [submitting, setSubmitting] = useState(false);

  if (pairingStatus === "paired") {
    return <Navigate to="/monitor" replace />;
  }

  if (pairingStatus === "restoring") {
    return (
      <div className="centered-page">
        <div className="spinner" aria-label="Reconnecting" />
        <p className="muted">Reconnecting to your last paired camera&hellip;</p>
      </div>
    );
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    const normalized = code.trim().toUpperCase();
    if (!normalized) return;

    setSubmitting(true);
    try {
      await pair(normalized);
      navigate("/monitor", { replace: true });
    } catch {
      // pairError from context already reflects the failure reason.
    } finally {
      setSubmitting(false);
    }
  };

  const isBusy = submitting || pairingStatus === "pairing";

  return (
    <div className="centered-page">
      <div className="card">
        <div className="brand brand--small">
          <span className="brand__icon">📡</span>
          <h1 className="brand__name">WatchWire</h1>
        </div>

        <h2>Connect to a Camera</h2>
        <p className="muted">
          Open the WatchWire app on your Android device and enter the code it displays below.
        </p>

        <form onSubmit={handleSubmit} className="form">
          <label className="form__field">
            <span>Client Code</span>
            <input
              type="text"
              value={code}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
              placeholder="e.g. 7K9F2Q"
              maxLength={12}
              autoCapitalize="characters"
              autoComplete="off"
              className="input--code"
              autoFocus
              required
            />
          </label>

          {pairError && pairingStatus === "error" && (
            <div className="form__error" role="alert">
              {pairError}
            </div>
          )}

          <button type="submit" className="btn btn--primary" disabled={isBusy}>
            {isBusy ? "Connecting…" : "Connect"}
          </button>
        </form>

        <p className="connection-hint">
          Server:{" "}
          <span className={`inline-status inline-status--${socketStatus}`}>
            {socketStatus === "connected" && "Connected"}
            {socketStatus === "connecting" && "Connecting…"}
            {socketStatus === "reconnecting" && "Reconnecting…"}
            {socketStatus === "disconnected" && "Disconnected"}
          </span>
        </p>

        <button type="button" className="btn btn--link" onClick={logout}>
          Log out
        </button>
      </div>
    </div>
  );
}
