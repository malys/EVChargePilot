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
without replacing missing data with zero. It records local trip summaries and presents its
current adaptive-range model as an estimate with an uncertainty band. It forecasts the charge
on arrival from the head unit's own route and the driver's recorded consumption, with a band,
and refuses the figure rather than showing one too wide to act on.

## Positioning

The product runs directly on the MG4 head unit, reads through the shared firmware-aware
EVHardware layer, and distinguishes measured, estimated and unavailable values throughout
the interface. Everything it does today it does with no network connection: telemetry,
trips, forecasts and exports are local. Route planning is the one capability that will
change that, and it is opt-in, driver-configured and confined to the route request itself.

## Operating Context

The primary surface is a 1920×720 landscape head unit viewed at roughly 70 cm, in daylight
and at night. Live information may be read while driving; configuration and recording
controls are parked-only. On-vehicle validation remains required for every firmware family.

## Capabilities and Constraints

- Read-only vehicle access; no setting writes, remote control or overlay.
- Network and location are declared, for route planning only, approved by the owner on
  2026-09-04 (CP-043). What leaves the car is a route request — origin, destination, road
  profile — to a host the driver configures, while a route is being computed on a screen the
  driver opened, plus a charger request carrying a 40 km stretch of that road around the
  planned stop — a window, never the route. Trip history, evidence, diagnostics, charge, range
  and speed never leave the car by any path the app controls. No API key ships inside the
  package. Location is optional
  at runtime: refused, the app keeps working from the head unit's own guidance.
- Vehicle-reported SOC, range and speed, plus an independently nullable thermal/climate block.
- Battery power and temperature remain hidden until EVHardware's exact-firmware evidence
  catalogue validates their units and semantics.
- Automatic foreground trip detection, bounded atomic trip history and a bounded sample track.
- Explicitly estimated adaptive range; no estimate is rendered as a vehicle measurement.
- App-private CSV/JSON trip export and parked-only bounded diagnostic export to removable USB.
- Stable and unstable packages install side by side; neither contains an updater.
- On a planned route, the consequences of driving differently: what each of 130, 120, 110,
  100 and 90 km/h would save in charge and cost in minutes, and the same pair of numbers for
  an alternative road. Never a recommended speed, and never one number without the other.
- Arrival charge is answered from the head unit's own guidance and the driver's recorded
  trips, offline. Charger routing and energy-source attribution stay out of the MVP.
- A parked-only charging-stop screen answers whether a typed destination needs a stop and in
  how many kilometres, planning to a reserve rather than to zero and refusing a plan too
  uncertain to act on. It needs a routing key the driver supplies — typed, or imported from a
  file on a USB stick — kept encrypted on the device and shown back to nobody. Without a key
  every other screen is unchanged.

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
- Read-only is the capability boundary; offline is the default, and leaving it is a product
  decision taken in the open, never an implementation detail.

## Accessibility & Inclusion

All state uses text in addition to colour. The interface follows the EVSuite daylight
contrast floor, large touch targets, minimum type size and landscape driver ergonomics.
