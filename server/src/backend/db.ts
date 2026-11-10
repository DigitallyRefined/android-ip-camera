import { SQL } from "bun";
import {
  createHash,
  randomBytes,
  randomUUID,
  timingSafeEqual,
} from "node:crypto";
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";

/**
 * Persistent state is deliberately tiny: the relay only remembers which
 * cameras are allowed to connect. Pairing codes expire, and device tokens are
 * stored as SHA-256 hashes so a copy of the database cannot be used to
 * impersonate a camera.
 */
const configuredPath =
  process.env.DB_PATH ?? resolve(process.cwd(), "data", "relay.sqlite");
const dbPath = configuredPath.startsWith("file:")
  ? configuredPath.slice(5)
  : configuredPath;

mkdirSync(dirname(dbPath), { recursive: true });

export const db = new SQL(`sqlite://${dbPath}`);

await db`PRAGMA journal_mode = WAL`;
await db`PRAGMA foreign_keys = ON`;

await db`
  CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    last_seen_at INTEGER
  )
`;

await db`
  CREATE TABLE IF NOT EXISTS pairing_codes (
    code TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    used_at INTEGER
  )
`;

export interface DeviceRow {
  id: string;
  name: string;
  token_hash: string;
  created_at: number;
  last_seen_at: number | null;
}

export interface DeviceSummary {
  id: string;
  name: string;
  createdAt: number;
  lastSeenAt: number | null;
}

const PAIRING_TTL_MS = 10 * 60 * 1000;
const PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

function formatPairingCode(raw: string): string {
  return `${raw.slice(0, 4)}-${raw.slice(4)}`;
}

export function normalizePairingCode(input: string): string {
  return input.toUpperCase().replace(/[^A-Z0-9]/g, "");
}

export function hashDeviceToken(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

function tokenMatches(storedHash: string, token: string): boolean {
  const provided = Buffer.from(hashDeviceToken(token), "hex");
  const stored = Buffer.from(storedHash, "hex");
  return provided.length === stored.length && timingSafeEqual(provided, stored);
}

export async function createPairingCode(): Promise<{
  code: string;
  displayCode: string;
  expiresAt: number;
}> {
  const now = Date.now();
  await db`DELETE FROM pairing_codes WHERE expires_at <= ${now} OR used_at IS NOT NULL`;

  const bytes = randomBytes(8);
  let raw = "";
  for (const byte of bytes)
    raw += PAIRING_ALPHABET[byte % PAIRING_ALPHABET.length];

  const expiresAt = now + PAIRING_TTL_MS;
  await db`INSERT INTO pairing_codes (code, created_at, expires_at) VALUES (${raw}, ${now}, ${expiresAt})`;

  return { code: raw, displayCode: formatPairingCode(raw), expiresAt };
}

/** Atomically consume a one-time code. Returns false for used, expired, or unknown codes. */
export async function consumePairingCode(input: string): Promise<boolean> {
  const code = normalizePairingCode(input);
  if (code.length !== 8) return false;
  const now = Date.now();
  const result = await db`
    UPDATE pairing_codes
       SET used_at = ${now}
     WHERE code = ${code}
       AND used_at IS NULL
       AND expires_at > ${now}
  `;
  return result.count > 0;
}

export async function createDevice(
  name: string,
): Promise<{ device: DeviceSummary; token: string }> {
  const id = randomUUID();
  const token = randomBytes(32).toString("base64url");
  const tokenHash = hashDeviceToken(token);
  const createdAt = Date.now();
  const safeName = name.trim().slice(0, 120) || "Android IP Camera";

  await db`
    INSERT INTO devices (id, name, token_hash, created_at)
    VALUES (${id}, ${safeName}, ${tokenHash}, ${createdAt})
  `;

  return {
    device: { id, name: safeName, createdAt, lastSeenAt: null },
    token,
  };
}

export async function listDevices(): Promise<DeviceSummary[]> {
  const rows = (await db`
    SELECT id, name, created_at, last_seen_at
      FROM devices
     ORDER BY created_at ASC
  `) as Array<Pick<DeviceRow, "id" | "name" | "created_at" | "last_seen_at">>;

  return rows.map((row) => ({
    id: row.id,
    name: row.name,
    createdAt: row.created_at,
    lastSeenAt: row.last_seen_at,
  }));
}

export async function authenticateDevice(
  id: string,
  token: string,
): Promise<DeviceRow | null> {
  if (!id || !token) return null;
  const rows = (await db`
    SELECT id, name, token_hash, created_at, last_seen_at
      FROM devices
     WHERE id = ${id}
     LIMIT 1
  `) as DeviceRow[];
  const row = rows[0];
  if (!row || !tokenMatches(row.token_hash, token)) return null;
  return row;
}

export async function markDeviceSeen(id: string): Promise<number> {
  const now = Date.now();
  await db`UPDATE devices SET last_seen_at = ${now} WHERE id = ${id}`;
  return now;
}

export async function renameDevice(id: string, name: string): Promise<boolean> {
  const result = await db`UPDATE devices SET name = ${name} WHERE id = ${id}`;
  return result.count > 0;
}

export async function deleteDevice(id: string): Promise<boolean> {
  const result = await db`DELETE FROM devices WHERE id = ${id}`;
  return result.count > 0;
}

export async function deviceExists(id: string): Promise<boolean> {
  if (!id) return false;
  const rows = await db`
    SELECT 1 FROM devices WHERE id = ${id} LIMIT 1
  `;
  return rows.length > 0;
}
