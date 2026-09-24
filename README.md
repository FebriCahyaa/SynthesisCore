# SynthesisCore

<p align="center">
  <b>Event-driven Android system monitor for root daemons — a modern replacement for <code>dumpsys</code></b><br/>
  Kotlin · runs via <code>app_process</code> · Android 9 – 17
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-9%E2%80%9317%20(API%2028%E2%80%9337)-3DDC84?logo=android&logoColor=white"/>
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white"/>
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue"/>
  <img src="https://img.shields.io/badge/Used%20By-Flux%20Tweaks-6C63FF"/>
</p>

- [What is SynthesisCore?](#what-is-synthesiscore)
- [Output format](#output-format)
- [Usage](#usage)
- [Architecture](#architecture)
- [Compatibility](#compatibility)
- [Security](#security)
- [Releases and changelog](#releases-and-changelog)
- [Building](#building)
- [License](#license)

---

## What is SynthesisCore?

SynthesisCore is a lightweight daemon that tracks the Android system state a performance module
needs — foreground app, screen, power, thermal, audio, battery — and publishes it as a small
plain-text file that native daemons such as [Flux Tweaks](https://github.com/FebriCahyaa/Flux)
watch with `inotify`.

It runs through `app_process`, so it has the full Android framework (including hidden APIs)
without being installed as an app. Where the platform offers a callback, SynthesisCore listens
instead of polling, and the file is only rewritten when a value changes.

| | SynthesisCore | `dumpsys` |
|---|---|---|
| **Latency** | Framework callbacks; ~1 s safety polls | One snapshot per invocation |
| **Precision** | Exactly the fields consumers need | Dumps everything, needs custom parsing |
| **Overhead** | One persistent process, polls slow down with the screen off | Spawns a process per call |
| **Integration** | Atomic, `inotify`-friendly file | Shell piping |

## Output Format

SynthesisCore writes a plain-text key-value file, updated only when any value changes. Writes are
atomic (`.tmp` + `fsync` + rename), so watchers must listen for `IN_MOVED_TO` as well as `IN_CLOSE_WRITE`.
Numbers always use `.` as the decimal separator, whatever the device locale.

```text
synthesis_version 3
focused_app com.rhmsoft.edit.pro 4720 10292
screen_awake 1
battery_saver 0
zen_mode 0
charging_state 1
thermal_status 0.85
audio_active 1
thermal_api_available 1
kernel_is_gki 1
thermal_level 0
battery_level 87
battery_temp 34.5
call_active 0
```

Consumers must ignore unknown keys. A key that is absent means the value is unsupported on this device.

### Field Reference

| Field | Type | Description |
|---|---|---|
| `synthesis_version` | `int` | Output format / CLI contract version (`Protocol.VERSION`). Missing = 1 |
| `focused_app` | `string int int` | Foreground package name, PID, UID |
| `screen_awake` | `0\|1` | Whether the display is interactive |
| `battery_saver` | `0\|1` | Power Save Mode active |
| `zen_mode` | `0–3` | DND level: 0=off, 1=priority, 2=silence, 3=alarms |
| `charging_state` | `0\|1` | `1` = device is charging (AC / USB / wireless) |
| `thermal_status` | `0.00–1.00` | Thermal headroom (1 s forecast) — `1.0` = cool, `0.0` = throttling. `-1.00` on API < 30 or when the HAL returns NaN |
| `audio_active` | `0\|1` | `1` = music/game audio stream is active |
| `thermal_api_available` | `0\|1` | `1` = `PowerManager.getThermalHeadroom()` exists (API 30+) |
| `kernel_is_gki` | `0\|1` | `1` = kernel reports GKI (`-androidXX-` in `uname -r`) |
| `thermal_level` | `0–6` | `PowerManager.getCurrentThermalStatus()`: 0=none, 1=light, 2=moderate, 3=severe, 4=critical, 5=emergency, 6=shutdown. API 29+ |
| `battery_level` | `0–100` | Battery capacity in percent |
| `battery_temp` | `float` | Battery temperature in °C (from `/sys/class/power_supply/battery/temp`) |
| `call_active` | `0\|1` | `1` = audio mode is a phone call, VoIP/communication or call screening |

## Usage

```shell
app_process -Djava.class.path=/sdcard/app-release.apk / \
  --nice-name=FluxSysMon \
  com.febricahyaa.synthesiscore.MainKt \
  /path/to/output/file \
  [/path/to/lock/file]
```

Other modes:

| Command | Purpose |
|---|---|
| `MainKt --once` | Print one status snapshot to stdout and exit (debugging) |
| `MainKt --capabilities` | Print how every provider runs on this device (`EVENT` / `POLL` / `STATIC` / `FAILED`) |
| `MainKt --version` | Print the protocol version |
| `MainKt --resolve [file]` | Resolve binder transaction codes (see below) |

The optional second argument is a lock-file path. If provided, SynthesisCore will acquire an exclusive `FileLock` on startup — any attempt to run a second instance against the same lock will exit immediately with an error.

### Binder transaction resolver (`--resolve`)

Binder transaction codes differ between Android versions and ROMs. The `--resolve` mode is a one-shot
lookup that lets native code (e.g. Flux) issue binder calls directly without keeping a JVM alive:

```shell
app_process -Djava.class.path=/sdcard/app-release.apk / \
  com.febricahyaa.synthesiscore.MainKt --resolve [/path/to/output/file] <<EOF
android.os.IPowerManager.Stub::TRANSACTION_isInteractive
android.app.INotificationManager.Stub::TRANSACTION_getZenMode
EOF
```

```text
android.os.IPowerManager.Stub::TRANSACTION_isInteractive 21
android.app.INotificationManager.Stub::TRANSACTION_getZenMode 123
```

- Input is one `Class::FIELD` per line; nested classes may use `.` or `$` (`IPowerManager.Stub` = `IPowerManager$Stub`). Blank lines and `#` comments are ignored.
- Output goes to stdout, or is written atomically to the given file.
- Entries that cannot be resolved are reported on stderr and omitted from the output.
- Exit code: `0` all resolved, `1` some entries failed, `2` output could not be written.

## Architecture

SynthesisCore is a small event-driven framework on top of the Android system services:

```
app/src/main/java/com/febricahyaa/synthesiscore/
├── MainKt.kt                 CLI entry point (monitor, --resolve, --once, --capabilities, --version)
├── core/
│   ├── SystemEnvironment     hidden API exemption, main Looper, system Context from ActivityThread
│   ├── Engine                single-Looper scheduler: events + adaptive polls, coalesced writes
│   ├── StateProvider         provider contract (start → sample → stop) and TriggerMode
│   ├── Protocol              field names, output order, locale-independent formatting
│   ├── Binders               ServiceManager / AIDL stub / TRANSACTION_* reflection helpers
│   ├── AtomicFile            tmp + fsync + rename writer
│   └── Log                   stderr logger with de-duplication
├── provider/
│   ├── foreground/           focused_app: TaskResolver (IActivityTaskManager), ProcessTracker,
│   │                         ForegroundAppProvider (UID importance listener trigger)
│   ├── DisplayProvider       screen_awake
│   ├── PowerProvider         battery_saver, zen_mode
│   ├── BatteryProvider       charging_state, battery_level, battery_temp
│   ├── ThermalProvider       thermal_status, thermal_level, thermal_api_available
│   ├── AudioProvider         audio_active, call_active
│   └── KernelProvider        kernel_is_gki
└── resolver/BinderResolver   --resolve mode
```

### How values are collected

Each provider picks the best mechanism the device supports and reports it as its `TriggerMode`:

| Provider | Trigger (EVENT) | Fallback / safety poll |
|---|---|---|
| foreground | `ActivityManager.addOnUidImportanceListener` (@SystemApi, via `Proxy`) | 1 s (0.5 s without listener), 5 s screen off |
| display | `DisplayManager.DisplayListener` | 5 s (1 s without listener) |
| thermal | `PowerManager.addThermalStatusListener` (API 29) | headroom polled every 1 s, 10 s screen off |
| audio | `AudioPlaybackCallback` (API 26), `OnModeChangedListener` (API 31) | 1–5 s, 10 s screen off |
| power | — (change broadcasts are not receivable from `app_process`) | 2 s, 10 s screen off |
| battery | — (same) | 2 s, 30 s screen off |
| kernel | `STATIC` | never |

Why no broadcasts: `app_process` has no app record in ActivityManager, so `registerReceiver` returns
`null` without registering. Binder-callback listeners (display, thermal, audio, UID importance) do not
need one, which is why they are used wherever the platform offers them.

All providers run on one `Looper` thread, so they need no locking. Framework callbacks call
`invalidate()`, which re-samples only that provider; changes within 25 ms are merged into one write.
Poll intervals slow down automatically while the screen is off. A provider that fails to start is
dropped and reported as `FAILED`; the others keep working.

### Adding a field

1. Add the key to `Protocol` (and to `FIELD_ORDER`), bump `Protocol.VERSION`.
2. Write a `StateProvider` (or extend one) that fills the key in `sample()`.
3. Register it in `MainKt.createProviders()`.
4. Teach the consumers (Flux `SynthesisCoreReader`, WebUI monitor store) to parse it.

## Compatibility

| Android | API | Notes |
|---|---|---|
| 9 | 28 | Minimum. Foreground via `IActivityManager`; no thermal fields |
| 10 | 29 | `IActivityTaskManager`; `thermal_level` with status listener |
| 11 – 15 | 30 – 35 | Thermal headroom (`thermal_status`) polled every second |
| 16 | 36 | Headroom pushed by `addThermalHeadroomListener` when the ROM enables it |
| 17 | 37 | Compiled and tested against the Android 17 framework (`compileSdk`/`targetSdk` 37) |

Every framework mechanism is probed at startup and falls back gracefully. Run
`MainKt --capabilities` on a device to see which providers are event-driven (`EVENT`), polled
(`POLL`) or unavailable (`FAILED`) — include that output in bug reports.

## Security

SynthesisCore runs as root, so it treats its inputs and outputs defensively:

- **Output** — keys are validated, control characters in values are replaced and values are
  length-capped, so no value can inject a forged line into the status file. Files are written
  through a temp file opened with `O_EXCL|O_NOFOLLOW`, `fsync`ed and renamed atomically: a
  symlink planted next to the output cannot redirect writes.
- **Input** — paths must be absolute and normalised; `--resolve` only accepts well-formed
  `Class::FIELD` identifiers (bounded count and length) and only reads `static final int` fields.
- **Binary** — release APKs are minified and obfuscated with R8 (only `MainKt.main` keeps its
  name), signed with the project key, and published with a SHA-256 checksum, the signing
  certificate digest and a GitHub build provenance attestation.
- **Consumers** — Flux only syncs a release whose checksum *and* pinned signing certificate
  verify, and re-checks the APK checksum on every boot before running it.

Report vulnerabilities privately through GitHub security advisories rather than public issues.

## Releases and changelog

Releases are fully automated by [`release.yml`](.github/workflows/release.yml):

1. Publish a GitHub release with a tag like `v2.1.0` — or run the **Release** workflow with a
   version and it creates the tag and release for you.
2. The workflow runs the tests, builds, signs and verifies the APK, and attaches:
   - `SynthesisCore-v2.1.0.apk`
   - `SynthesisCore-v2.1.0.apk.sha256`
   - `SynthesisCore-v2.1.0.apk.cert.sha256` (signing certificate digest)
3. The release notes get a changelog generated from the
   [Conventional Commits](https://www.conventionalcommits.org) since the previous tag, grouped into
   features, fixes, performance, etc. Hand-written notes above it are kept.
4. Flux is notified and opens a verified sync pull request.

Commit messages therefore follow `type(scope): description`, e.g. `feat(thermal): …`,
`fix(foreground): …`; a `!` after the type or a `BREAKING CHANGE:` footer marks breaking changes.
Preview the next changelog locally with `.github/scripts/changelog.sh`.

### Verifying a release

```shell
sha256sum -c SynthesisCore-v2.1.0.apk.sha256
apksigner verify --print-certs SynthesisCore-v2.1.0.apk     # compare with the release notes
gh attestation verify SynthesisCore-v2.1.0.apk --repo FebriCahyaa/SynthesisCore
```

Release secrets: `KEYSTORE_BASE64`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`,
`SIGNING_STORE_PASSWORD`; optional `FLUX_DISPATCH_TOKEN` (fine-grained token, repository
access *Only FebriCahyaa/Flux*, permission *Contents: Read and write*) and the Telegram secrets.

## Building

```shell
./gradlew testDebugUnitTest   # unit tests
./gradlew assembleRelease     # minified, unsigned release APK
```

**Requirements:** JDK 25 · Android Gradle Plugin 9.x · `compileSdk 37`

The `CI` workflow runs the tests and builds an unsigned APK on every push and pull request; the APK
is kept as a workflow artifact for on-device testing (`app_process` does not check signatures).

## License

```
Copyright 2026 FebriCahyaa

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
