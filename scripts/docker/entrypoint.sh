#!/usr/bin/env bash
# Entry point for the android-ip-camera Docker builder image.
#
# The image only contains the JDK, the Android command-line tools and basic
# build tools. Everything expensive (Android SDK/NDK, Gradle caches and the
# Rust toolchain) is installed into the bind-mounted /cache directory on first
# use and reused by later runs.
set -euo pipefail

WORKSPACE=/workspace
CACHE_DIR="${CACHE_DIR:-/cache}"
APKS_DIR=app/build/outputs/apk
BUNDLES_DIR=app/build/outputs/bundle

# Keep these in sync with app/build.gradle and rust-toolchain.toml.
ANDROID_PLATFORM=android-36
ANDROID_BUILD_TOOLS=36.0.0
ANDROID_NDK_VERSION=27.2.12479018
SDKMANAGER=/opt/cmdline-tools/bin/sdkmanager

# SHA-256 of the official release signing certificate (see README.md).
# Override with -e EXPECTED_RELEASE_SHA256=... if you sign with your own key.
EXPECTED_RELEASE_SHA256="${EXPECTED_RELEASE_SHA256:-1111be81c861e199c6485d367c37680c4b778fba301980d2f0f9a2800f77f70a}"

if [ -d "$WORKSPACE" ]; then
  cd "$WORKSPACE"
else
  cd /tmp
fi

APKSIGNER=""
find_apksigner() {
  APKSIGNER="$(ls "${ANDROID_HOME}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
  if [ -z "${APKSIGNER}" ]; then
    echo "ERROR: apksigner not found under \$ANDROID_HOME/build-tools" >&2
    echo "       Install it with: sdkmanager 'build-tools;${ANDROID_BUILD_TOOLS}'" >&2
    exit 1
  fi
}

log() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
warn() { printf '\033[1;33m== %s ==\033[0m\n' "$*"; }

ensure_cache() {
  mkdir -p "$CACHE_DIR" "$ANDROID_HOME" "$GRADLE_USER_HOME" "$CARGO_HOME" "$RUSTUP_HOME"
  if ! mountpoint -q "$CACHE_DIR"; then
    warn "$CACHE_DIR is not a mount point."
    warn "Cache everything between runs with: -v \"\$HOME/.cache\":/cache"
  fi
}

ensure_android_sdk() {
  log "Accepting Android SDK licences in ${ANDROID_HOME}"
  # sdkmanager sometimes stops reading before `yes` gets SIGPIPE; that is not a
  # failure, so keep pipefail out of this pipeline.
  set +o pipefail
  yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
  set -o pipefail

  if [ ! -d "$ANDROID_HOME/platforms/$ANDROID_PLATFORM" ] \
    || [ ! -d "$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS" ] \
    || [ ! -d "$ANDROID_HOME/platform-tools" ] \
    || [ ! -d "$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION" ]; then
    log "Installing Android SDK/NDK into ${ANDROID_HOME} (first run only)"
    "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --install \
      "platform-tools" \
      "platforms;$ANDROID_PLATFORM" \
      "build-tools;$ANDROID_BUILD_TOOLS" \
      "ndk;$ANDROID_NDK_VERSION"
  fi
}

ensure_rust() {
  if ! command -v rustup >/dev/null 2>&1; then
    echo "ERROR: rustup is missing from the builder image" >&2
    exit 1
  fi

  local toolchain_file="$WORKSPACE/rust-toolchain.toml"
  if [ ! -f "$toolchain_file" ]; then
    echo "ERROR: ${toolchain_file} was not found." >&2
    echo "       Run the container from the repository root and make sure the" >&2
    echo "       checkout contains rust-toolchain.toml; the build uses the exact" >&2
    echo "       toolchain and targets pinned there." >&2
    exit 1
  fi

  log "Using Rust toolchain pinned by ${toolchain_file}"

  # Resolve and install the pinned toolchain with `show active-toolchain`.
  # `rustup show` must not be used here: on a reused cache it fails with
  # "error: no default toolchain configured". Installing from a toolchain file
  # does not set a default, so only the very first run (empty cache) succeeds.
  local toolchain=""
  if ! toolchain="$(rustup show active-toolchain | awk 'NR == 1 { print $1 }')" \
    || [ -z "$toolchain" ]; then
    echo "ERROR: rustup could not activate the toolchain pinned in ${toolchain_file}" >&2
    exit 1
  fi

  rustup target add --toolchain "$toolchain" \
    aarch64-linux-android \
    armv7-linux-androideabi >/dev/null
}

provision() {
  ensure_cache
  ensure_android_sdk
  ensure_rust
}

check_release_sha256() {
  local sha256="$1"
  if [ -z "$sha256" ]; then
    return
  fi

  if [ "$sha256" = "$EXPECTED_RELEASE_SHA256" ]; then
    log "OK: signer SHA-256 ${sha256} matches the official release key documented in README.md"
  else
    warn "Signer SHA-256 ${sha256} differs from the official release key (${EXPECTED_RELEASE_SHA256})."
    warn "This is expected when building with a different or freshly generated key."
  fi
}

signing_info() {
  local apk="$1"

  find_apksigner
  log "Signing (and build) info for: ${apk}"
  "${APKSIGNER}" verify --print-certs "$apk"

  local sha256
  sha256="$("${APKSIGNER}" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
  check_release_sha256 "$sha256"
}

