# Pull Request: EVChargePilot

## 📝 What and Why

**What changes:**
<!-- List the files changed and the functional changes -->

**Why:**
<!-- Explain the problem this solves and how it solves it. "The diff already says what" — your job is to say WHY. -->

---

## 🔍 Verification

**Testing performed:**
- [ ] `mise run check` passes (permission gate + lint + unit tests)
- [ ] New behavior covered by a unit test
- [ ] Tested on emulator (`mise run run`)
- [ ] Tested on real MG4 head unit — Firmware version: ____________

**Telemetry correctness (if a vehicle reading changed):**
- [ ] Property id, area and unit checked against AOSP `VehiclePropertyIds`, not retyped by hand
- [ ] Sign convention stated and unchanged end to end (positive = energy leaving the pack)
- [ ] An unreadable property still yields `null` — never `0`, never `false`
- [ ] Implausible values rejected by an explicit range, not clamped silently
- [ ] Firmware dispatch covers every supported generation; `UNKNOWN` stays null

**Trip and history (if integration or storage changed):**
- [ ] Gaps longer than the sampling bound are skipped, never interpolated
- [ ] Duration, distance and energy stay mutually consistent (the averages divide comparable numbers)
- [ ] History write remains atomic and leaves no temporary file behind
- [ ] A corrupt or truncated history file degrades to an empty list instead of crashing

---

## 🔐 Security Checklist

**Read-only integrity:**
- [ ] No vehicle setter is called, directly or through EVHardware
- [ ] No new `android.car.*` permission (or added to `.github/security/permission-allowlist.txt` with justification)
- [ ] The permission gate `bash .github/security/check-permissions.sh` passes
- [ ] Nothing new is written outside app-private storage

**Offline integrity:**
- [ ] No network code, no `INTERNET` permission, no self-update path
- [ ] No vehicle data reaches a log sink that leaves the head unit
- [ ] Diagnostics output contains no credential, VIN or precise position

**Driver safety:**
- [ ] Controls that change recording state remain disabled above 0 km/h
- [ ] Nothing draws over another app and nothing asks for attention while moving
- [ ] Unreadable speed is treated as "moving", never as "parked"

---

## 🤖 Optional: Claude AI Assistance

If you'd like Claude AI to help review this PR, include this checklist:
- [ ] I request automated code review from Claude AI
- [ ] I understand Claude may suggest improvements to clarity, efficiency, or safety
- [ ] I grant permission to use my PR content for training (per GitHub's terms)

**Claude Refinement Prompt** (optional — paste if requesting AI review):
```
Please review this read-only energy dashboard PR for:
1. Vehicle property correctness (id, area, unit, sign convention)
2. Null-vs-zero discipline on every unreadable signal
3. Trip integration honesty (no invented distance or energy across gaps)
4. Security checklist compliance (no vehicle write, no network, no permission drift)
5. Driver distraction while the vehicle is moving
```

---

## 📋 Notes for Reviewer

<!-- Any context, gotchas, or decisions for the reviewer -->


<!-- Anything you are unsure about, or deliberately left out of scope. -->
