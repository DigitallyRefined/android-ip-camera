import {
  createHash,
  randomBytes,
  randomUUID,
  timingSafeEqual,
} from "node:crypto";
import {
  formatNotificationUrls,
  parseNotificationUrls,
} from "@/shared/notifications";
import { db } from "../connection";
import { dateMs, nowMs, sqlDateOffset, sqlNow } from "../dateUtils";
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

export async function getDeviceName(id: string): Promise<string | null> {
  if (!id) return null;
  const row = await db
    .selectFrom("devices")
    .select("name")
    .where("id", "=", id)
    .limit(1)
    .executeTakeFirst();
  return row?.name ?? null;
}

export async function getDeviceNotificationUrls(id: string): Promise<string[]> {
  if (!id) return [];
  const row = await db
    .selectFrom("devices")
    .select("notification_urls")
    .where("id", "=", id)
    .limit(1)
    .executeTakeFirst();
  if (!row?.notification_urls) return [];
  const parsed = parseNotificationUrls(row.notification_urls);
  return parsed.ok ? parsed.urls : [];
}

export async function setDeviceNotificationUrls(
  id: string,
  urls: readonly string[],
): Promise<boolean> {
  const result = await db
    .updateTable("devices")
    .set({
      notification_urls: urls.length > 0 ? formatNotificationUrls(urls) : null,
    })
    .where("id", "=", id)
    .executeTakeFirst();
  return Number(result?.numUpdatedRows ?? 0n) > 0;
}

export const NOTIFICATION_MIN_INTERVAL_MS = 5_000;

export type NotificationClaimResult =
  { allowed: true } | { allowed: false; retryAfterMs: number };

/**
 * Atomically reserve a notification send for a camera. Sends (including test
 * notifications) share this window, so concurrent motion events cannot exceed
 * one request every five seconds.
 */
export async function claimDeviceNotificationSend(
  id: string,
  now = nowMs(),
): Promise<NotificationClaimResult> {
  if (!id) return { allowed: false, retryAfterMs: 0 };

  const result = await db
    .updateTable("devices")
    .set({ notification_last_sent: sqlNow() })
    .where("id", "=", id)
    .where((expression) =>
      expression.or([
        expression("notification_last_sent", "is", null),
        expression(
          "notification_last_sent",
          "<=",
          sqlDateOffset(-NOTIFICATION_MIN_INTERVAL_MS / 1000),
        ),
      ]),
    )
    .executeTakeFirst();

  if (Number(result?.numUpdatedRows ?? 0n) > 0) return { allowed: true };

  const row = await db
    .selectFrom("devices")
    .select("notification_last_sent")
    .where("id", "=", id)
    .limit(1)
    .executeTakeFirst();
  const lastSentAt = row?.notification_last_sent;
  if (lastSentAt === null || lastSentAt === undefined) {
    // The device disappeared between the update and the read. Let the caller
    // report its own not-found response instead of treating this as a limit.
    return { allowed: true };
  }

  return {
    allowed: false,
    retryAfterMs: Math.max(
      0,
      dateMs(lastSentAt) + NOTIFICATION_MIN_INTERVAL_MS - now,
    ),
  };
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
