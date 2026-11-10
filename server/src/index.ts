import { serve } from "bun";
import type { BunRequest } from "bun";
import index from "./index.html";
import {
  handleAssociate,
  handleDevice,
  handlePairing,
  handleProxy,
  handleStatus,
  handleWebRtcAuth,
  handleWebRtcOffer,
} from "./backend/routes";
import { json } from "./backend/utils";
import {
  handleDeviceClose,
  handleDeviceMessage,
  handleDeviceOpen,
  handleDeviceSocket,
} from "./backend/websocket";

const isDev = process.env.NODE_ENV !== "production";
const devServerHostname = isDev ? process.env.DEV_SERVER_HOSTNAME : undefined;

const server = serve({
  ...(devServerHostname ? { hostname: devServerHostname } : {}),

  routes: {
    "/api/status": () => handleStatus(),
    "/api/pairing": (request) => handlePairing(request),
    "/api/associate": (request) => handleAssociate(request),
    "/api/devices/:id": (request) =>
      handleDevice(
        request,
        (request as BunRequest<"/api/devices/:id">).params.id,
      ),
    "/api/webrtc/:deviceId/offer": (request) =>
      handleWebRtcOffer(request as BunRequest<"/api/webrtc/:deviceId/offer">),
    "/api/webrtc/:deviceId/auth": (request) =>
      handleWebRtcAuth(request as BunRequest<"/api/webrtc/:deviceId/auth">),
    "/ws/device": (request, server) => handleDeviceSocket(request, server),
    "/r/:deviceId/*": (request) =>
      handleProxy(request as BunRequest<"/r/:deviceId/*">),
    "/api/*": () => json({ error: "Not found" }, 404),

    // Serve index.html for all unmatched routes.
    "/*": index,
  },

  websocket: {
    open: handleDeviceOpen,
    message: handleDeviceMessage,
    close: handleDeviceClose,
  },

  development: isDev && {
    // Enable browser hot reloading in development
    hmr: true,

    // Echo console logs from the browser to the server
    console: true,
  },
});

console.log(`🚀 Server running at ${server.url}`);
