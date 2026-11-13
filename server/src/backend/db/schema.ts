/** Date columns are epoch milliseconds in SQLite and dates elsewhere. */
export type DateValue = number | string | Date;

export interface DevicesTable {
  id: string;
  name: string;
  token_hash: string;
  created_at: DateValue;
  last_seen_at: DateValue | null;
  /** Newline-separated Apprise URLs used for motion notifications. */
  notification_urls: string | null;
  /** Date of the last notification send, used to rate limit per camera. */
  notification_last_sent: DateValue | null;
}

export interface PairingCodesTable {
  code: string;
  created_at: DateValue;
  expires_at: DateValue;
  used_at: DateValue | null;
}

export interface Database {
  devices: DevicesTable;
  pairing_codes: PairingCodesTable;
}

export type DeviceRow = DevicesTable;
