import { buildApprisePayload } from "@/shared/notifications";
import {
  claimDeviceNotificationSend,
  getDeviceNotificationUrls,
} from "./db/tables/devices";

const DEFAULT_APPRISE_URL = "http://apprise:8000";
const NOTIFY_TIMEOUT_MS = 15_000;
const MAX_ERROR_LENGTH = 4_000;

export interface MotionNotification {
  deviceId: string;
  deviceName: string;
  at: string;
}

export type AppriseSendResult = { ok: true } | { ok: false; error: string };

function appriseBaseUrl(): string {
  const configured = process.env.APPRISE_URL?.trim();
  return (configured || DEFAULT_APPRISE_URL).replace(/\/+$/, "");
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function formatMotionTime(at: string): string {
  const date = new Date(at);
  if (!Number.isFinite(date.getTime())) return at;
  return date.toLocaleString("sv-SE", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

/**
 * Turn Apprise's JSON failure body into something the settings dialog can
 * show. Apprise returns details as [level, timestamp, message] rows.
 */
function appriseFailure(status: number, rawBody: string): string {
  const lines: string[] = [];
  try {
    const body = JSON.parse(rawBody) as {
      error?: unknown;
      details?: unknown;
    };
    if (typeof body.error === "string" && body.error) lines.push(body.error);
    if (Array.isArray(body.details)) {
      for (const detail of body.details) {
        if (!Array.isArray(detail)) continue;
        const message = detail[2];
        if (typeof message === "string" && message) lines.push(message);
      }
    }
  } catch {
    // Fall back to the raw response below.
  }

  if (lines.length === 0) {
    lines.push(
      rawBody.trim()
        ? `Apprise returned ${status}: ${rawBody.trim()}`
        : `Apprise returned ${status}.`,
    );
  }
  return lines.join("\n").slice(0, MAX_ERROR_LENGTH);
}

/** Send one notification request for the given Apprise URLs. */
export async function sendAppriseNotification(
  urls: readonly string[],
  title: string,
  body: string,
): Promise<AppriseSendResult> {
  try {
    const response = await fetch(`${appriseBaseUrl()}/notify`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: buildApprisePayload(urls, title, body),
      signal: AbortSignal.timeout(NOTIFY_TIMEOUT_MS),
    });
    const responseBody = await response.text().catch(() => "");
    if (!response.ok)
      return {
        ok: false,
        error: appriseFailure(response.status, responseBody),
      };

    // Apprise normally uses a non-2xx status for failures, but surface an
    // error field if a successful status still reports one.
    try {
      const parsed = JSON.parse(responseBody) as { error?: unknown };
      if (typeof parsed.error === "string" && parsed.error) {
        return { ok: false, error: parsed.error.slice(0, MAX_ERROR_LENGTH) };
      }
    } catch {
      // An empty or non-JSON success body is fine.
    }

    return { ok: true };
  } catch (error) {
    return {
      ok: false,
      error: errorMessage(error).slice(0, MAX_ERROR_LENGTH),
    };
  }
}

/**
 * Deliver a motion event through every Apprise URL configured for the camera.
 * Failures are logged only: notification delivery must never interfere with
 * the camera socket or with other connected cameras.
 */
export async function notifyMotion(event: MotionNotification): Promise<void> {
  let urls: string[];
  try {
    urls = await getDeviceNotificationUrls(event.deviceId);
  } catch (error) {
    console.warn(
      `Could not read notification settings for ${event.deviceName} (${event.deviceId})`,
      error,
    );
    return;
  }
  if (urls.length === 0) return;

  try {
    const claim = await claimDeviceNotificationSend(event.deviceId);
    if (!claim.allowed) return;
  } catch (error) {
    console.warn(
      `Could not reserve a notification send for ${event.deviceName} (${event.deviceId})`,
      error,
    );
    return;
  }

  const body = `Camera ${event.deviceName} detected motion at ${formatMotionTime(event.at)}`;
  const result = await sendAppriseNotification(urls, "Motion detected", body);
  if (!result.ok) {
    console.warn(
      `Apprise notification failed for ${event.deviceName} (${event.deviceId}): ${result.error}`,
    );
  }
}
