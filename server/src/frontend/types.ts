export interface Device {
  id: string;
  name: string;
  online: boolean;
  createdAt: number;
  lastSeenAt: number | null;
}

export interface RelayStatus {
  serverTime: number;
  associated: boolean;
  devices: Device[];
  webrtc: {
    iceServers: Array<{
      urls: string | string[];
      username?: string;
      credential?: string;
      hasCredentials: boolean;
    }>;
    icePortRange: [number, number] | null;
    iceTransportPolicy: string;
    advertiseHost: string | null;
  };
}

export interface PairingLink {
  code: string;
  expiresAt: number;
  link: string;
  serverUrl: string;
}

export type CameraAuthMode =
  "unknown" | "required" | "disabled" | "not-configured";

export interface CameraCredentials {
  username: string;
  password: string;
}

export interface RecordingEntry {
  name: string;
  isDirectory: boolean;
  sizeBytes: number;
  lastModifiedMs: number;
}

export interface RecordingListing {
  folder: string;
  path: string;
  entries: RecordingEntry[];
}
