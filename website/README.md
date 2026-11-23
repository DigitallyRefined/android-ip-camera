# Android IP Camera website

The project website and documentation, built with
[Docusaurus](https://docusaurus.io/) deployed to: https://ip-cam.app

The documentation is **generated from the repository's `README.md`** by
[`plugins/readme-docs`](plugins/readme-docs/index.ts). Do not edit files in
`docs/`: the plugin recreates the folder every time the site starts or builds.
Edit the root `README.md` instead, then restart the dev server so the generated
pages and sidebar are rebuilt.

The site does **not** keep copies of the app artwork. The logo, favicon and
screenshots — including `screenshot.webp` — live in the Fastlane image
directory, mounted at `/workspace/assets` in the container and exposed through
Docusaurus's `staticDirectories` setting. They are copied into `build/` at build
time.

## Local development

Run from the repository root:

```bash
docker rm -f nodejs

docker run -d --name nodejs \
  -w /workspace/website \
  -v "$PWD/website:/workspace/website" \
  -v "$PWD/README.md:/workspace/README.md:ro" \
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
`../fastlane/metadata/android/en-US/images`. Set `README_PATH` if the README is
not mounted at `/workspace/README.md` or found at `../README.md`.

## Build

Run from the repository root:

```bash
docker run --rm \
  -w /workspace/website \
  -v "$PWD/website:/workspace/website" \
  -v "$PWD/README.md:/workspace/README.md:ro" \
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
`main` that touch `README.md`, `website/**`, or the Fastlane images. It can also
be run manually with `workflow_dispatch`.

Configure the repository secrets:

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
