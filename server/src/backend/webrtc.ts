import { lookup, resolve4 } from "node:dns/promises";
import { isIP } from "node:net";
import { setTimeout as delay } from "node:timers/promises";
import {
  EncodedPacketSink,
  Input,
  MP4,
  type EncodedPacket,
  type InputVideoTrack,
  type Source,
} from "mediabunny";
import {
  H264Packetizer,
  MediaStreamTrack,
  RTCPeerConnection,
  useH264,
} from "werift";
import { parseAvcDecoderConfigurationRecord } from "werift/nonstandard";
import type { MediaViewer } from "./relay";
import type { DeviceConnection } from "./relay";

/**
 * The relay is only a packetizer: H.264 access units arrive from the camera
 * over the outbound WebSocket and are wrapped in RTP for each browser viewer.
 * Recorded MP4s are read from the camera with byte ranges, demuxed as they
 * play, and packetized the same way. No media is written to disk.
 */

interface IceServerConfig {
  urls: string | string[];
  username?: string;
  credential?: string;
}

interface BridgeConfig {
  iceServers: IceServerConfig[];
  icePortRange?: [number, number];
  iceTransportPolicy?: "all" | "relay";
}

function parseIceServerEntry(raw: string): IceServerConfig | undefined {
  const value = raw.trim();
  if (!value) return undefined;
  // Allow credentials embedded in the URL, which is easier to pass through a
  // single environment variable:
  //   turn:user:password@turn.example.com:3478?transport=udp
  const match = /^(stuns?|turns?):([^@/]+)@(.+)$/i.exec(value);
  if (match) {
    const [, scheme, userinfo, rest] = match;
    if (scheme && userinfo && rest) {
      const separator = userinfo.indexOf(":");
      if (separator > 0) {
        return {
          urls: `${scheme}:${rest}`,
          username: decodeURIComponent(userinfo.slice(0, separator)),
          credential: decodeURIComponent(userinfo.slice(separator + 1)),
        };
      }
    }
  }
  return { urls: value };
}

function parseIceServers(): IceServerConfig[] {
  const entries: IceServerConfig[] = [];
  const raw = process.env.WEBRTC_STUN_URLS;
  if (raw === undefined) {
    entries.push({ urls: "stun:stun.l.google.com:19302" });
  } else {
    for (const value of raw.split(",")) {
      const parsed = parseIceServerEntry(value);
      if (parsed) entries.push(parsed);
    }
  }

  // Optional dedicated TURN URL with credentials kept in separate variables.
  const turnUrl = process.env.WEBRTC_TURN_URL?.trim();
  if (turnUrl) {
    entries.push({
      urls: turnUrl,
      username: process.env.WEBRTC_TURN_USERNAME?.trim() || undefined,
      credential: process.env.WEBRTC_TURN_CREDENTIAL || undefined,
    });
  }
  return entries;
}

function parsePortRange(): [number, number] | undefined {
  const raw = process.env.WEBRTC_UDP_PORT_RANGE;
  if (!raw) return undefined;
  const [minText, maxText] = raw.split("-");
  const min = Number(minText);
  const max = Number(maxText ?? minText);
  if (
    !Number.isInteger(min) ||
    !Number.isInteger(max) ||
    min < 1024 ||
    max > 65535 ||
    min >= max
  ) {
    console.warn(`Ignoring invalid WEBRTC_UDP_PORT_RANGE=${raw}`);
    return undefined;
  }
  return [min, max];
}

const bridgeConfig: BridgeConfig = {
  iceServers: parseIceServers(),
  icePortRange: parsePortRange(),
  iceTransportPolicy:
    process.env.WEBRTC_FORCE_TURN === "true" ? "relay" : "all",
};

/** RFC 1918, link-local, CGNAT (Tailscale/NetBird), and loopback space. */
function isPrivateIPv4(address: string): boolean {
  const parts = address.split(".").map(Number);
  if (
    parts.length !== 4 ||
    parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)
  ) {
    return false;
  }
  const [a, b] = parts as [number, number, number, number];
  return (
    a === 10 ||
    a === 127 ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 169 && b === 254) ||
    (a === 100 && b >= 64 && b <= 127)
  );
}

