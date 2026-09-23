# syntax=docker/dockerfile:1
#
# =============================================================================
# android-ip-camera - Docker Android builder
# =============================================================================
#
# Builds the app's DEBUG and SIGNED RELEASE APK(s) inside a container, using
# your repo's own signing.properties + app/signing-key.jks. The signing key and
# passwords are NEVER baked into the image - they are used from the bind-mounted
# repo at run time, so the image can be shared safely.
#
# Release builds are automatically verified with `apksigner verify --print-certs`
# and the signing certificate digest is checked against the official key in
# README.md. Override with `-e EXPECTED_RELEASE_SHA256=...` for your own key.
#
# -----------------------------------------------------------------------------
# USAGE
# -----------------------------------------------------------------------------
# 1) Build the image once:
#
#        docker build -t android-ip-camera:builder .
#
# 2) Seed the persistent Android SDK cache once (licenses + packages):
#    Uses --sdk_root so the (still empty) cache can't shadow the SDK tooling
#    that ships inside the image:
#
#        mkdir -p .cache/android-sdk
#        docker run --rm \
#          -v "$PWD/.cache/android-sdk":/cache/sdk \
#          android-ip-camera:builder \
#          sh -lc 'yes | sdkmanager --sdk_root=/cache/sdk --licenses >/dev/null \
#                   && sdkmanager --sdk_root=/cache/sdk --install "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null'
#
#    (If you skip .cache/android-sdk, the SDK baked into the image is used
#     instead and AGP simply re-downloads anything extra on demand.)
#
#    NOTE on users/permissions: no `--user` flag is needed. In rootless Docker
#    the container root maps to your host user, so build outputs and caches are
#    owned by you. Under rootful Docker, add `-u "$(id -u):$(id -g)"` if you
#    want the files to be owned by your host user.
#
# 3) Commands - every run shares the .cache/ folder, so dependencies, the
#    Gradle build cache and the SDK are kept between builds:
#
#    Debug APK:
#        docker run --rm \
#          -v "$PWD":/workspace \
#          -v "$PWD/.cache/gradle":/cache/gradle \
#          -v "$PWD/.cache/android-sdk":/opt/android-sdk \
#          android-ip-camera:builder debug
#
#    Signed release APK + signing-key info (shown after the build):
#        docker run --rm \
#          -v "$PWD":/workspace \
#          -v "$PWD/.cache/gradle":/cache/gradle \
#          -v "$PWD/.cache/android-sdk":/opt/android-sdk \
#          android-ip-camera:builder release
#
#    Release as a single universal APK (F-Droid style):
#        ... android-ip-camera:builder release -PenableAbiSplits=false
#
#    Print key info for already-built release APKs:
#        ... android-ip-camera:builder signing-info
#
#    Any other command, e.g. run tests:
#        ... android-ip-camera:builder gradlew test
#
# 4) APKs are output to:
#
#        app/build/outputs/apk/debug/androidipcamera-*-universal-debug.apk
#        app/build/outputs/apk/release/androidipcamera-*-universal-release.apk
#        app/build/outputs/apk/release/androidipcamera-*-{arm64-v8a,armeabi-v7a}-release.apk
#
#    The release APKs are signed with your app/signing-key.jks credentials from
#    signing.properties (storeFile=signing-key.jks) and verified afterwards.
# =============================================================================

FROM eclipse-temurin:21-jdk

# Repo's Gradle daemon config targets JDK 21; AGP 9.x needs 17+.
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/cache/gradle \
    PATH="/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}"

# SDK components matched to .github/workflows/reproducible-build.yml
ARG ANDROID_CMD_TOOLS_VERSION=11076708
ARG ANDROID_PLATFORM=android-34
ARG ANDROID_BUILD_TOOLS=34.0.0

WORKDIR /workspace

# Minimal tooling to fetch/unpack the SDK (git/ssh keep Gradle VCS plugins happy).
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl unzip git openssh-client \
 && rm -rf /var/lib/apt/lists/*

# Install Android command-line tools.
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
 && curl -fSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMD_TOOLS_VERSION}_latest.zip" \
 && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
 && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
 && rm -rf /tmp/cmdline-tools.zip

# Accept licenses (keeps AGP able to auto-download anything extra at build time)
# and pre-install the SDK packages used by CI.
RUN yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null \
 && "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" \
      "platform-tools" \
      "platforms;${ANDROID_PLATFORM}" \
      "build-tools;${ANDROID_BUILD_TOOLS}"

# The baked-in SDK must be writable so AGP auto-download and the optional
# `.cache/android-sdk` bind-mount (mounted as an unprivileged host user) work.
RUN chmod -R a+w "${ANDROID_HOME}"

COPY scripts/docker/entrypoint.sh /usr/local/bin/apic-build
RUN chmod +x /usr/local/bin/apic-build

ENTRYPOINT ["/usr/local/bin/apic-build"]
CMD ["--help"]