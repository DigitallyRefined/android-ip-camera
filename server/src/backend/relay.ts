import type { ServerWebSocket } from "bun";

/**
 * One camera connection. The protocol multiplexes all browser traffic over a
 * single outbound WebSocket:
 *
 *   server -> camera (text)  http-request | http-cancel | media-start |
 *                            media-stop | keyframe | auth-check
 *   camera -> server (text)  http-response | http-chunk | http-end | http-error |
 *                            auth-result | media-denied
 *   camera -> server (binary) [1, flags, ptsUs:u64be, ...annex-b access unit]
 *
 * Nothing is written to disk. Request and response bodies live in memory only
 * for as long as the browser request is active.
 */

export interface CameraAuthResult {
  ok: boolean;
  status: number;
  reason: string;
  error: string;
}

export interface DeviceSocketData {
  deviceId: string;
  deviceName: string;
  /** Set when the socket opens, so close/message events can verify identity. */
  connection?: DeviceConnection;
}

interface PendingRequest {
  id: string;
  method: string;
  path: string;
  createdAt: number;
  stream: ReadableStream<Uint8Array>;
  controller: ReadableStreamDefaultController<Uint8Array>;
  resolveHeaders: (response: Response) => void;
  rejectHeaders: (error: Error) => void;
  headersSent: boolean;
  closed: boolean;
  timer: ReturnType<typeof setTimeout>;
}

export interface MediaViewer {
  writeVideo(annexB: Uint8Array, isKey: boolean, ptsUs: number): void;
  /** Called when the camera disconnects so the viewer can tear down its peer. */
  close?(): void;
}

const HEADER_TIMEOUT_MS = 45_000;
const AUTH_TIMEOUT_MS = 10_000;
const MAX_PENDING_PER_DEVICE = 32;

export class DeviceConnection {
  readonly deviceId: string;
  readonly deviceName: string;
  readonly connectedAt = Date.now();
  private readonly ws: ServerWebSocket<DeviceSocketData>;
  private readonly pending = new Map<string, PendingRequest>();
  private readonly authPending = new Map<
    string,
    {
      resolve: (result: CameraAuthResult) => void;
      reject: (error: Error) => void;
      timer: ReturnType<typeof setTimeout>;
    }
  >();
  private readonly viewers = new Set<MediaViewer>();
  private closed = false;
  private mediaActive = false;

  constructor(ws: ServerWebSocket<DeviceSocketData>) {
    this.ws = ws;
    this.deviceId = ws.data.deviceId;
    this.deviceName = ws.data.deviceName;
  }

  isOpen(): boolean {
    return !this.closed;
  }

  get viewerCount(): number {
    return this.viewers.size;
  }

  sendJson(message: unknown): void {
    if (this.closed) return;
    try {
      this.ws.send(JSON.stringify(message));
    } catch {
      // The close handler tears everything down.
    }
  }

  requestKeyFrame(): void {
    this.sendJson({ type: "keyframe" });
  }

