import { CustomSource } from "mediabunny";
import type { DeviceConnection } from "./relay";

/**
 * Progressive recording playback: the relay never downloads a whole MP4.
 * mediabunny asks for byte ranges, and each range is fetched from the camera
 * over the existing device WebSocket as an HTTP Range request. Only a small
 * read-ahead window (bounded by {@link RECORDING_READ_CACHE_BYTES}) is held in
 * memory, and it is dropped when the WebRTC session ends.
 */

/** The requested recording cannot be read from the camera. */
export class RecordingAccessError extends Error {
  constructor(
    message: string,
    readonly status = 502,
  ) {
    super(message);
    this.name = "RecordingAccessError";
  }
}

/**
 * Upper bound for mediabunny's read cache. The demuxer needs random access to
 * the MP4 index and the samples around the play position, but never the whole
 * file. Larger values trade relay memory for fewer camera round trips.
 */
const RECORDING_READ_CACHE_BYTES = 8 * 1024 * 1024;

interface Credentials {
  username: string;
  password: string;
}

function encodeRecordingPath(path: string): string {
  return path.split("/").map(encodeURIComponent).join("/");
}

function authHeaders(credentials: Credentials): Record<string, string> {
  if (!credentials.username && !credentials.password) return {};
  return {
    authorization: `Basic ${Buffer.from(
      `${credentials.username}:${credentials.password}`,
      "utf8",
    ).toString("base64")}`,
  };
}

interface ContentRange {
  start: number;
  end: number;
  total: number;
}

function parseContentRange(value: string | null): ContentRange | undefined {
  const match = /^bytes\s+(\d+)-(\d+)\/(\d+)$/i.exec(value?.trim() ?? "");
  if (!match) return undefined;
  const start = Number(match[1]);
  const end = Number(match[2]);
  const total = Number(match[3]);
  if (![start, end, total].every(Number.isSafeInteger)) return undefined;
  if (start > end || end >= total) return undefined;
  return { start, end, total };
}

/** Stop reading the camera response once the requested range is complete. */
function takeBytes(
  stream: ReadableStream<Uint8Array>,
  limit: number,
): ReadableStream<Uint8Array> {
  const reader = stream.getReader();
  let remaining = limit;
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      try {
        if (remaining <= 0) {
          controller.close();
          await reader.cancel().catch(() => {});
          return;
        }
        const { done, value } = await reader.read();
        if (done) {
          controller.close();
          return;
        }
        if (value.byteLength > remaining) {
          controller.enqueue(value.subarray(0, remaining));
          remaining = 0;
          controller.close();
          await reader.cancel().catch(() => {});
          return;
        }
        remaining -= value.byteLength;
        controller.enqueue(value);
        if (remaining === 0) {
          controller.close();
          await reader.cancel().catch(() => {});
        }
      } catch (error) {
        controller.error(error);
      }
    },
    cancel(reason) {
      return reader.cancel(reason);
    },
  });
}

async function cancelBody(response: Response): Promise<void> {
  await response.body?.cancel().catch(() => {});
}

/**
 * Probe the recording and return a source that streams it lazily. The probe
 * also verifies that the app supports HTTP Range, because progressive playback
 * is impossible against a server that always sends the whole file.
 */
export async function openRecordingSource(
  device: DeviceConnection,
  credentials: Credentials,
  recordingPath: string,
): Promise<CustomSource> {
  const cameraPath = `/files/${encodeRecordingPath(recordingPath)}`;
  const headers = authHeaders(credentials);

  const probe = await device.proxyRequest("GET", cameraPath, {
    ...headers,
    range: "bytes=0-0",
  });
  if (!probe.ok) {
    await cancelBody(probe);
    if (probe.status === 404) {
      throw new RecordingAccessError("Recording not found", 404);
    }
    if (probe.status === 416) {
      throw new RecordingAccessError("The recording is empty", 415);
    }
    throw new RecordingAccessError(
      `The camera returned ${probe.status} ${probe.statusText}`,
    );
  }

  const contentRange = parseContentRange(probe.headers.get("content-range"));
  await cancelBody(probe);
  if (probe.status !== 206 || !contentRange) {
    throw new RecordingAccessError(
      "The camera app does not support ranged recording playback. Update the Android app on the camera.",
    );
  }
  if (contentRange.total <= 0) {
    throw new RecordingAccessError("The recording is empty", 415);
  }

  return new CustomSource({
    getSize: () => contentRange.total,
    read: async (start, end) => {
      const response = await device.proxyRequest("GET", cameraPath, {
        ...headers,
        range: `bytes=${start}-${end - 1}`,
      });
      if (response.status !== 206 || !response.body) {
        await cancelBody(response);
        throw new RecordingAccessError(
          response.status === 404
            ? "Recording not found"
            : `The camera returned ${response.status} ${response.statusText}`,
          response.status === 404 ? 404 : 502,
        );
      }
      const range = parseContentRange(response.headers.get("content-range"));
      if (range && range.start !== start) {
        await cancelBody(response);
        throw new RecordingAccessError(
          "The camera returned an unexpected byte range",
        );
      }
      return takeBytes(response.body, end - start);
    },
    prefetchProfile: "network",
    maxCacheSize: RECORDING_READ_CACHE_BYTES,
  });
}
