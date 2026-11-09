import { useEffect, useState } from "react";
import type {
  CameraAuthMode,
  CameraCredentials,
  Device,
  RelayStatus,
} from "../types";
import { basicAuth, timeAgo } from "../utils";
import { LiveView } from "./LiveView";
import { RecordingsView } from "./RecordingsView";

/**
 * Owns the camera credentials for one device so live view and recordings use
 * the same username/password. The camera decides whether authentication is
 * required; this component only asks it once and shares the answer.
 */
export function CameraView({
  device,
  webrtc,
  renaming,
  removing,
  onRename,
  onRemove,
}: {
  device: Device;
  webrtc: RelayStatus["webrtc"];
  renaming: boolean;
  removing: boolean;
  onRename: () => void;
  onRemove: () => void;
}) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  // Requests only use credentials after the user presses Log in; typing must
  // not send partially entered credentials.
  const [credentials, setCredentials] = useState<CameraCredentials>({
    username: "",
    password: "",
  });
  const [loginKey, setLoginKey] = useState(0);
  const [credentialsValid, setCredentialsValid] = useState(false);
  const [authMode, setAuthMode] = useState<CameraAuthMode>("unknown");
  const [authMessage, setAuthMessage] = useState("");
  const [recordingsEmpty, setRecordingsEmpty] = useState(false);

  const logIn = () => {
    if (
      username !== credentials.username ||
      password !== credentials.password
    ) {
      setCredentialsValid(false);
    }
    setCredentials({ username, password });
    setLoginKey((value) => value + 1);
    setAuthMessage("");
  };

  // Ask the camera whether it requires credentials, and validate the committed
  // ones when authentication is enabled. Typing only changes the draft fields,
  // so this request runs on Log in, connection changes, and device reconnect.
  useEffect(() => {
    if (!device.online) return;
    let cancelled = false;
    const hasCommitted = Boolean(credentials.username || credentials.password);
    const check = async () => {
      try {
        const headers: Record<string, string> = {};
        if (hasCommitted) {
          headers.Authorization = basicAuth(
            credentials.username,
            credentials.password,
          );
        }
        const response = await fetch(`/api/webrtc/${device.id}/auth`, {
          cache: "no-store",
          headers,
        });
        const body = (await response.json().catch(() => ({}))) as {
          reason?: string;
          error?: string;
        };
        if (cancelled) return;
        if (response.ok) {
          setCredentialsValid(true);
          setAuthMode(
            body.reason === "auth-disabled" ? "disabled" : "required",
          );
          setAuthMessage("");
        } else if (response.status === 403) {
          setAuthMode("not-configured");
          setCredentialsValid(false);
          setAuthMessage(
            body.error ||
              "Authentication is enabled in the app, but no valid username and password are configured.",
          );
        } else if (response.status === 401) {
          setAuthMode("required");
          setCredentialsValid(false);
          setAuthMessage(
            hasCommitted
              ? body.error ||
                  "The camera rejected the credentials. Check the username and password above."
              : "",
          );
        } else {
          setAuthMode("unknown");
          setCredentialsValid(false);
          setAuthMessage(body.error || "");
        }
      } catch {
        if (!cancelled) {
          setAuthMode("unknown");
          setCredentialsValid(false);
        }
      }
    };
    void check();
    return () => {
      cancelled = true;
    };
  }, [
    device.id,
    device.online,
    credentials.username,
    credentials.password,
    loginKey,
  ]);

  const showRecordings =
    device.online &&
    authMode !== "not-configured" &&
    (authMode === "disabled" || credentialsValid);

  // Forget the previous answer whenever the recordings view is unavailable.
  useEffect(() => {
    if (!showRecordings) setRecordingsEmpty(false);
  }, [showRecordings]);

  return (
    <article className="space-y-6 rounded-2xl border border-border bg-card p-5 shadow-sm">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-xl font-semibold">{device.name}</h2>
          <p className="text-xs text-muted-foreground">
            Last seen {timeAgo(device.lastSeenAt)}
          </p>
        </div>
        <span
          className={`rounded-full px-3 py-1 text-xs font-semibold ${
            device.online
              ? "bg-emerald-500/15 text-emerald-600"
              : "bg-muted text-muted-foreground"
          }`}
        >
          {device.online ? "Online" : "Offline"}
        </span>
      </header>

      <LiveView
        device={device}
        iceServers={webrtc.iceServers}
        iceTransportPolicy={webrtc.iceTransportPolicy}
        advertiseHost={webrtc.advertiseHost}
        username={username}
        password={password}
        onUsernameChange={setUsername}
        onPasswordChange={setPassword}
        onLogIn={logIn}
        loggedIn={credentialsValid}
        autoStart={recordingsEmpty}
        authMode={authMode}
        authMessage={authMessage}
        onAuthModeChange={setAuthMode}
        onAuthMessageChange={setAuthMessage}
        onCredentialsInvalid={() => setCredentialsValid(false)}
      />
      {showRecordings && (
        <RecordingsView
          device={device}
          iceServers={webrtc.iceServers}
          iceTransportPolicy={webrtc.iceTransportPolicy}
          advertiseHost={webrtc.advertiseHost}
          credentials={credentials}
          loginKey={loginKey}
          authMode={authMode}
          onAuthModeChange={setAuthMode}
          onAuthMessageChange={setAuthMessage}
          onCredentialsInvalid={() => setCredentialsValid(false)}
          onRootEmptyChange={setRecordingsEmpty}
        />
      )}

      <div className="flex flex-wrap items-center justify-end gap-4 border-t border-border pt-4">
        <button
          type="button"
          onClick={onRename}
          disabled={renaming || removing}
          className="text-xs font-medium text-muted-foreground underline-offset-4 hover:text-foreground hover:underline disabled:opacity-50"
        >
          {renaming ? "Renaming…" : "Rename camera"}
        </button>
        <button
          type="button"
          onClick={onRemove}
          disabled={removing || renaming}
          className="text-xs font-medium text-muted-foreground underline-offset-4 hover:text-destructive hover:underline disabled:opacity-50"
        >
          {removing ? "Removing…" : "Remove this camera from the relay"}
        </button>
      </div>
    </article>
  );
}