  /**
   * Ask the camera to verify the viewer's Basic credentials. The camera owns
   * the username/password policy, so the relay never decides on its own
   * whether a viewer may stream.
   */
  checkAuth(username: string, password: string): Promise<CameraAuthResult> {
    if (this.closed) {
      return Promise.reject(new Error("Camera is offline"));
    }
    const id = crypto.randomUUID();
    return new Promise<CameraAuthResult>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.authPending.delete(id);
        reject(
          new Error("Camera did not answer the authentication check in time"),
        );
      }, AUTH_TIMEOUT_MS);
      this.authPending.set(id, { resolve, reject, timer });
      this.sendJson({ type: "auth-check", id, username, password });
    });
  }

  addViewer(
    viewer: MediaViewer,
    credentials: { username: string; password: string },
  ): () => void {
    if (this.closed) return () => {};
    this.viewers.add(viewer);
    if (this.viewers.size === 1) {
      this.mediaActive = true;
      this.sendJson({
        type: "media-start",
        username: credentials.username,
        password: credentials.password,
      });
      // A new viewer cannot decode anything until the camera produces an IDR.
      this.requestKeyFrame();
    }
    return () => {
      if (!this.viewers.delete(viewer)) return;
      if (this.viewers.size === 0 && this.mediaActive) {
        this.mediaActive = false;
        this.sendJson({ type: "media-stop" });
      }
    };
  }

  handleText(raw: string): void {
    let message: Record<string, unknown>;
    try {
      message = JSON.parse(raw) as Record<string, unknown>;
    } catch {
      return;
    }

    const id = typeof message.id === "string" ? message.id : "";
    const type = typeof message.type === "string" ? message.type : "";

    if (type === "auth-result") {
      if (!id) return;
      const auth = this.authPending.get(id);
      if (!auth) return;
      clearTimeout(auth.timer);
      this.authPending.delete(id);
      auth.resolve({
        ok: message.ok === true,
        status: typeof message.status === "number" ? message.status : 401,
        reason: typeof message.reason === "string" ? message.reason : "unknown",
        error:
          typeof message.error === "string"
            ? message.error
            : "The camera rejected the credentials",
      });
      return;
    }

    if (type === "media-denied") {
      // The camera refused to start media even though the relay asked. Tear
      // down any viewers so a rejected stream never stays half-open.
      const reason =
        typeof message.reason === "string" ? message.reason : "unknown";
      console.warn(`camera denied media: ${reason}`);
      this.mediaActive = false;
      const viewers = [...this.viewers];
      this.viewers.clear();
      for (const viewer of viewers) {
        try {
          viewer.close?.();
        } catch {
          // Viewer teardown must not affect other viewers.
        }
      }
      return;
    }

    if (!id) return;
    const request = this.pending.get(id);
    if (!request) return;

    switch (type) {
      case "http-response": {
        if (request.headersSent || request.closed) return;
        const status =
          typeof message.status === "number" ? message.status : 502;
        const headers = new Headers();
        if (message.headers && typeof message.headers === "object") {
          for (const [name, value] of Object.entries(
            message.headers as Record<string, unknown>,
          )) {
            if (typeof value !== "string") continue;
            const lower = name.toLowerCase();
            if (
              lower === "connection" ||
              lower === "transfer-encoding" ||
              lower === "keep-alive"
            ) {
              continue;
            }
            try {
              headers.set(name, value);
            } catch {
              // Ignore malformed header names from the device.
            }
          }
        }
        request.headersSent = true;
        clearTimeout(request.timer);
        request.resolveHeaders(
          new Response(request.stream, {
            status: status >= 100 && status <= 599 ? status : 502,
            headers,
          }),
        );
        return;
      }
      case "http-chunk": {
        if (
          request.closed ||
          !request.headersSent ||
          typeof message.data !== "string"
        )
          return;
        this.enqueueChunk(request, message.data);
        return;
      }
      case "http-end": {
        this.finishRequest(request);
        return;
      }
      case "http-error": {
        const text =
          typeof message.message === "string"
            ? message.message
            : "Camera request failed";
        this.failRequest(request, new Error(text));
        return;
      }
      default:
        return;
    }
  }

  private enqueueChunk(request: PendingRequest, base64: string): void {
    if (request.closed) return;
    try {
      const bytes = Buffer.from(base64, "base64");
      if (bytes.length > 0) request.controller.enqueue(new Uint8Array(bytes));
    } catch (error) {
      this.failRequest(
        request,
        error instanceof Error ? error : new Error(String(error)),
      );
    }
  }

  private finishRequest(request: PendingRequest): void {
    if (request.closed) return;
    request.closed = true;
    clearTimeout(request.timer);
    this.pending.delete(request.id);
    if (!request.headersSent) {
      request.rejectHeaders(
        new Error("Camera closed the response before sending headers"),
      );
      return;
    }
    try {
      request.controller.close();
    } catch {
      // Already closed or cancelled by the browser.
    }
  }

  private failRequest(request: PendingRequest, error: Error): void {
    if (request.closed) return;
    request.closed = true;
    clearTimeout(request.timer);
    this.pending.delete(request.id);
    if (!request.headersSent) {
      request.rejectHeaders(error);
      return;
    }
    try {
      request.controller.error(error);
    } catch {
      // Already closed or cancelled by the browser.
    }
  }

  async proxyRequest(
    method: string,
    path: string,
    headers: Record<string, string>,
    body?: Uint8Array,
  ): Promise<Response> {
    if (this.closed) throw new Error("Camera is offline");
    if (this.pending.size >= MAX_PENDING_PER_DEVICE) {
      throw new Error("Too many concurrent requests to the camera");
    }

    const id = crypto.randomUUID();
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const stream = new ReadableStream<Uint8Array>({
      start(c) {
        controller = c;
      },
      cancel: () => {
        const request = this.pending.get(id);
        if (request) {
          request.closed = true;
          clearTimeout(request.timer);
          this.pending.delete(id);
          this.sendJson({ type: "http-cancel", id });
        }
      },
    });

    return new Promise<Response>((resolve, reject) => {
      const timer = setTimeout(() => {
        const request = this.pending.get(id);
        if (!request) return;
        this.pending.delete(id);
        request.closed = true;
        this.sendJson({ type: "http-cancel", id });
        reject(new Error("Camera did not answer in time"));
      }, HEADER_TIMEOUT_MS);

      const request: PendingRequest = {
        id,
        method,
        path,
        createdAt: Date.now(),
        stream,
        controller,
        resolveHeaders: resolve,
        rejectHeaders: reject,
        headersSent: false,
        closed: false,
        timer,
      };
      this.pending.set(id, request);

      this.sendJson({
        type: "http-request",
        id,
        method,
        path,
        headers,
        body:
          body && body.length > 0
            ? Buffer.from(body).toString("base64")
            : undefined,
      });
    });
  }

  handleBinary(raw: Buffer): void {
    // Byte 0: frame kind (1 = H.264 access unit). Byte 1: flags (1 = keyframe).
    if (raw.length < 10 || raw[0] !== 1 || this.viewers.size === 0) return;
    const flags = raw[1] ?? 0;
    const ptsUs = Number(
      new DataView(raw.buffer, raw.byteOffset, raw.byteLength).getBigUint64(
        2,
        false,
      ),
    );
    const annexB = new Uint8Array(
      raw.buffer,
      raw.byteOffset + 10,
      raw.byteLength - 10,
    );
    if (annexB.length === 0) return;

    for (const viewer of [...this.viewers]) {
      try {
        viewer.writeVideo(annexB, (flags & 1) !== 0, ptsUs);
      } catch {
        this.viewers.delete(viewer);
        try {
          viewer.close?.();
        } catch {
          // Viewer teardown must not affect other viewers.
        }
      }
    }
  }

  close(code = 1000, reason = "closed"): void {
    if (this.closed) return;
    this.closed = true;
    this.mediaActive = false;
    const viewers = [...this.viewers];
    this.viewers.clear();
    // Close peers explicitly; otherwise browsers keep a dead WebRTC session
    // (and the server keeps its UDP/TURN allocations) after a camera drop.
    for (const viewer of viewers) {
      try {
        viewer.close?.();
      } catch {
        // Best-effort teardown.
      }
    }
    for (const request of [...this.pending.values()]) {
      this.failRequest(request, new Error("Camera disconnected"));
    }
    for (const auth of this.authPending.values()) {
      clearTimeout(auth.timer);
      auth.reject(new Error("Camera disconnected"));
    }
    this.authPending.clear();
    try {
      this.ws.close(code, reason);
    } catch {
      // Already closed.
    }
  }
}

