# CP-031 — Grade input decision

Date: 2026-09-01

Decision owner: Codex, applying the ticket's unresolved-permission default

## Decision

Do not add a grade input to either stable or unstable EVChargePilot yet. Keep the CP-030
model grade-free and explicitly accept its wider uncertainty on hilly trips.

No reviewed source is both trustworthy and inside the current read-only, offline, minimal-
permission boundary. In particular, no location permission is added without the owner's
separate explicit approval required by CP-031. Absence of grade is represented by absence;
it is never replaced with zero.

## Candidate assessment

| Source | Availability | Permission/capability cost | Accuracy and noise | Decision |
| --- | --- | --- | --- | --- |
| GPS altitude | Android location provider; actual fixes and altitude quality depend on hardware, antenna, environment and runtime grant | `ACCESS_FINE_LOCATION`, allowlist/security review, reachable runtime request and grant-result handling; changes stable capability boundary | Absolute altitude can jump or drift; grade needs distance-window filtering and rejected low-quality fixes | Rejected pending explicit owner approval; no prototype shipped |
| Vehicle altitude, pitch or inclination | No public or runtime candidate found by CP-004; no firmware generation validated | A new vehicle read would require a public/runtime identifier, per-generation evidence and CP-003 validation | Unknown until a legitimate candidate exists | Unavailable; never probe unknown property IDs |
| Energy-derived inference | Power and speed may become available after CP-003 | No new Android permission, but consumes the same signals and fitted residuals as the energy model | Not identifiable: slope, wind, HVAC, payload and acceleration produce similar residuals; would feed model error back as an input | Rejected as circular and unsafe to label as grade |
| Barometric pressure sensor | Android API supports pressure sensors, but presence on the MG4 head unit is unverified and not guaranteed by AAOS | No manifest permission, but adds a hardware capability and lifecycle sampling path | Weather and cabin-pressure drift; needs calibration/elevation reference and motion filtering | Rejected until hardware presence and a defensible reference are proven |

## Shipped behavior

- Stable manifest remains location-free.
- Unstable manifest remains location-free; no hidden prototype broadens permissions.
- `EnergyModel` retains its rolling, speed-squared and outside-temperature terms. Its
  synthetic tests prove a valid fit with no grade input.
- Predictions remain `ESTIMATED`, carry residual-derived uncertainty, and refuse values
  outside the trained speed/temperature envelope. Hilly-trip residuals therefore widen or
  reject a fit instead of becoming a fabricated grade value.

## Revisit gate

Reopen only after one of these events:

1. owner explicitly approves location capability and its stable/unstable scope; or
2. a legitimate read-only vehicle/barometer source is identified and validated per firmware.

Any GPS implementation must first update the permission allowlist and security review, then
request permission on a reachable screen, inspect the callback result, discard fixes without
altitude or adequate accuracy, smooth over travelled distance, and remain optional at runtime.
