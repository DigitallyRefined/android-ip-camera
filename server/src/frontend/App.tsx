import "./index.css";
import { useEffect, useState } from "react";
import { requestJson } from "./api";
import { AssociatePage } from "./components/AssociatePage";
import { Dashboard } from "./components/Dashboard";
import type { RelayStatus } from "./types";

export function App() {
  const [status, setStatus] = useState<RelayStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  // Non-null while the onboarding screen was opened from the dashboard. It
  // holds the devices that already existed, so a newly paired device ends the
  // pairing flow automatically.
  const [pairingDeviceIds, setPairingDeviceIds] = useState<string[] | null>(
    null,
  );

  const refresh = () => setReloadKey((key) => key + 1);

  const startPairing = () => {
    setPairingDeviceIds(status?.devices.map((device) => device.id) ?? []);
  };

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const next = await requestJson<RelayStatus>("/api/status");
        if (!cancelled) {
          setStatus(next);
          setError(null);
        }
      } catch (cause) {
        if (!cancelled)
          setError(cause instanceof Error ? cause.message : String(cause));
      }
    };
    void load();
    const timer = setInterval(load, 5000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [reloadKey]);

  // Leave the onboarding screen once a new camera has paired.
  useEffect(() => {
    if (!pairingDeviceIds || !status) return;
    if (
      status.devices.some((device) => !pairingDeviceIds.includes(device.id))
    ) {
      setPairingDeviceIds(null);
    }
  }, [pairingDeviceIds, status]);

  if (error && !status) {
    return (
      <main className="mx-auto flex min-h-screen max-w-xl flex-col justify-center gap-4 px-6">
        <h1 className="text-2xl font-bold">Relay unavailable</h1>
        <p className="text-sm text-destructive">{error}</p>
        <button
          type="button"
          onClick={refresh}
          className="w-fit rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground"
        >
          Retry
        </button>
      </main>
    );
  }

  if (!status) {
    return (
      <main className="flex min-h-screen items-center justify-center text-sm text-muted-foreground">
        Loading relay…
      </main>
    );
  }

  const pairing = pairingDeviceIds !== null;
  if (pairing || !status.associated) {
    return (
      <AssociatePage
        addingAnother={pairing}
        onCancel={pairing ? () => setPairingDeviceIds(null) : undefined}
      />
    );
  }

  return (
    <Dashboard
      status={status}
      refresh={refresh}
      onPairAnotherCamera={startPairing}
    />
  );
}

export default App;
