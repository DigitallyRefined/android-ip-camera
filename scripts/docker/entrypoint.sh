#!/usr/bin/env bash
# Entry point for the android-ip-camera Docker builder image.
set -euo pipefail

WORKSPACE=/workspace
APKS_DIR=app/build/outputs/apk

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
    echo "       Install it with: sdkmanager 'build-tools;34.0.0'" >&2
    exit 1
  fi
}

log() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
warn() { printf '\033[1;33m== %s ==\033[0m\n' "$*"; }

signing_info() {
  local apk="$1"

  find_apksigner
  log "Signing (and build) info for: ${apk}"
  "${APKSIGNER}" verify --print-certs "$apk"

  local sha256
  sha256="$("${APKSIGNER}" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
  if [ -n "$sha256" ]; then
    if [ "$sha256" = "$EXPECTED_RELEASE_SHA256" ]; then
      log "OK: signer SHA-256 ${sha256} matches the official release key documented in README.md"
    else
      warn "Signer SHA-256 ${sha256} differs from the official release key (${EXPECTED_RELEASE_SHA256})."
      warn "This is expected when building with a different or freshly generated key."
    fi
  fi
}

usage() {
  cat <<'EOF'
Usage: apic-build <command> [gradle args...]

Commands:
  debug        Build the debug APK:       ./gradlew assembleDebug [extra args]
  release      Build + verify the signed release APK:
                 ./gradlew assembleRelease [extra args]
                 then apksigner verify --print-certs on every release APK
  signing-info Print signing-key info for already-built release APKs
  install      Build & locally install the signed release onto a connected device
  <anything>   Passed straight through, e.g. `gradlew test` or `/bin/bash`

Examples:
  apic-build debug
  apic-build release
  apic-build release -PenableAbiSplits=false     # single universal APK
  apic-build gradlew tasks
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
  debug)
    exec ./gradlew assembleDebug "$@"
    ;;
  release)
    ./gradlew assembleRelease "$@"
    shopt -s nullglob
    apks=( "${APKS_DIR}"/release/*-release.apk )
    if [ ${#apks[@]} -eq 0 ]; then
      echo "ERROR: no release APK under ${APKS_DIR}/release" >&2
      exit 1
    fi
    for apk in "${apks[@]}"; do signing_info "$apk"; done
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
    ./gradlew installRelease "$@"
    ;;
  *)
    exec "$cmd" "$@"
    ;;
esac