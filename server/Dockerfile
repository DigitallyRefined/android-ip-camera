# https://github.com/oven-sh/bun/releases
FROM oven/bun:1.4.4-alpine AS base

RUN apk upgrade --no-cache

RUN apk add --no-cache apprise

FROM base AS builder

COPY package.json bun.lock ./

RUN bun install

COPY . .

ENV NODE_ENV=production
RUN bunx @tailwindcss/cli --minify -i ./src/frontend/index.css -o ./src/frontend/index-out.css
RUN mv ./src/frontend/index-out.css ./src/frontend/index.css
RUN bun build src/index.ts --minify --target=bun --outdir=dist

EXPOSE 3000

ENV NODE_ENV=development
CMD ["bun", "dev"]

FROM base AS production

ARG TARGETPLATFORM

LABEL org.opencontainers.image.source=https://github.com/DigitallyRefined/android-ip-camera
LABEL org.opencontainers.image.description="android-ip-camera-server ${TARGETPLATFORM}"

COPY --from=builder /home/bun/app/dist .

EXPOSE 3000

ENV NODE_ENV=production
CMD ["bun", "."]
