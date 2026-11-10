import type { Server, ServerWebSocket } from "bun";
import { authenticateDevice, markDeviceSeen } from "./db/tables/devices";
import {
  registerDevice,
  unregisterDevice,
  type DeviceSocketData,
} from "./relay";

export async function handleDeviceSocket(
  request: Request,
  server: Server<DeviceSocketData>,
): Promise<Response | undefined> {
  const url = new URL(request.url);
  const deviceId = url.searchParams.get("deviceId") ?? "";
  const bearer =
    request.headers.get("authorization")?.replace(/^Bearer\s+/i, "") ?? "";
  const token = bearer || url.searchParams.get("token") || "";
  const device = await authenticateDevice(deviceId, token);
  // Keep the status at 401 for older clients, but include a specific body so
  // the app can distinguish this from a WAF/proxy 401 or 403 and only forget
  // the association for a real "device not registered" response.
  if (!device) return new Response("device not registered", { status: 401 });

  const upgraded = server.upgrade(request, {
    data: {
      deviceId: device.id,
      deviceName: device.name,
    } satisfies DeviceSocketData,
  });
  if (!upgraded)
    return new Response("WebSocket upgrade failed", { status: 400 });
  return undefined;
}

export function handleDeviceOpen(ws: ServerWebSocket<DeviceSocketData>): void {
  const connection = registerDevice(ws);
  void markDeviceSeen(connection.deviceId);
  console.log(
    `camera connected: ${connection.deviceName} (${connection.deviceId})`,
  );
}

export function handleDeviceMessage(
  ws: ServerWebSocket<DeviceSocketData>,
  message: string | Buffer,
): void {
  // Use the socket's own connection: after a reconnect, the registry points
  // at the newer socket and the old one must not receive the work.
  const connection = ws.data.connection;
  if (!connection?.isOpen()) return;
  if (typeof message === "string") connection.handleText(message);
  else connection.handleBinary(message);
}

export function handleDeviceClose(ws: ServerWebSocket<DeviceSocketData>): void {
  // Same identity check: a replaced socket closing must not unregister the
  // camera's new connection.
  const connection = ws.data.connection;
  if (connection) unregisterDevice(connection);
  console.log(
    `camera disconnected: ${ws.data.deviceName} (${ws.data.deviceId})`,
  );
}
