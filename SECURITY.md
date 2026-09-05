# Security

## Capability boundary

EVChargePilot reads the car and does not write to it, with one stated exception. Its manifest
declares the car permissions needed to read speed, energy, exterior environment, climate and
vendor-extension data, plus network and location for route planning (CP-043). It has no
overlay, no installer, no boot receiver and no vehicle-setting write path.

Trip history, evidence captures and crash reports stay in app-private storage. Telemetry never
leaves the device. What leaves it for a route is origin, destination and road profile, and — only
when a charging stop is needed — a window of road around that stop. Never a trip, a charge level,
an odometer, a speed, a climate state or an identifier.

### The one write: handing a destination to the car's navigation

`ChargeStopActivity` can send an `ACTION_VIEW` intent with a `geo:` URI, which moves whichever
navigation app the driver uses to a destination this app planned. It is the only thing here that
asks a vehicle system to act rather than to answer, and it exists because the alternative was a
driver retyping a route — the step that gets skipped, and that silently invalidates every figure
on the screen when it is done wrong.

Its bounds, all of which are testable:

- **Parked.** The parked gate is re-read at the tap, not trusted from the render that drew the
  button. A navigation screen changing under someone at 110 km/h is the hazard.
- **One tap, one destination.** Nothing hands over a destination on a timer, on a route arriving,
  on a drift, or on anything but a press.
- **One method.** `IMapNotificationListener` carries `stopNav`, `goHome` and `setFastestRoute`.
  None of them is called anywhere in this tree, and registering on that interface — which would
  mean impersonating a navigation provider — is not done.
- **It cannot lie about what it did.** A destination handed over without the route it belongs to
  is labelled on screen as exactly that: the car picks its own road, and the plan's figures stop
  describing the drive if it picks a different one.
- **No coordinates in any log or probe.** The validation artifact records that a handoff was
  tapped and whether anything accepted it. Never where to.

## Reporting

Do not open a public issue for a vulnerability that could affect a vehicle. Use GitHub's
private vulnerability reporting for the repository and include the firmware generation,
application version, reproduction steps and relevant redacted logs.

## Release expectations

Security CI blocks undeclared permissions and leaked secrets. Release APKs are minified and
must be signed through CI or local secret properties. Emulator success never replaces an
on-vehicle stability check.

