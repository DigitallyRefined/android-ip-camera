# Android IP Camera relay

A small Bun + React server that gives the Android IP Camera app a public
HTTPS/WSS endpoint without opening a port on the phone, running an SSH tunnel,
or using a third-party tunnel provider.

The camera makes one outbound WebSocket connection to the relay. Browsers talk
to the relay, and the relay multiplexes each request over that existing socket.
Live video uses WebRTC: the app sends H.264 access units over the same socket
and the relay packetizes them per viewer. Requests and media stay in memory;
only device records and pairing codes are written to the configured database
(SQLite by default, with MySQL and PostgreSQL also supported).

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

| Endpoint                                     | Purpose                                              |
| -------------------------------------------- | ---------------------------------------------------- |
| `GET /api/status`                            | Associated devices, online state, WebRTC ICE summary |
| `POST /api/pairing`                          | Create a one-time pairing code and intent link       |
| `POST /api/associate`                        | Exchange a code for a device ID + bearer token       |
| `PATCH /api/devices/:id`                     | Rename a device                                      |
| `DELETE /api/devices/:id`                    | Forget a device                                      |
| `GET /api/devices/:id/notifications`         | Read the camera's Apprise notification URLs          |
| `PUT /api/devices/:id/notifications`         | Replace the camera's Apprise notification URLs       |
| `POST /api/devices/:id/notifications/test`   | Send a test to the supplied Apprise URLs (5s limit)  |
| `GET /r/:deviceId/*`                         | In-memory HTTP proxy to the camera                   |
| `POST /api/webrtc/:deviceId/offer`           | WebRTC SDP offer/answer for live H.264               |
| `POST /api/webrtc/:deviceId/recording/offer` | Stream an H.264 MP4 recording over WebRTC            |
| `WS /ws/device`                              | Camera's authenticated outbound connection           |

The browser never receives the camera's Basic-auth credentials. The app injects
them when it replays a relayed request against `127.0.0.1:<streaming port>`.

The dashboard's **Recordings** browser lists the camera's `/files.json`
recording folders. Select an MP4 to play it over WebRTC. Recording playback
currently supports video-only H.264 MP4 files (the format written by the app);
it does not transcode. Playback is progressive: the relay never downloads the
whole file. mediabunny reads the MP4 index and the samples around the play
position as HTTP byte ranges from the camera, so only a small read-ahead window
(bounded to 8 MiB) is held in memory and it is discarded when the WebRTC
session ends. The camera must stay online while a recording is being watched,
and the installed app must support `Range` requests (all current releases do).

The player's scrub bar seeks by renegotiating WebRTC from the nearest H.264
keyframe. The offer body is
`{ type, sdp, path, startTime? }`, where `startTime` is in seconds; the answer
includes the recording `duration` and the actual keyframe `startTime`. Because
the relay keeps no copy of the recording, each seek and each new playback
session reads the needed ranges from the camera again.

### Motion events

When **Record on Motion** is enabled in the app and the camera is connected to
the relay, the app sends a `motion` text message over its normal authenticated
device WebSocket:

```json
{ "type": "motion", "at": 1762862400000 }
```

The message travels on the same socket that is authenticated with the device's
bearer token, so the relay identifies the camera from the connection itself. The
payload contains no device ID or token and cannot name another camera. The app
reports only motion starts; while motion remains active no further event is
sent.

The relay always writes the event to its log against that camera:

```
motion detected: Front Door (2f0c…) at 2026-11-11T14:00:00.000Z
```

Each camera can also have Apprise notification URLs configured under
**Notification settings** in the dashboard. Enter one Apprise URL per line; the
relay sends them as one Apprise request when motion arrives:

```json
{
  "urls": [
    "mailto://user:password@gmail.com",
    "slack://TOKEN_A/TOKEN_B/TOKEN_C",
    "tgram://BOT_TOKEN/CHAT_ID"
  ],
  "title": "Motion detected",
  "body": "Camera Front Door detected motion at 2026-11-11 14:00"
}
```

The request goes to `APPRISE_URL` (the bundled `apprise` service by default) at
`POST /notify`. Delivery failures are only logged; they never disrupt the camera
socket. Notification sends, including tests, are rate limited per camera to one
every five seconds. Motion inside that window is still logged but is not sent,
and a test request returns HTTP 429. The settings dialog's **Test** button sends
the URLs currently entered without saving them and reports Apprise's success or
error details. With no URLs configured, the relay only logs the event. Examples
and a link to the Apprise documentation are shown in the settings dialog. Motion
events are not stored, included in `/api/status`, or pushed to viewers. When the
camera is offline, its motion events are dropped rather than queued.

