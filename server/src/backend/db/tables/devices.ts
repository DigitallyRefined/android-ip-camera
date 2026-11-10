import {
  createHash,
  randomBytes,
  randomUUID,
  timingSafeEqual,
} from "node:crypto";
import { db } from "../connection";
import { dateMs, nowMs, sqlNow } from "../dateUtils";
import type { DeviceRow } from "../schema";

export interface DeviceSummary {
  id: string;
  name: string;
  createdAt: number;
  lastSeenAt: number | null;
}

export function hashDeviceToken(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

function tokenMatches(storedHash: string, token: string): boolean {
  const provided = Buffer.from(hashDeviceToken(token), "hex");
  const stored = Buffer.from(storedHash, "hex");
  return provided.length === stored.length && timingSafeEqual(provided, stored);
}

export async function createDevice(
  name: string,
): Promise<{ device: DeviceSummary; token: string }> {
  const id = randomUUID();
  const token = randomBytes(32).toString("base64url");
  const tokenHash = hashDeviceToken(token);
  const createdAt = nowMs();
  const safeName = name.trim().slice(0, 120) || "Android IP Camera";

  await db
    .insertInto("devices")
    .values({
      id,
      name: safeName,
      token_hash: tokenHash,
      created_at: sqlNow(),
      last_seen_at: null,
    })
    .execute();

  return {
    device: { id, name: safeName, createdAt, lastSeenAt: null },
    token,
  };
}

export async function listDevices(): Promise<DeviceSummary[]> {
  const rows = await db
    .selectFrom("devices")
    .select(["id", "name", "created_at", "last_seen_at"])
    .orderBy("created_at", "asc")
    .execute();

  return rows.map((row) => ({
    id: row.id,
    name: row.name,
    createdAt: dateMs(row.created_at),
    lastSeenAt: row.last_seen_at === null ? null : dateMs(row.last_seen_at),
  }));
}

export async function authenticateDevice(
  id: string,
  token: string,
): Promise<DeviceRow | null> {
  if (!id || !token) return null;
  const row = await db
    .selectFrom("devices")
    .selectAll()
    .where("id", "=", id)
    .limit(1)
    .executeTakeFirst();
  if (!row || !tokenMatches(row.token_hash, token)) return null;
  return {
    ...row,
    created_at: dateMs(row.created_at),
    last_seen_at: row.last_seen_at === null ? null : dateMs(row.last_seen_at),
  };
}

export async function markDeviceSeen(id: string): Promise<number> {
  const now = nowMs();
  await db
    .updateTable("devices")
    .set({ last_seen_at: sqlNow() })
    .where("id", "=", id)
    .execute();
  return now;
}

export async function renameDevice(id: string, name: string): Promise<boolean> {
  const result = await db
    .updateTable("devices")
    .set({ name })
    .where("id", "=", id)
    .executeTakeFirst();
  return Number(result?.numUpdatedRows ?? 0n) > 0;
}

export async function deleteDevice(id: string): Promise<boolean> {
  const result = await db
    .deleteFrom("devices")
    .where("id", "=", id)
    .executeTakeFirst();
  return Number(result?.numDeletedRows ?? 0n) > 0;
}

export async function deviceExists(id: string): Promise<boolean> {
  if (!id) return false;
  const row = await db
    .selectFrom("devices")
    .select("id")
    .where("id", "=", id)
    .limit(1)
    .executeTakeFirst();
  return row !== undefined;
}
