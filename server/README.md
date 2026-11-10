# Android IP Camera relay

A small Bun + React server that gives the Android IP Camera app a public
HTTPS/WSS endpoint without opening a port on the phone, running an SSH tunnel,
or using a third-party tunnel provider.

The camera makes one outbound WebSocket connection to the relay. Browsers talk
to the relay, and the relay multiplexes each request over that existing socket.
Live video uses WebRTC: the app sends H.264 access units over the same socket
and the relay packetizes them per viewer. Requests and media stay in memory;
only device records and pairing codes are written to SQLite.

```
browser ──HTTPS/WebRTC──▶ relay (public) ◀──outbound WSS── Android app
```

## How pairing works

1. Open the relay URL in a browser. With no device associated, the page shows
   **Connect your camera** and creates a one-time, 10-minute pairing code.
2. The page builds an intent link using the server FQDN:

   ```
   androidipcamera://associate?server=https%3A%2F%2Frelay.example.com&code=ABCD-EFGH
   ```

3. Open that link on the phone (the **Open the Android app and associate**
   button, or paste it into the device browser). The app posts the code to
   `POST /api/associate`, stores the returned device token in Android
   `EncryptedSharedPreferences`, and starts its outbound WebSocket.
4. Reload the relay page. It switches to the live view automatically.

Only a SHA-256 hash of the device token is stored on the server. Pairing codes
are one-time and are deleted when used or expired.

The app can forget a pairing under **Settings → Remote Access (Relay) →
Disconnect from Relay**. Remove it from the relay at the bottom of the live
view. Both sides must be reset before pairing the phone again.

## Endpoints

| Endpoint                           | Purpose                                              |
| ---------------------------------- | ---------------------------------------------------- |
| `GET /api/status`                  | Associated devices, online state, WebRTC ICE summary |
| `POST /api/pairing`                | Create a one-time pairing code and intent link       |
| `POST /api/associate`              | Exchange a code for a device ID + bearer token       |
| `DELETE /api/devices/:id`          | Forget a device                                      |
| `GET /r/:deviceId/*`               | In-memory HTTP proxy to the camera                   |
| `POST /api/webrtc/:deviceId/offer` | WebRTC SDP offer/answer for live H.264               |
| `WS /ws/device`                    | Camera's authenticated outbound connection           |

The browser never receives the camera's Basic-auth credentials. The app injects
them when it replays a relayed request against `127.0.0.1:<streaming port>`.

## Configuration

| Variable                 | Default                      | Meaning                                         |
| ------------------------ | ---------------------------- | ----------------------------------------------- |
| `PORT`                   | `3000`                       | HTTP listen port                                |
| `DB_PATH`                | `./data/relay.sqlite`        | SQLite file for devices + pairing codes         |
| `PUBLIC_URL`             | request origin               | Public origin used in pairing links             |
| `WEBRTC_STUN_URLS`       | Google STUN                  | Comma-separated STUN/TURN URLs                  |
| `WEBRTC_TURN_URL`        | unset                        | Extra TURN URL used by the relay and viewers    |
| `WEBRTC_TURN_USERNAME`   | unset                        | TURN username                                   |
| `WEBRTC_TURN_CREDENTIAL` | unset                        | TURN password                                   |
| `WEBRTC_UDP_PORT_RANGE`  | ephemeral                    | Fixed UDP range, for example `40000-40100`      |
| `WEBRTC_ADVERTISE_HOST`  | private `PUBLIC_URL` address | Host/IP viewers use for WebRTC candidates       |
| `WEBRTC_FORCE_TURN`      | `false`                      | Force `iceTransportPolicy: relay` on both sides |

Copy `.env.example` to `.env` and adjust the public URL and, if needed, TURN
credentials. `PUBLIC_URL` matters when the server sits behind a reverse proxy,
because the pairing link must contain the address the phone can reach.

### WebRTC and firewalls

