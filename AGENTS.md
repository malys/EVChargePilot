# AGENTS.md — EVChargePilot

Read-only energy dashboard and local trip analyser for the SAIC MG4. The workspace
`AGENTS.md` and normative workspace `DESIGN.md` apply; this file adds only ChargePilot's
product boundary and commands.

Commit author: `malys.training@gmail.com`.

## Product boundary

- EVChargePilot reads vehicle state and writes its bounded trip/export files in app-private
  storage. A diagnostic report may additionally be written only after an explicit parked-user
  choice to a removable USB volume; it is bounded, atomic and never targets internal AAOS
  storage as a fallback.
- It never changes a vehicle setting, controls charging, exposes a remote command surface,
  draws an overlay, or silently opens another application.
- Vehicle property ids, service descriptors, transaction codes and firmware routing belong
  in standalone EVHardware. This app consumes typed EVHardware APIs only.
- Missing telemetry remains `null` through acquisition, calculation and presentation. The UI
  renders an em dash and explains the unavailable source; it never substitutes zero.
- Measured, derived and estimated values must be visibly named. A model may not be labelled as
  a vehicle measurement.

## Driver UX

- The live dashboard is passive while moving. Trip start/stop controls are enabled only when
  a readable speed is at or below 0.1 km/h.
- No driver-facing overlay is permitted.
- Preserve the asymmetric instrument + rail layout: SOC is the single display-sized value;
  secondary measurements must not compete with it.
- Follow the workspace `DESIGN.md` exactly. Resources synchronized from `tools/tokens/` are
  generated copies and must not be edited locally.

## Shared energy calculations

- `EnergyTelemetryReader`, snapshots, trip integration and persistence are owned by
  EVHardware and must remain JVM-tested there; do not fork them into this app.
- Integrate only adjacent samples no more than five seconds apart. A larger gap represents
  unknown motion/energy and must not be filled by extrapolation.
- Energy and consumption remain null until at least one valid power interval exists.
- The app owns only sampling cadence, lifecycle and presentation.

## Security

- The stable manifest has no `INTERNET`, location, overlay, install or boot capability.
- USB diagnostic export adds no storage permission: offer only removable roots already visible
  to the app, and fall back only to this app's directory on that same removable volume.
- Any new permission requires an allowlist change and a security review in the same commit.
- Signing configuration comes only from environment variables or local Gradle properties.

## Build and test

Run `mise run test`, `mise run lint`, or `mise run build`. `mise run emulator-setup` creates
the complementary `emulator-car` (Automotive API 33) and `emulator-screen` (API 28,
1920×720) profiles; `mise run run` installs and launches the debug APK. JDK 17 and Android
SDK 36 are used for compilation; the deployed minimum remains API 28. Emulator checks do
not replace a vehicle pass on each supported firmware generation.

## Continuation handoff

- The implemented baseline is the passive dashboard, nullable shared energy snapshot,
  automatic foreground trip detection/recording with parked-only manual controls, bounded
  atomic trip history, crash diagnostics, stable/unstable variants, CI gates and the two
  emulator profiles documented in `README.md`.
- Arrival-SOC prediction, charger routing and energy attribution are not implemented. Treat
  each as a separately scoped milestone with explicit evidence for every input; do not fill
  missing vehicle signals with inferred measurements.
- Start app work by checking `git status` and the EVHardware submodule branch. Any shared
  telemetry model, property, unit, fallback or integration change starts in the standalone
  `../EVHardware` repository, is tested and pushed there, then reaches this app through a
  submodule pointer update. Never develop library changes inside the nested checkout.
- Before handing off a change, run `mise run check` and `mise run build`. For UI/lifecycle
  work, also run the API 28 screen emulator at 1920×720; for vehicle interfaces, record the
  remaining on-vehicle validation requirement rather than claiming emulator coverage.
