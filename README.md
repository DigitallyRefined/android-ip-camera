# Android IP Camera

[![downloads](https://img.shields.io/github/downloads/DigitallyRefined/android-ip-camera/total.svg)](https://github.com/DigitallyRefined/android-ip-camera/releases)
[![downloads](https://img.shields.io/github/downloads/DigitallyRefined/android-ip-camera/latest/total.svg)](https://github.com/DigitallyRefined/android-ip-camera/releases)

An Android H.264 / MJPEG IP Camera app

![Desktop Browser](screenshot.webp)

## 📲 Install

<div align="center">
<a href="https://github.com/DigitallyRefined/android-ip-camera/releases">
<img src="https://user-images.githubusercontent.com/69304392/148696068-0cfea65d-b18f-4685-82b5-329a330b1c0d.png"
alt="Get it on GitHub" align="center" height="70" /></a>

<a href="https://github.com/ImranR98/Obtainium" target="_blank">
<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/refs/heads/main/assets/graphics/badge_obtainium.png"
alt="Get it on Obtainium" align="center" height="70" /></a>

<a href="https://f-droid.org/en/packages/com.github.digitallyrefined.androidipcamera/" target="_blank">
<img src="https://f-droid.org/badge/get-it-on.png"
alt="Get it on F-Droid" align="center" height="70" /></a>
</div>

## Features

* 🌎 Built in server, just open the video stream in a web browser, video app or even set it as a camera for [Frigate](https://github.com/blakeblackshear/frigate) or a Home Assistant MJPEG IP Camera (using `https://[ip_address]:4444/video/mjpeg`)
* 📴 Option to turn the display off while streaming
* 🤳 Switch between the main or selfie camera
* 🎛️ Remote web interface with controls for camera section, image rotation, video recording, audio/video sync, flash light toggle, resolution, zoom, exposure and contrast
* 🖼️ Choose between different image quality settings and frame rates (to help reduce phone over heating)
* 🛂 Username and password protection
* 🔐 Automatic TLS certificate support to protect stream and login details via HTTPS
* 🥾 Optional start on boot

## ⚠️ Warning

If you are planning to run this 24/7, please make sure that your phone does not stay at 100% charge. Doing so may damage the battery and cause it to swell up, which could cause it to explode.

Some models include an option to only charge to 80%, make sure this is enabled where possible.

Note: running at a higher image quality may cause some phones to over heat, which can also damage the battery.

## 🎥 Frigate config

Use the example config below to add your phones camera to [Frigate](https://github.com/blakeblackshear/frigate), optionally uncommenting the audio lines (if required) & update the `rtsp` stream:

```yaml
go2rtc:
  streams:
    android-cam-video:
      - "https://[ip_address]:4444/video/h264"
    # android-cam-audio:
    #   - "https://[ip_address]:4444/audio"
    # android-cam:
    #  - ffmpeg:android-cam-video#video=copy
    #  - ffmpeg:android-cam-audio#audio=copy

cameras:
  android-cam:
    enabled: true
    ffmpeg:
      inputs:
        - path: rtsp://127.0.0.1:8554/android-cam-video # or android-cam
          input_args: preset-rtsp-restream
          roles:
            - detect
            - record
    #        - audio
```

## Server URL Paths & Remote Control API

When the streaming server is running (default port `4444`, via `https://` or `http://` depending on TLS configuration), you can access the following endpoints:

### 📺 Streams and Interfaces

* **Web Control Panel (`/`)**
  * **Usage:** Open `https://[ip_address]:[port]/` (or `http://...`) in any web browser.
  * **Description:** Serves the built-in control panel, which plays the rear or front camera video stream and allows muting/unmuting audio. Remote controls include: camera section, image rotation, flash light toggle, resolution, zoom, exposure and contrast.
* **Motion JPEG Video Stream (`/video/mjpeg`)**
  * **Usage:** Open directly in a web browser or configure in external home automation tools (e.g. Home Assistant MJPEG IP Camera).
  * **Format:** `multipart/x-mixed-replace; boundary=frame`
* **Raw H.264 Video Stream (`/video/h264`)**
  * **Usage:** Play in media players that support raw Annex-B H.264 stream demuxing.
    * **VLC:** Run `vlc https://[ip_address]:[port]/video/h264` (may require ignoring TLS validation if self-signed).
    * **MPV:** Run `mpv https://[ip_address]:[port]/video/h264 --demuxer-lavf-format=h264`
* **Live Audio Stream (`/audio`)**
  * **Usage:** Listen to the live microphone audio.
  * **Format:** `audio/wav` chunked transfer-encoding (WAV container, 16-bit PCM mono, 44.1kHz).
    * **VLC/MPV:** Run `vlc https://[ip_address]:[port]/audio` or `mpv https://[ip_address]:[port]/audio`.
* **Raw Audio Stream (`/audio/raw`)**
  * **Usage:** Listen to the raw (unprocessed) microphone source, bypassing any system audio processing (e.g. noise suppression, echo cancellation, AGC). On Android 7.0+ (API 24+) this uses the `UNPROCESSED` audio source; on older devices it falls back to the standard microphone.
  * **Format:** `audio/wav` chunked transfer-encoding (WAV container, 16-bit PCM mono, 44.1kHz).
    * **VLC/MPV:** Run `vlc https://[ip_address]:[port]/audio/raw` or `mpv https://[ip_address]:[port]/audio/raw`.
* **Still Snapshot (`/video/snapshot`)**
  * **Usage:** Fetch a single high-resolution image.
  * **Format:** `image/jpeg`
  * **Query Parameter:** `?camera=<id|front|back|toggle>` to query a specific camera sensor.
    * *Example:* `https://[ip_address]:[port]/video/snapshot?camera=back`
* **Device Info and Capabilities JSON (`/info.json`)**
  * **Usage:** Query available camera sensors, their supported resolutions, device battery percentage, Wi-Fi signal strength and individual camera len settings.
  * **Format:** `application/json`
* **Start Recording (`/record/start`, POST)**
  * **Usage:** Start recording the live camera feed to a local MP4 file. The camera and H.264 encoder are started on demand if not already running.
  * **Responses:**
    * `201 Created`: Recording started; returns the recording status JSON.
    * `409 Conflict`: `{"error":"already_recording", ...}` — a recording is already in progress.
    * `503 Service Unavailable`: `{"error":"no_encoder", ...}` — failed to initialize the H.264 encoder.
    * `500 Internal Server Error`: `{"error":"start_failed", ...}` — recording failed to start.
* **Stop Recording (`/record/stop`, POST)**
  * **Usage:** Stop the in-progress local MP4 recording and finalize the file.
  * **Responses:**
    * `200 OK`: Recording stopped; returns the final recording status JSON.
    * `409 Conflict`: `{"error":"not_recording", ...}` — no recording is in progress.
    * `500 Internal Server Error`: `{"error":"stop_failed", ...}` — failed to finalize the recording.
* **Recording Status (`/record/status`)**
  * **Usage:** Query whether a local MP4 recording is currently active.
  * **Format:** `application/json` — `{"recording":<bool>, "uri":<string>, "durationMs":<long>, "width":<int>, "height":<int>}` (or `{"recording":false}` when idle).

### 🎛️ Remote Control Commands

Settings can be changed dynamically by passing query parameters in HTTP GET requests (e.g., to the root path `/` or any control endpoint).

* **Parameters:**
  * `camera=<id|front|back|toggle>`: Switches the active camera sensor (supports logical:physical ids).
  * `resolution=<low|medium|high|auto|max|WxH>`: Change capture/stream resolution. Use `low|medium|high` for presets; `auto`/`max` or explicit `WxH` may be used to control the negotiated stream size.
  * `zoom=<value>`: Adjusts digital zoom (e.g., `1.0`, `2.5`).
  * `scale=<value>`: Adjusts preview stream scale (per-camera; e.g., `0.5`, `1.0`).
  * `exposure=<value>`: Adjusts exposure value (per-camera).
  * `contrast=<value>`: Adjusts contrast (software; per-camera, integer).
  * `torch=<on|off|toggle>`: Controls the flashlight.
  * `audio_gain=<value>`: Changes microphone gain multiplier (e.g., `1.0`, `2.0`).
  * `focus_distance=<0..1|-1>`: Set manual focus distance (0..1). Use `-1` to restore autofocus.
  * `snapshot_res=<max|stream>`: Choose snapshot resolution for the selected camera (`max` for full sensor, `stream` to match current stream resolution).
  * `rotate=<degrees>`: Rotate preview/snapshot (persisted per-camera).
  * `mirror=<true|false>`: Mirror the video.
  * `api=<auto|camerax|camera1>`: Prefer capture API implementation.
* **Example command:** `https://[ip_address]:[port]/?torch=on&zoom=2.0`

## 🔐 HTTPS/TLS certificates

To protect the stream and the password from being sent in plain-text over HTTP, a certificate can be used to start the stream over HTTPS.

The app will automatically generate a self-signed certificate on first launch, but if you have your own domain you can use [Let's Encrypt](https://letsencrypt.org) to generate a trusted certificate and skip the self-signed security warning message, by changing the TLS certificate in the settings.

To generate a new self-signed certificate, clear the app settings and restart or clone this repo and run `./scripts/generate-certificate.sh` then use the certificate `personal_certificate.p12` file it generates.

## 🤖 AI generated code disclaimer

Some of the code in this repository may be generated with the assistance of AI tools. All changes are reviewed and tested on a real device with a human in the loop before being released.

## 🛂 Permissions

The app uses the following permissions to function:

* **Camera (`android.permission.CAMERA`):** Required to capture and stream the video feed.
* **Microphone (`android.permission.RECORD_AUDIO`):** Required to record and stream audio. (Requested optionally at runtime; streaming works without audio if denied).
* **Notifications (`android.permission.POST_NOTIFICATIONS`):** Required on Android 13+ to post a persistent foreground service notification, keeping the background streaming server running reliably.
* **Network (`android.permission.INTERNET`, `android.permission.ACCESS_NETWORK_STATE`):** Required to host the server and stream data to your browser/connected clients.
* **Storage (`android.permission.READ_EXTERNAL_STORAGE`, `android.permission.WRITE_EXTERNAL_STORAGE`):** Required on older Android versions to load custom TLS/HTTPS certificates from file storage and to save locally recorded MP4 files (`WRITE_EXTERNAL_STORAGE` only applies to Android 9 / API 28 and below; on newer versions recordings are saved to app-specific storage without extra permissions).
* **Wi-Fi & Location (`android.permission.ACCESS_WIFI_STATE`, `android.permission.ACCESS_FINE_LOCATION`, `android.permission.ACCESS_COARSE_LOCATION`, `android.permission.NEARBY_WIFI_DEVICES`):** Used optionally to read the current Wi-Fi network's connection signal strength so it can be displayed in the web control panel overlay. If not granted, the Wi-Fi icon will be hidden. (Location permissions only apply to Android 12L / API 32 and below.)
* **Start on boot (`android.permission.RECEIVE_BOOT_COMPLETED`):** Allows the app to optionally start streaming automatically when the device boots. This feature is off by default and must be enabled in the app settings.

<details>
<summary>Reproducible builds</summary>

This project uses [reproducible builds](https://f-droid.org/docs/Reproducible_Builds/). Release APKs should be built from a clean tree at the tagged commit using Gradle directly:

```bash
./gradlew clean assembleRelease
```

The release variant will automatically sign the APK build. Build-tools 35+ is known to produce signatures that fail reproducibility verification.

### Build Variants

By default, release builds generate architecture-specific APK splits (armeabi-v7a, arm64-v8a) in addition to a universal APK. For F-Droid and other scenarios where a single universal APK is preferred, you can disable ABI splits:

**F-Droid builds (single universal APK):**
```bash
./gradlew clean assembleRelease -PenableAbiSplits=false
```

**Release builds with architecture-specific APKs (default):**
```bash
./gradlew clean assembleRelease
```

The `enableAbiSplits` property defaults to `true`. Set it to `false` to generate only the universal APK, which is the recommended approach for F-Droid to avoid unnecessary complexity in the build pipeline.

To verify that two unsigned builds from the same source are identical:

```bash
mkdir -p build/unsigned
./gradlew clean assembleRelease -PskipSigning --no-daemon --max-workers=1 -Dorg.gradle.parallel=false
cp app/build/outputs/apk/release/*universal-release.apk build/unsigned/build1.apk
./gradlew clean assembleRelease -PskipSigning --no-daemon --max-workers=1 -Dorg.gradle.parallel=false
cp app/build/outputs/apk/release/*universal-release.apk build/unsigned/build2.apk
cmp -s build/unsigned/build1.apk build/unsigned/build2.apk && echo OK
# or: shasum -a 256 build/unsigned/build1.apk build/unsigned/build2.apk
```

If they differ, inspect with `diffoscope build/unsigned/build1.apk build/unsigned/build2.apk`.

To verify your **signed** release APK matches an unsigned rebuild use `apksigcopier` - the first APK must be signed:

```bash
./gradlew clean assembleRelease
apksigcopier compare app/build/outputs/apk/release/*universal-release.apk --unsigned build/unsigned/build1.apk && echo OK
```

CI runs this check automatically via the [Reproducible Build workflow](.github/workflows/reproducible-build.yml).

Builds downloaded from the [official repository](https://github.com/DigitallyRefined/android-ip-camera/releases) should match the following signing certificate:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/*universal-release.apk
Signer #1 certificate DN: CN=DigitallyRefined
Signer #1 certificate SHA-256 digest: 1111be81c861e199c6485d367c37680c4b778fba301980d2f0f9a2800f77f70a
Signer #1 certificate SHA-1 digest: 1560ceccdd719b2b97d431ad9a4c877abf5c2f32
Signer #1 certificate MD5 digest: 5fdf04f5b6bab9fdacbe28aa6dc85abb
```

</details>
