# Changelog

All notable changes follow [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

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
