import { useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { EventHistoryList } from "../components/EventHistoryList";
import { MotionAlertBanner } from "../components/MotionAlertBanner";
import { StatusBadge } from "../components/StatusBadge";
import { useAuth } from "../context/AuthContext";
import { useClientSocket } from "../context/ClientSocketContext";
import { useAudioAlert } from "../hooks/useAudioAlert";
import { useNotifications } from "../hooks/useNotifications";

export function MonitoringPage() {
  const { logout } = useAuth();
  const { socketStatus, cameraConnected, monitoring, events, lastEvent, forgetPairing } = useClientSocket();
  const { playAlert, unlock, isUnlocked } = useAudioAlert();
  const { permission, requestPermission, notify } = useNotifications();
  const navigate = useNavigate();

  const lastSeenEventId = useRef<string | null>(null);

  useEffect(() => {
    if (permission === "default") {
      void requestPermission();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!lastEvent) return;
    if (lastSeenEventId.current === lastEvent.id) return;
    lastSeenEventId.current = lastEvent.id;

    playAlert();
    notify("WatchWire: Motion Detected", `Confidence ${(lastEvent.score * 100).toFixed(0)}% at ${new Date(lastEvent.timestamp).toLocaleTimeString()}`);
  }, [lastEvent, playAlert, notify]);

  const handleReconnectDifferentCamera = () => {
    forgetPairing();
    navigate("/connect", { replace: true });
  };

  return (
    <div className="monitoring-page">
      <header className="monitoring-header">
        <div className="brand brand--small">
          <span className="brand__icon">📡</span>
          <h1 className="brand__name">WatchWire</h1>
        </div>
        <button type="button" className="btn btn--link" onClick={logout}>
          Log out
        </button>
      </header>

      {socketStatus === "reconnecting" && (
        <div className="banner banner--warning">Reconnecting to WatchWire server&hellip;</div>
      )}

      {!isUnlocked && (
        <div className="banner banner--info">
          <span>Enable sound alerts so you'll hear motion events even while looking away.</span>
          <button type="button" className="btn btn--small" onClick={unlock}>
            🔊 Enable Sound
          </button>
        </div>
      )}

      {permission === "denied" && (
        <div className="banner banner--info">
          Browser notifications are blocked. In-page alerts will still work while this tab is open.
        </div>
      )}

      <MotionAlertBanner event={lastEvent} />

      <div className="status-row">
        <StatusBadge label="Camera" active={cameraConnected} activeText="Connected" inactiveText="Disconnected" />
        <StatusBadge label="Monitoring" active={monitoring} activeText="LIVE" inactiveText="Idle" pulse />
      </div>

      <section className="card card--history">
        <h2>Event History</h2>
        <EventHistoryList events={events} />
      </section>

      <button type="button" className="btn btn--secondary" onClick={handleReconnectDifferentCamera}>
        Pair a Different Camera
      </button>
    </div>
  );
}
