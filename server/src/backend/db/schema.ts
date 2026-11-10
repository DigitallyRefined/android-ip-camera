/** Date columns are epoch milliseconds in SQLite and dates elsewhere. */
export type DateValue = number | string | Date;

export interface DevicesTable {
  id: string;
  name: string;
  token_hash: string;
  created_at: DateValue;
  last_seen_at: DateValue | null;
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
