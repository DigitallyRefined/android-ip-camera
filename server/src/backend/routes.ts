import type { BunRequest } from "bun";
import { normalizeDeviceName } from "../shared/deviceName";
import type { JsonValue } from "../shared/json";
import {
  createDevice,
  deleteDevice,
  deviceExists,
  listDevices,
  renameDevice,
} from "./db/tables/devices";
import { consumePairingCode, createPairingCode } from "./db/tables/pairing";
import { forwardHeaders, getDeviceConnection, onlineDeviceIds } from "./relay";
import type { DeviceConnection } from "./relay";
import { cameraProxyPath } from "./proxyPath";
import { RecordingCache } from "./recordingCache";
import {
  authFailure,
  json,
  parseBasicCredentials,
  publicBaseUrl,
} from "./utils";
import {
  UnsupportedRecordingError,
  WebRtcBridge,
  WebRtcRecordingBridge,
  webrtcConfigSummary,
} from "./webrtc";

const DEFAULT_RECORDING_MAX_BYTES = 512 * 1024 * 1024;

function recordingMaxBytes(): number {
  const raw = process.env.WEBRTC_RECORDING_MAX_BYTES?.trim();
  if (!raw) return DEFAULT_RECORDING_MAX_BYTES;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) {
    console.warn(`Ignoring invalid WEBRTC_RECORDING_MAX_BYTES=${raw}`);
    return DEFAULT_RECORDING_MAX_BYTES;
  }
  return value;
}

const RECORDING_MAX_BYTES = recordingMaxBytes();

function recordingCacheBytes(): number {
  const raw = process.env.WEBRTC_RECORDING_CACHE_BYTES?.trim();
  if (!raw) return RECORDING_MAX_BYTES;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) {
    console.warn(`Ignoring invalid WEBRTC_RECORDING_CACHE_BYTES=${raw}`);
    return RECORDING_MAX_BYTES;
  }
  return value;
}

const recordingCache = new RecordingCache(recordingCacheBytes());
const pendingRecordings = new Map<string, Promise<Buffer>>();

class RecordingTooLargeError extends Error {}

class CameraRecordingError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

function recordingCacheKey(deviceId: string, path: string): string {
  return `${deviceId}\n${path}`;
}

function invalidateCachedRecording(deviceId: string, path: string): void {
  recordingCache.invalidate(recordingCacheKey(deviceId, path));
  recordingCache.invalidatePrefix(`${recordingCacheKey(deviceId, path)}/`);
}

/** A proxied DELETE can remove a file or a folder, so invalidate both. */
function invalidateDeletedRecordingPath(
  deviceId: string,
  requestPath: string,
): void {
  const pathname = requestPath.split("?")[0] ?? "";
  if (!pathname.startsWith("/files/")) return;
  let decoded: string;
  try {
    decoded = pathname
      .slice("/files/".length)
      .split("/")
      .map(decodeURIComponent)
      .join("/");
  } catch {
    return;
  }
  if (!decoded) return;
  invalidateCachedRecording(deviceId, decoded);
}

/**
 * Recording paths are relative to the camera's recordings root. Reject
 * anything that could escape it or reach a non-recording file.
 */
function normalizeRecordingPath(raw: JsonValue): string | undefined {
  if (typeof raw !== "string" || raw.length === 0) return undefined;
  if (raw.startsWith("/") || raw.includes("\\")) return undefined;
  for (const character of raw) {
    const code = character.charCodeAt(0);
    if (code < 32 || code === 127) return undefined;
  }
  const segments = raw.split("/");
  if (
    segments.some(
      (segment) => segment.length === 0 || segment === "." || segment === "..",
    )
  ) {
    return undefined;
  }
  if (!/\.mp4$/i.test(raw)) return undefined;
  return segments.join("/");
}

/** Percent-encode each segment but keep the separators the camera expects. */
function encodeRecordingPath(path: string): string {
  return path.split("/").map(encodeURIComponent).join("/");
}

