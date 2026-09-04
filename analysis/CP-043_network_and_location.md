# CP-043 — Network and location capability

Date: 2026-09-04

Decision taken by the owner on 2026-09-04: **`INTERNET` and location are accepted** for the
route work. This document is the review that decision requires, and it reaches one conclusion
the owner did not ask for and should read: **the permissions are approved but not declared
yet**, because nothing in the app can use them today. See "What lands, and when".

## What CP-040 left, and what needs a network

The head unit publishes a usable route for free: remaining distance in metres, remaining time,
guidance status and road name, through `IGeneralService`, for no permission and no network.
CP-041 turned that into an arrival charge forecast, so the driver's simplest question —
"what will I arrive with?" — is already answered offline.

What the head unit does **not** publish is everything else the original goal asked for:

| Wanted | Head unit | Needs |
| --- | --- | --- |
| "You'll need to charge once, in N km" | remaining distance only | charger dataset + network |
| "Slow to 100 and you don't charge, +X min" | no route geometry | routing engine, alternatives |
| "The departmental road saves 5 %, +X min" | no alternatives at all | routing engine |
| Grade ahead of the car | nothing about elevation | elevation profile along a route |
| Where the car is | `getLocationProvider` returns a provider *name* | `ACCESS_FINE_LOCATION` |

`IGeneralService.getLocationProvider` (tx 6) is worth stating plainly because it looks like a
position source and is not: it answers with the name of the provider the head unit is using,
not with coordinates. There is no OEM path to the car's position.

## The three permissions, and what each actually buys

### `INTERNET`

Buys the routing engine, and only that. Nothing else in the app has ever had a reason to open
a socket: trips, evidence and diagnostics are app-private files, and the USB export is a file
copy the driver initiates while parked.

Cost is the boundary itself. "No network capability" has been the first line of this app's
safety argument since the first release — an app that cannot reach a network cannot exfiltrate
a trip history, and that is a property you get for free by not declaring a permission. It does
not survive this ticket, so the replacement argument has to be written down instead of assumed,
which is what the privacy boundary section below does.

### `ACCESS_FINE_LOCATION`

Buys the origin of a route request, and an altitude reading.

The altitude matters more than it looks. CP-031 closed on 2026-09-01 with "no grade source
ships", one of whose reasons was that GPS changes the permission boundary. The owner reversed
that on 2026-09-04 ("Oui, via GPS altitude"). **CP-031's decision is superseded, not
contradicted** — its analysis was right about the cost and simply predates the owner paying it.

`ACCESS_COARSE_LOCATION` is not enough: a route origin snapped to the wrong side of a junction
sends the driver the wrong way, and coarse location carries no usable altitude.

### `FOREGROUND_SERVICE_LOCATION`

Buys nothing yet, and may never be needed. It is required only if a *service* reads position
while the app is not in the foreground — a live-coaching recompute during a drive would.
`TripRecordingService` currently claims `specialUse` and reads no position.

If it is ever declared, the foreground type mask must keep being derived from permissions held
at runtime rather than from the manifest, as CP-091 item 6 requires and as
`TripRecordingService` already does.

## What lands, and when

The permission-drift gate compares the manifest against the allowlist. Those are two different
statements: the allowlist says *reviewed and approved*, the manifest says *the shipped APK asks
for this*. This ticket writes the first and deliberately not the second.

| Permission | Allowlisted | Declared in manifest | Lands with |
| --- | --- | --- | --- |
| `INTERNET` | yes, 2026-09-04 | no | CP-042, when a routing client exists |
| `ACCESS_FINE_LOCATION` | yes, 2026-09-04 | no | CP-042, same commit as the first request |
| `FOREGROUND_SERVICE_LOCATION` | yes, 2026-09-04 | no | only if a service reads position |

The reason is not caution for its own sake. Between this ticket and CP-042 there is no code
that can use either permission: without a routing engine, a granted location gives a latitude,
a longitude and an altitude with nothing to compare them to, and `INTERNET` gives a socket with
nowhere to send anything. Declaring them now would mean shipping an APK that asks the driver
for capabilities it does not exercise — and a permission prompt with no visible benefit is how
drivers learn to deny prompts that matter.

The ticket's own words are the test: *"no manifest line lands before the review that is supposed
to precede it."* The review is done. The manifest line waits for the consumer, which is one
ticket away and unblocked.

