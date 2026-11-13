import { expect, test } from "bun:test";
import { cameraProxyPath } from "./proxyPath";

test("cameraProxyPath keeps the files listing path and empty dir query", () => {
  const url = new URL("https://relay.example/r/device-123/files.json?dir=");

  expect(cameraProxyPath(url, "device-123")).toBe("/files.json?dir=");
});

test("cameraProxyPath preserves nested and percent-encoded file paths", () => {
  const url = new URL(
    "https://relay.example/r/device-123/files/2026/11/clip%20one.mp4?download=1",
  );

  expect(cameraProxyPath(url, "device-123")).toBe(
    "/files/2026/11/clip%20one.mp4?download=1",
  );
});

test("Bun wildcard routes forward the suffix from the request URL", async () => {
  const server = Bun.serve({
    port: 0,
    routes: {
      "/r/:deviceId/*": (request) =>
        new Response(
          cameraProxyPath(new URL(request.url), request.params.deviceId),
        ),
    },
  });

  try {
    const response = await fetch(
      new URL("/r/device-123/files.json?dir=", server.url),
    );
    expect(await response.text()).toBe("/files.json?dir=");
  } finally {
    server.stop();
  }
});
