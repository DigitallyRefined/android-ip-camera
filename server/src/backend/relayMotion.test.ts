import { expect, test } from "bun:test";
import type { ServerWebSocket } from "bun";
import { DeviceConnection, type DeviceSocketData } from "./relay";

function fakeConnection(): {
  connection: DeviceConnection;
  sent: string[];
} {
  const sent: string[] = [];
  const ws = {
    data: { deviceId: "device-123", deviceName: "Front Door" },
    send: (message: string) => sent.push(message),
    close: () => {},
  } as unknown as ServerWebSocket<DeviceSocketData>;
  return { connection: new DeviceConnection(ws), sent };
}

function captureLogs(run: () => void): string[] {
  const logs: string[] = [];
  const original = console.log;
  console.log = (...args: unknown[]) => {
    logs.push(args.map(String).join(" "));
  };
  try {
    run();
  } finally {
    console.log = original;
  }
  return logs;
}

test("motion is logged for the authenticated camera and not sent back", () => {
  const { connection, sent } = fakeConnection();
  const at = 1_760_000_000_000;
  const logs = captureLogs(() => {
    connection.handleText(JSON.stringify({ type: "motion", at }));
  });

  expect(logs).toEqual([
    `Motion detected: Front Door (device-123) at ${new Date(at).toISOString()}`,
  ]);
  expect(sent).toEqual([]);
});

test("motion is passed to the notification listener", () => {
  const sent: string[] = [];
  const events: Array<{ deviceId: string; deviceName: string; at: string }> =
    [];
  const ws = {
    data: { deviceId: "device-123", deviceName: "Front Door" },
    send: (message: string) => sent.push(message),
    close: () => {},
  } as unknown as ServerWebSocket<DeviceSocketData>;
  const connection = new DeviceConnection(ws, (event) => events.push(event));
  const at = 1_760_000_000_000;

  captureLogs(() => {
    connection.handleText(JSON.stringify({ type: "motion", at }));
  });

  expect(events).toEqual([
    {
      deviceId: "device-123",
      deviceName: "Front Door",
      at: new Date(at).toISOString(),
    },
  ]);
  expect(sent).toEqual([]);
});

test("motion without a usable timestamp uses the relay clock", () => {
  const { connection } = fakeConnection();
  const logs = captureLogs(() => {
    connection.handleText(JSON.stringify({ type: "motion" }));
    connection.handleText(JSON.stringify({ type: "motion", at: "soon" }));
  });

  expect(logs).toHaveLength(2);
  expect(logs[0]).toContain("Motion detected: Front Door (device-123) at ");
  expect(logs[1]).toContain("Motion detected: Front Door (device-123) at ");
});

test("non-motion messages without a request id are ignored", () => {
  const { connection, sent } = fakeConnection();
  const logs = captureLogs(() => {
    connection.handleText(JSON.stringify({ type: "unknown" }));
    connection.handleText("not json");
  });

  expect(logs).toEqual([]);
  expect(sent).toEqual([]);
});
