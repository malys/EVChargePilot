# Contributing

Use English for code, comments, documentation and commit messages. Keep vehicle access in
EVHardware, preserve nullable readings, and add JVM coverage for calculation changes.

Before submitting a change:

```bash
mise run test
mise run lint
mise run build
```

Do not commit signing material, credentials, vehicle traces containing personal data, or
generated build output. A vehicle-facing release also requires an MG4 test pass while parked.

