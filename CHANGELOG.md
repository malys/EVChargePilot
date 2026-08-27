# Changelog

All notable changes follow [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0] - 2026-08-27

### Added

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

### Fixed

- A trip's duration now counts only the time covered by usable samples. Hiding the dashboard
  for ten minutes used to add ten minutes to a trip whose distance and energy had not moved,
  which made the consumption average divide numbers that did not belong together.
- The vehicle snapshot read by a control tap is published across threads (`@Volatile`).
