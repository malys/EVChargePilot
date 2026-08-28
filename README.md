# EVChargePilot

[![Tests](https://github.com/malys/EVChargePilot/actions/workflows/tests.yml/badge.svg)](https://github.com/malys/EVChargePilot/actions/workflows/tests.yml)
[![Security](https://github.com/malys/EVChargePilot/actions/workflows/security.yml/badge.svg)](https://github.com/malys/EVChargePilot/actions/workflows/security.yml)
[![Release](https://img.shields.io/github/v/release/malys/EVChargePilot?sort=semver)](https://github.com/malys/EVChargePilot/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Read-only live energy dashboard and local trip analyser for the SAIC MG4 head unit.

<p align="center">
  <img src="artifacts/dashboard-dark-1920x720.png" width="49%" alt="Dashboard, dark">
  <img src="artifacts/dashboard-light-1920x720.png" width="49%" alt="Dashboard, light">
</p>

> ⚠️ **No warranty, no liability.** Telemetry may be wrong, delayed or unavailable. Never
> use this app as the sole basis for a range or charging decision. See [DISCLAIMER.md](DISCLAIMER.md).

## Contents

- [Overview](#overview)
- [Features](#features)
- [Requirements](#requirements)
- [How it works](#how-it-works)
- [Install](#install)
- [Configuration](#configuration)
- [Building](#building)
- [Project layout](#project-layout)
- [Project documents](#project-documents)
- [Security](#security)
- [Contributing](#contributing)
- [Legal](#legal)

## Overview

EVChargePilot is an offline EVSuite application for Android Automotive OS 9. It reads typed,
firmware-aware vehicle capabilities from EVHardware and renders unavailable readings as `—`.
It does not write to the vehicle and does not contain network or update code.

## Features

- Vehicle-reported SOC and range.
- Speed, outside temperature and climate state.
- Parked-only trip recording controls.
- Distance and duration calculated locally.
- Battery power, pack temperature and charging state when exposed by the current firmware.
- Energy and regeneration integration from the shared EVHardware power convention.
- Atomic, app-private trip history and in-app crash diagnostics.

Every signal remains best-effort: unsupported or unreadable properties are displayed as `—`,
never zero. Arrival SOC, charger routing and energy-source attribution remain outside this
initial milestone.

## Requirements

- SAIC MG4 head unit running Android Automotive OS 9 or a compatible test device.
- Android API 28 or newer.
- Platform signing for privileged car-property permissions where required by the firmware.

## How it works

EVHardware's `EnergyTelemetryReader` produces one coherent nullable snapshot per second.
Its shared `EnergyTripAccumulator` integrates adjacent snapshots, rejecting gaps longer than
five seconds instead of inventing motion. `EnergyTripHistoryStore` writes a bounded history
through a unique temporary file and atomic rename.

Recording currently samples only while the dashboard is visible and survives activity
recreation only while the process remains alive. Persistent background recording is a later,
separately reviewed milestone. A trip's reported duration is therefore the time actually
covered by usable samples, not wall clock: hiding the dashboard for ten minutes adds nothing
to the duration, distance or energy, so consumption averages compare values measured over
the same interval.

## Install

Install only a signed APK you trust, while the vehicle is parked. Stable releases do not
self-update.

## Configuration

There is no configuration in v0.1. Start or stop a trip from the right-hand rail while the
vehicle reports zero speed.

## Building

With [mise](https://mise.jdx.dev/):

```bash
mise install
mise run bootstrap
mise run test
mise run build
```

### Emulator profiles

Android has no Automotive API 28 system image, so local validation uses two complementary
profiles. Neither replaces testing on every supported vehicle firmware:

```bash
mise run emulator-setup   # one-time image download and AVD creation
mise run emulator-car     # API 33 Automotive: car-service lifecycle
mise run emulator-screen  # API 28, 1920x720: target OS and driver layout
mise run run              # build, install and launch on the connected device
mise run logs             # focused application and EVHardware logs
mise run emulator-stop
```

The emulators do not expose the MG4 vendor services or signals. Seeing `—` for unavailable
telemetry is the expected fail-safe behaviour, not simulated vehicle data.

Release signing reads `EV_KEYSTORE`, `EV_KEYSTORE_PASSWORD`, `EV_KEY_ALIAS` and
`EV_KEY_PASSWORD`, or their local `gradle.properties` equivalents. Never commit credentials.

## Project layout

- `app/src/main/` — driver dashboard, lifecycle, presentation and diagnostics.
- `app/src/main/res/` — EVSuite driver interface.
- `EVHardware/` — shared vehicle abstraction, telemetry model, trip maths and storage.

## Project documents

- [DESIGN.md](DESIGN.md) — normative EVSuite interface rules.
- [SECURITY.md](SECURITY.md) — capability boundary and disclosure.
- [CONTRIBUTING.md](CONTRIBUTING.md) — development expectations.
- [DISCLAIMER.md](DISCLAIMER.md) — vehicle-safety disclaimer.
- [CHANGELOG.md](CHANGELOG.md) — release history.

## Security

The application has no network, location, overlay, installer or vehicle-write capability.
Permission drift is blocked in CI. Report vulnerabilities according to [SECURITY.md](SECURITY.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Vehicle-facing changes require nullable failure paths,
JVM tests for decisions, and on-vehicle confirmation before release.

## Legal

MIT licensed. Not affiliated with, endorsed by or supported by SAIC Motor or MG Motor.
