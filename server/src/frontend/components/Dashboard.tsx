import { useState } from "react";
import { requestJson } from "../api";
import type { Device, RelayStatus } from "../types";
import { LiveView } from "./LiveView";

export function Dashboard({
  status,
  refresh,
  onPairAnotherCamera,
}: {
  status: RelayStatus;
  refresh: () => void;
  onPairAnotherCamera: () => void;
}) {
  const [removing, setRemoving] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const removeDevice = async (device: Device) => {
    if (!confirm(`Remove ${device.name} from this relay?`)) return;
    setRemoving(device.id);
    setError(null);
    try {
      await requestJson(`/api/devices/${device.id}`, { method: "DELETE" });
      refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setRemoving(null);
    }
  };

  return (
    <main className="mx-auto w-full max-w-6xl space-y-8 px-6 py-10">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div className="space-y-2">
          <h1 className="flex items-start gap-2 text-4xl font-bold tracking-tight sm:text-5xl">
            <span>Android IP Camera Relay</span>
            <span className="inline-flex items-center rounded-full border border-border bg-card px-3 py-1 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Beta
            </span>
          </h1>
          <p className="text-sm text-muted-foreground">
            Traffic is relayed in temporary memory over the camera's outbound
            WebSocket. Streams are not stored.
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={onPairAnotherCamera}
            className="rounded-lg border border-border bg-card px-4 py-2 text-sm font-medium"
          >
            Pair another camera
          </button>
        </div>
      </header>

      {error && (
        <div className="rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {error}
        </div>
      )}

      {status.devices.map((device) => (
        <section key={device.id} className="space-y-3">
          <LiveView
            device={device}
            iceServers={status.webrtc.iceServers}
            iceTransportPolicy={status.webrtc.iceTransportPolicy}
            advertiseHost={status.webrtc.advertiseHost}
          />
          <div className="flex justify-end">
            <button
              type="button"
              onClick={() => void removeDevice(device)}
              disabled={removing === device.id}
              className="text-xs font-medium text-muted-foreground underline-offset-4 hover:text-destructive hover:underline disabled:opacity-50"
            >
              {removing === device.id
                ? "Removing…"
                : "Remove this camera from the relay"}
            </button>
          </div>
        </section>
      ))}
    </main>
  );
}
