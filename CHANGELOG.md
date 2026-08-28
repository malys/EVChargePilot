# Changelog

All notable changes follow [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

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
