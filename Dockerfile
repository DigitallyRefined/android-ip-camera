# syntax=docker/dockerfile:1
# check=skip=FromPlatformFlagConstDisallowed
#
# =============================================================================
# android-ip-camera - Docker Android builder
# =============================================================================
#
# A small image that only ships the JDK and the tools needed to bootstrap a
# build. The Android SDK/NDK, the Gradle caches and the Rust toolchain are
# installed into a bind-mounted cache on first use, so they are reused between
# runs and do not make the image itself huge.
#
# Gradle's daemon is disabled (GRADLE_OPTS below): a daemon cannot survive the
# end of a `docker run` container, so starting one only wastes time and leaves
# stale daemon registries in the cache.
#
# -----------------------------------------------------------------------------
# USAGE
# -----------------------------------------------------------------------------
# 1) Build the image once:
#
#        docker build -t android-ip-camera:builder .
#
# 2) Run a command. Mount the repo and a cache directory. The first run
#    downloads the Android SDK/NDK and the Rust toolchain into the cache; later
#    runs reuse it:
#
#        mkdir -p "$HOME/.cache"
#        docker run --rm \
#          -v "$PWD":/workspace \
#          -v "$HOME/.cache":/cache \
#          android-ip-camera:builder debug
#
#    Debug APK:
#        ... android-ip-camera:builder debug
#
#    Signed release APK + signing-key info (shown after the build):
#        ... android-ip-camera:builder release
#
#    Release as a single universal APK (F-Droid style):
#        ... android-ip-camera:builder release -PenableAbiSplits=false
#
#    Signed release app bundle + certificate info (shown after the build):
#        ... android-ip-camera:builder releaseBundle
#
#    Print key info for already-built release APKs:
#        ... android-ip-camera:builder signing-info
#
#    Any other command, e.g. run tests:
#        ... android-ip-camera:builder ./gradlew test
#
#    The cache holds gradle/, android-sdk/, cargo/ and rustup/ under
#    "$HOME/.cache". Remove those directories to force a clean reinstall.
#
#    No `--user` flag is needed with rootless Docker: container root maps to
#    your host user, so build outputs and caches stay owned by you. Under
#    rootful Docker, add `-u "$(id -u):$(id -g)"`.
#
# 3) APKs are output to:
#
#        app/build/outputs/apk/debug/androidipcamera-*-universal-debug.apk
#        app/build/outputs/apk/release/androidipcamera-*-universal-release.apk
#        app/build/outputs/apk/release/androidipcamera-*-{arm64-v8a,armeabi-v7a}-release.apk
#        app/build/outputs/bundle/release/app-release.aab
#
#    The release APKs and app bundle are signed with your app/signing-key.jks
#    credentials from signing.properties (storeFile=signing-key.jks) and
#    verified afterwards.
# =============================================================================

# The Android NDK only ships x86_64 Linux host tools, so a native arm64
# container cannot run its clang. Pin the image to linux/amd64: on x86_64 hosts
# that is native, and on ARM hosts (Apple Silicon, ARM Linux) Docker runs it
# through x86_64 emulation, which Docker Desktop sets up automatically. The
# constant platform trips BuildKit's FromPlatformFlagConstDisallowed lint, which
# is skipped deliberately at the top of this file.
FROM --platform=linux/amd64 eclipse-temurin:21-jdk

# Everything expensive lives in the mounted /cache and is provisioned by the
# entry point. Only the SDK command-line tools and build tools are in the image.
ENV CACHE_DIR=/cache \
    ANDROID_HOME=/cache/android-sdk \
    ANDROID_SDK_ROOT=/cache/android-sdk \
    ANDROID_NDK_HOME=/cache/android-sdk/ndk/27.2.12479018 \
    GRADLE_USER_HOME=/cache/gradle \
    RUSTUP_HOME=/cache/rustup \
    CARGO_HOME=/cache/cargo \
    GRADLE_OPTS="-Dorg.gradle.daemon=false" \
    PATH="/cache/cargo/bin:/opt/cmdline-tools/bin:/cache/android-sdk/platform-tools:${PATH}"

ARG ANDROID_CMD_TOOLS_VERSION=11076708

WORKDIR /workspace

# Build tools for Gradle and for compiling Rust crates. rustup comes from the
# distribution; the toolchain it manages is installed into the mounted cache.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl unzip git build-essential rustup \
 && rustup --version \
 && rm -rf /var/lib/apt/lists/*

# Install only the Android command-line tools; sdkmanager downloads the SDK and
# NDK into $ANDROID_HOME (the mounted cache) on first run.
RUN mkdir -p /opt/cmdline-tools \
 && curl -fSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMD_TOOLS_VERSION}_latest.zip" \
 && unzip -q /tmp/cmdline-tools.zip -d /opt/cmdline-tools \
 && mv /opt/cmdline-tools/cmdline-tools/* /opt/cmdline-tools/ \
 && rmdir /opt/cmdline-tools/cmdline-tools \
 && rm -f /tmp/cmdline-tools.zip

COPY scripts/docker/entrypoint.sh /usr/local/bin/apic-build
RUN chmod +x /usr/local/bin/apic-build

ENTRYPOINT ["/usr/local/bin/apic-build"]
CMD ["--help"]