const online = new Map<string, DeviceConnection>();

export function registerDevice(
  ws: ServerWebSocket<DeviceSocketData>,
): DeviceConnection {
  const previous = online.get(ws.data.deviceId);
  if (previous && previous.isOpen())
    previous.close(4000, "replaced by a newer connection");
  const connection = new DeviceConnection(ws);
  ws.data.connection = connection;
  online.set(connection.deviceId, connection);
  return connection;
}

export function unregisterDevice(connection: DeviceConnection): void {
  if (online.get(connection.deviceId) === connection) {
    online.delete(connection.deviceId);
  }
  connection.close();
}

export function getDeviceConnection(
  deviceId: string,
): DeviceConnection | undefined {
  const connection = online.get(deviceId);
  return connection?.isOpen() ? connection : undefined;
}

export function onlineDeviceIds(): Set<string> {
  return new Set(online.keys());
}

export function isDeviceOnline(deviceId: string): boolean {
  return getDeviceConnection(deviceId) !== undefined;
}

export function forwardHeaders(request: Request): Record<string, string> {
  const headers: Record<string, string> = {};
  for (const [name, value] of request.headers.entries()) {
    const lower = name.toLowerCase();
    if (
      lower === "host" ||
      lower === "connection" ||
      lower === "upgrade" ||
      lower === "content-length" ||
      lower === "transfer-encoding" ||
      lower === "accept-encoding"
    ) {
      continue;
    }
    headers[name] = value;
  }
  return headers;
}
