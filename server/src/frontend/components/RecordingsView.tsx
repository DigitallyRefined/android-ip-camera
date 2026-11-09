import { ChevronRight, Folder, Play, RefreshCw, Trash2 } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import type {
  CameraAuthMode,
  CameraCredentials,
  Device,
  RecordingEntry,
  RecordingListing,
  RelayStatus,
} from "../types";
import {
  basicAuth,
  formatBytes,
  formatDateTime,
  formatDuration,
  waitForIceGathering,
} from "../utils";

type PlaybackState =
  "idle" | "loading" | "seeking" | "connecting" | "playing" | "ended" | "error";

function playbackTitleFor(state: PlaybackState): string {
  if (state === "loading") return "Loading recording…";
  if (state === "seeking") return "Seeking…";
  if (state === "connecting") return "Connecting…";
  if (state === "ended") return "Playback finished";
  return "Playback unavailable";
}

export function RecordingsView({
  device,
  iceServers,
  iceTransportPolicy,
  advertiseHost,
  credentials,
  loginKey,
  authMode,
  onAuthModeChange,
  onAuthMessageChange,
  onCredentialsInvalid,
  onRootEmptyChange,
}: {
  device: Device;
  iceServers: RelayStatus["webrtc"]["iceServers"];
  iceTransportPolicy: string;
  advertiseHost: string | null;
  credentials: CameraCredentials;
  loginKey: number;
  authMode: CameraAuthMode;
  onAuthModeChange: (mode: CameraAuthMode) => void;
  onAuthMessageChange: (message: string) => void;
  onCredentialsInvalid: () => void;
  onRootEmptyChange: (empty: boolean) => void;
}) {
  const [path, setPath] = useState("");
  const [folderLabel, setFolderLabel] = useState("Recordings");
  const [listing, setListing] = useState<RecordingListing | null>(null);
  const [listingState, setListingState] = useState<
    "idle" | "loading" | "error"
  >("idle");
  const [listingError, setListingError] = useState("");
  const [actionError, setActionError] = useState("");
  const [deletingPath, setDeletingPath] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [playbackPath, setPlaybackPath] = useState<string | null>(null);
  const [playbackState, setPlaybackState] = useState<PlaybackState>("idle");
  const [playbackMessage, setPlaybackMessage] = useState("");
  const [duration, setDuration] = useState(0);
  const [playbackStartTime, setPlaybackStartTime] = useState(0);
  const [currentTime, setCurrentTime] = useState(0);
  const [seekPreview, setSeekPreview] = useState<number | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const peerRef = useRef<RTCPeerConnection | null>(null);
  const playbackAbortRef = useRef<AbortController | null>(null);
  const connectedRef = useRef(false);
  const connectedAtRef = useRef(0);
  const durationRef = useRef(0);
  const seekPreviewRef = useRef<number | null>(null);

  const hasCredentials = Boolean(credentials.username || credentials.password);
  const canBrowse =
    device.online &&
    authMode !== "not-configured" &&
    (authMode !== "required" || hasCredentials);

  const closePeer = useCallback(() => {
    playbackAbortRef.current?.abort();
    playbackAbortRef.current = null;
    connectedRef.current = false;
    connectedAtRef.current = 0;
    peerRef.current?.close();
    peerRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
  }, []);

  const stopPlayback = useCallback(() => {
    closePeer();
    durationRef.current = 0;
    seekPreviewRef.current = null;
    setPlaybackPath(null);
    setPlaybackState("idle");
    setPlaybackMessage("");
    setDuration(0);
    setPlaybackStartTime(0);
    setCurrentTime(0);
    setSeekPreview(null);
  }, [closePeer]);

  useEffect(() => () => stopPlayback(), [stopPlayback]);

  // The relay paces packets in real time, so a local clock is enough to drive
  // the scrub bar between seeks.
  useEffect(() => {
    if (playbackState !== "playing") return;
    const update = () => {
      const elapsed = (performance.now() - connectedAtRef.current) / 1000;
      const limit = durationRef.current;
      const next =
        limit > 0
          ? Math.min(playbackStartTime + elapsed, limit)
          : playbackStartTime + elapsed;
      setCurrentTime(next);
    };
    update();
    const timer = window.setInterval(update, 250);
    return () => window.clearInterval(timer);
  }, [playbackState, playbackStartTime]);

  // Load the current folder. The relay proxies the camera's own files.json
  // endpoint, so credentials never leave this request path.
  useEffect(() => {
    if (!canBrowse) {
      setListing(null);
      setListingState("idle");
      return;
    }

    let cancelled = false;
    const controller = new AbortController();
    setListingState("loading");
    setListingError("");

    const load = async () => {
      try {
        const headers: Record<string, string> = {};
        if (hasCredentials) {
          headers.Authorization = basicAuth(
            credentials.username,
            credentials.password,
          );
        }
        const response = await fetch(
          `/r/${device.id}/files.json?dir=${encodeURIComponent(path)}`,
          { cache: "no-store", headers, signal: controller.signal },
        );
        if (!response.ok) {
          if (response.status === 401) {
            onAuthModeChange("required");
            onAuthMessageChange(
              "The camera rejected the credentials. Check the username and password above.",
            );
            onCredentialsInvalid();
          } else if (response.status === 403) {
            onAuthModeChange("not-configured");
            onCredentialsInvalid();
          }
          throw new Error(
            response.status === 503
              ? "The camera is offline."
              : `The camera returned ${response.status} ${response.statusText}`,
          );
        }
        const body = (await response.json()) as RecordingListing;
        if (cancelled) return;
        setListing(body);
        setFolderLabel(
          body.folder === "Movies/AndroidIPCamera"
            ? "Home"
            : body.folder || "Home",
        );
        setListingState("idle");
        onAuthMessageChange("");
      } catch (cause) {
        if (cancelled || controller.signal.aborted) return;
        setListing(null);
        setListingState("error");
        setListingError(cause instanceof Error ? cause.message : String(cause));
      }
    };

    void load();
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [
    canBrowse,
    device.id,
    path,
    credentials.username,
    credentials.password,
    hasCredentials,
    loginKey,
    reloadKey,
    onAuthModeChange,
    onAuthMessageChange,
  ]);

  // Tell the parent when the root has no recordings so it can start the live
  // view instead of leaving an empty page.
  useEffect(() => {
    if (path !== "" || listingState !== "idle" || listing === null) {
      onRootEmptyChange(false);
      return;
    }
    onRootEmptyChange(listing.entries.length === 0);
  }, [path, listingState, listing, onRootEmptyChange]);

  const startPlayback = async (recordingPath: string, startTime = 0) => {
    closePeer();
    setPlaybackPath(recordingPath);
    setPlaybackState(startTime > 0 ? "seeking" : "loading");
    setPlaybackMessage("");
    setCurrentTime(startTime);
    seekPreviewRef.current = null;
    setSeekPreview(null);

    const controller = new AbortController();
    playbackAbortRef.current = controller;
    const pc = new RTCPeerConnection({
      iceServers: iceServers.map(({ urls, username: user, credential }) => ({
        urls,
        ...(user ? { username: user } : {}),
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
      const state = pc.connectionState;
      if (state === "connected") {
        connectedRef.current = true;
        connectedAtRef.current = performance.now();
        setPlaybackState("playing");
        setPlaybackMessage("");
        return;
      }
      if (state === "failed") {
        connectedRef.current = false;
        setPlaybackState("error");
        setPlaybackMessage(
          `WebRTC failed. Check that the relay's UDP ports are reachable at ${advertiseHost ?? "the relay"}.`,
        );
        return;
      }
      if (state === "disconnected" || state === "closed") {
        // The relay closes the peer shortly after the last frame.
        if (connectedRef.current) {
          connectedRef.current = false;
          if (durationRef.current > 0) {
            setCurrentTime(durationRef.current);
          }
          setPlaybackState("ended");
          setPlaybackMessage("");
        } else {
          setPlaybackState("error");
          setPlaybackMessage("The connection closed before playback started.");
        }
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
      if (hasCredentials) {
        headers.Authorization = basicAuth(
          credentials.username,
          credentials.password,
        );
      }

      const response = await fetch(`/api/webrtc/${device.id}/recording/offer`, {
        method: "POST",
        cache: "no-store",
        headers,
        signal: controller.signal,
        body: JSON.stringify({
          type: "offer",
          sdp: pc.localDescription?.sdp,
          path: recordingPath,
          startTime,
        }),
      });
      const body = (await response.json().catch(() => ({}))) as {
        type?: RTCSdpType;
        sdp?: string;
        duration?: number;
        startTime?: number;
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
      if (pc !== peerRef.current) return;

      const answerDuration =
        typeof body.duration === "number" && Number.isFinite(body.duration)
          ? Math.max(0, body.duration)
          : 0;
      const answerStartTime =
        typeof body.startTime === "number" && Number.isFinite(body.startTime)
          ? Math.max(0, body.startTime)
          : startTime;
      durationRef.current = answerDuration;
      setDuration(answerDuration);
      setPlaybackStartTime(answerStartTime);
      setCurrentTime(answerStartTime);

      await pc.setRemoteDescription({ type: body.type, sdp: body.sdp });
      onAuthMessageChange("");
      setPlaybackState("connecting");
      setPlaybackMessage("");
    } catch (cause) {
      if (controller.signal.aborted || pc !== peerRef.current) return;
      pc.close();
      peerRef.current = null;
      if (videoRef.current) videoRef.current.srcObject = null;
      setPlaybackState("error");
      setPlaybackMessage(
        cause instanceof Error ? cause.message : String(cause),
      );
    }
  };

  const play = async (entry: RecordingEntry) => {
    const recordingPath = path ? `${path}/${entry.name}` : entry.name;
    stopPlayback();
    await startPlayback(recordingPath, 0);
  };

  const seekTo = (target: number) => {
    if (!playbackPath) return;
    const limit = duration > 0 ? duration : target;
    const clamped = Math.max(0, Math.min(target, limit));
    if (
      (playbackState === "playing" ||
        playbackState === "seeking" ||
        playbackState === "connecting") &&
      Math.abs(clamped - currentTime) < 0.5
    ) {
      return;
    }
    void startPlayback(playbackPath, clamped);
  };

  const previewSeek = (value: number) => {
    seekPreviewRef.current = value;
    setSeekPreview(value);
  };

  const commitSeek = () => {
    const target = seekPreviewRef.current;
    seekPreviewRef.current = null;
    setSeekPreview(null);
    if (target === null) return;
    seekTo(target);
  };

  const cancelSeek = () => {
    seekPreviewRef.current = null;
    setSeekPreview(null);
  };

  const navigate = (nextPath: string) => {
    stopPlayback();
    setActionError("");
    setPath(nextPath);
  };

  const deleteEntry = async (entry: RecordingEntry, entryPath: string) => {
    const kind = entry.isDirectory ? "folder" : "recording";
    const contents = entry.isDirectory ? " and everything inside it" : "";
    if (
      !window.confirm(
        `Delete ${kind} “${entry.name}”${contents}? This cannot be undone.`,
      )
    ) {
      return;
    }

    setDeletingPath(entryPath);
    setActionError("");
    try {
      const headers: Record<string, string> = {};
      if (hasCredentials) {
        headers.Authorization = basicAuth(
          credentials.username,
          credentials.password,
        );
      }
      const encodedPath = entryPath
        .split("/")
        .map(encodeURIComponent)
        .join("/");
      const response = await fetch(`/r/${device.id}/files/${encodedPath}`, {
        method: "DELETE",
        cache: "no-store",
        headers,
      });

      if (!response.ok) {
        const responseText = await response.text();
        let detail = responseText.trim();
        try {
          const body = JSON.parse(responseText) as { error?: string };
          detail = body.error || detail;
        } catch {
          // The camera may return a plain-text error body.
        }
        if (response.status === 401) {
          onAuthModeChange("required");
          onAuthMessageChange(
            "The camera rejected the credentials. Check the username and password above.",
          );
          onCredentialsInvalid();
        }
        throw new Error(
          detail ||
            `The camera returned ${response.status} ${response.statusText}`,
        );
      }

      setListing((current) =>
        current?.path === path
          ? {
              ...current,
              entries: current.entries.filter(
                (currentEntry) => currentEntry.name !== entry.name,
              ),
            }
          : current,
      );
      if (
        playbackPath === entryPath ||
        (entry.isDirectory && playbackPath?.startsWith(`${entryPath}/`))
      ) {
        stopPlayback();
      }
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setDeletingPath(null);
    }
  };

  const segments = path ? path.split("/") : [];
  // Do not render the section until the root listing is known, otherwise it
  // flashes briefly before an empty root hides it again.
  const rootPending =
    path === "" && listing === null && listingState !== "error";
  const rootIsEmpty =
    path === "" &&
    listingState === "idle" &&
    listing !== null &&
    listing.entries.length === 0;

  if (rootPending || rootIsEmpty) return null;

  const playbackTitle = playbackTitleFor(playbackState);

  return (
    <section className="space-y-4 border-t border-border pt-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-base font-semibold">Recordings</h3>
          <p className="text-xs text-muted-foreground">
            Browse the camera's recording folder and play a clip over WebRTC.
          </p>
        </div>
        {canBrowse && (
          <button
            type="button"
            onClick={() => setReloadKey((value) => value + 1)}
            disabled={listingState === "loading"}
            className="inline-flex items-center gap-1 rounded-lg border border-border px-3 py-1.5 text-xs font-medium disabled:opacity-50"
          >
            <RefreshCw className="h-3.5 w-3.5" />
            Refresh
          </button>
        )}
      </div>

      {playbackPath && (
        <div className="space-y-2">
          <div className="relative overflow-hidden rounded-xl bg-black">
            <video
              ref={videoRef}
              muted
              playsInline
              autoPlay
              className="aspect-video w-full object-contain"
            />
            {playbackState !== "playing" && (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-black/65 px-6 text-center text-sm text-white">
                <span className="font-medium">{playbackTitle}</span>
                {playbackMessage && (
                  <span className="max-w-md text-xs text-white/70">
                    {playbackMessage}
                  </span>
                )}
              </div>
            )}
          </div>
          <div className="space-y-1">
            <p className="truncate text-xs text-muted-foreground">
              {playbackPath}
            </p>
            <div className="flex flex-wrap items-center gap-3">
              <input
                type="range"
                min={0}
                max={Math.max(duration, 0)}
                step={0.1}
                value={Math.min(
                  seekPreview ?? currentTime,
                  Math.max(duration, 0),
                )}
                onChange={(event) => previewSeek(Number(event.target.value))}
                onPointerUp={commitSeek}
                onPointerCancel={cancelSeek}
                onKeyUp={commitSeek}
                onBlur={commitSeek}
                disabled={
                  duration <= 0 ||
                  playbackState === "loading" ||
                  playbackState === "seeking" ||
                  playbackState === "connecting"
                }
                aria-label="Recording position"
                className="h-2 min-w-40 flex-1 accent-primary disabled:opacity-50"
              />
              <span className="text-xs tabular-nums text-muted-foreground">
                {formatDuration(seekPreview ?? currentTime)} /{" "}
                {formatDuration(duration)}
              </span>
              <button
                type="button"
                onClick={stopPlayback}
                className="rounded-lg border border-border px-3 py-1.5 text-xs font-medium"
              >
                Stop
              </button>
            </div>
          </div>
        </div>
      )}

      <nav className="flex flex-wrap items-center gap-1 text-sm">
        <button
          type="button"
          onClick={() => navigate("")}
          className={
            path
              ? "text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
              : "font-medium"
          }
        >
          {folderLabel}
        </button>
        {segments.map((segment, index) => {
          const segmentPath = segments.slice(0, index + 1).join("/");
          const isLast = index === segments.length - 1;
          return (
            <span
              key={segmentPath}
              className="flex items-center gap-1 text-muted-foreground"
            >
              <ChevronRight className="h-4 w-4" />
              {isLast ? (
                <span className="font-medium text-foreground">{segment}</span>
              ) : (
                <button
                  type="button"
                  onClick={() => navigate(segmentPath)}
                  className="underline-offset-4 hover:text-foreground hover:underline"
                >
                  {segment}
                </button>
              )}
            </span>
          );
        })}
      </nav>

      {listingState === "loading" && (
        <p className="text-sm text-muted-foreground">Loading folders…</p>
      )}

      {listingState === "error" && (
        <div className="rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {listingError}
        </div>
      )}

      {actionError && (
        <div className="rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {actionError}
        </div>
      )}

      {listing && listingState === "idle" && (
        <div className="overflow-hidden rounded-xl border border-border">
          {listing.entries.length === 0 ? (
            <p className="px-4 py-3 text-sm text-muted-foreground">
              This folder is empty.
            </p>
          ) : (
            <ul className="divide-y divide-border">
              {listing.entries.map((entry) => {
                const entryPath = path ? `${path}/${entry.name}` : entry.name;
                const isPlayable =
                  !entry.isDirectory && /\.mp4$/i.test(entry.name);
                return (
                  <li key={entry.name} className="flex items-stretch">
                    {entry.isDirectory ? (
                      <>
                        <button
                          type="button"
                          onClick={() => navigate(entryPath)}
                          className="flex min-w-0 flex-1 items-center gap-3 px-4 py-3 text-left text-sm hover:bg-muted/60"
                        >
                          <Folder className="h-4 w-4 shrink-0 text-muted-foreground" />
                          <span className="min-w-0 flex-1 truncate font-medium">
                            {entry.name}
                          </span>
                          <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
                        </button>
                        <button
                          type="button"
                          onClick={() => void deleteEntry(entry, entryPath)}
                          disabled={deletingPath === entryPath}
                          aria-label={`Delete folder ${entry.name}`}
                          className="inline-flex shrink-0 items-center gap-1.5 px-3 text-xs font-medium text-muted-foreground hover:bg-destructive/10 hover:text-destructive disabled:opacity-50"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                          {deletingPath === entryPath ? "Deleting…" : "Delete"}
                        </button>
                      </>
                    ) : (
                      <>
                        <button
                          type="button"
                          onClick={() => void play(entry)}
                          disabled={!isPlayable}
                          className="flex min-w-0 flex-1 items-center gap-3 px-4 py-3 text-left text-sm hover:bg-muted/60 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                          <Play className="h-4 w-4 shrink-0 text-muted-foreground" />
                          <span className="min-w-0 flex-1">
                            <span className="block truncate font-medium">
                              {entry.name}
                            </span>
                            <span className="block text-xs text-muted-foreground">
                              {formatBytes(entry.sizeBytes)} ·{" "}
                              {formatDateTime(entry.lastModifiedMs)}
                              {!isPlayable && " · not an MP4"}
                            </span>
                          </span>
                        </button>
                        <button
                          type="button"
                          onClick={() => void deleteEntry(entry, entryPath)}
                          disabled={deletingPath === entryPath}
                          aria-label={`Delete recording ${entry.name}`}
                          className="inline-flex shrink-0 items-center gap-1.5 px-3 text-xs font-medium text-muted-foreground hover:bg-destructive/10 hover:text-destructive disabled:opacity-50"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                          {deletingPath === entryPath ? "Deleting…" : "Delete"}
                        </button>
                      </>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}
    </section>
  );
}
