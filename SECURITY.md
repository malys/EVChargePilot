# Security

## Capability boundary

EVChargePilot is offline and read-only. Its manifest declares only the car permissions needed
to read speed, energy, exterior environment, climate and vendor-extension data. It has no
network, location, overlay, installer, boot receiver or vehicle-setting write path.

Trip history and crash reports stay in app-private storage. No telemetry leaves the device.

## Reporting

Do not open a public issue for a vulnerability that could affect a vehicle. Use GitHub's
private vulnerability reporting for the repository and include the firmware generation,
application version, reproduction steps and relevant redacted logs.

## Release expectations

Security CI blocks undeclared permissions and leaked secrets. Release APKs are minified and
must be signed through CI or local secret properties. Emulator success never replaces an
on-vehicle stability check.

