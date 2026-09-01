# CP-003 energy signal validation

Status: protocol and bounded USB evidence bundle prepared by Codex on 2026-09-01; no vehicle
evidence collected yet.

This protocol validates observed runtime signals. It does not claim support from an APK,
firmware file, emulator, or another vehicle generation. Phase 3 remains blocked until the
required vehicle evidence is attached and interpreted.

## Safety and storage boundary

- Use only the unstable EVChargePilot build. Stable contains no evidence recorder.
- Driver never operates the screen while moving. Use a passenger operator, or start and stop
  only after safely parking; the app also enforces a readable speed at or below 0.1 km/h.
- Keep the evidence screen visible for the complete capture. Sampling stops when its activity
  leaves the foreground. No overlay is used.
- Abort a road segment when traffic, weather, road rules, vehicle warnings, or driver workload
  make the requested condition unsafe. Record `not tested`; never force a test condition.
- Do not flash/downgrade firmware, disconnect the 12 V battery, change Android permissions, or
  use a proprietary firmware artifact for this run.
- Captures store aggregate statistics, not a raw sample stream: 5 Hz for the first minute, then
  1 Hz, with at most 1,024 change intervals per signal in memory. Files are app-private atomic
  JSON summaries; only the eight newest are retained. The eight named road/charge segments below
  exactly fit that ceiling. Export before any extra exploratory capture or another generation.
- Parked-only USB export writes one atomic ZIP, capped at 768 KiB. It contains a 128 KiB-bounded
  diagnostic, all eight newest evidence JSON files (64 KiB each maximum), and a manifest with byte
  counts and SHA-256 hashes. It creates no additional persistent file on AAOS.
- Diagnostic includes exact detected firmware, app/APK/signature identity, Android/head-unit build,
  permissions, heap/storage state, recorder state, latest normalized snapshot, property probes,
  value provenance, bounded recent logs and previous crash. VIN, serial, location and accounts are
  deliberately excluded.

## Preconditions

Record before installation or driving:

| Field | Value |
|---|---|
| Date/time and timezone | pending |
| Vehicle model/year/trim | pending |
| Firmware string shown by runtime | pending |
| EVHardware generation classification | pending |
| EVChargePilot APK filename/version | pending; version is also in `diagnostic.txt` |
| APK SHA-256 | emitted in `diagnostic.txt` |
| APK signing certificate SHA-256 | emitted in `diagnostic.txt` |
| Operator and passenger present | pending |
| Charging equipment/rated power, if used | pending |

Confirm package `com.evsuite.chargepilot.unstable` is installed, the live evidence table updates,
firmware is not `UNKNOWN`, and speed reads `0` while stationary. If speed is unavailable, do not
bypass the gate: stop and record the generation as untestable with this build.

For every capture, note scenario, local start/end time, ambient temperature, SOC, relevant HVAC
state, and any interruption. Never transcribe an unavailable value as zero.

## Capture sequence

Run each named segment as a separate capture. From **Diagnostic**, choose **Open evidence capture**
and select the exact scenario first; that ID is embedded in the JSON filename. Start parked. Return
to a safe parked state before stopping and saving. A skipped scenario is explicitly `not tested`.

1. **Stationary, ignition on, HVAC off — 3 minutes.** Doors closed; climate power, AC, seat and
   steering heat off. Establish idle availability, cadence and battery-power baseline.
2. **Stationary, HVAC maximum — 3 minutes.** Use the vehicle's ordinary maximum climate setting.
   Record requested temperature and fan level. This known discharge load is the primary battery
   power sign check against scenario 1; climate state is not treated as measured HVAC power.
3. **Urban accelerate/regen — 10 minutes.** Repeat normal, legal acceleration and lift-off/regen
   cycles. No hard manoeuvre is required. Record selected regen/drive mode as operator context,
   not as EVSuite compatibility evidence.
4. **Motorway steady speed — two captures when legal and safe.** Capture approximately 110 km/h
   for 5 minutes, then approximately 130 km/h for 5 minutes. If either speed is illegal or unsafe,
   omit it. These prove availability/cadence; they are not model-fitting data because this recorder
   intentionally retains no raw track.
5. **Grade — separate downhill and uphill captures.** Use an existing safe route; do not seek a
   steep road. Record approximate segment times and whether each segment completed. This checks
   that both battery-power polarities remain observable in ordinary driving.
6. **Charging — at least 3 minutes, if reachable.** Start after the charge session is stable and
   note the charger-displayed power range. This known charging state is the independent sign and
   order-of-magnitude check. If unavailable, battery-power sign/scale cannot be marked fully
   validated from driving correlation alone.

## Evidence retrieval

Primary path, with vehicle parked and a removable USB key mounted:

1. Open **Diagnostic**. Confirm displayed firmware, current scenario and latest sample age.
2. Select **Export to USB**, choose the removable volume, and wait for the exported `.zip` path.
3. On a workstation, keep the ZIP unchanged, verify `manifest.txt`, then extract
   `evidence/*.json` into `analysis/evidence/`. The manifest already carries SHA-256 per artifact;
   hash the ZIP itself too for transport integrity.
4. Keep `diagnostic.txt` with the intake. It explains the exact build/runtime and failure context
   but never substitutes for the evidence JSON.

ADB is fallback only. From an already-authorized workstation, pull the exact path shown in-app
without changing app or filesystem permissions:

