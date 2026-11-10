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
