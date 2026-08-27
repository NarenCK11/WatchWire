// Mirrors backend/app/schemas.py -- keep these in sync with the server's WebSocket
// message contracts.

export interface PairedMessage {
  type: "paired";
  client_token: string;
  paired_at: number;
  camera_connected: boolean;
  monitoring: boolean;
}

export interface CameraStatusMessage {
  type: "camera_status";
  connected: boolean;
  monitoring: boolean;
}

export interface MotionEventMessage {
  type: "motion_event";
  score: number;
  timestamp: string;
}

export interface PeerDisconnectedMessage {
  type: "peer_disconnected";
}

export interface ErrorMessage {
  type: "error";
  code: string;
  message: string;
}

export interface PongMessage {
  type: "pong";
}

export type ServerToClientMessage =
  | PairedMessage
  | CameraStatusMessage
  | MotionEventMessage
  | PeerDisconnectedMessage
  | ErrorMessage
  | PongMessage;

export interface MotionEvent {
  id: string;
  score: number;
  timestamp: string;
  receivedAt: number;
}

export type PairingStatus = "idle" | "restoring" | "pairing" | "paired" | "error";
export type SocketStatus = "disconnected" | "connecting" | "connected" | "reconnecting";