```bash
mkdir -p analysis/evidence
adb pull '<path-shown-by-EVChargePilot>' analysis/evidence/<generation>-<scenario>.json
sha256sum analysis/evidence/<generation>-<scenario>.json
```

If direct pull is denied, do not weaken the app sandbox. Record the blocker and stop intake. On a
debuggable local build only, `run-as com.evsuite.chargepilot.unstable` may read its own files.

After every pull:

1. Confirm non-zero file size and save SHA-256 in the manifest below.
2. Parse with the checked-in schema; reject an unknown `schemaVersion` or `firmware` mismatch.
3. Confirm start/end timestamps and snapshot count match the operator log.
4. Keep the original JSON unchanged under `analysis/evidence/`; analysis goes in this document.
5. Do not begin another generation until all current files are present and hashes re-check.

## Evidence manifest

| Generation | Scenario | File | SHA-256 | Result |
|---|---|---|---|---|
| pending | stationary-hvac-off | pending | pending | not tested |
| pending | stationary-hvac-max | pending | pending | not tested |
| pending | urban | pending | pending | not tested |
| pending | motorway-110 | pending | pending | not tested |
| pending | motorway-130 | pending | pending | not tested |
| pending | grade-uphill | pending | pending | not tested |
| pending | grade-downhill | pending | pending | not tested |
| pending | charging | pending | pending | not tested |

## Generation coverage

Never copy a conclusion from one row into another.

| Generation | Vehicle/firmware tested | Capture set | Conclusion |
|---|---|---|---|
| SWI68 | none | none | not tested |
| SWI69 | none | none | not tested |
| SWI131 | none | none | not tested |
| SWI132 | none | none | not tested |
| SWI133 | none | none | not tested |
| SWI165 | none | none | not tested |

## Per-signal conclusions

Allowed availability values: `validated`, `unavailable`, `intermittent`, `not tested`. Units and
semantics must be observed or remain `unknown`; do not infer them from field names.

| Signal | Generation | Availability | Unit/semantics | Sign | Median update | Valid range | Evidence files |
|---|---|---|---|---|---|---|---|
| socPercent | pending | not tested | unknown | n/a | unknown | unknown | pending |
| rangeKm | pending | not tested | unknown | n/a | unknown | unknown | pending |
| speedKmh | pending | not tested | unknown | n/a | unknown | unknown | pending |
| batteryPowerKw | pending | not tested | unknown | unknown | unknown | unknown | pending |
| batteryTempCelsius | pending | not tested | unknown | n/a | unknown | unknown | pending |
| batteryEnergyKwh | pending | not tested | unknown | n/a | unknown | unknown | pending |
| batteryCapacityKwh | pending | not tested | unknown | n/a | unknown | unknown | pending |
| odometerKm | pending | not tested | unknown | n/a | unknown | unknown | pending |
| outsideTempCelsius | pending | not tested | unknown | n/a | unknown | unknown | pending |
| cabinTempCelsius | pending | not tested | unknown | n/a | unknown | unknown | pending |
| chargingStatus | pending | not tested | unknown | n/a | unknown | unknown | pending |
| chargePortConnected | pending | not tested | unknown | n/a | unknown | unknown | pending |
| parked | pending | not tested | unknown | n/a | unknown | unknown | pending |
| climate.powerOn | pending | not tested | unknown | n/a | unknown | unknown | pending |
| climate.acOn | pending | not tested | unknown | n/a | unknown | unknown | pending |
| climate.autoOn | pending | not tested | unknown | n/a | unknown | unknown | pending |
| climate.econOn | pending | not tested | unknown | n/a | unknown | unknown | pending |
| climate.recirculationOn | pending | not tested | unknown | n/a | unknown | unknown | pending |
| climate.fanLevel | pending | not tested | unknown | n/a | unknown | unknown | pending |
| climate.fanLevelMax | pending | not tested | unknown | n/a | unknown | unknown | pending |
| climate.driverTargetCelsius | pending | not tested | unknown | n/a | unknown | unknown | pending |
| climate.passengerTargetCelsius | pending | not tested | unknown | n/a | unknown | unknown | pending |

## Battery-power decision gate

Current conversion divides the runtime value by `-1_000_000`. Accept it for one generation only
when all of these are evidenced on that same generation:

- HVAC-max stationary load moves power consistently in the discharge direction relative to the
  HVAC-off baseline;
- a stable charging capture moves power in the opposite direction and has the same order of
  magnitude as the charger display;
- urban/grade captures contain the expected two polarities without implausible magnitudes;
- cadence and null rate are usable for five-second-bounded integration.

Any contradiction keeps the catalogue unvalidated. Correct EVHardware plus a JVM conversion test,
update both changelogs, then repeat the affected capture. Absence of a charging capture is not
permission to guess the sign.

## Intake checklist

- [ ] Original JSON and SHA-256 manifest committed.
- [ ] Every generation row is either tested or explicitly `not tested`.
- [ ] Every signal row has availability, unit, semantics, cadence and range conclusions.
- [ ] Battery-power sign and scale are observed, or remain unvalidated.
- [ ] `CarPropertyEvidence.kt` reflects only conclusions supported by attached files.
- [ ] Any conversion correction has a JVM test and changelog entries.
- [ ] README firmware matrix matches the proven support set.
- [ ] EVHardware `mise run check` and EVChargePilot `mise run check && mise run build` pass.