bundle_signing_info() {
  local bundle="$1"

  # apksigner only understands APKs. App bundles carry a JAR signature, so
  # verify them with jarsigner and print the certificate with keytool.
  if ! command -v jarsigner >/dev/null 2>&1 || ! command -v keytool >/dev/null 2>&1; then
    echo "ERROR: jarsigner/keytool not found on PATH (a JDK is required)" >&2
    exit 1
  fi

  # jarsigner also prints a warning for every entry that is signed in the JAR
  # but not in the streaming JarInputStream; those are normal for AABs, so only
  # its exit status matters here.
  if ! jarsigner -verify "$bundle" >/dev/null 2>&1; then
    echo "ERROR: the app bundle signature could not be verified: ${bundle}" >&2
    exit 1
  fi

  local certs sha256
  certs="$(keytool -printcert -jarfile "$bundle" 2>/dev/null || true)"
  sha256="$(printf '%s\n' "$certs" \
    | sed -n 's/^[[:space:]]*SHA256: //p' \
    | head -n 1 \
    | tr -d ':' \
    | tr 'A-F' 'a-f')"

  if [ -z "$sha256" ]; then
    echo "ERROR: no signer certificate found in ${bundle}" >&2
    echo "       The bundle is unsigned; remove -PskipSigning or configure signing.properties." >&2
    exit 1
  fi

  log "Signing (and build) info for: ${bundle}"
  printf '%s\n' "$certs"
  check_release_sha256 "$sha256"
}

usage() {
  cat <<'EOF'
Usage: apic-build <command> [gradle args...]

The first build installs the Android SDK/NDK and the Rust toolchain into the
mounted /cache directory. Mount one to reuse them, e.g.:

  docker run --rm \
    -v "$PWD":/workspace \
    -v "$HOME/.cache":/cache \
    android-ip-camera:builder debug

Commands:
  debug          Build the debug APK:       ./gradlew assembleDebug [extra args]
  release        Build + verify the signed release APK:
                   ./gradlew assembleRelease [extra args]
                   then apksigner verify --print-certs on every release APK
  releaseBundle  Build + verify the signed release app bundle:
                   ./gradlew bundleRelease [extra args]
                   then jarsigner verify + keytool -printcert on the AAB
  clean          Clean the Gradle build:    ./gradlew clean [extra args]
  clean-cache    Delete the SDK/NDK, Gradle, Cargo and rustup caches from /cache
  signing-info   Print signing-key info for already-built release APKs
  install        Build & locally install the signed release onto a connected device
  <anything>     Passed straight through, e.g. `./gradlew test`

Examples:
  apic-build debug
  apic-build release
  apic-build release -PenableAbiSplits=false     # single universal APK
  apic-build releaseBundle
  apic-build clean
  apic-build clean-cache                          # next build redownloads everything
  apic-build ./gradlew tasks
EOF
  exit 0
}

[ $# -ge 1 ] || usage

cmd="$1"
shift

case "$cmd" in
  help|--help|-h)
    usage
    ;;
esac

# Only provision toolchains for commands that need them, so `apic-build
# /bin/bash` stays instant.
case "$cmd" in
  debug|release|releaseBundle|release-bundle|clean|install|signing-info|*gradlew*|gradle)
    provision
    ;;
esac

case "$cmd" in
  debug)
    exec ./gradlew assembleDebug --no-daemon "$@"
    ;;
  release)
    ./gradlew assembleRelease --no-daemon "$@"
    shopt -s nullglob
    apks=( "${APKS_DIR}"/release/*-release.apk )
    if [ ${#apks[@]} -eq 0 ]; then
      echo "ERROR: no release APK under ${APKS_DIR}/release" >&2
      exit 1
    fi
    for apk in "${apks[@]}"; do signing_info "$apk"; done
    ;;
  releaseBundle|release-bundle)
    ./gradlew bundleRelease --no-daemon "$@"
    shopt -s nullglob
    bundles=( "${BUNDLES_DIR}"/release/*.aab )
    if [ ${#bundles[@]} -eq 0 ]; then
      echo "ERROR: no app bundle under ${BUNDLES_DIR}/release" >&2
      exit 1
    fi
    for bundle in "${bundles[@]}"; do bundle_signing_info "$bundle"; done
    ;;
  clean)
    exec ./gradlew clean --no-daemon "$@"
    ;;
  clean-cache)
    log "Deleting cached Android SDK/NDK, Gradle and Rust toolchains from ${CACHE_DIR}"
    rm -rf "$ANDROID_HOME" "$GRADLE_USER_HOME" "$CARGO_HOME" "$RUSTUP_HOME"
    log "Cache cleared; the next build will download everything again"
    ;;
  signing-info)
    shopt -s nullglob
    apks=( "${APKS_DIR}"/release/*-release.apk )
    if [ ${#apks[@]} -eq 0 ]; then
      echo "ERROR: no release APK under ${APKS_DIR}/release - run: apic-build release" >&2
      exit 1
    fi
    for apk in "${apks[@]}"; do signing_info "$apk"; done
    ;;
  install)
    ./gradlew installRelease --no-daemon "$@"
    ;;
  *)
    exec "$cmd" "$@"
    ;;
esac