function basicAuthHeader(username: string, password: string): string {
  return `Basic ${Buffer.from(`${username}:${password}`, "utf8").toString("base64")}`;
}

async function readRecordingBody(
  response: Response,
  maxBytes: number,
): Promise<Buffer> {
  const declared = Number(response.headers.get("content-length") ?? "");
  if (Number.isFinite(declared) && declared > maxBytes) {
    await response.body?.cancel().catch(() => {});
    throw new RecordingTooLargeError(
      `The recording is larger than the ${maxBytes}-byte relay limit`,
    );
  }
  if (!response.body) {
    throw new Error("The camera returned an empty response");
  }

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value) continue;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel().catch(() => {});
        throw new RecordingTooLargeError(
          `The recording is larger than the ${maxBytes}-byte relay limit`,
        );
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  return Buffer.concat(chunks, total);
}

async function fetchRecording(
  device: DeviceConnection,
  credentials: { username: string; password: string },
  recordingPath: string,
): Promise<Buffer> {
  const headers: Record<string, string> = {};
  if (credentials.username || credentials.password) {
    headers.authorization = basicAuthHeader(
      credentials.username,
      credentials.password,
    );
  }

  const response = await device.proxyRequest(
    "GET",
    `/files/${encodeRecordingPath(recordingPath)}`,
    headers,
  );
  if (!response.ok) {
    await response.body?.cancel().catch(() => {});
    if (response.status === 404) {
      throw new CameraRecordingError("Recording not found", 404);
    }
    throw new CameraRecordingError(
      `The camera returned ${response.status} ${response.statusText}`,
      502,
    );
  }

  return readRecordingBody(response, RECORDING_MAX_BYTES);
}

/**
 * Return a downloaded recording, sharing an in-flight fetch for the same file
 * so a scrub that arrives while the first request is still downloading does
 * not open a second camera request.
 */
async function loadRecording(
  device: DeviceConnection,
  credentials: { username: string; password: string },
  recordingPath: string,
): Promise<Buffer> {
  const key = recordingCacheKey(device.deviceId, recordingPath);
  const cached = recordingCache.get(key);
  if (cached) return cached;

  let pending = pendingRecordings.get(key);
  if (!pending) {
    pending = fetchRecording(device, credentials, recordingPath)
      .then((data) => {
        recordingCache.set(key, data);
        return data;
      })
      .finally(() => {
        pendingRecordings.delete(key);
      });
    pendingRecordings.set(key, pending);
  }
  return pending;
}

export async function handleStatus(): Promise<Response> {
  const devices = await listDevices();
  const online = onlineDeviceIds();
  return json({
    serverTime: Date.now(),
    associated: devices.length > 0,
    devices: devices.map((device) => ({
      ...device,
      online: online.has(device.id),
    })),
    webrtc: webrtcConfigSummary(),
  });
}

export async function handlePairing(request: Request): Promise<Response> {
  if (request.method !== "POST")
    return json({ error: "Method not allowed" }, 405);

  const pairing = await createPairingCode();
  const baseUrl = publicBaseUrl(request);
  const link = `androidipcamera://associate?server=${encodeURIComponent(baseUrl)}&code=${encodeURIComponent(pairing.displayCode)}`;
  return json({
    code: pairing.displayCode,
    expiresAt: pairing.expiresAt,
    link,
    serverUrl: baseUrl,
  });
}

export async function handleAssociate(request: Request): Promise<Response> {
  if (request.method !== "POST")
    return json({ error: "Method not allowed" }, 405);

  let payload: { code?: JsonValue; name?: JsonValue };
  try {
    payload = (await request.json()) as typeof payload;
  } catch {
    return json({ error: "Expected a JSON body" }, 400);
  }

  const code = typeof payload.code === "string" ? payload.code : "";
  const name =
    typeof payload.name === "string" ? payload.name : "Android IP Camera";
  if (!(await consumePairingCode(code))) {
    return json(
      { error: "Pairing code is invalid, expired, or already used" },
      400,
    );
  }

  const { device, token } = await createDevice(name);
  return json({
    deviceId: device.id,
    name: device.name,
    token,
    serverUrl: publicBaseUrl(request),
  });
}

