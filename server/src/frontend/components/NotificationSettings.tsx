import { useEffect, useState, type SubmitEvent } from "react";
import { NOTIFICATION_URLS_MAX_LENGTH } from "@/shared/notifications";
import { requestJson } from "../api";
import type { Device } from "../types";

const EXAMPLES = [
  "slack://TOKEN_A/TOKEN_B/TOKEN_C",
  "tgram://BOT_TOKEN/CHAT_ID",
  "mailtos://_?user=username@example.com&pass=complex-password&smtp=smtp.email.example.com&from=noreply@example.com&to=me@example.com",
];

export function NotificationSettings({ device }: { device: Device }) {
  const [open, setOpen] = useState(false);

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="text-xs font-medium text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
      >
        Notification settings
      </button>
      {open && (
        <NotificationSettingsDialog
          device={device}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  );
}

function NotificationSettingsDialog({
  device,
  onClose,
}: {
  device: Device;
  onClose: () => void;
}) {
  const [notificationUrls, setNotificationUrls] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<
    { ok: true } | { ok: false; message: string } | null
  >(null);

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [onClose]);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const settings = await requestJson<{ notificationUrls?: string }>(
          `/api/devices/${device.id}/notifications`,
        );
        if (!cancelled) setNotificationUrls(settings.notificationUrls ?? "");
      } catch (cause) {
        if (!cancelled)
          setError(cause instanceof Error ? cause.message : String(cause));
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void load();
    return () => {
      cancelled = true;
    };
  }, [device.id]);

  const test = async () => {
    setTesting(true);
    setError(null);
    setTestResult(null);
    try {
      await requestJson<{ ok: true }>(
        `/api/devices/${device.id}/notifications/test`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ notificationUrls }),
        },
      );
      setTestResult({ ok: true });
    } catch (cause) {
      setTestResult({
        ok: false,
        message: cause instanceof Error ? cause.message : String(cause),
      });
    } finally {
      setTesting(false);
    }
  };

  const save = async (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await requestJson(`/api/devices/${device.id}/notifications`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ notificationUrls }),
      });
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      role="presentation"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="notification-settings-title"
        className="max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-2xl border border-border bg-card p-6 shadow-xl"
        onClick={(event) => event.stopPropagation()}
      >
        <form onSubmit={save} className="space-y-5">
          <div className="space-y-1">
            <h2
              id="notification-settings-title"
              className="text-xl font-semibold"
            >
              Notification settings
            </h2>
            <p className="text-sm text-muted-foreground">
              Motion notifications for {device.name}.
            </p>
          </div>

          <div className="space-y-2">
            <label
              htmlFor="notification-urls"
              className="block text-sm font-medium"
            >
              Apprise notification URLs
            </label>
            <p className="text-xs text-muted-foreground">
              Enter one URL per line. Each line is sent as a separate
              notification when the camera reports motion. Leave the field empty
              to disable notifications.
            </p>
            <textarea
              id="notification-urls"
              value={notificationUrls}
              onChange={(event) => {
                setNotificationUrls(event.target.value);
                setTestResult(null);
              }}
              maxLength={NOTIFICATION_URLS_MAX_LENGTH}
              rows={7}
              disabled={loading || saving}
              spellCheck={false}
              autoCapitalize="off"
              autoCorrect="off"
              placeholder={EXAMPLES.join("\n")}
              className="w-full resize-y rounded-lg border border-border bg-background px-3 py-2 font-mono text-xs text-foreground disabled:opacity-60"
            />
          </div>

          <div className="space-y-2 rounded-lg border border-border bg-muted/40 p-4">
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              Examples
            </p>
            <pre className="overflow-x-auto whitespace-pre-wrap break-all font-mono text-xs text-foreground">
              {EXAMPLES.join("\n")}
            </pre>
            <a
              href="https://appriseit.com/services/"
              target="_blank"
              rel="noreferrer"
              className="text-xs font-medium text-primary underline underline-offset-4"
            >
              Apprise notification service documentation
            </a>
          </div>

          {error && (
            <div className="rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
              {error}
            </div>
          )}

          {testResult && (
            <div
              role={testResult.ok ? "status" : "alert"}
              className={`rounded-lg border px-4 py-3 text-sm ${
                testResult.ok
                  ? "border-emerald-500/30 bg-emerald-500/10 text-emerald-600"
                  : "border-destructive/30 bg-destructive/10 text-destructive"
              }`}
            >
              <p className="font-medium">
                {testResult.ok
                  ? "Test notification sent"
                  : "Test notification failed"}
              </p>
              {!testResult.ok && (
                <p className="mt-1 whitespace-pre-wrap break-words">
                  {testResult.message}
                </p>
              )}
            </div>
          )}

          <div className="flex flex-wrap items-center justify-end gap-3 border-t border-border pt-4">
            <button
              type="button"
              onClick={() => void test()}
              disabled={
                loading ||
                saving ||
                testing ||
                notificationUrls.trim().length === 0
              }
              className="mr-auto rounded-lg border border-border px-4 py-2 text-sm disabled:opacity-50"
            >
              {testing ? "Sending test…" : "Test"}
            </button>
            <button
              type="button"
              onClick={onClose}
              disabled={saving || testing}
              className="rounded-lg border border-border px-4 py-2 text-sm"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={loading || saving || testing}
              className="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground disabled:opacity-50"
            >
              {saving ? "Saving…" : "Save notifications"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
