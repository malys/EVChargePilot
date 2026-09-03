# CP-040 — Route and destination source

Date: 2026-09-02

Decision owner: pending. This document states the finding and the recommendation; the
network question at the end of it is the owner's, not this session's.

Firmware examined: `saicadapterservice_overseas_eh32` (R69 EH32), decompiled sources under
workspace `apks/`.

## Verdict in one line

The head unit **does** publish remaining distance and remaining time to the destination, to
any process that registers as a listener on its general service — no Android permission, no
network, no account. That closes the question CP-040 was written to answer. It does **not**
close Phase 4, because it carries no elevation, no chargers and no alternative routes.

## What was actually found

`SaicNav` recorded that the map adapter answers no synchronous question about a trip. That
is correct and remains correct. What it did not record is where the guidance goes instead.

The navigation app calls into `com.saicmotor.adapterservice`:

| Call | Transaction | Payload |
| --- | --- | --- |
| `remainingDistanceChange` | `IMapService` 39 | one int, logged as `remainingDistance` |
| `remainingTimesChange` | `IMapService` 38 | one int, logged as `remainingMinutes` |
| `guideStatusChange` | `IMapService` 29 | one int |
| `guideInfosChange` | `IMapService` 30 | icon, distance to next manoeuvre, direction text |

`MapService` stores each of these and forwards it to `GeneralService`, which fans it out over
a `RemoteCallbackList` to every registered `IGeneralNotificationListener`:

| Callback | Transaction |
| --- | --- |
| `guideStatusChange` | 1 |
| `guideInfosChange` | 2 |
| `roadInfoChange` | 3 |
| `remainingTimesChange` | 12 |
| `remainingDistanceChange` | 13 |

Registration is `IGeneralService.registerNotificationListener`, transaction 1, on
`com.saicmotor.adapterservice/.services.GeneralService`.

Implemented as `EVHardware/lib/.../saic/SaicNavGuidance.kt`; the probe that captures it is
`EVChargePilot/app/src/unstable/.../NavGuidanceProbeActivity.kt`.

## Two traps found on the way, both avoided

**`IMapNotificationListener` is the wrong direction.** It is tempting because
`IMapService.registerNotificationListener` is transaction 1 and the name matches. But that
interface carries `goHome`, `zoomIn`, `stopNav`, `setFastestRoute` and `startNavFromEVRout` —
it is the channel the head unit uses to *command the navigation app*, not a feed of guidance.
Registering on it would make this app impersonate a navigation provider, and every method on
it is a write. It is out of bounds for a read-only app. `IGeneralNotificationListener` is the
observer side and is what is used.

**The callbacks are not `oneway`.** The generated proxy calls
`transact(code, data, reply, 0)` and then `readException()`, and `GeneralService` fans out
while holding the lock on its callback list. A listener that blocks therefore stalls every
other consumer of guidance, the instrument cluster included. `SaicNavGuidance` does one
parcel read and one volatile write per callback and nothing else — no I/O, no lock, no
allocation beyond the immutable state object. Unrecognised transactions are answered without
being parsed, so unrelated callbacks cost a reply and nothing more.

Registration is additive: `RemoteCallbackList.register` appends and the list drops a dead
listener on its own, so this displaces neither the navigation app nor the cluster.

## What is still unproven

- **The unit of `remainingDistance`.** The service logs the value without naming a unit and
  `distanceUnitChange` exists as a separate concern. Metres and kilometres are both plausible
  and the difference is a factor of a thousand. Nothing is converted anywhere in the code;
  the value is carried as `remainingDistanceRaw`.
- **The meaning of `guideStatus` codes.** Undocumented; carried raw.
- **Behaviour with no guidance running.** If the navigation app publishes nothing when idle,
  the feature only exists for drivers who navigate with the OEM app. This is the single most
  decision-relevant unknown and the probe is built to record it.
- **Whether R67 numbers the interface as R69 does.** See the section below; the probe is
  built to answer it in the same capture.
- **Whether a third-party navigation app feeds the same path.** TomTom broadcasts
  (`com.tomtom.navigation.GUIDE_INFO`) reach `MapService`, so at least one non-SAIC app does.

