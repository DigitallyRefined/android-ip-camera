import { QRCodeSVG } from "qrcode.react";
import { useEffect, useState } from "react";
import { requestJson } from "../api";
import type { PairingLink } from "../types";

export function AssociatePage({
  addingAnother = false,
  onCancel,
}: {
  addingAnother?: boolean;
  onCancel?: () => void;
}) {
  const [pairing, setPairing] = useState<PairingLink | null>(null);
  const [error, setError] = useState<string | null>(null);

  const createPairing = async () => {
    setError(null);
    try {
      setPairing(
        await requestJson<PairingLink>("/api/pairing", { method: "POST" }),
      );
    } catch (cause) {
      setPairing(null);
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  };

  useEffect(() => {
    void createPairing();
    // The code is short-lived and this page has no manual refresh control, so
    // rotate it automatically before it expires.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!pairing) return;
    // Rotate shortly before expiry so the QR code on screen always works.
    const delay = Math.max(0, pairing.expiresAt - Date.now() - 30_000);
    const timer = setTimeout(() => void createPairing(), delay);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pairing?.expiresAt]);

  const remaining = pairing
    ? Math.max(0, Math.ceil((pairing.expiresAt - Date.now()) / 60000))
    : 0;

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-3xl flex-col justify-center gap-8 px-6 py-12">
      <header className="space-y-3">
        <h1 className="text-4xl font-bold tracking-tight sm:text-5xl">
          {addingAnother ? "Add another camera" : "Connect your camera"}
        </h1>
        <p className="max-w-2xl text-base leading-7 text-muted-foreground">
          {addingAnother
            ? "Pair another Android device with this relay."
            : "This relay has no camera associated yet."}{" "}
          <span className="hidden sm:inline">
            Scan the QR code below with the Android device that runs Android IP
            Camera to pair it with this relay.
          </span>
          <span className="sm:hidden">
            Tap the association link below on the Android device that runs
            Android IP Camera to pair it with this relay.
          </span>
        </p>
        {onCancel && (
          <button
            type="button"
            onClick={onCancel}
            className="rounded-lg border border-border bg-card px-4 py-2 text-sm font-medium"
          >
            Back to live view
          </button>
        )}
      </header>

      <section className="grid gap-5 rounded-2xl border border-border bg-card p-6 shadow-sm">
        {error && (
          <div className="rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
            {error}
          </div>
        )}

        {pairing ? (
          <div className="flex flex-col items-center gap-4">
            <div className="hidden flex-col items-center gap-4 sm:flex">
              <div className="rounded-2xl bg-white p-4 shadow-sm">
                <QRCodeSVG
                  value={pairing.link}
                  size={240}
                  level="M"
                  marginSize={2}
                  title="Scan to associate the Android camera"
                />
              </div>
              <p className="text-center text-sm text-muted-foreground">
                Scan with your <strong>default</strong> camera app or QR
                scanner. This code is valid for about {remaining} more minute
                {remaining === 1 ? "" : "s"}.
              </p>
            </div>
            <a
              href={pairing.link}
              className="sm:hidden rounded-xl bg-emerald-600 px-5 py-3 text-center text-sm font-semibold text-white shadow-sm transition hover:bg-emerald-500"
            >
              Tap to associate with this relay server
            </a>
          </div>
        ) : (
          !error && (
            <p className="text-sm text-muted-foreground">
              <span className="hidden sm:inline">
                Generating a pairing QR code…
              </span>
              <span className="sm:hidden">Preparing the association link…</span>
            </p>
          )
        )}

        <ol className="list-decimal space-y-2 pl-5 text-sm text-muted-foreground">
          <li>
            <span className="hidden sm:inline">
              Open your <strong>default</strong> camera app or QR scanner app
              and tap or share the QR code to the app to associate this device.
            </span>
            <span className="sm:hidden">
              Tap the link above on this device to associate it.
            </span>
          </li>
          <li>
            The app stores a private device token and connects outbound to this
            relay.
          </li>
          <li>
            Return to this page. It will switch to the live view automatically.
          </li>
        </ol>
      </section>
    </main>
  );
}
