import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { WS_BASE_URL } from "../lib/config";
import { WS_CLOSE_UNAUTHENTICATED } from "../lib/types";
import type { MotionEvent, PairingStatus, ServerToClientMessage, SocketStatus } from "../lib/types";
import { useAuth } from "./AuthContext";

const CLIENT_TOKEN_STORAGE_KEY = "watchwire_client_token";
const MAX_EVENT_HISTORY = 100;
const HEARTBEAT_INTERVAL_MS = 20_000;
const PAIR_RESPONSE_TIMEOUT_MS = 10_000;
const MAX_RECONNECT_DELAY_MS = 10_000;

interface ClientSocketContextValue {
  socketStatus: SocketStatus;
  pairingStatus: PairingStatus;
  pairError: string | null;
  cameraConnected: boolean;
  monitoring: boolean;
  events: MotionEvent[];
  lastEvent: MotionEvent | null;
  pair: (code: string) => Promise<void>;
  forgetPairing: () => void;
}

const ClientSocketContext = createContext<ClientSocketContextValue | undefined>(undefined);

function makeEventId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export function ClientSocketProvider({ children }: { children: ReactNode }) {
  const { token, isAuthenticated, logout } = useAuth();

  const [socketStatus, setSocketStatus] = useState<SocketStatus>("disconnected");
  const [pairingStatus, setPairingStatus] = useState<PairingStatus>("idle");
  const [pairError, setPairError] = useState<string | null>(null);
  const [cameraConnected, setCameraConnected] = useState(false);
  const [monitoring, setMonitoring] = useState(false);
  const [events, setEvents] = useState<MotionEvent[]>([]);

  const wsRef = useRef<WebSocket | null>(null);
  const clientTokenRef = useRef<string | null>(localStorage.getItem(CLIENT_TOKEN_STORAGE_KEY));
  const reconnectAttemptRef = useRef(0);
  const reconnectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const heartbeatIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const manualCloseRef = useRef(false);
  const pendingPairRef = useRef<{ resolve: () => void; reject: (err: Error) => void } | null>(null);
  const pendingPairTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pairInFlightRef = useRef(false);
  /** Set once the server has told us the token is invalid, so the backoff loop gives up
   * instead of reconnecting forever with credentials that can never work. */
  const authFailedRef = useRef(false);

  const setClientToken = useCallback((value: string | null) => {
    clientTokenRef.current = value;
    if (value) {
      localStorage.setItem(CLIENT_TOKEN_STORAGE_KEY, value);
    } else {
      localStorage.removeItem(CLIENT_TOKEN_STORAGE_KEY);
    }
  }, []);

  const clearReconnectTimer = useCallback(() => {
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
  }, []);

  const clearHeartbeat = useCallback(() => {
    if (heartbeatIntervalRef.current) {
      clearInterval(heartbeatIntervalRef.current);
      heartbeatIntervalRef.current = null;
    }
  }, []);

  const settlePendingPair = useCallback((err: Error | null) => {
    if (pendingPairTimeoutRef.current) {
      clearTimeout(pendingPairTimeoutRef.current);
      pendingPairTimeoutRef.current = null;
    }
    const pending = pendingPairRef.current;
    pendingPairRef.current = null;
    if (!pending) return;
    if (err) pending.reject(err);
    else pending.resolve();
  }, []);

  const handleServerMessage = useCallback(
    (message: ServerToClientMessage) => {
      switch (message.type) {
        case "paired": {
          setClientToken(message.client_token);
          setPairingStatus("paired");
          setPairError(null);
          setCameraConnected(message.camera_connected);
          setMonitoring(message.monitoring);
          settlePendingPair(null);
          break;
        }
        case "camera_status": {
          setCameraConnected(message.connected);
          setMonitoring(message.monitoring);
          break;
        }
        case "motion_event": {
          setEvents((prev) => {
            const next: MotionEvent[] = [
              { id: makeEventId(), score: message.score, timestamp: message.timestamp, receivedAt: Date.now() },
              ...prev,
            ];
            return next.slice(0, MAX_EVENT_HISTORY);
          });
          break;
        }
        case "peer_disconnected": {
          setCameraConnected(false);
          setMonitoring(false);
          break;
        }
        case "error": {
          if (message.code === "UNAUTHENTICATED") {
            // The login token is dead (commonly: the backend restarted and regenerated its
            // JWT secret). Retrying can never succeed, so drop it and send the user back to
            // the login page rather than spinning forever.
            authFailedRef.current = true;
            setClientToken(null);
            logout("Your session expired, so you were signed out. Please log in again.");
            break;
          }
          if (message.code === "SESSION_EXPIRED" || message.code === "INVALID_SESSION") {
            setClientToken(null);
            setPairingStatus("idle");
            setCameraConnected(false);
            setMonitoring(false);
          } else if (pendingPairRef.current) {
            setPairingStatus("error");
          }
          setPairError(message.message);
          settlePendingPair(new Error(message.message));
          break;
        }
        case "pong":
          break;
      }
    },
    [setClientToken, settlePendingPair, logout],
  );

  const connectSocket = useCallback((): WebSocket | null => {
    if (!token) return null;
    if (wsRef.current && (wsRef.current.readyState === WebSocket.OPEN || wsRef.current.readyState === WebSocket.CONNECTING)) {
      return wsRef.current;
    }

    manualCloseRef.current = false;
    setSocketStatus((prev) => (prev === "disconnected" ? "connecting" : prev));

    const ws = new WebSocket(`${WS_BASE_URL}/ws/client?token=${encodeURIComponent(token)}`);
    wsRef.current = ws;

    ws.onopen = () => {
      reconnectAttemptRef.current = 0;
      setSocketStatus("connected");

      clearHeartbeat();
      heartbeatIntervalRef.current = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "ping" }));
      }, HEARTBEAT_INTERVAL_MS);

      const savedToken = clientTokenRef.current;
      if (savedToken && !pairInFlightRef.current) {
        setPairingStatus((prev) => (prev === "idle" || prev === "error" ? "restoring" : prev));
        ws.send(JSON.stringify({ type: "resume", session_token: savedToken }));
      }
    };

    ws.onmessage = (event) => {
      try {
        const parsed = JSON.parse(event.data) as ServerToClientMessage;
        handleServerMessage(parsed);
      } catch {
        // Ignore malformed frames rather than crashing the UI.
      }
    };

    ws.onclose = (event) => {
      clearHeartbeat();
      wsRef.current = null;
      setCameraConnected(false);

      if (manualCloseRef.current) {
        setSocketStatus("disconnected");
        return;
      }

      // A rejected token can never succeed on retry -- stop and force a fresh login. The
      // explicit close code covers the case where the error frame didn't arrive first.
      if (event.code === WS_CLOSE_UNAUTHENTICATED || authFailedRef.current) {
        authFailedRef.current = false;
        setSocketStatus("disconnected");
        setClientToken(null);
        logout("Your session expired, so you were signed out. Please log in again.");
        return;
      }

      const hadPairing = clientTokenRef.current !== null;
      setSocketStatus(hadPairing ? "reconnecting" : "disconnected");

      const attempt = reconnectAttemptRef.current + 1;
      reconnectAttemptRef.current = attempt;
      const delay = Math.min(1000 * 2 ** (attempt - 1), MAX_RECONNECT_DELAY_MS);
      clearReconnectTimer();
      reconnectTimeoutRef.current = setTimeout(() => {
        connectSocket();
      }, delay);
    };

    ws.onerror = () => {
      // onclose will fire right after and drive reconnect/backoff; nothing else to do here.
    };

    return ws;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, clearHeartbeat, clearReconnectTimer, handleServerMessage, logout, setClientToken]);

  useEffect(() => {
    if (!isAuthenticated) {
      manualCloseRef.current = true;
      clearReconnectTimer();
      clearHeartbeat();
      wsRef.current?.close();
      wsRef.current = null;
      setSocketStatus("disconnected");
      return;
    }

    connectSocket();

    return () => {
      manualCloseRef.current = true;
      clearReconnectTimer();
      clearHeartbeat();
      wsRef.current?.close();
      wsRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAuthenticated, token]);

  const pair = useCallback(
    (code: string): Promise<void> => {
      setPairingStatus("pairing");
      setPairError(null);
      pairInFlightRef.current = true;

      const finish = (resolve: () => void, reject: (err: Error) => void) => ({
        resolve: () => {
          pairInFlightRef.current = false;
          resolve();
        },
        reject: (err: Error) => {
          pairInFlightRef.current = false;
          reject(err);
        },
      });

      return new Promise<void>((resolvePromise, rejectPromise) => {
        const { resolve, reject } = finish(resolvePromise, rejectPromise);

        const ws = connectSocket();
        if (!ws) {
          setPairingStatus("error");
          reject(new Error("Not connected to the WatchWire server."));
          return;
        }

        const sendPairMessage = () => {
          pendingPairRef.current = { resolve, reject };
          pendingPairTimeoutRef.current = setTimeout(() => {
            pendingPairRef.current = null;
            setPairingStatus("error");
            const timeoutErr = new Error("Timed out waiting for the server to respond. Please try again.");
            setPairError(timeoutErr.message);
            reject(timeoutErr);
          }, PAIR_RESPONSE_TIMEOUT_MS);
          ws.send(JSON.stringify({ type: "pair", code }));
        };

        if (ws.readyState === WebSocket.OPEN) {
          sendPairMessage();
        } else {
          ws.addEventListener("open", sendPairMessage, { once: true });
        }
      });
    },
    [connectSocket],
  );

  const forgetPairing = useCallback(() => {
    setClientToken(null);
    setPairingStatus("idle");
    setPairError(null);
    setCameraConnected(false);
    setMonitoring(false);
    setEvents([]);
  }, [setClientToken]);

  const lastEvent = events.length > 0 ? events[0] : null;

  const value = useMemo<ClientSocketContextValue>(
    () => ({
      socketStatus,
      pairingStatus,
      pairError,
      cameraConnected,
      monitoring,
      events,
      lastEvent,
      pair,
      forgetPairing,
    }),
    [socketStatus, pairingStatus, pairError, cameraConnected, monitoring, events, lastEvent, pair, forgetPairing],
  );

  return <ClientSocketContext.Provider value={value}>{children}</ClientSocketContext.Provider>;
}

export function useClientSocket(): ClientSocketContextValue {
  const ctx = useContext(ClientSocketContext);
  if (!ctx) {
    throw new Error("useClientSocket must be used within a ClientSocketProvider");
  }
  return ctx;
}