## The runtime flow, specified here and enforced in CP-042

1. Location is requested from a screen the driver reaches deliberately — the arrival forecast
   screen — never at application start and never from a service.
2. The request is parked-only, consistent with every other configuration control.
3. The grant result is read in the callback. `shouldShowRequestPermissionRationale` decides
   between asking again with a reason and pointing at settings; a permanent denial is never
   re-prompted.
4. **Refusal is a supported state, not an error.** The app falls back to the CP-040 source: the
   arrival forecast keeps working exactly as it does today, because it never needed a position.
   What is lost is route alternatives, chargers and grade, and the screen says which.
5. Every read is `checkSelfPermission` at the point of use. A grant can be revoked between two
   screens; a cached boolean cannot see that.

## Routing engine

Constraint that eliminates most of the field first: **no API key ships inside the APK.** A key
in a published APK is not a secret — it is a string in a file anybody can unzip, and one this
project publishes on GitHub Releases. So the engine is either keyless, self-hosted, or the key
belongs to the driver.

| Engine | Licence | Elevation | Alternatives | Key | Notes |
| --- | --- | --- | --- | --- | --- |
| OpenRouteService | GPLv3 engine, hosted API | yes, 3D geometry on request | yes | driver's own, free personal tier | Signup required. Verify current quota at signup rather than trusting a number written here. |
| GraphHopper | Apache-2.0 engine, hosted API | yes | yes | driver's own or self-host | Cleanest licence for self-hosting. |
| Valhalla | MIT, self-host | only with an elevation service configured alongside | yes | none if self-hosted | Public community instances exist; their usage policies suit light use and guarantee nothing. |
| OSRM | BSD, self-host | **no** | limited | none if self-hosted | Fails the elevation requirement outright. |

**Chosen: OpenRouteService, with a key the driver enters.** It is the only option that gives an
elevation profile and alternative routes without asking the owner to run infrastructure, and the
key-entry requirement is a feature here rather than friction: the quota, the account and the
disclosure are the driver's, and there is nothing in the APK to leak.

Key handling: entered on a parked-only settings screen, stored in app-private preferences,
never logged, never written into a diagnostic export, and never included in the USB bundle.
The diagnostic exporter must be re-checked against this when the key lands.

**Upgrade path for anyone who will not disclose coordinates to a third party:** self-hosted ORS
or Valhalla with the base URL entered the same way. The client should therefore take a base URL
rather than hardcoding a host, with the host allowlist enforced against the configured value.

## Transport rules

Non-negotiable, from the workspace `AGENTS.md` OTA pattern, which applies to any URL this app
opens and not only to updates:

- `https` only, enforced on the initial URL **and on every redirect hop**. An `https` → `http`
  hop is a MITM and is refused, not followed.
- A host allowlist checked at each hop against the configured base URL's host. Never follow a
  `Location` header blindly.
- No credentials in a URL or a query string.
- Failure is visible and offline-safe: a route request that cannot be made leaves the CP-040
  forecast on screen, unchanged.

## Privacy boundary

What leaves the car, once CP-042 ships:

- A route request: origin coordinates, destination coordinates, and the profile (car). Nothing
  else. No vehicle identifier, no account, no state of charge, no trip history, no telemetry.
- To one host: the base URL the driver configured. Nowhere else.
- When: only while a route is being computed on a screen the driver opened. Not on a timer, not
  in the background, not at start.

What never leaves the car, and must not start to:

- Trip summaries, sample tracks, energy history.
- Evidence captures and signal statistics — unstable-only artifacts that describe the vehicle's
  firmware behaviour.
- The diagnostic bundle. It reaches USB by an explicit parked action and has no network path.
- State of charge, range, speed, odometer, climate state.

This is recorded in the workspace `LEGAL_SAFETY_AUDIT.md` as well, because that document is
where the capability boundary is stated for the suite rather than for one app.

## What this unblocks

CP-042 (SOC at the next charger) was blocked on this decision and is now unblocked, and it is
where the manifest lines, the client and the key-entry screen land. The grade term CP-031 left
out becomes reachable at the same time, from the route's elevation profile rather than from
sampled GPS altitude — a profile of the road ahead is what the energy model wanted, and sampled
altitude behind the car never was.
