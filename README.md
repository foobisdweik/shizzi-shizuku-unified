# Shizzi–Shizuku Unified

This repository consolidates [Shizzi](https://github.com/carlelieser/shizzi) and the necessary parts of [Shizuku](https://github.com/RikkaApps/Shizuku) into one installable Android app.

Shizzi keeps its tethering interface and includes its own privileged helper (a separate process that uses Android's shell permissions for tethering). The integrated code handles wireless pairing, helper startup, protection, and recovery. A separate Shizuku installation is no longer required, so users can keep one app pinned or locked in Recents.

The helper serves Shizzi exclusively. Initial setup still requires Android's wireless debugging or an authorized computer. Settings → Protection and recovery exposes sixteen controls, including optional changes that affect the whole phone.

See the [v0.6.0 release notes](docs/releases/v0.6.0.md) for every control and its default. See [standalone setup](docs/standalone-shizzi.md) for pairing and startup instructions.

---

<p align="center">
  <img src="docs/assets/icon.png" width="96" alt="">
</p>

<h1 align="center">Shizzi</h1>

<p align="center">
  Wi-Fi and USB tethering through Shizzi’s dedicated privileged helper.
</p>

Creates a test TUN interface, sets it as the preferred tethering upstream, and
forwards hotspot traffic through a Go datapath. Supports IPv4 and IPv6.

## Requirements

- Android 11 (API 30+), arm64
- Wireless debugging for initial pairing, or an authorized computer with USB debugging

Android 13 and up work outright. On Android 11 and 12 it depends on the device's
tethering module; the app checks on first launch and offers to install the
module it needs. See [compatibility](docs/android-compatibility.md).

## Install

Build this standalone version with the instructions below. Install `app/build/outputs/apk/debug/app-debug.apk` through `adb install -r`.

Follow [standalone setup](docs/standalone-shizzi.md) to pair or start the included helper. The Shizuku app is no longer required.

Settings → Protection and recovery exposes sixteen controls. App protections default to maximum persistence. Changes that affect the whole phone remain individually disabled.

## Features

- 🚀 **Unlimited hotspot.** Sharing draws on your regular data instead of your
  hotspot allowance.
- 🛡️ **VPN compatible.** Stay private on every connected device.
- ⚙️ **Uses your existing hotspot.** No extra configuration required.
- 🔌 **USB tethering.** Share over a USB cable alongside, or instead of, the
  hotspot. Pick it under Settings → Tethering → USB tethering.
- 🙌 **No root.** Shizzi starts its own helper through Android debugging.
- 🎛️ **One tap away.** A quick settings tile starts and stops sharing from the
  notification shade.
- 🤖 **Automatable.** Start and stop from Tasker or MacroDroid. See
  [automation](docs/automation.md).

## Build

Needs the Android SDK with NDK, Go, and gomobile:

```
go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init
./gradlew assembleDebug
```
