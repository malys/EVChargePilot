# Changelog

All notable changes follow [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

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
