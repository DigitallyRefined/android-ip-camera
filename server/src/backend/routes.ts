import type { BunRequest } from "bun";
import {
  consumePairingCode,
  createDevice,
  createPairingCode,
  deleteDevice,
  listDevices,
} from "./db";
import { forwardHeaders, getDeviceConnection, onlineDeviceIds } from "./relay";
import {
  authFailure,
  json,
  parseBasicCredentials,
  publicBaseUrl,
} from "./utils";
import { WebRtcBridge, webrtcConfigSummary } from "./webrtc";

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

  let payload: { code?: unknown; name?: unknown };
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

export async function handleDeleteDevice(
  request: Request,
  deviceId: string,
): Promise<Response> {
  if (request.method !== "DELETE")
    return json({ error: "Method not allowed" }, 405);
  const removed = await deleteDevice(deviceId);
  if (!removed) return json({ error: "Unknown device" }, 404);
  getDeviceConnection(deviceId)?.close(4001, "removed from relay");
  return json({ ok: true });
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
  const params = request.params as Record<string, string>;
  const suffix = params["*"] ?? "";
  const path = `/${suffix}${url.search}`;
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

  let body: { type?: unknown; sdp?: unknown };
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
