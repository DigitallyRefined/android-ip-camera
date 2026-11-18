# A self-hosted OpenTunnel server

Runs the [OpenTunnel](https://github.com/anomalyco/opentunnel) server in place of
`opentunnel.xyz`: anyone can point the CLI or an SDK at
`https://tunnel.example.com` and gets a random `<id>.tunnel.example.com` URL that
tunnels to their own machine.

OpenTunnel is a *blind* TLS tunnel. The server routes on the hostname in the TLS
ClientHello and never decrypts visitor traffic; each client holds the private key
for its own certificate. Nothing here is an account system — a tunnel URL is
public to anyone who has it.

Two containers:

- `opentunnel` — the server (`crates/opentunnel-server`), built from source
  because upstream publishes no server image. It listens for TLS on 8443 (the
  API, the bridge WebSocket, and every tunnel) and plain HTTP on 8080.
- `mysql` — tunnel records, hashed tokens, certificate state and issuance jobs.

## Pick a stack

`docker-compose.yml` is the whole stack. `docker-compose.traefik.yml` is an
override file, not a stack of its own: it only holds the settings that change
when Traefik sits in front, and is layered on top of the base file.

| | direct (base file only) | behind Traefik (base + override) |
| --- | --- | --- |
| Public ports | the host's `443` and `80` | none; Traefik's |
| TLS for the API domain | issued and served by the server | Traefik's certificate |
| Tunnel hostnames | the server's own TLS listener | Traefik TCP passthrough |
| Client address | the real one, so limits are per client | Traefik's, so the limit is global |
| Needs | 443 and 80 free on the host | the external `traefik` network |

The direct stack is the simpler one: the server does the SNI routing itself, so
there is nothing between it and the Internet. Use the Traefik stack when Traefik
already owns 443 and serves other sites.

To layer the override, pass both files with the base first, on every command:

```sh
docker compose -f docker-compose.yml -f docker-compose.traefik.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.traefik.yml logs -f opentunnel
```

Both variants use the same compose project (`tunnel-server`), `.env`, and
`./mysql-data`, so switching keeps every tunnel. Run one at a time — they both
want the public port (the override removes the base file's `ports:`).

## Setup

The commands below use `docker compose`, and the direct stack. If you picked the
Traefik stack, add the two files to every command, base first:

```sh
docker compose -f docker-compose.yml -f docker-compose.traefik.yml <command>
```

Rather than repeating the flags, export them once per shell; plain
`docker compose` then behaves as the Traefik stack:

```sh
export COMPOSE_FILE=docker-compose.yml:docker-compose.traefik.yml
```

1. **DNS.** Point both `tunnel.example.com` and `*.tunnel.example.com` at this
   host (A and/or AAAA). The wildcard record is what makes
   `<id>.tunnel.example.com` resolve.

2. **`.env`.** Every setting lives there, and the containers read it directly:

   ```sh
   cp .env.example .env
   ```

   Its comments say how to generate or look up each value, and the Traefik
   labels read it too, so the domain is set in one place.

3. **ACME.** The server needs an ACME CA with external account binding; the
   default is ZeroSSL's free DV90. Create an account at
   <https://app.zerossl.com>, then copy the EAB KID and HMAC key from
   *Developer → EAB Credentials* into `.env`. The account key is a P-256 JWK you
   generate once, from the built server:

   ```sh
   docker compose build
   docker compose run --rm --no-deps opentunnel account-key
   ```

   Put the printed JSON on one line as `ACME_ACCOUNT_KEY_JWK=`. Keep it: it names
   the ACME account, and a new key means a new account.

   Certificates are issued over DNS-01, so the server also needs to write
   `_acme-challenge` TXT records in the zone. With Cloudflare, set
   `CLOUDFLARE_ZONE_ID` (`example.com`, the zone that holds the domain) and a
   `CLOUDFLARE_API_TOKEN` with `Zone → DNS → Edit`. For any other provider, set
   `DNS_PROVIDER=exec` and `DNS_HOOK` to an executable in the image; see
   [docs/server.md](https://github.com/anomalyco/opentunnel/blob/dev/docs/server.md#self-hosting).

4. **Traefik (Traefik stack only).** The override makes the server join the
   external `traefik` network. Its labels assume the entrypoint is called
   `websecure`; adjust `traefik.http.routers.tunnel-api.entrypoints` and
   `traefik.tcp.routers.tunnel.entrypoints` in `docker-compose.traefik.yml` if
   yours differs. See [Behind Traefik](#behind-traefik) for the certificates it
   needs.

5. **Start:**

   ```sh
   docker compose up -d --build
   docker compose logs -f opentunnel
   ```

   The image build is the slow part (Rust plus the website, several minutes on a
   small host); later `up` runs reuse it.

   With the direct stack, `http://tunnel.example.com/health` answers right away;
   `https://` works once the server has issued its own certificate for the
   domain (a minute or two on first start, logged as `installed a new certificate
   for the API domain`). On the Traefik stack, `https://` answers as soon as
   Traefik has a certificate for the domain. Either way the first tunnel a user
   starts can take a minute or two while its certificate is issued.

## Using it

Point the CLI at the server. Nothing else changes; hostnames come from it.

```sh
OPENTUNNEL_API=https://tunnel.example.com opentunnel route add 3000
# Added route 21992cc9713e5fc5 → 127.0.0.1:3000
# https://21992cc9713e5fc5.<id>.tunnel.example.com
```

To make it permanent, `export OPENTUNNEL_API=https://tunnel.example.com` in your
shell or pass `--api`. Each server has its own tunnels, so an existing profile
created against `opentunnel.xyz` cannot be reused; use `--profile <name>` for a
second identity.

The nginx example in `../nginx-opentunnel` does the same with
`OPENTUNNEL_API=https://tunnel.example.com` in its `.env`.

## Behind Traefik

The hostname is the only routing information the server needs, and it lives in
the ClientHello. The certificate's key is on the user's machine, so anything that
decrypts the connection (an HTTPS router with `tls: true`, a normal reverse
proxy) would have to hold that key, which defeats the design. So the wildcard is
a Traefik **TCP** router with `tls.passthrough: true`. Traefik tries TCP routers
before HTTP routers on the same entrypoint, which is why the apex can still use a
normal HTTPS router.

The apex needs a certificate for `tunnel.example.com` in Traefik's store. A
`*.example.com` wildcard already covers it; otherwise give the router a
`certresolver` (see the commented label in `docker-compose.traefik.yml`). Do
**not** ask Traefik for `*.tunnel.example.com`: those certificates belong to the
clients.

## Operations

- **Logs:** `docker compose logs -f opentunnel` (JSON lines; `LOG_FORMAT` is
  `json`). `RUST_LOG=opentunnel_server=debug` for per-connection detail.
- **Stats / backups:** set `ADMIN_TOKEN` in `.env` and recreate the container
  to enable `/api/admin/*`:

  ```sh
  curl -fsS -X POST https://tunnel.example.com/api/admin/stats \
    -H "authorization: Bearer $ADMIN_TOKEN" | jq .
  docker compose exec -T opentunnel opentunnel-server export - > tunnels.json
  docker compose exec -T opentunnel opentunnel-server import - < tunnels.json
  ```

  Exports contain token *hashes* but identify every live tunnel — keep them
  private. `./mysql-data` is the other thing to back up.
- **Upgrades:** change the tag in `docker-compose.yml`
  (`...opentunnel.git#v0.6.2`), then `docker compose build && docker compose up -d`.
  Clients reconnect on their own. Changes to the Traefik labels or limits only
  need `docker compose up -d`, no rebuild.
- **Restart / move:** tunnels and their hostnames live in MySQL, not in the
  container, so `up -d` or a new host keeps every URL.

## Things worth knowing

- **Open by design.** Anyone who can reach the API can create tunnels; there is
  no auth. That is the point of a public tunnel service, but it means the
  bandwidth and the ACME quota are shared. Keep the rate limits on.
- **Direct stack:** the server issues the certificate for `tunnel.example.com`
  itself (via the same ACME/DNS-01 flow as tunnel hostnames) and renews it
  before expiry, so nothing else needs a copy of the domain's key. It also sees
  real client addresses, which keeps `CREATE_RATE_PER_HOUR` per client (30/h by
  default).
- **Traefik stack:** all API requests arrive from Traefik's container address,
  so the per-IP create limit is effectively a global budget — hence the raised
  `300/h` in that compose file. Its `TRUSTED_PROXY_RANGES` logic only knows
  `CF-Connecting-IP`, and Traefik cannot rewrite a header from the client
  address, so real per-IP limiting would need a PROXY protocol transport
  (Traefik can only define those in a file provider, not labels). For the same
  reason the server sees every visitor's address as Traefik's; certificates,
  tunnels and `--proxy-protocol` routes are unaffected.
- **The domain is part of every hostname.** Changing `OPENTUNNEL_DOMAIN` on an
  existing database leaves old tunnels unroutable; start with a fresh
  `./mysql-data` if you need to change it.
- **No server certificate for the wildcard.** Visitors and clients get a
  per-tunnel certificate issued to `<id>.<domain>` plus its wildcard, and the
  client holds the private key. The server only keeps CSRs and certificate
  chains.
- **Metrics** are off (`METRICS_LISTEN=off`). Set e.g. `METRICS_LISTEN=0.0.0.0:9091`
  and scrape `/metrics` from a private network if you want them.
