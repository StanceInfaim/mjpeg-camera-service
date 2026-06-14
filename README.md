# MJPEG Camera Service

[![Build APK](https://github.com/StanceInfaim/mjpeg-camera-service/actions/workflows/build.yml/badge.svg)](https://github.com/StanceInfaim/mjpeg-camera-service/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/StanceInfaim/mjpeg-camera-service?sort=semver)](https://github.com/StanceInfaim/mjpeg-camera-service/releases/latest)

**English | [Русский](README.ru.md)**

A headless Android app (no Activity, no UI) that turns a phone into an MJPEG IP camera over plain HTTP. It was built to give a 3D printer running Klipper/KlipperScreen a cheap, reliable webcam from a spare Android phone, but it works as a general-purpose network camera for anything that can read an MJPEG stream.

The whole app is one foreground `Service`: it captures frames with Camera2 and serves them from a tiny built-in HTTP server. No external libraries.

## Features

- **MJPEG stream** and **single-frame JPEG snapshot** over HTTP.
- **Web settings page** at `/settings` — configure everything from a browser, no rebuild.
- **Configurable** camera (front/back), resolution, FPS, JPEG quality, rotation, horizontal flip, exposure compensation, autofocus mode and HTTP port. Settings persist across reboots.
- **Hybrid capture** — uses the camera's hardware JPEG encoder when the chosen resolution supports it (near-zero CPU), and transparently falls back to software encoding (`YUV_420_888` → JPEG) for sizes the hardware doesn't expose.
- **Efficient streaming** — a client is only sent genuinely new frames (no duplicate frames, no busy-polling), then paced to the configured FPS cap.
- **Resilient** — self-restarts on crash (AlarmManager + `START_STICKY`), re-opens the camera on disconnect, holds a partial wake lock so the stream survives screen-off.

## Requirements

- An Android phone, **Android 5.0 (API 21) or newer**.
- **Root is required for the intended use** (unattended, starts on boot). See [Why root](#why-root).
- A working `adb` setup on your computer for the first install.

## Download

Grab the latest APK from the [**Releases**](https://github.com/StanceInfaim/mjpeg-camera-service/releases/latest) page, or build it yourself (see [Building](#building-from-source)).

## Quick start

```bash
# 1. Install
adb install mjpeg-camera-service-v2.0.0.apk

# 2. Grant the camera permission (there is no UI to prompt for it)
adb shell pm grant com.stanceinfaim.mjpegcamera android.permission.CAMERA

# 3. Start the service
adb shell am start-foreground-service com.stanceinfaim.mjpegcamera/.CameraStreamService
```

Then open `http://<phone-ip>:8080/stream` in a browser, or `http://<phone-ip>:8080/settings` to configure it.

> Find the phone's IP with `adb shell ip -o addr show wlan0`.

## Web interface & HTTP API

Default port is **8080**.

| Path | Method | Behaviour |
|---|---|---|
| `/settings` | GET | Web settings page |
| `/api/settings` | GET | Current settings + supported resolutions (JSON) |
| `/api/settings` | POST | Apply settings (JSON body); persists to storage |
| `/`, `/video`, `/stream` | GET | MJPEG stream (`multipart/x-mixed-replace`) |
| `/shot`, `/capture` | GET | Single JPEG snapshot |
| `/front` | GET | Switch to the front camera |
| `/back` | GET | Switch to the back camera |
| `/status` | GET | Current camera, port, resolution, FPS (JSON) |

### Settings

| Setting | Range / values | Notes |
|---|---|---|
| Camera | any on the device | Enumerated by lens facing (back/front/external) |
| Resolution | reported by the device | Sizes marked `(SW)` use software encoding |
| FPS | 1–60 | Upper bound that the stream is paced to |
| JPEG quality | 10–95 | Main lever for bandwidth |
| Rotation | 0 / 90 / 180 / 270° | Hardware on the JPEG path |
| Horizontal flip | on / off | Software (decode → flip → encode) |
| Exposure compensation | device range | Useful for dark/bright scenes |
| Autofocus | continuous / fixed | |
| HTTP port | 1024–65535 | Changing it restarts the server |

## Klipper integration

In **Mainsail** or **Fluidd**, add a webcam:

- **Stream URL:** `http://<phone-ip>:8080/stream`
- **Snapshot URL:** `http://<phone-ip>:8080/shot`
- **Service / type:** `MJPEG-Streamer (adaptive)` or any "MJPEG stream" option.

The same URLs work in **KlipperScreen**, OctoPrint, Frigate, Home Assistant, or anything else that consumes an MJPEG/HTTP stream.

## Autostart on boot

The app does **not** start itself on boot, and this is intentional — see below. Start it from a privileged (root) context at boot. With Magisk, the simplest options are a Termux:Boot script or a `service.d` script that runs:

```sh
su -c "am startforegroundservice -n com.stanceinfaim.mjpegcamera/.CameraStreamService"
```

### Why root

You can install and run the app manually over `adb` without root. Root is required for the real use case — **starting unattended on boot with camera access**:

- On **Android 11+**, a foreground service started from a background broadcast (e.g. an in-app `BOOT_COMPLETED` receiver) is denied camera access by the *while-in-use* permission policy — the camera reports `CAMERA_DISABLED ... disabled by policy`. A privileged `am startforegroundservice` (root/shell) gets a temporary exemption and the camera opens. That's why the app relies on a root boot script instead of its own boot receiver.
- Granting the camera permission to a UI-less app and starting it at boot is only practical from a privileged context.

## Caveats

- **No authentication, no TLS.** The stream is open to everyone on the network. Keep the phone on a trusted/isolated LAN.
- **Bandwidth, not the app, is usually the FPS limit.** At high resolution and quality a single JPEG can be hundreds of KB; on a weak Wi-Fi link that caps the achievable frame rate. Lower the resolution or JPEG quality first.
- In dim light the camera's auto-exposure lowers the real frame rate to keep images bright, so the delivered FPS can be below the configured cap.

## Building from source

JDK 17 is required for the build toolchain (source/target compatibility is Java 8).

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

## Releases & CI

- **CI** ([`build.yml`](.github/workflows/build.yml)) builds the debug APK on every push and pull request to `main`.
- **Releases** ([`release.yml`](.github/workflows/release.yml)) build and attach an APK to a GitHub Release whenever a `v*` tag is pushed:

  ```bash
  git tag v2.0.0
  git push origin v2.0.0
  ```

  To ship a properly signed release, add these repository secrets (otherwise the build falls back to the debug key, which is still installable):

  | Secret | Description |
  |---|---|
  | `KEYSTORE_BASE64` | base64 of your `.jks` keystore |
  | `KEYSTORE_PASSWORD` | keystore password |
  | `KEY_ALIAS` | key alias |
  | `KEY_PASSWORD` | key password |
