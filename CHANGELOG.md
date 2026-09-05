# Changelog

All notable changes follow [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- **The speed what-if now works on the car it was written for.** This MG4 declares battery power
  and never publishes it — three diagnostic bundles, 51 samples, not one reading — so the kWh
  model behind the what-if was never going to train, and the screen would have said "not yet"
  forever. Consumption is now fitted in percent of charge per 100 km straight from the gauge and
  the speed, which needs no battery power and no pack capacity from a specification sheet. The
  saving comes out in the unit the driver's own gauge is in.

- **The two screens that stay blank now say why, and stop promising.** Battery power is what the
  energy breakdown and the post-trip speed comparison are made of, and this vehicle never sends
  it. They said "not validated yet", which invites coming back to a screen that will never fill
  in. They now say the vehicle does not publish it, now or later.

- **"If you drive at 110 you will not need to charge" — with the twenty minutes it costs.**
  The charge-stop screen now says what driving differently would change on the route it just
  planned. Where the road ahead is fast — which comes from the router's own duration over its
  own distance, not from a speed-limit dataset — it shows what 130, 120, 110, 100 and 90 km/h
  would save and what each would cost in delay, and puts first the mildest slowdown that
  removes the charging stop altogether. A saving is never shown without its delay: that would
  be advice, and this app does not give advice. Consumption comes from the same fitted model as
  the post-trip comparison, so the two screens cannot disagree, and outside its trained
  envelope this refuses rather than inventing a number. The same request also brings back a
  second road, compared on distance, time and charge. ORS bills one request either way, so the
  alternative costs the driver's quota nothing.

- **"Do I need to charge on this trip, and in how many kilometres" — the sentence this app was
  described by, now on a screen.** Type a destination while parked, choose which of the answers
  you meant, and the app plans to a reserve rather than to zero: either the charge left on
  arrival with the margin above that reserve, or the distance at which to stop. The stop
  distance is computed at the top of the rate band, not its middle — being told to charge
  earlier than necessary costs minutes, being told to charge later costs a tow truck — and a
  plan too uncertain to act on is refused instead of shown. The arithmetic is `ChargeStopPlan`
  in EVHardware, seven JVM tests, and it has no idea a network exists.
- **A routing key that is yours.** No key ships in the APK, so there is a screen to enter one
  and a USB import for the realistic case: this head unit's picker answers "no apps can perform
  this action", so a `key = value` file is found by scanning the stick and parsing it, the same
  format and the same reasoning as EVABRPUploader. The key is stored through the Android
  keystore, is never displayed back, never logged, and never reaches a diagnostic export — the
  exporter reads no preferences at all, which is a stronger guarantee than a filter.
- **One place, and only one, where this app opens a socket.** `https` on the first URL and on
  every redirect hop, checked against the host the driver configured, with redirects not
  followed by the platform; the key travels in an `Authorization` header and never in a query,
  even where the service documents a query; timeouts, a 2 MB cap, single flight, and a local
  quota counter so the app refuses before the server does. An `https` → `http` `Location` is a
  man in the middle and is refused, not followed.
- **Elevation, which the car will never give.** The route comes back as a 3D geometry, so climb
  and descent are shown with the distance and duration, and CP-050 has something to model with.
  OpenStreetMap under ODbL: the service's attribution is displayed wherever a route is.
- **And *where* the stop is, not only how far away.** With a second key of the driver's own, the
  screen names the last charger reachable before the reserve is reached — the last, not the
  nearest, because stopping earlier than necessary costs a driver time they came for — with its
  power, its connector as this car can use it, and the charge it would be reached with. Open
  Charge Map was chosen over the routing engine's own POI service for the two things that
  service cannot answer: which connector, and how old the record is. Every charger is shown
  with its data provider and the date the record was last confirmed, because a charger dataset
  is wrong the day it is published and hiding that turns a suggestion into a promise
  (CC BY 4.0 for contributed records, the provider's own licence for imported ones, attributed
  on screen either way). What leaves the car is a 40 km stretch of road around the planned stop
  and a 5 km corridor — never the route, never the origin, never the destination.
- **The Cévennes are on the screen, in charge rather than in metres.** The route already showed
  climb and descent; now they cost something. The stop distance and the arrival figure carry a
  grade term from `RouteGrade`, on its own line, labelled as modelled and never as measured —
  and absent entirely when no profile came back, because a zero would be a claim that the road
  is flat. The profile itself is filtered first: elevation behind these geometries is a sampled
  terrain model that wobbles a metre or two at every one of thousands of points, and summing
  that raw turns a flat road into hundreds of metres of imaginary climb. A 10 m threshold
  discards the noise and keeps the col.

### Changed

- **"Works offline" stopped being true in one direction only, and the documents say so.**
  Network and location were approved for route planning, which retires the first line of this
  app's safety argument — an app that cannot open a socket cannot exfiltrate a trip history, and
  that property came free from not declaring a permission. `PRODUCT.md` now states the boundary
  as it is rather than as it was: everything shipped so far is local, a route request is the one
  thing that will ever leave, and it carries an origin, a destination and a road profile to a
  host the driver configures — never a trip, an evidence capture, a diagnostic bundle, a charge
  or an identifier. The three permissions are in the allowlist with a security note each and in
  the manifest, by the owner's decision, ahead of the routing client that will use them:
  `INTERNET` opens no socket yet and a granted position is compared to nothing yet.
  `FOREGROUND_SERVICE_LOCATION` stays out, because no service reads a position;
  `ACCESS_COARSE_LOCATION` had to come in, because Android 12+ refuses a fine-only declaration,
  and a coarse-only grant will have to be treated as a refusal rather than as an origin. Engine chosen
  (OpenRouteService, key entered by the driver, because a key inside a published APK is not a
  secret), transport rules written down, security gate re-run green twice.

  One thing to confirm in the car: `CAR_SPEED` is in permission group `LOCATION`, and on API 28
  the system grants a permission without a dialog when the app already holds another from the
  same group. A driver who granted speed at startup may therefore be handed location with no
  prompt. The speed request and the location request are kept in separate call sites so the
  coupling stays visible rather than merged into one silent grant.

### Fixed

- **Trip distances were 3.6× too long.** Speed is read through EVHardware, which converted a
  property this firmware already reports in km/h, so every integrated distance carried the same
  factor: a 2.4 km town route was recorded as 8.46 km. Fixed in the library and picked up here;
  trips already stored keep the distances they were recorded with.

### Added

- **The dashboard forecasts the charge you will arrive with.** The screen the whole trip
  companion was supposed to lead to could not be built the obvious way: CP-003 proved this
  firmware declares battery power and never publishes it, so nothing can integrate kWh. Charge
  per kilometre needs no energy unit, and both its inputs read. The new "Arrival charge" screen
  takes the remaining distance from the car's own guidance — read through synchronous getters,
  never by registering on the adapter's callback fan-out — and spends it at a rate taken from
  your recorded trips, or from the vehicle's own range estimate until three trips exist.
  It refuses to show a figure whose band is wider than fifteen points, because a forecast that
  says "somewhere between 20 and 60" is not a forecast. Trips recorded before the 3.6× speed fix
  are excluded by the estimator rather than silently averaged in.

- **The bundle answers the speed-unit question by itself.** A trip distance is integrated from
  speed, so a wrong speed unit scales it by the same factor and nothing in the number says so —
  a 2.4 km drive recorded as more than 7 km was the only symptom, and it needed somebody to
  notice. The unstable signal recorder now also reads the navigation adapter's odometer, which
  is not where the snapshot's odometer comes from and which nothing derives from speed. The trip
  artifact carries the ratio between the two distances and names what it means: consistent, the
  m/s factor applied twice, a disagreement that is neither, or too short a drive to tell.

- **The unstable channel records its own evidence and the export carries all of it.** Validation
  had to be driven by hand: open a screen, arm a probe, remember a save button, then export —
  and the run that mattered most, the one where a probe heard nothing, was the one nobody
  thought worth saving. The guidance listener and a session-long signal recorder now arm when
  the application starts, and one "Export to USB" writes the guidance trace, the signal
  statistics and the recorded trip summaries into the bundle by itself. The probe screen became
  a window onto the recorder rather than its owner, so closing it no longer ends a capture.
  Trip summaries travel because a distance integrated from a wrong speed scale shows up there
  first. Stable arms nothing and contains none of it.

### Fixed

- **The dashboard now asks for the vehicle signals it already declared.** AAOS classifies
  `CAR_SPEED` and `CAR_ENERGY` as `dangerous` permissions, so declaring them in the manifest
  granted nothing and every matching property read failed silently. The first on-vehicle test
  showed the consequence: no speed anywhere, trip start and automatic detection permanently
  disabled, and the USB diagnostic export refusing because it could not prove the car was
  stopped — all of it indistinguishable from a firmware that publishes nothing. The main screen
  now requests the two at launch and logs a denial. No permission was added: the manifest is
  unchanged, and the app still holds no location, network, overlay or vehicle-write capability.

### Added

- **A route source exists after all, and the unstable channel can prove it.** The head unit's
  adapter service fans remaining distance, remaining time, guidance status and road name out to
  any registered listener, so a route no longer requires a network, a permission or an account.
  A parked-armed probe in the unstable channel records that fan-out over a drive and saves it
  as a trace file the diagnostic export carries to USB. Nothing is displayed as a value: the
  distance callbacks carry no proven unit and the status codes no documented meaning, so every
  reading stays raw pending the on-vehicle run. The stable channel is unchanged and gains no
  capability.

### Changed

- **Every probe now leaves the car on a USB stick, not through the clipboard.** The guidance
  probe kept its trace in memory and offered a copy button, so the only way off the head unit
  was a paste into some other app — which the read-only, offline boundary makes pointless, and
  which loses the census and the caveats a reading has to be judged against. Both probes now
  write bounded JSON into the folder the diagnostic export bundles, so one **Export to USB**
  carries the signal captures and the guidance trace out together, each hashed in the bundle
  manifest. The copy actions are gone, and the capture screen no longer points at adb.

### Security

- The application declares an empty task affinity, reducing launcher-task hijacking exposure on
  the target Android 9 platform without adding a permission or changing vehicle access.

### Added

- **A two-column post-trip breakdown separates facts from model claims.** Integrated consumed,
  regenerated and net battery energy plus the unmodelled remainder sit beside banded traction
  and residual estimates. Plain-language summaries call out model noise, negative error and the
  lack of a vehicle consumer meter. Unsupported battery-heating and accessory categories never
  appear; missing evidence gets a complete explanatory state.

- **Trip detail exposes honest energy attribution.** Integrated consumption is reconciled with
  modelled traction, measured regeneration, climate-conditioned residual groups and a visible
  unmodelled discrepancy. Every attributed number has a band; model-noise residuals say they are
  indistinguishable from zero, negative residuals remain model error, and the copy explicitly
  says no vehicle per-consumer measurement exists.

- **Completed trips can show a parked-only motorway speed comparison.** The panel offers only
  90–130 km/h reference speeds inside the exact trained speed/temperature envelope, labels every
  result as estimated, and propagates the model band into energy and equivalent-range intervals.
  Missing model evidence, motorway samples, power, temperature, range baseline or fresh parked
  speed produces an explanation instead of a number. Training remains bounded and fail-closed
  until CP-003 validates exact firmware evidence.

- **Unstable telemetry evidence now has a hard retention ceiling.** Captures remain
  app-private and atomic, but only the eight newest JSON summaries are kept; stale temporary
  files are removed before a write. Repeated validation runs therefore cannot grow AAOS storage
  without bound.

- **Thermal and climate telemetry is grouped without implying energy use.** Outside, cabin and
  battery temperatures now sit with HVAC, AC, auto, econ and recirculation states, fan
  level/maximum and both target temperatures. Every field stays independently nullable, so a
  partial firmware snapshot remains useful and every missing value keeps an explained em dash.
  Battery temperature is evidence-gated per firmware and therefore remains explicitly
  unvalidated until CP-003 proves it. The block states that it shows climate state only and that
  no HVAC power is measured; it adds no control path and retains no additional history.

- **Diagnostics and vehicle-test evidence export as one bounded USB bundle.** The parked-only ZIP
  carries runtime/APK/signer identity, exact firmware, service and latest-sample state, property
  probes, provenance, bounded logs, previous crash, and up to eight scenario-named CP-003 JSON
  captures. Its manifest provides byte counts and SHA-256 per artifact. Report, capture and bundle
  limits are 128/64/768 KiB; the atomic temporary file lives on USB, no archive accumulates on
  AAOS, and no network or storage permission is added. Unstable diagnostics now require choosing
  the physical test scenario before capture so evidence cannot be mistaken for another segment.

- **Battery power flow is directional, static and evidence-gated.** The signed kW value is paired
  with a centred, non-animated scale and explicit output/regeneration text, so colour never
  carries direction alone. EVHardware's per-firmware evidence catalogue starts empty: until
  CP-003 validates a generation, raw power is not integrated into normal trips and every
  dependent dashboard calculation renders as unavailable instead of confidently applying an
  assumed scale or sign. Trusted trip totals store only a small exact firmware/conversion tag;
  legacy and mismatched totals are excluded from models, while CP-003's bounded unstable
  evidence recorder remains the separate path for proving the raw signal. CSV and JSON schema 2
  exports preserve that tag explicitly; legacy exports carry empty/null evidence instead of
  silently presenting the totals as validated.

- **Automatic trip detection is fail-closed and parked-configurable.** A small persisted switch
  enables the existing foreground sampler, while EVHardware alone decides start/end boundaries.
  Five seconds of motion starts a trip; a confirmed two-minute standstill ends it. Missing speed
  or a telemetry gap can do neither, and the dashboard states whether it is waiting, confirming,
  or unavailable. Ten consecutive unavailable speed reads suspend idle background polling until
  the next foreground launch, keeping unsupported firmware quiet and bounded.

- **Vehicle range stays authoritative beside a visibly modelled adaptive estimate.**
  Up to eight recent trip averages feed EVHardware's estimate after three usable observations;
  the dashboard prefixes it with `≈` and shows the observed-spread uncertainty in kilometres.
  Missing current power, usable energy, or history leaves an explained gap instead of a guess.

- **The dashboard now separates immediate and trip consumption without competing with SOC.**
  Instant kWh/100 km uses EVHardware's five-second smoothing and stays unavailable below
  5 km/h; regeneration remains negative. The adjacent trip average keeps the accumulator's
  integrated consumed-energy/distance definition, and both numbers are labelled as arithmetic
  derived from vehicle readings rather than measurements or predictions.

- **Completed trips can now leave the head unit without granting broad storage access.** The
  ledger exports either one trip or all retained trips as a documented CSV summary or a
  schema-versioned JSON document with sample tracks. Files are bounded, written atomically to
  app-private storage on a worker thread, and shared only through a user-chosen app with
  temporary read access; unavailable readings stay empty or null rather than becoming zero.

- **A parked-only trip ledger now exposes the complete local record.** Up to 200 trips appear
  newest first with recorded totals, SOC change, consumed/regenerated energy and a lightweight
  speed/power trace when samples exist. Missing values remain explicit and explainable; single
  and bulk deletion require a fresh readable speed at or below 0.1 km/h and a full-screen
  confirmation.

- **The unstable evidence capture records usable battery capacity as an unvalidated candidate.**
  The public post-AAOS 9 property is shown beside normal snapshot fields, remains null when the
  runtime does not publish it, and is never copied into `EnergySnapshot` or a stable screen.

- **Unstable builds can capture firmware-specific telemetry evidence from one parked-only
  diagnostics screen.** The screen shows per-signal availability and statistics, records at the
  dashboard's 1 Hz cadence plus a 200 ms burst for the first minute, and saves schema-versioned
  JSON atomically in app-private storage. Start and stop both fail closed until speed is readable
  and at or below 0.1 km/h. The completed file path stays visible for `adb pull`, while its
  Markdown table can be copied without storage or network permission. Stable builds contain no
  capture activity, recorder controller or file store.

- **A trip keeps recording while the driver looks at something else.** Sampling used to live
  between the dashboard's `onStart` and `onStop`, so switching to the media app for ten minutes
  left a hole in the middle of the drive: the accumulator refuses to integrate across a gap
  longer than five seconds, correctly, since it has no idea what the car did in between, and the
  trip came back missing the distance and energy of the part nobody was watching. A foreground
  service now owns the sampler and the trip history, and the dashboard is one of its readers. It
  samples while a dashboard is bound or a trip is recording, and stops itself when neither is
  true, so nothing reads vehicle properties for nobody. The service claims only the foreground
  type its held permissions back, and the notification stays a low-importance status line — it
  never interrupts a moving car.

- **Every figure on the dashboard now says what kind of claim it is.** The energy work ahead is
  mostly inference — the MG4 publishes no per-consumer counters, so the climate share, the speed
  comparison and the arrival state of charge will all be models — and a model drawn in the same
  typeface as a vehicle reading is a vehicle reading as far as the driver is concerned. Readings
  are mapped into EVHardware's `Provenanced` carrier, which cannot hold an estimate without its
  uncertainty band and cannot hold a number for a value it calls unavailable. An estimate wears a
  `≈` and its band; a missing figure stays an em dash whose reason — unsupported generation,
  signal not published, not enough samples — is spoken by the reading's description and spelled
  out in the diagnostics report. The distinction is carried by text, not colour, so it survives
  daylight and reaches a screen reader.

- **The diagnostics dialog lists what each energy property actually answers.** A field showing
  an em dash says a signal is unusable but not why, and unsupported, declared-but-never-published
  and unreachable all look identical from the driver's seat. The report names the status, the raw
  value and the publication timestamp per property, so a firmware generation's telemetry surface
  can be recorded from the car.
- Initial read-only energy dashboard.
- Nullable vehicle snapshot backed by EVHardware.
- Parked-only local trip recording with tested distance and energy integration.
- Atomic bounded trip history and in-app crash diagnostics.
- Shared EVHardware energy snapshot with battery power, pack temperature, charging and
  climate fallbacks; shared trip integration and persistence replace app-local copies.
- Complementary Automotive API 33 and screen-accurate API 28 emulator profiles, with setup,
  launch, install, logs and stop commands through `mise`.
- The suite's CI and security workflows: blocking permission-drift and secret gates,
  mobsfscan/semgrep SARIF, OWASP dependency scan, a tag-driven stable release and a rolling
  unstable pre-release. Unlike the launcher apps, neither channel self-updates — this app
  declares no network permission at all.
- Issue, security and pull-request templates matching the rest of the suite.

### Changed

- **A recorded trip now stores its sample track alongside its totals.** The recorder hands the
  track to EVHardware's versioned history file, which keeps one sample per five seconds and
  drops the oldest tracks — never a whole trip — when the file approaches its size ceiling.
  Nothing on screen changes yet; the tracks are what the consumption, climate-share and
  arrival-state-of-charge models will be fitted from, and they have to exist before those
  models can.

- The diagnostics report is built on the sampler thread. It reads ten vehicle properties over
  binder and the crash file off disk, neither of which belongs on the thread drawing the dialog.

### Fixed

- **The battery temperature no longer reads 0 °C on a warm car.** The reading came from a
  property SWI68 declares and never publishes, which answers a read with a plain zero that looks
  exactly like a measurement. EVHardware now checks the status the vehicle attaches to each
  property, so an unpublished signal arrives as unknown and the dashboard shows an em dash —
  the rule this app already stated and could not previously enforce.

- A trip's duration now counts only the time covered by usable samples. Hiding the dashboard
  for ten minutes used to add ten minutes to a trip whose distance and energy had not moved,
  which made the consumption average divide numbers that did not belong together.
- The vehicle snapshot read by a control tap is published across threads (`@Volatile`).
