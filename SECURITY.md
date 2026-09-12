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

### The unstable channel's update check

Unstable builds ask GitHub's rolling `unstable` pre-release at start whether a newer APK exists,
download it, and say where it landed. They do not install it, and they cannot: this app declares
no `REQUEST_INSTALL_PACKAGES`, runs under no system UID and calls no `pm install`. Installing the
downloaded file is the driver's own tap in the head unit's package installer. **Stable builds
contain none of this code** — no release URL, no socket to GitHub, nothing a preference could
switch on.

Every control fails closed:

- **`https` and an exact-match host allowlist**, on the initial URL *and* on every redirect hop.
  A `Location:` header is a remote instruction; an `https` → `http` downgrade or a hop to
  `github.com.attacker.net` is a refusal, not a download.
- **A size ceiling on the declared and on the transferred length.** A lying `Content-Length` and
  a chunked body are the same attack — filling a car's storage — and both are cut.
- **Same signing certificate as the running app, or the file is deleted.** The download lands in
  app-private cache and only reaches shared storage after that proof. An unreadable archive is
  the same answer as a mismatched one: refuse.
- **The version comes from a remote asset name, so it never reaches a path unsanitised.**
- **One storage permission, unstable only.** The verified APK is written to the head unit's
  `Download` folder so the driver can find it, which on API 28 costs `WRITE_EXTERNAL_STORAGE`.
  It is declared in `app/src/unstable/AndroidManifest.xml` and nowhere else, capped at
  `maxSdkVersion=28` (from API 29 scoped storage refuses that write whatever is granted), and
  requested at runtime from the dashboard — a permission that is never asked for is a permission
  the app does not have. The grant is re-read at the moment of writing, never remembered. A
  refusal is a supported state: the APK goes to this app's own `Download` directory on the same
  volume instead, and the dialog names whichever path was used. One published APK is kept.
  Nothing else in this app reads or writes shared storage; the USB diagnostic export still adds
  no permission of its own.

### The one write: handing a destination to the car's navigation

`ChargeStopActivity` can send an `ACTION_VIEW` intent with a `geo:` URI, which moves whichever
navigation app the driver uses to a destination this app planned. It is the only thing here that
asks a vehicle system to act rather than to answer, and it exists because the alternative was a
driver retyping a route — the step that gets skipped, and that silently invalidates every figure
on the screen when it is done wrong.

Its bounds, all of which are testable:

- **Parked, for the tap.** The parked gate is re-read at the tap, not trusted from the render that
  drew the button. A navigation screen changing under someone at 110 km/h is the hazard.
- **One tap, one plan — but a plan with a charging stop is two handovers.** Nothing starts a
  handover except a press. What a press may start is the *plan the driver read and accepted*: this
  head unit has not been seen to drive a stop and a destination from one command, so `NavLegs`
  holds the accepted plan's remaining leg in memory and sends it when the car's own guidance flag
  reports the previous leg reached. No new destination can enter that chain, a chain is replaced
  rather than raced by the next press, it is never written to disk, and a process restart drops
  it. Nothing hands over on a drift, on a timer of its own, or on anything the driver did not
  already accept.
- **The chained leg is NOT speed-gated, and that is a known gap.** It fires on arrival, where the
  car is stopping or stopped, but nothing reads `PERF_VEHICLE_SPEED` before it sends — unlike the
  tap. Closing it means deciding what a handover refused at 3 km/h rolling into a charger should
  do, which is a product decision and not a patch; until it is made, this is the one place in this
  app where a vehicle-facing command can leave without a speed reading behind it.
- **One method.** `IMapNotificationListener` carries `stopNav`, `goHome` and `setFastestRoute`.
  None of them is called anywhere in this tree, and registering on that interface — which would
  mean impersonating a navigation provider — is not done.
- **It cannot lie about what it did.** A destination handed over without the route it belongs to
  is labelled on screen as exactly that: the car picks its own road, and the plan's figures stop
  describing the drive if it picks a different one.
- **No coordinates in any log or probe.** The validation artifact records that a handoff was
  tapped, whether anything accepted it, and the package name of the map installed on the car.
  Never where to.
- **The fallback carries nothing.** When no app answers the URI — which is this head unit —
  `MapApps` starts the vendor's navigation package with `ACTION_MAIN`, the intent its own
  launcher sends. It contains no destination and no data of any kind; the map opens where it
  already was, and the coordinates are printed on this app's screen for the driver to type.

### What the followed plan keeps

CP-058 freezes the chosen plan on that same tap so the drive can be compared against it later. It
is stored in app-private preferences and describes no particular journey: a leg distance, the
charge and odometer at departure, two rates, a reserve, and the route's sections as
distance-and-duration pairs. **No destination, no coordinate, no place name, no road name** — none
of it is needed by the arithmetic, and all of it would be an itinerary sitting on disk. The file
expires twelve hours after it is written, is cleared by the driver from the dashboard while
parked, and is never exported: no diagnostic bundle, USB export or log line reads it.

Watching it costs no network. The comparison is a division over the charge gauge and the odometer,
both of which are already being read, and the speed that would restore the plan comes from the
sections stored above and a model fitted from trips already on disk.

## Reporting

Do not open a public issue for a vulnerability that could affect a vehicle. Use GitHub's
private vulnerability reporting for the repository and include the firmware generation,
application version, reproduction steps and relevant redacted logs.

## Release expectations

Security CI blocks undeclared permissions and leaked secrets. Release APKs are minified and
must be signed through CI or local secret properties. Emulator success never replaces an
on-vehicle stability check.

