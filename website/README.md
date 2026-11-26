# Android IP Camera website

The project website and documentation, built with
[Docusaurus](https://docusaurus.io/) deployed to: https://ip-cam.app

The documentation is **generated from the repository's `README.md`** by
[`plugins/readme-docs`](plugins/readme-docs/index.ts). Do not edit files in
`docs/`: the plugin recreates the folder every time the site starts or builds.
Edit the root `README.md` instead, then restart the dev server so the generated
pages and sidebar are rebuilt.

The site does **not** keep copies of the app artwork. The logo, favicon and
store screenshots live in the Fastlane image directory, mounted at
`/workspace/assets` in the container and exposed through Docusaurus's
`staticDirectories` setting. The README screenshot, `screenshot.webp`, lives at
the repository root and is copied to `website/static/` by the
[`readme-docs` plugin](plugins/readme-docs/index.ts) before the dev server starts
or the site builds. All of these are copied into `build/` at build time.

## Local development

Run from the repository root:

```bash
docker rm -f nodejs

docker run -d --name nodejs \
  -w /workspace/website \
  -v "$PWD/website:/workspace/website" \
  -v "$PWD/README.md:/workspace/README.md:ro" \
  -v "$PWD/screenshot.webp:/workspace/screenshot.webp:ro" \
  -v "$PWD/fastlane/metadata/android/en-US/images:/workspace/assets:ro" \
  -e DOCUSAURUS_ASSETS_DIR=/workspace/assets \
  -p 3000:3000 \
  node:lts \
  npx docusaurus start --host 0.0.0.0 --poll 1000
```

The development server listens on <http://localhost:3000>.

Outside the container:

```bash
npm install
npm start
```

Without `DOCUSAURUS_ASSETS_DIR`, the images default to
`../fastlane/metadata/android/en-US/images`. The plugin copies the root
`screenshot.webp` into `website/static/` when the dev server starts or the site
builds. Set `README_PATH` if the README is not mounted at `/workspace/README.md`
or found at `../README.md`.

## Build

Run from the repository root:

```bash
docker run --rm \
  -w /workspace/website \
  -v "$PWD/website:/workspace/website" \
  -v "$PWD/README.md:/workspace/README.md:ro" \
  -v "$PWD/screenshot.webp:/workspace/screenshot.webp:ro" \
  -v "$PWD/fastlane/metadata/android/en-US/images:/workspace/assets:ro" \
  -e DOCUSAURUS_ASSETS_DIR=/workspace/assets \
  node:lts \
  sh -c 'npm install && npm run build'
```

Static output is written to `website/build/`. The generated documentation and
sidebar are read from `website/docs/`. The plugin picks up the README from the
repository root, `/README.md`, or the website folder, in that order.

To build without Docker:

```bash
npm run build
```

## Theme

The theme follows the browser or operating system light/dark preference by
default (`themeConfig.colorMode.respectPrefersColorScheme`), and keeps the
navbar light/dark switch available for an explicit choice.

## Deployment

`url` defaults to `https://ip-cam.app` and `baseUrl` defaults to `/`. Override
them when building for another domain:

```bash
DOCUSAURUS_URL=https://example.com \
DOCUSAURUS_BASE_URL=/ \
npm run build
```

### Cloudflare Pages

[`deploy-website.yml`](../.github/workflows/deploy-website.yml) builds the site
with Node LTS and deploys `website/build` to Cloudflare Pages on pushes to
`main` that touch `README.md`, `screenshot.webp`, `website/**`, or the Fastlane
images. It can also be run manually with `workflow_dispatch`.

#### Cloudflare credentials

##### `CLOUDFLARE_API_TOKEN`

Create a **User API Token** from **My Profile → API Tokens**:

1. Open <https://dash.cloudflare.com/profile/api-tokens>.
2. Select **Create Token**.
3. Choose **Create Custom Token → Get started**.
4. Name it something like `android-ip-camera-pages-deploy`.
5. Add these permissions:

   | Scope | Permission |
   | --- | --- |
   | Account | Cloudflare Pages → Edit |
   | Account | Account Settings → Read |
   | User | Memberships → Read |

   `User → User Details → Read` is optional, but can help Wrangler resolve the
   account when the membership lookup still fails.

6. Set **Account Resources** to **Include** your Cloudflare account.
7. Select **Continue to summary → Create Token**.
8. Copy the token immediately; Cloudflare only shows it once.

No Zone, DNS, Workers, or R2 permissions are needed. Do not use a Global API
Key.

`Cloudflare Pages → Edit` is required to create deployments.
`Account Settings → Read` and `User → Memberships → Read` allow Wrangler to
resolve the account and its membership roles.

If you created the token under **Account API Tokens**, it cannot have
`User → Memberships → Read` because that is a user-scoped permission. Use a
token created from **My Profile → API Tokens** instead. The workflow already
passes `CLOUDFLARE_ACCOUNT_ID`, so the membership warning can be harmless, but
the deployment still requires `Cloudflare Pages → Edit` on the account that
owns the Pages project.

##### `CLOUDFLARE_ACCOUNT_ID`

Find the account ID for the account that owns the Pages project:

- **Dashboard:** open **Workers & Pages** or a **Zone Overview** and find
  **Account ID** under the API/account details.
- **Wrangler:** after `npx wrangler login`, run `npx wrangler whoami`.

The account ID is a 32-character identifier such as:

```text
0123456789abcdef0123456789abcdef
```

##### Add the GitHub secrets

In the repository, open **Settings → Secrets and variables → Actions → New
repository secret** and add:

```text
CLOUDFLARE_API_TOKEN
CLOUDFLARE_ACCOUNT_ID
```

Optional repository variables:

```text
CLOUDFLARE_PAGES_PROJECT   # default: android-ip-camera
DOCUSAURUS_URL             # default: https://ip-cam.app
DOCUSAURUS_BASE_URL        # default: /
```