## Configuration

| Variable                       | Default                       | Meaning                                              |
| ------------------------------ | ----------------------------- | ---------------------------------------------------- |
| `PORT`                         | `3000`                        | HTTP listen port                                     |
| `DATABASE_URL`                 | `sqlite:///data/relay.sqlite` | `sqlite://`, `mysql://`, or `postgres://` database   |
| `PUBLIC_URL`                   | request origin                | Public origin used in pairing links                  |
| `APPRISE_URL`                  | `http://apprise:8000`         | Internal Apprise API used for motion notifications   |
| `WEBRTC_STUN_URLS`             | Google STUN                   | Comma-separated STUN/TURN URLs                       |
| `WEBRTC_TURN_URL`              | unset                         | Extra TURN URL used by the relay and viewers         |
| `WEBRTC_TURN_USERNAME`         | unset                         | TURN username                                        |
| `WEBRTC_TURN_CREDENTIAL`       | unset                         | TURN password                                        |
| `WEBRTC_UDP_PORT_RANGE`        | ephemeral                     | Fixed UDP range, for example `40000-40100`           |
| `WEBRTC_ADVERTISE_HOST`        | private `PUBLIC_URL` address  | Host/IP viewers use for WebRTC candidates            |
| `WEBRTC_FORCE_TURN`            | `false`                       | Force `iceTransportPolicy: relay` on both sides      |

Copy `.env.example` to `.env` and adjust the public URL and, if needed, TURN
credentials. `PUBLIC_URL` matters when the server sits behind a reverse proxy,
because the pairing link must contain the address the phone can reach.

Set `DATABASE_URL` to choose the database backend. The relay creates its tables
on startup, so an empty database is enough:

```bash
DATABASE_URL=sqlite:///data/relay.sqlite
DATABASE_URL=mysql://user:pass@localhost:3306/relay
DATABASE_URL=postgres://user:pass@localhost:5432/relay
```

When `DATABASE_URL` is unset, the relay uses `sqlite:///data/relay.sqlite`.

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

The compose file publishes the WebRTC UDP range and stores the default SQLite
database under `./storage`. Traefik terminates HTTPS and routes the web/API/WebSocket
traffic to port 3000; the UDP range bypasses Traefik.

For a standalone run without Traefik:

```bash
docker build -t android-ip-camera-relay .
docker run --rm -p 3000:3000 -p 40000-40100:40000-40100/udp \
  -v "$PWD/storage:/storage" \
  -e PUBLIC_URL=https://relay.example.com \
  -e DATABASE_URL=sqlite:///data/relay.sqlite \
  -e WEBRTC_UDP_PORT_RANGE=40000-40100 \
  android-ip-camera-relay
```

## Local development

The base `compose.yml` builds and runs the production bundle. For hot reload,
layer the development override on top of it:

```bash
docker compose -f compose.yml -f compose.dev.yml up --build
```

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
- Recording playback is progressive: the relay reads byte ranges from the camera
  and holds at most an 8 MiB read-ahead buffer per active recording. No complete
  recording is downloaded, and all playback data is discarded when the WebRTC
  session ends. Keep the Android app up to date, because playback requires the
  app's `/files/` endpoint to honour `Range` requests.
- `/r/:deviceId/*` is only reachable while that camera's WebSocket is online.
- Notification URLs can contain service tokens and passwords. They are stored in
  the device row and returned by `GET /api/devices/:id/notifications`, so protect
  the dashboard and API with your reverse proxy's authentication when the relay
  is public.
- Device names are user generated and limited to 1–100 characters using letters,
  numbers, spaces, and hyphens. The server trims the name and validates it with
  the same rules the dashboard's rename prompt uses.
- Put the relay behind HTTPS. The Android app validates the relay's certificate
  for the outbound WSS connection; only the loopback camera proxy uses a
  trust-all context (for the app's self-signed certificate).
- After the relay removes a camera, its token is rejected with HTTP 401 and the
  body `device not registered`; the app stops reconnecting and forgets the
  association. The app treats 401/403 as terminal only when the body contains
  that marker, so a WAF or proxy 401/403 does not clear the association.
