import { expect, test } from "bun:test";
import type { DeviceConnection } from "./relay";
import { RecordingAccessError, openRecordingSource } from "./recordingSource";

interface CameraCall {
  method: string;
  path: string;
  headers: Record<string, string>;
}

type Handler = (call: CameraCall) => Response;

function fakeDevice(handler: Handler): {
  device: DeviceConnection;
  calls: CameraCall[];
} {
  const calls: CameraCall[] = [];
  const device = {
    proxyRequest: async (
      method: string,
      path: string,
      headers: Record<string, string>,
    ) => {
      const call = { method, path, headers };
      calls.push(call);
      return handler(call);
    },
  } as unknown as DeviceConnection;
  return { device, calls };
}

function rangeResponse(
  start: number,
  end: number,
  total: number,
  bytes: number[],
): Response {
  return new Response(new Uint8Array(bytes), {
    status: 206,
    headers: { "content-range": `bytes ${start}-${end}/${total}` },
  });
}

const credentials = { username: "cam", password: "secret" };

/** Serves the exact byte range requested, with absolutely indexed values. */
function serveRange(total: number): Handler {
  return (call) => {
    const match = /^bytes=(\d+)-(\d+)$/.exec(call.headers.range ?? "");
    if (!match) return new Response("Bad range", { status: 400 });
    const start = Number(match[1]);
    const end = Number(match[2]);
    return rangeResponse(
      start,
      end,
      total,
      Array.from({ length: end - start + 1 }, (_, i) => (start + i) % 251),
    );
  };
}

test("probes the recording with a one-byte range and exposes its size", async () => {
  const { device, calls } = fakeDevice(serveRange(1000));
  const source = await openRecordingSource(
    device,
    credentials,
    "2026/11/09/clip.mp4",
  );

  expect(await source.getSize()).toBe(1000);
  expect(calls[0]?.path).toBe("/files/2026/11/09/clip.mp4");
  expect(calls[0]?.headers.range).toBe("bytes=0-0");
  expect(calls[0]?.headers.authorization).toBe(
    `Basic ${Buffer.from("cam:secret").toString("base64")}`,
  );
  // Every later access also asks the camera for a bounded byte range.
  expect(calls.slice(1).every((call) => call.headers.range)).toBe(true);
});

test("reads only the requested byte range", async () => {
  const { device, calls } = fakeDevice(serveRange(1000));
  const source = await openRecordingSource(
    device,
    credentials,
    "2026/11/09/clip.mp4",
  );

  type InternalSource = {
    _read(
      start: number,
      end: number,
      min: number,
      max: number,
    ): Promise<{ bytes: Uint8Array; offset: number } | null>;
  };
  const slice = await (source as unknown as InternalSource)._read(
    10,
    20,
    0,
    Infinity,
  );

  // mediabunny prefetches from the start of the file for a small sequential
  // read, and the source fetches exactly that range.
  expect(calls[1]?.headers.range).toBe("bytes=0-999");
  expect(slice?.offset).toBe(10);
  expect([...(slice?.bytes ?? [])]).toEqual([
    10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
  ]);
});

test("reports a missing recording as 404", async () => {
  const { device } = fakeDevice(
    () => new Response("Not Found", { status: 404 }),
  );

  const error = await openRecordingSource(
    device,
    credentials,
    "2026/11/09/missing.mp4",
  ).catch((caught: unknown) => caught);

  expect(error).toBeInstanceOf(RecordingAccessError);
  expect((error as RecordingAccessError).status).toBe(404);
  expect((error as Error).message).toBe("Recording not found");
});

test("rejects a camera that ignores Range instead of downloading everything", async () => {
  const { device } = fakeDevice(
    () => new Response(new Uint8Array([1, 2, 3]), { status: 200 }),
  );

  const error = await openRecordingSource(
    device,
    credentials,
    "2026/11/09/clip.mp4",
  ).catch((caught: unknown) => caught);

  expect(error).toBeInstanceOf(RecordingAccessError);
  expect((error as Error).message).toContain("ranged recording playback");
  expect((error as RecordingAccessError).status).toBe(502);
});

test("rejects a range response that starts at the wrong offset", async () => {
  const total = 10_000_000;
  const { device } = fakeDevice((call) =>
    call.headers.range === "bytes=0-0"
      ? rangeResponse(0, 0, total, [0])
      : new Response(new Uint8Array([1]), {
          status: 206,
          headers: { "content-range": `bytes 0-0/${total}` },
        }),
  );
  const source = await openRecordingSource(
    device,
    credentials,
    "2026/11/09/clip.mp4",
  );

  const error = await (
    source as unknown as {
      _read(
        start: number,
        end: number,
        min: number,
        max: number,
      ): Promise<unknown>;
    }
  )
    ._read(5_000_000, 5_000_010, 0, Infinity)
    .catch((caught: unknown) => caught);

  expect(error).toBeInstanceOf(RecordingAccessError);
  expect((error as Error).message).toContain("unexpected byte range");
});