/** Route the device resource by method: DELETE removes, PATCH renames. */
export async function handleDevice(
  request: Request,
  deviceId: string,
): Promise<Response> {
  if (request.method === "DELETE") return handleDeleteDevice(request, deviceId);
  if (request.method === "PATCH") return handleRenameDevice(request, deviceId);
  return json({ error: "Method not allowed" }, 405);
}

export async function handleDeleteDevice(
  request: Request,
  deviceId: string,
): Promise<Response> {
  if (request.method !== "DELETE")
    return json({ error: "Method not allowed" }, 405);

  // Deleting an unknown device is idempotent: the caller's goal is already
  // satisfied. Check this before auth so a retried delete still succeeds after
  // the first attempt removed the row.
  if (!(await deviceExists(deviceId)))
    return json({ error: "Unknown device" }, 404);

  const removed = await deleteDevice(deviceId);
  if (!removed) return json({ error: "Unknown device" }, 404);
  getDeviceConnection(deviceId)?.close(4001, "removed from relay");
  return json({ ok: true });
}

export async function handleRenameDevice(
  request: Request,
  deviceId: string,
): Promise<Response> {
  if (request.method !== "PATCH")
    return json({ error: "Method not allowed" }, 405);

  if (!(await deviceExists(deviceId)))
    return json({ error: "Unknown device" }, 404);

  let payload: { name?: JsonValue };
  try {
    payload = (await request.json()) as typeof payload;
  } catch {
    return json({ error: "Expected a JSON body" }, 400);
  }

  const name = normalizeDeviceName(payload.name);
  if (!name) {
    return json(
      {
        error:
          "Name must be 1-100 characters using letters, numbers, spaces, or hyphens",
      },
      400,
    );
  }

  const renamed = await renameDevice(deviceId, name);
  if (!renamed) return json({ error: "Unknown device" }, 404);
  return json({ ok: true, device: { id: deviceId, name } });
}

export async function handleProxy(
  request: BunRequest<"/r/:deviceId/*">,
): Promise<Response> {
  const deviceId = request.params.deviceId;
  const device = getDeviceConnection(deviceId);
  if (!device) {
    return new Response(
      "The camera is not connected to this relay right now. Open the Android app and make sure the relay status says Connected.",
      {
        status: 503,
        headers: {
          "Content-Type": "text/plain; charset=utf-8",
          "Cache-Control": "no-store",
        },
      },
    );
  }

  const url = new URL(request.url);
  const path = cameraProxyPath(url, deviceId);
  const method = request.method.toUpperCase();
  const body =
    method === "GET" || method === "HEAD"
      ? undefined
      : new Uint8Array(await request.arrayBuffer());

  try {
    const response = await device.proxyRequest(
      method,
      path,
      forwardHeaders(request),
      body,
    );
    // Keep browser caching out of the live-camera path.
    if (!response.headers.has("Cache-Control")) {
      response.headers.set("Cache-Control", "no-store");
    }
    if (method === "DELETE" && response.ok) {
      invalidateDeletedRecordingPath(deviceId, path);
    }
    return response;
  } catch (error) {
    return new Response(
      error instanceof Error ? error.message : "Camera request failed",
      {
        status: 502,
        headers: {
          "Content-Type": "text/plain; charset=utf-8",
          "Cache-Control": "no-store",
        },
      },
    );
  }
}

