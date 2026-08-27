import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { useClientSocket } from "../context/ClientSocketContext";

export function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth();
  if (!isAuthenticated) {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}

export function RequirePairing({ children }: { children: ReactNode }) {
  const { pairingStatus } = useClientSocket();

  if (pairingStatus === "restoring") {
    return (
      <div className="centered-page">
        <div className="spinner" aria-label="Reconnecting" />
        <p className="muted">Reconnecting to your last paired camera&hellip;</p>
      </div>
    );
  }

  if (pairingStatus !== "paired") {
    return <Navigate to="/connect" replace />;
  }

  return <>{children}</>;
}