The relay terminates WebRTC on the server with `werift`. Its UDP sockets must be
reachable from viewers. Map the same range in Docker:

```yaml
ports:
  - "40000-40100:40000-40100/udp"
environment:
  - WEBRTC_UDP_PORT_RANGE=40000-40100
```

Docker hides the host's LAN address from the container, so werift would
otherwise advertise its bridge address (for example `172.20.0.20`) and ICE
would never connect. Set `WEBRTC_ADVERTISE_HOST` to the address viewers use,
or let the relay derive it from `PUBLIC_URL` when that hostname resolves to a
private IPv4 address:

```yaml
environment:
  - WEBRTC_ADVERTISE_HOST=192.168.1.10
```

The published UDP range must also be reachable from the viewer. On a flat LAN
that is enough; for internet viewers, forward the same range on your router or
use TURN (below). `/api/status` reports the resolved `webrtc.advertiseHost`.

If the relay is behind symmetric NAT or viewers are on restrictive networks,
provide a TURN server through `WEBRTC_TURN_URL` plus
`WEBRTC_TURN_USERNAME`/`WEBRTC_TURN_CREDENTIAL` (or embed the credentials in
`WEBRTC_STUN_URLS` as `turn:user:password@turn.example.com:3478?transport=udp`)
and set `WEBRTC_FORCE_TURN=true`. `/api/status` gives the browser the same ICE
servers and policy, so both sides use TURN. The snapshot and audio controls in
the dashboard use the HTTP proxy and work without WebRTC.

TURN credentials are sent to the viewer page because the browser must
authenticate to the TURN server. Anyone who can open the relay can read them,
so prefer short-lived TURN credentials when the relay is public.

## Docker

```bash
cd server
cp .env.example .env   # edit PUBLIC_URL / TURN settings
docker compose up --build
```

The compose file publishes the WebRTC UDP range and stores the SQLite database
under `./storage`. Traefik terminates HTTPS and routes the web/API/WebSocket
traffic to port 3000; the UDP range bypasses Traefik.

For a standalone run without Traefik:

```bash
docker build -t android-ip-camera-relay .
docker run --rm -p 3000:3000 -p 40000-40100:40000-40100/udp \
  -v "$PWD/storage:/storage" \
  -e PUBLIC_URL=https://relay.example.com \
  -e DB_PATH=/storage/relay.sqlite \
  -e WEBRTC_UDP_PORT_RANGE=40000-40100 \
  android-ip-camera-relay
```

## Local development

Bun and Java tooling are only expected inside Docker in this repository:

```bash
docker run --rm -e HOME=/tmp -v "$PWD/server:/app" -w /app oven/bun:1.4.4-alpine bun install
docker run --rm -e HOME=/tmp -v "$PWD/server:/app" -w /app oven/bun:1.4.4-alpine bun run typecheck
docker run --rm -e HOME=/tmp -v "$PWD/server:/app" -w /app oven/bun:1.4.4-alpine bun run dev
```

Build the production bundle:

```bash
docker run --rm -e HOME=/tmp -v "$PWD/server:/app" -w /app oven/bun:1.4.4-alpine bun run build
```

## Security notes

- Anyone who can open the relay URL can add, view, edit and delete cameras.
- Put the relay behind your reverse proxy's own authentication if the URL
  should not be public.
- Device tokens are random 256-bit values; only their SHA-256 hashes are stored.
- Pairing codes expire after 10 minutes and are single-use.
- The relay does not persist request bodies, snapshots, audio, or video.
- `/r/:deviceId/*` is only reachable while that camera's WebSocket is online.
- Pairing codes themselves are the authorization to add a camera.
- Put the relay behind HTTPS. The Android app validates the relay's certificate
  for the outbound WSS connection; only the loopback camera proxy uses a
  trust-all context (for the app's self-signed certificate).
- After the relay removes a camera, its token is rejected with HTTP 401 and the
  app stops reconnecting and forgets the association.
