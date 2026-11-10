import { useEffect, useRef, useState } from "react";
import type { Device, RelayStatus } from "../types";
import { basicAuth, timeAgo } from "../utils";

export function LiveView({
  device,
  iceServers,
  iceTransportPolicy,
  advertiseHost,
}: {
  device: Device;
  iceServers: RelayStatus["webrtc"]["iceServers"];
  iceTransportPolicy: string;
  advertiseHost: string | null;
}) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const peerRef = useRef<RTCPeerConnection | null>(null);
  const [state, setState] = useState<"idle" | "connecting" | "live" | "error">(
    "idle",
  );
  const [message, setMessage] = useState<string>("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [authMode, setAuthMode] = useState<
    "unknown" | "required" | "disabled" | "not-configured"
  >("unknown");
  const [authMessage, setAuthMessage] = useState("");

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

  // Ask the camera whether it requires credentials. The answer comes from the
  // app's own settings, so passwordless viewing works whenever authentication
  // is disabled there.
  useEffect(() => {
    if (!device.online) return;
    let cancelled = false;
    const check = async () => {
      try {
        const response = await fetch(`/api/webrtc/${device.id}/auth`, {
          cache: "no-store",
        });
        const body = (await response.json().catch(() => ({}))) as {
          reason?: string;
          error?: string;
        };
        if (cancelled) return;
        if (response.ok && body.reason === "auth-disabled") {
          setAuthMode("disabled");
          setAuthMessage("");
        } else if (response.status === 403) {
          setAuthMode("not-configured");
          setAuthMessage(
            body.error ||
              "Authentication is enabled in the app, but no valid username and password are configured.",
          );
        } else if (response.status === 401) {
          setAuthMode("required");
          setAuthMessage("");
        } else {
          setAuthMode("unknown");
          setAuthMessage(body.error || "");
        }
      } catch {
        if (!cancelled) setAuthMode("unknown");
      }
    };
    void check();
    return () => {
      cancelled = true;
    };
  }, [device.id, device.online]);

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
      await new Promise<void>((resolve) => {
        if (pc.iceGatheringState === "complete") return resolve();
        const timer = setTimeout(resolve, 4000);
        pc.addEventListener("icegatheringstatechange", () => {
          if (pc.iceGatheringState === "complete") {
            clearTimeout(timer);
            resolve();
          }
        });
      });

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
          setAuthMode("not-configured");
          setAuthMessage(body.error || "");
        } else if (
          body.reason === "missing-credentials" ||
          body.reason === "invalid-credentials"
        ) {
          setAuthMode("required");
        }
        throw new Error(
          body.error || `${response.status} ${response.statusText}`,
        );
      }
      if (!body.type || !body.sdp) {
        throw new Error("The relay returned an invalid WebRTC answer");
      }
      await pc.setRemoteDescription({ type: body.type, sdp: body.sdp });
      setAuthMessage("");
      if (authMode === "not-configured") {
        setAuthMode(username || password ? "required" : "disabled");
      } else if (!username && !password) {
        setAuthMode("disabled");
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

  return (
    <article className="space-y-4 rounded-2xl border border-border bg-card p-5 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-lg font-semibold">{device.name}</h3>
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
      </div>

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
            // Drop keyboard focus so the field stops showing a caret and the
            // mobile keyboard closes when the stream starts.
            const focused = event.currentTarget.querySelector("input:focus");
            if (focused instanceof HTMLElement) focused.blur();
            void connect();
          }}
        >
          {authMessage && (
            <div className="rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
              {authMessage}
            </div>
          )}

          {authMode !== "disabled" &&
            authMode !== "not-configured" &&
            state !== "live" && (
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="space-y-1 text-xs font-medium text-muted-foreground">
                  <span>Username</span>
                  <input
                    value={username}
                    onChange={(event) => setUsername(event.target.value)}
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
                    onChange={(event) => setPassword(event.target.value)}
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
              className={`${state !== "live" ? "rounded-lg bg-primary  px-4 py-2 text-sm font-semibold text-primary-foreground transition hover:opacity-90" : "rounded-lg border border-border px-4 py-2 text-sm"}`}
            >
              {state === "live" ? "Reconnect" : "Start live view"}
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
    </article>
  );
}