export async function handleWebRtcOffer(
  request: BunRequest<"/api/webrtc/:deviceId/offer">,
): Promise<Response> {
  if (request.method !== "POST")
    return json({ error: "Method not allowed" }, 405);
  const device = getDeviceConnection(request.params.deviceId);
  if (!device) return json({ error: "Camera is offline" }, 503);

  let body: { type?: JsonValue; sdp?: JsonValue };
  try {
    body = (await request.json()) as typeof body;
  } catch {
    return json({ error: "Expected a JSON body" }, 400);
  }
  if (body.type !== "offer" || typeof body.sdp !== "string") {
    return json({ error: "Expected a WebRTC offer" }, 400);
  }

  const credentials = parseBasicCredentials(request);
  try {
    const auth = await device.checkAuth(
      credentials.username,
      credentials.password,
    );
    if (!auth.ok) return authFailure(auth);

    const answer = await WebRtcBridge.answer(
      device,
      {
        type: "offer",
        sdp: body.sdp,
      },
      credentials,
    );
    return json(answer);
  } catch (error) {
    console.error("WebRTC negotiation failed", error);
    return json(
      {
        error:
          error instanceof Error ? error.message : "WebRTC negotiation failed",
      },
      502,
    );
  }
}

export async function handleWebRtcRecordingOffer(
  request: BunRequest<"/api/webrtc/:deviceId/recording/offer">,
): Promise<Response> {
  if (request.method !== "POST")
    return json({ error: "Method not allowed" }, 405);
  const device = getDeviceConnection(request.params.deviceId);
  if (!device) return json({ error: "Camera is offline" }, 503);

  let body: {
    type?: JsonValue;
    sdp?: JsonValue;
    path?: JsonValue;
    startTime?: JsonValue;
  };
  try {
    body = (await request.json()) as typeof body;
  } catch {
    return json({ error: "Expected a JSON body" }, 400);
  }
  if (
    body.type !== "offer" ||
    typeof body.sdp !== "string" ||
    body.sdp.length === 0
  ) {
    return json({ error: "Expected a WebRTC offer" }, 400);
  }

  const recordingPath = normalizeRecordingPath(body.path);
  if (!recordingPath) {
    return json(
      { error: "Expected a recording path relative to the recordings folder" },
      400,
    );
  }

  const credentials = parseBasicCredentials(request);
  try {
    const auth = await device.checkAuth(
      credentials.username,
      credentials.password,
    );
    if (!auth.ok) return authFailure(auth);

    const startTime =
      typeof body.startTime === "number" && Number.isFinite(body.startTime)
        ? Math.max(0, body.startTime)
        : 0;
    const data = await loadRecording(device, credentials, recordingPath);
    const answer = await WebRtcRecordingBridge.answer(
      { type: "offer", sdp: body.sdp },
      data,
      startTime,
    );
    return json(answer);
  } catch (error) {
    if (error instanceof CameraRecordingError) {
      return json({ error: error.message }, error.status);
    }
    if (error instanceof RecordingTooLargeError) {
      return json({ error: error.message }, 413);
    }
    if (error instanceof UnsupportedRecordingError) {
      return json({ error: error.message }, 415);
    }
    console.error("WebRTC recording negotiation failed", error);
    return json(
      {
        error:
          error instanceof Error ? error.message : "WebRTC negotiation failed",
      },
      502,
    );
  }
}

export async function handleWebRtcAuth(
  request: BunRequest<"/api/webrtc/:deviceId/auth">,
): Promise<Response> {
  if (request.method !== "GET")
    return json({ error: "Method not allowed" }, 405);
  const device = getDeviceConnection(request.params.deviceId);
  if (!device) return json({ error: "Camera is offline" }, 503);

  const credentials = parseBasicCredentials(request);
  try {
    const auth = await device.checkAuth(
      credentials.username,
      credentials.password,
    );
    return json(auth, auth.ok ? 200 : auth.status);
  } catch (error) {
    return json(
      {
        error:
          error instanceof Error
            ? error.message
            : "The camera did not answer the authentication check",
      },
      502,
    );
  }
}
