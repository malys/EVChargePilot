# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Native Android Views and Kotlin, aligned with the existing EVTasker toolchain. Android
Automotive OS 9 (API 28) is the deployment floor; EVHardware owns all vehicle access.

## Users

MG4 drivers who want a glanceable explanation of live energy use and a factual record of
completed trips on the vehicle's centre display.

## Product Purpose

EVChargePilot presents available battery, range, motion, temperature and climate readings
without replacing missing data with estimates. It records local trip summaries and will
later support explicitly labelled energy models and arrival-SOC forecasts.

## Positioning

The product runs directly on the MG4 head unit, reads through the shared firmware-aware
EVHardware layer, works offline, and distinguishes measured, estimated and unavailable
values throughout the interface.

## Operating Context

The primary surface is a 1920×720 landscape head unit viewed at roughly 70 cm, in daylight
and at night. Live information may be read while driving; configuration and recording
controls are parked-only. On-vehicle validation remains required for every firmware family.

## Capabilities and Constraints

- Read-only vehicle access; no setting writes, remote control, overlay or network capability.
- Vehicle-reported SOC, range, speed, outside temperature and climate state where available.
- Nullable battery power and temperature supplied by EVHardware where the firmware exposes
  the standard read-only properties.
- Local atomic trip history. The initial recording session is process-local and samples only
  while the dashboard is visible.
- Stable and unstable packages install side by side; neither contains an updater in v0.1.
- Arrival SOC, charger routing and energy-source attribution are deliberately out of the MVP.

## Brand Commitments

EVSuite's existing Instrument Binnacle design system is binding: Roboto, DayNight role
tokens, 7:1 text contrast, 72dp targets, 16sp minimum, one 12dp radius, no shadows and no
decorative colour.

## Evidence on Hand

EVHardware exposes the firmware-aware speed, energy, charging and climate snapshot used by
both EVChargePilot and EVABRPUploader. Unsupported runtime properties remain unavailable;
EVChargePilot does not copy their identifiers or reinterpret their units.

## Product Principles

- Unknown is a first-class state, never zero.
- Measurement and estimation must never look interchangeable.
- The road gets attention before the interface.
- Vehicle access belongs to EVHardware.
- Offline and read-only are the default capability boundary.

## Accessibility & Inclusion

All state uses text in addition to colour. The interface follows the EVSuite daylight
contrast floor, large touch targets, minimum type size and landscape driver ergonomics.