None of these can be settled off-vehicle, which is why CP-040 carries `on_vehicle_required`.

## Firmware revision risk, and how the probe catches it

A diagnostic bundle captured on the vehicle reports `detected_firmware=SWI68-29958-1300R67`.
The transaction map above was read from an R69 build of the same generation — two revisions
apart, not a different family, but not the same binary either.

This matters more than a missing signal would. If SAIC inserted a method into
`IGeneralNotificationListener` between R67 and R69, every code after the insertion point
shifts by one. A shifted map does not throw and does not read as unavailable: it decodes
whatever callback now occupies code 13 and reports it as a remaining distance. Silent wrong
numbers are the one outcome this project's provenance rules exist to prevent.

`TransactionCensus` therefore counts every code the adapter sends, decoded or not, and the
probe prints it. Two patterns condemn the map:

- traffic on codes the build does not decode, marked `?` in the trace header; or
- silence on the decoded codes while guidance is visibly running on the head unit.

Either one means this firmware numbers the interface differently, and no reading from the
capture may be used until the map is re-derived from an R67 build. Confirming the map is
therefore part of the vehicle run, not an assumption it rests on.

## Scored comparison

Scores are against CP-040's four criteria. "Boundary" means the read-only, offline,
minimal-permission boundary stated in `PRODUCT.md`.

| Source | Offline | Permission cost | Firmware stability | Boundary | Verdict |
| --- | --- | --- | --- | --- | --- |
| **OEM adapter guidance listener** | full | none | unproven; private interface, transaction codes are firmware-specific and must be evidence-gated per generation | intact — read-only, additive registration | **recommended for remaining distance and time** |
| Manual destination entry + straight-line estimate | full | none | not firmware-dependent | intact | fallback only; a great-circle distance is wrong by 15–30 % on real roads and cannot see terrain |
| Destination handed in by intent from another app | full | none | not firmware-dependent | intact | no such app exists today; keep as an interface, not a plan |
| External routing engine (ORS, GraphHopper, Valhalla) | no | `INTERNET`, plus API-key handling | stable | **breaks it** — stable variant becomes networked | the only source that yields elevation and alternative routes |
| ABRP `get_next_charge` | no | `INTERNET` | undocumented contract | breaks it, and adds an unagreed dependency | rejected, per `PLAN.md` |
| Do not build Phase 4 | — | — | — | intact | still available, and still acceptable |

## Recommendation

**Take the adapter listener, and split Phase 4 in two.**

1. **CP-041 arrival SOC becomes buildable** on remaining distance from the listener, once the
   probe returns a unit and an idle-behaviour answer from the vehicle. It stays offline,
   permission-free and inside the stated boundary. Its band must widen for the absent grade
   term, per the CP-031 decision.

2. **Everything else in the driver's stated goal stays blocked on a network decision.**
   Elevation profile, charger locations and "via the departmental road instead" cannot come
   from the head unit. They need a routing engine, and that means `INTERNET` in the stable
   variant, a `PRODUCT.md` rewrite and a fresh CP-091 pass. That decision is not taken here.

## Do-not-build option

If the probe shows the adapter publishes nothing when the OEM navigation is idle, and the
driver does not use the OEM navigation, then the listener is dead weight: delete
`SaicNavGuidance` and the probe, mark CP-041 and CP-042 `blocked` permanently, and ship
without Phase 4. That remains an acceptable outcome.

## Security note

No permission is added by this work; `mise run permissions` passes unchanged. The listener
exists only in the unstable variant's reachable UI. Should CP-041 promote it to stable, the
promotion needs its own CP-091 entry, because a stable build would then hold a registration
on a head-unit service for the life of the process.

## Next step

Run the probe on the vehicle: park, arm, start a guidance in the car's own navigation, drive a
leg whose distance is known independently, then park, save the trace, and take it out with
**Diagnostic → Export to USB** — the trace lands in the bundle as `evidence/navguidance-*.json`
beside the signal captures. It answers the unit question and the idle question together.
