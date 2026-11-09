import { useEffect, useRef, useState } from "react";
import type { CameraAuthMode, Device, RelayStatus } from "../types";
import { basicAuth, waitForIceGathering } from "../utils";

export function LiveView({
  device,
  iceServers,
  iceTransportPolicy,
  advertiseHost,
  username,
  password,
  onUsernameChange,
  onPasswordChange,
  onLogIn,
  loggedIn,
  autoStart,
  authMode,
  authMessage,
  onAuthModeChange,
  onAuthMessageChange,
  onCredentialsInvalid,
}: {
  device: Device;
  iceServers: RelayStatus["webrtc"]["iceServers"];
  iceTransportPolicy: string;
  advertiseHost: string | null;
  username: string;
  password: string;
  onUsernameChange: (value: string) => void;
  onPasswordChange: (value: string) => void;
  onLogIn: () => void;
  loggedIn: boolean;
  autoStart: boolean;
  authMode: CameraAuthMode;
  authMessage: string;
  onAuthModeChange: (mode: CameraAuthMode) => void;
  onAuthMessageChange: (message: string) => void;
  onCredentialsInvalid: () => void;
}) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const peerRef = useRef<RTCPeerConnection | null>(null);
  const autoStartedRef = useRef(false);
  const [state, setState] = useState<"idle" | "connecting" | "live" | "error">(
    "idle",
  );
  const [message, setMessage] = useState<string>("");
  const missingRequiredCredentials =
    authMode === "required" && (!username || !password);
  const showLogin =
    authMode !== "disabled" && authMode !== "not-configured" && !loggedIn;
  const loginDisabled = missingRequiredCredentials || (!username && !password);

  const disconnect = () => {
    peerRef.current?.close();
    peerRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
    setState("idle");
    setMessage("");
  };

  useEffect(() => () => disconnect(), []);

  // Stop streaming when the page is no longer visible (tab switch, minimize,
  // or screen lock) so the relay and camera do not keep sending video.
  useEffect(() => {
    const onVisibilityChange = () => {
      if (document.visibilityState === "hidden") disconnect();
    };
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () =>
      document.removeEventListener("visibilitychange", onVisibilityChange);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The camera's WebRTC peer can take ~30s to notice that the relay closed the
  // connection (ICE consent timeout). The 5s status poll is faster, so react to
  // the device going offline here instead of leaving a frozen frame on screen.
  useEffect(() => {
    if (!device.online && (state === "live" || state === "connecting")) {
      disconnect();
      setState("error");
      setMessage(
        "The camera went offline. It will try to reconnect automatically.",
      );
    }
  }, [device.online, state]);

  const connect = async () => {
    disconnect();
    setState("connecting");
    setMessage("Negotiating WebRTC…");
    const pc = new RTCPeerConnection({
      iceServers: iceServers.map(({ urls, username, credential }) => ({
        urls,
        ...(username ? { username } : {}),
        ...(credential ? { credential } : {}),
      })),
      ...(iceTransportPolicy === "relay"
        ? { iceTransportPolicy: "relay" as RTCIceTransportPolicy }
        : {}),
    });
    peerRef.current = pc;

    pc.ontrack = (event) => {
      const stream = event.streams[0] ?? new MediaStream([event.track]);
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        void videoRef.current.play().catch(() => {});
      }
    };
    pc.onconnectionstatechange = () => {
      if (pc !== peerRef.current) return;
      if (pc.connectionState === "connected") {
        setState("live");
        setMessage("Live");
      } else if (pc.connectionState === "failed") {
        setState("error");
        setMessage(
          `WebRTC failed. Check that the relay's UDP ports are reachable at ${advertiseHost ?? "the relay"}.`,
        );
      } else if (pc.connectionState === "disconnected") {
        setState("error");
        setMessage("Connection lost. Try reconnecting.");
      }
    };

    try {
      pc.addTransceiver("video", { direction: "recvonly" });
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      await waitForIceGathering(pc);

      const headers: Record<string, string> = {
        "Content-Type": "application/json",
      };
      // Send the same Basic credentials the app's own HTTP server expects.
      if (username || password) {
        headers.Authorization = basicAuth(username, password);
      }

      const response = await fetch(`/api/webrtc/${device.id}/offer`, {
        method: "POST",
        cache: "no-store",
        headers,
        body: JSON.stringify({
          type: "offer",
          sdp: pc.localDescription?.sdp,
        }),
      });
      const body = (await response.json().catch(() => ({}))) as {
        type?: RTCSdpType;
        sdp?: string;
        reason?: string;
        error?: string;
      };
      if (!response.ok) {
        if (body.reason === "not-configured") {
          onAuthModeChange("not-configured");
          onAuthMessageChange(body.error || "");
          onCredentialsInvalid();
        } else if (
          body.reason === "missing-credentials" ||
          body.reason === "invalid-credentials"
        ) {
          onAuthModeChange("required");
          onAuthMessageChange(body.error || "");
          onCredentialsInvalid();
        }
        throw new Error(
          body.error || `${response.status} ${response.statusText}`,
        );
      }
      if (!body.type || !body.sdp) {
        throw new Error("The relay returned an invalid WebRTC answer");
      }
      await pc.setRemoteDescription({ type: body.type, sdp: body.sdp });
      onAuthMessageChange("");
      if (authMode === "not-configured") {
        onAuthModeChange(username || password ? "required" : "disabled");
      } else if (!username && !password) {
        onAuthModeChange("disabled");
      }
    } catch (cause) {
      // Keep the error state visible: disconnect() would return to idle and
      // hide the only place where the failure is reported.
      peerRef.current?.close();
      peerRef.current = null;
      if (videoRef.current) videoRef.current.srcObject = null;
      setState("error");
      setMessage(cause instanceof Error ? cause.message : String(cause));
    }
  };

  // When the camera has no recordings, start the live view automatically once
  // the user is authenticated (or authentication is disabled).
  useEffect(() => {
    if (!autoStart || autoStartedRef.current) return;
    if (!device.online || showLogin) return;
    autoStartedRef.current = true;
    // A manual connection attempt already covers this case; do not restart it
    // later if the user presses Stop.
    if (state !== "idle") return;
    void connect();
    // connect is intentionally not a dependency; the guards above make this
    // run at most once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoStart, device.online, showLogin, state]);

  return (
    <section className="space-y-4">
      <h3 className="text-base font-semibold">Live view</h3>

      {state !== "idle" && (
        <div className="relative overflow-hidden rounded-xl bg-black">
          <video
            ref={videoRef}
            muted
            playsInline
            autoPlay
            className="aspect-video w-full object-contain"
          />
          {state !== "live" && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-black/65 px-6 text-center text-sm text-white">
              <span className="font-medium">
                {state === "connecting"
                  ? "Connecting…"
                  : "Live view unavailable"}
              </span>
              <span className="max-w-md text-xs text-white/70">{message}</span>
            </div>
          )}
        </div>
      )}

      {device.online ? (
        <form
          className="space-y-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (showLogin && loginDisabled) return;
            // Drop keyboard focus so the field stops showing a caret and the
            // mobile keyboard closes when logging in or starting the stream.
            const focused = event.currentTarget.querySelector("input:focus");
            if (focused instanceof HTMLElement) focused.blur();
            if (showLogin) {
              onLogIn();
              return;
            }
            void connect();
          }}
        >
          {authMessage && (
            <div className="rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
              {authMessage}
            </div>
          )}

          {showLogin && (
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="space-y-1 text-xs font-medium text-muted-foreground">
                <span>Username</span>
                <input
                  value={username}
                  onChange={(event) => onUsernameChange(event.target.value)}
                  autoComplete="username"
                  placeholder="Camera username"
                  className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground"
                />
              </label>
              <label className="space-y-1 text-xs font-medium text-muted-foreground">
                <span>Password</span>
                <input
                  type="password"
                  value={password}
                  onChange={(event) => onPasswordChange(event.target.value)}
                  autoComplete="current-password"
                  placeholder="Camera password"
                  className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground"
                />
              </label>
              <p className="text-xs text-muted-foreground sm:col-span-2">
                Use the username and password from the camera's app settings.
              </p>
            </div>
          )}

          {authMode === "disabled" && (
            <p className="text-xs text-muted-foreground">
              Authentication is disabled in the app, so no credentials are
              required.
            </p>
          )}

          <div className="flex flex-wrap gap-2">
            <button
              type="submit"
              disabled={showLogin ? loginDisabled : missingRequiredCredentials}
              className={`${showLogin || state !== "live" ? "rounded-lg bg-primary  px-4 py-2 text-sm font-semibold text-primary-foreground transition hover:opacity-90" : "rounded-lg border border-border px-4 py-2 text-sm"} disabled:cursor-not-allowed disabled:opacity-50`}
            >
              {showLogin
                ? "Log in"
                : state === "live"
                  ? "Reconnect"
                  : "Start live view"}
            </button>
            {state === "live" && (
              <button
                type="button"
                onClick={disconnect}
                className="rounded-lg border border-border px-4 py-2 text-sm"
              >
                Stop
              </button>
            )}
          </div>
        </form>
      ) : (
        <p className="text-sm text-muted-foreground">
          The camera is offline. Live view will be available when it reconnects.
        </p>
      )}
    </section>
  );
}
