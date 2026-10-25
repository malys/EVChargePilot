#!/usr/bin/env python3
"""Render every app window on an emulator at the car's measured viewport."""
import os
import io
from pathlib import Path
import shlex
import shutil
import subprocess
import tarfile
import tomllib

ROOT = Path(__file__).resolve().parents[1]
suite = subprocess.check_output(
    ["git", "-C", str(ROOT), "rev-parse", "--show-superproject-working-tree"], text=True,
).strip()
if suite:
    expected = subprocess.check_output(
        ["git", "-C", suite, "rev-parse", f"HEAD:{ROOT.relative_to(suite).as_posix()}"], text=True,
    ).strip()
    assert subprocess.run(["git", "-C", str(ROOT), "merge-base", "--is-ancestor", expected, "HEAD"]).returncode == 0, \
        "App checkout is older than the workspace gitlink; restore the expected commit before testing"
config = tomllib.loads((ROOT / "mise.toml").read_text())
for key, expected in {"EMU_WIDTH": "1778", "EMU_APP_HEIGHT": "640", "EMU_DENSITY": "160"}.items():
    assert config["env"][key] == expected, f"{key} must stay {expected} (measured car app box)"

sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
adb_path = str(sdk / "platform-tools/adb")
serial = os.environ.get("ANDROID_SERIAL")
if not serial:
    devices = subprocess.check_output([adb_path, "devices"], text=True).splitlines()[1:]
    devices = [line.split()[0] for line in devices if line.endswith("\tdevice")]
    assert len(devices) == 1, "Set ANDROID_SERIAL when several devices are connected"
    serial = devices[0]


def adb(*args):
    return subprocess.check_output([adb_path, "-s", serial, *args], text=True, timeout=300)


assert serial.startswith("emulator-") and adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", \
    "Window tests seed fixtures and resize the screen: emulator only"
env = {**os.environ, **{k: v for k, v in config["env"].items() if k.startswith("EMU_")},
       "ANDROID_HOME": str(sdk), "ANDROID_SERIAL": serial}
subprocess.run(["bash", "-ec", config["tasks"]["emulator-viewport"]["run"]], env=env, check=True)
subprocess.run([str(ROOT / "gradlew"), "assembleStableDebug", "assembleStableDebugAndroidTest"],
               cwd=ROOT, check=True)
for folder in ["app/build/outputs/apk/stable/debug", "app/build/outputs/apk/androidTest/stable/debug"]:
    apk = next((ROOT / folder).glob("*.apk"))
    print(adb("install", "-r", "-g", str(apk)))

package = "com.evsuite.chargepilot"
user = adb("shell", "am", "get-current-user").strip()
adb("shell", "am", "force-stop", package)
data = f"/data/user/{user}/{package}/files"
for source, name in [("evchargepilot-trips.json", "trips.json"),
                     ("evchargepilot-battery-ledger.json", "battery-ledger.json")]:
    adb("push", str(ROOT / source), "/data/local/tmp/window-fixture.json")
    command = f"mkdir -p {data}; cat /data/local/tmp/window-fixture.json > {data}/{name}"
    adb("shell", f"run-as {package} --user {user} sh -c {shlex.quote(command)}")
adb("shell", "rm", "/data/local/tmp/window-fixture.json")

output = ROOT / "app/build/reports/window-bounds"
output.mkdir(parents=True, exist_ok=True)
font_scale = adb("shell", "settings", "--user", user, "get", "system", "font_scale").strip()
animations = {key: adb("shell", "settings", "get", "global", key).strip() for key in
              ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]}
failed = []
try:
    for key in animations:
        adb("shell", "settings", "put", "global", key, "0")
    for scale in ["1.0", "1.3"]:
        adb("shell", "settings", "--user", user, "put", "system", "font_scale", scale)
        for night in ["no", "yes"]:
            label = f"api-{adb('shell', 'getprop', 'ro.build.version.sdk').strip()}-font-{scale}-night-{night}"
            # One retry: the API 28 emulator's swiftshader drops frames ("bad color buffer handle")
            # and one configuration failed that way on 2026-10-25 while two runs of the same commit
            # passed. Layout is deterministic, so a real clipping fails both attempts.
            for attempt in (1, 2):
                result = adb("shell", "am", "instrument", "--user", user, "-w", "-r", "-e", "night", night,
                             "-e", "font_scale", scale,
                             f"{package}.test/com.evsuite.chargepilot.WindowBoundsTest")
                (output / f"{label}-attempt-{attempt}.txt").write_text(result)
                print(result, flush=True)
                if "window_bounds_failures=0" in result:
                    break
                # Annotations are readable without a token; job logs are not.
                for line in result.splitlines():
                    if line.startswith("FAIL"):
                        print(f"::error title=window-bounds {label} #{attempt}::{line[:900]}", flush=True)
                print(f"RETRY {label}: attempt {attempt} failed", flush=True)
            archive = subprocess.check_output([adb_path, "-s", serial, "exec-out", "run-as", package,
                "--user", user, "tar", "-C", data, "-cf", "-", "window-bounds"], timeout=30)
            if (output / label).exists():
                shutil.rmtree(output / label)
            with tarfile.open(fileobj=io.BytesIO(archive)) as files:
                files.extractall(output / label, filter="data")
            if "window_bounds_failures=0" not in result:
                failed.append(label)
finally:
    for key, value in animations.items():
        adb("shell", "settings", "delete" if value == "null" else "put", "global", key,
            *([] if value == "null" else [value]))
    if font_scale == "null":
        # API 28's settings refuses --user on delete; it deletes for the current user.
        adb("shell", "settings", "delete", "system", "font_scale")
    else:
        adb("shell", "settings", "--user", user, "put", "system", "font_scale", font_scale)
assert not failed, f"Clipped windows: {failed}; reports/screenshots: {output}"
print(f"PASS: all activities + About, 6 languages, day/night, 100%/130%; reports: {output}")