async function resolveIPv4(
  host: string,
  useDns = false,
): Promise<string | undefined> {
  const family = isIP(host);
  if (family === 4) return host;
  if (family === 6) return undefined;

  // `resolve4` performs a real DNS query and ignores /etc/hosts. The compose
  // file maps PUBLIC_URL to 0.0.0.0 there so the dev server can bind all
  // interfaces without losing the Host-header check; we still need the real
  // address for WebRTC.
  if (useDns) {
    try {
      const [address] = await resolve4(host);
      if (address && isIP(address) === 4) return address;
    } catch {
      // Fall back to the hosts-file aware lookup below.
    }
  }

  try {
    const { address } = await lookup(host, { family: 4 });
    return isIP(address) === 4 ? address : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Docker publishes the WebRTC UDP range on the host, but werift only sees the
 * container interface and advertises that address (for example 172.20.0.20).
 * Browsers outside the container network cannot reach it, so ICE never
 * connects even though the published ports are open.
 *
 * Resolve an address viewers can use and rewrite the host candidates in the
 * SDP answer. `WEBRTC_ADVERTISE_HOST` is explicit; otherwise use the
 * `PUBLIC_URL` hostname when it resolves to a private address (the common
 * single-host/LAN deployment). Public origins are left alone because they may
 * be a reverse proxy that does not forward UDP.
 */
async function resolveAdvertisedAddress(): Promise<string | undefined> {
  const explicit = process.env.WEBRTC_ADVERTISE_HOST?.trim();
  if (explicit) {
    const address = await resolveIPv4(explicit);
    if (!address) {
      console.warn(
        `Ignoring WEBRTC_ADVERTISE_HOST=${explicit}: not a resolvable IPv4 address`,
      );
      return undefined;
    }
    return address;
  }

  const publicUrl = process.env.PUBLIC_URL?.trim();
  if (!publicUrl) return undefined;

  let hostname: string;
  try {
    hostname = new URL(publicUrl).hostname;
  } catch {
    return undefined;
  }

  const address = await resolveIPv4(hostname, true);
  if (
    !address ||
    address === "0.0.0.0" ||
    address.startsWith("127.") ||
    address.startsWith("169.254.") ||
    !isPrivateIPv4(address)
  ) {
    return undefined;
  }
  return address;
}

let resolvedAdvertisedAddress: string | undefined;
const advertisedAddress = resolveAdvertisedAddress().then((address) => {
  if (address) console.log(`WebRTC host candidates will advertise ${address}`);
  resolvedAdvertisedAddress = address;
  return address;
});

function rewriteAdvertisedAddress(sdp: string, address: string): string {
  const lines = sdp.split("\r\n");
  const hostAddresses = new Set<string>();
  for (const line of lines) {
    if (!line.startsWith("a=candidate:")) continue;
    const parts = line.slice("a=candidate:".length).split(" ");
    const candidateAddress = parts[4];
    if (
      parts[6] === "typ" &&
      parts[7] === "host" &&
      candidateAddress &&
      isIP(candidateAddress) === 4
    ) {
      hostAddresses.add(candidateAddress);
    }
  }
  if (hostAddresses.size === 0) return sdp;

  return lines
    .map((line) => {
      if (line.startsWith("a=candidate:")) {
        const parts = line.slice("a=candidate:".length).split(" ");
        if (
          parts[6] === "typ" &&
          parts[7] === "host" &&
          isIP(parts[4] ?? "") === 4
        ) {
          parts[4] = address;
          return `a=candidate:${parts.join(" ")}`;
        }
        return line;
      }
      const connection = /^c=IN IP4 (\S+)$/.exec(line);
      if (connection?.[1] && hostAddresses.has(connection[1])) {
        return `c=IN IP4 ${address}`;
      }
      return line;
    })
    .join("\r\n");
}

export interface WebRtcAnswer {
  type: "answer";
  sdp: string;
}

/** The file was readable but cannot be sent as-is over WebRTC. */
export class UnsupportedRecordingError extends Error {}

export interface WebRtcRecordingAnswer extends WebRtcAnswer {
  /** Total recording duration in seconds. */
  duration: number;
  /** Presentation time, in seconds, of the first packet sent. */
  startTime: number;
}

export class WebRtcBridge {
  private readonly connection: RTCPeerConnection;
  private readonly device: DeviceConnection;
  private readonly track: MediaStreamTrack;
  private readonly packetizer: H264Packetizer;
  private readonly removeViewer: () => void;
  private closed = false;

  private constructor(
    connection: RTCPeerConnection,
    device: DeviceConnection,
    credentials: { username: string; password: string },
  ) {
    this.connection = connection;
    this.device = device;
    this.track = new MediaStreamTrack({ kind: "video" });
    this.packetizer = new H264Packetizer({
      payloadType: 96,
      ssrc: Math.floor(Math.random() * 0xffffffff) >>> 0,
      maxPayloadSize: 1200,
    });

    const transceiver = connection.addTransceiver(this.track, {
      direction: "sendonly",
    });
    transceiver.sender.onKeyFrameRequest.subscribe(() => {
      this.device.requestKeyFrame();
    });

    const viewer: MediaViewer = {
      writeVideo: (annexB, _isKey, ptsUs) => {
        if (this.closed) return;
        try {
          const rtpTimestamp = Math.floor((ptsUs * 90) / 1000) >>> 0;
          for (const packet of this.packetizer.packetize(
            Buffer.from(annexB),
            rtpTimestamp,
          )) {
            this.track.writeRtp(packet);
          }
        } catch {
          // A corrupt access unit must not tear down the whole viewer.
        }
      },
      close: () => this.close(),
    };
    this.removeViewer = device.addViewer(viewer, credentials);

    connection.onconnectionstatechange = () => {
      const state = connection.connectionState;
      if (
        state === "failed" ||
        state === "closed" ||
        state === "disconnected"
      ) {
        this.close();
      }
    };
  }

  static async answer(
    device: DeviceConnection,
    offer: { type: string; sdp: string },
    credentials: { username: string; password: string },
  ): Promise<WebRtcAnswer> {
    if (
      offer.type !== "offer" ||
      typeof offer.sdp !== "string" ||
      offer.sdp.length === 0
    ) {
      throw new Error("Expected a WebRTC offer");
    }

    const connection = new RTCPeerConnection({
      iceServers: bridgeConfig.iceServers,
      icePortRange: bridgeConfig.icePortRange,
      iceTransportPolicy: bridgeConfig.iceTransportPolicy,
      codecs: { video: [useH264()], audio: [] },
      headerExtensions: { video: [], audio: [] },
    });

    const bridge = new WebRtcBridge(connection, device, credentials);
    try {
      await connection.setRemoteDescription({ type: "offer", sdp: offer.sdp });
      const answer = await connection.createAnswer();
      await connection.setLocalDescription(answer);
      const local = connection.localDescription;
      if (!local?.sdp) throw new Error("WebRTC answer did not contain an SDP");
      const advertised = await advertisedAddress;
      const sdp = advertised
        ? rewriteAdvertisedAddress(local.sdp, advertised)
        : local.sdp;
      return { type: "answer", sdp };
    } catch (error) {
      bridge.close();
      throw error;
    }
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.removeViewer();
    try {
      this.track.stop();
    } catch {
      // Track already ended.
    }
    void this.connection.close().catch(() => {});
  }
}

/**
 * Plays an MP4 recording from the camera over WebRTC. The relay reads the file
 * from the camera through a byte-range source; mediabunny demuxes its H.264
 * track and paces the packets to the viewer in real time. Only a small
 * read-ahead window is buffered, never the whole recording. No transcoding is
 * involved, so the recording must already be H.264 (which the app writes).
 */
export class WebRtcRecordingBridge {
  private readonly connection: RTCPeerConnection;
  private readonly track: MediaStreamTrack;
  private readonly input: Input;
  private readonly abort = new AbortController();
  private startPlayback?: () => void;
  private started = false;
  private closed = false;

  private constructor(
    connection: RTCPeerConnection,
    track: MediaStreamTrack,
    input: Input,
  ) {
    this.connection = connection;
    this.track = track;
    this.input = input;

    connection.onconnectionstatechange = () => {
      const state = connection.connectionState;
      if (state === "connected") {
        // Wait for ICE/DTLS before sending RTP. Unlike a live stream, a
        // recording has only one initial keyframe, so packets sent before the
        // viewer is connected would leave it with a permanently black frame.
        this.startPlayback?.();
        return;
      }
      if (
        state === "failed" ||
        state === "closed" ||
        state === "disconnected"
      ) {
        this.close();
      }
    };
  }

  static async answer(
    offer: { type: string; sdp: string },
    source: Source,
    requestedStartTime = 0,
  ): Promise<WebRtcRecordingAnswer> {
    if (
      offer.type !== "offer" ||
      typeof offer.sdp !== "string" ||
      offer.sdp.length === 0
    ) {
      throw new Error("Expected a WebRTC offer");
    }

    const input = new Input({
      source,
      formats: [MP4],
    });
    let bridge: WebRtcRecordingBridge | undefined;
    try {
      if (!(await input.canRead())) {
        throw new UnsupportedRecordingError(
          "The recording is not a readable MP4 file",
        );
      }
      const videoTrack = await input.getPrimaryVideoTrack();
      if (!videoTrack) {
        throw new UnsupportedRecordingError(
          "The recording does not contain a video track",
        );
      }
      const codec = await videoTrack.getCodec();
      if (codec !== "avc") {
        throw new UnsupportedRecordingError(
          `Unsupported recording codec "${codec ?? "unknown"}". Only H.264 MP4 recordings can be streamed.`,
        );
      }

      let duration = await input.getDurationFromMetadata([videoTrack]);
      if (duration == null || !Number.isFinite(duration) || duration <= 0) {
        duration = await input.computeDuration([videoTrack]);
      }
      if (!Number.isFinite(duration) || duration < 0) duration = 0;

      const requested = Number.isFinite(requestedStartTime)
        ? Math.max(0, requestedStartTime)
        : 0;
      const seekTarget =
        duration > 0
          ? Math.min(requested, Math.max(0, duration - 0.25))
          : requested;
      const sink = new EncodedPacketSink(videoTrack);
      const startPacket =
        (seekTarget > 0
          ? await sink.getKeyPacket(seekTarget, { verifyKeyPackets: true })
          : null) ?? (await sink.getFirstKeyPacket({ verifyKeyPackets: true }));
      if (!startPacket) {
        throw new UnsupportedRecordingError(
          "The recording does not contain a video keyframe",
        );
      }
      const startTimestamp = startPacket.timestamp;

      const decoderConfig = await videoTrack.getDecoderConfig();
      const description =
        decoderConfig && "description" in decoderConfig
          ? (decoderConfig.description ?? null)
          : null;
      const avcConfig = description
        ? parseAvcDecoderConfigurationRecord(description)
        : undefined;
      // Keep the default profile-level-id. The browser advertises a fixed set
      // of H.264 profiles in its offer, and the recording's real profile may
      // not be in it; the in-band SPS/PPS still configure the decoder.
      const videoCodec = useH264();

      const connection = new RTCPeerConnection({
        iceServers: bridgeConfig.iceServers,
        icePortRange: bridgeConfig.icePortRange,
        iceTransportPolicy: bridgeConfig.iceTransportPolicy,
        codecs: { video: [videoCodec], audio: [] },
        headerExtensions: { video: [], audio: [] },
      });
      const track = new MediaStreamTrack({
        kind: "video",
        codec: videoCodec,
      });
      connection.addTransceiver(track, { direction: "sendonly" });

      const packetizer = new H264Packetizer({
        payloadType: videoCodec.payloadType ?? 96,
        ssrc: Math.floor(Math.random() * 0xffffffff) >>> 0,
        maxPayloadSize: 1200,
        naluLengthSize: avcConfig?.naluLengthSize,
        parameterSets: avcConfig?.parameterSets,
        // An avcC description means the samples are length-prefixed (AVCC).
        inputFormat: avcConfig ? "length-prefixed" : "auto",
      });

      bridge = new WebRtcRecordingBridge(connection, track, input);
      await connection.setRemoteDescription({
        type: "offer",
        sdp: offer.sdp,
      });
      const answer = await connection.createAnswer();
      await connection.setLocalDescription(answer);
      const local = connection.localDescription;
      if (!local?.sdp) throw new Error("WebRTC answer did not contain an SDP");
      const advertised = await advertisedAddress;
      const sdp = advertised
        ? rewriteAdvertisedAddress(local.sdp, advertised)
        : local.sdp;

      bridge.schedulePlayback(
        videoTrack,
        packetizer,
        startPacket,
        startTimestamp,
      );
      return { type: "answer", sdp, duration, startTime: startTimestamp };
    } catch (error) {
      if (bridge) bridge.close();
      else input.dispose();
      throw error;
    }
  }

  /**
   * Delay the first RTP packet until the peer is connected. The browser only
   * starts ICE after it receives the SDP answer, so starting earlier would
   * drop the recording's opening keyframe.
   */
  private schedulePlayback(
    videoTrack: InputVideoTrack,
    packetizer: H264Packetizer,
    startPacket: EncodedPacket,
    startTimestamp: number,
  ): void {
    this.startPlayback = () => {
      if (this.closed || this.started) return;
      this.started = true;
      this.play(videoTrack, packetizer, startPacket, startTimestamp);
    };
    if (this.connection.connectionState === "connected") {
      this.startPlayback();
    }
  }

  private play(
    videoTrack: InputVideoTrack,
    packetizer: H264Packetizer,
    startPacket: EncodedPacket,
    startTimestamp: number,
  ): void {
    void this.runPlayback(
      videoTrack,
      packetizer,
      startPacket,
      startTimestamp,
    ).catch((error) => {
      if (!this.closed) {
        console.error("Recording playback failed", error);
      }
      this.close();
    });
  }

  private async runPlayback(
    videoTrack: InputVideoTrack,
    packetizer: H264Packetizer,
    startPacket: EncodedPacket,
    startTimestamp: number,
  ): Promise<void> {
    const sink = new EncodedPacketSink(videoTrack);
    const startedAt = performance.now();
    for await (const packet of sink.packets(startPacket, undefined, {
      verifyKeyPackets: true,
    })) {
      if (this.closed) return;
      const dueAtMs = (packet.timestamp - startTimestamp) * 1000;
      const remainingMs = dueAtMs - (performance.now() - startedAt);
      if (remainingMs > 0) {
        await delay(remainingMs, undefined, { signal: this.abort.signal });
      }
      if (this.closed) return;

      const rtpTimestamp =
        Math.floor((packet.timestamp - startTimestamp) * 90_000) >>> 0;
      const data = Buffer.from(
        packet.data.buffer,
        packet.data.byteOffset,
        packet.data.byteLength,
      );
      for (const rtpPacket of packetizer.packetize(data, rtpTimestamp, {
        isKeyframe: packet.type === "key",
      })) {
        this.track.writeRtp(rtpPacket);
      }
    }

    if (this.closed) return;
    // Give the browser a moment to render the final frames, then end the peer.
    await delay(1_000, undefined, { signal: this.abort.signal }).catch(
      () => {},
    );
    this.close();
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.startPlayback = undefined;
    this.abort.abort();
    try {
      this.track.stop();
    } catch {
      // Track already ended.
    }
    void this.connection.close().catch(() => {});
    try {
      this.input.dispose();
    } catch {
      // Input already disposed.
    }
  }
}

export function webrtcConfigSummary() {
  return {
    iceServers: bridgeConfig.iceServers.map((server) => ({
      urls: server.urls,
      username: server.username,
      credential: server.credential,
      hasCredentials: Boolean(server.username),
    })),
    icePortRange: bridgeConfig.icePortRange ?? null,
    iceTransportPolicy: bridgeConfig.iceTransportPolicy,
    advertiseHost: resolvedAdvertisedAddress ?? null,
  };
}
