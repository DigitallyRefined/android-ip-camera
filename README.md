# Android IP Camera

[![downloads](https://img.shields.io/github/downloads/DigitallyRefined/android-ip-camera/total.svg)](https://github.com/DigitallyRefined/android-ip-camera/releases)
[![downloads](https://img.shields.io/github/downloads/DigitallyRefined/android-ip-camera/v0.0.4/total.svg)](https://github.com/DigitallyRefined/android-ip-camera/releases)
[![downloads](https://img.shields.io/github/downloads/DigitallyRefined/android-ip-camera/latest/total.svg)](https://github.com/DigitallyRefined/android-ip-camera/releases)

An Android MJPEG IP Camera app

![Desktop Browser](screenshot.webp)

## Install

<div align="center">
<a href="https://github.com/DigitallyRefined/android-ip-camera/releases">
<img src="https://user-images.githubusercontent.com/69304392/148696068-0cfea65d-b18f-4685-82b5-329a330b1c0d.png"
alt="Get it on GitHub" align="center" height="80" /></a>

<a href="https://github.com/ImranR98/Obtainium">
<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/refs/heads/main/assets/graphics/badge_obtainium.png"
alt="Get it on Obtainium" align="center" height="54" /></a>
</div>

## Features

- 🌎 Built in server, just open the video stream in a web browser, video app or even set it as a Home Assistant MJPEG IP Camera
- 📴 Option to turn the display off while streaming
- 🤳 Switch between the main or selfie camera
- 🖼️ Choose between different image quality settings and frame rates (to help reduce phone over heating)
- 🛂 Optional username and password
- 🔐 Optional TLS certificate support to protect stream and login details via HTTPS

## ⚠️ Warning

If you are planning to run this 24/7, please make sure that your phone does not stay at 100% charge. Doing so may damage the battery and cause it to swell up, which could cause it to explode.

Some models include an option to only charge to 80%, make sure this is enabled where possible.

Note: running at a higher image quality may cause some phones to over heat, which can also damage the battery.

## HTTPS/TLS certificates

To protect the stream and the password from being sent in plain-text over HTTP, a certificate can be used to start the stream over HTTPS.

To generate a new self-signed certificate, clone this repo and run `./scripts/generate-certificate.sh` then use the `server.p12`. Or if you have your own domain you can use [Let's Encrypt](https://letsencrypt.org) to skip the self-signed security warning message.
