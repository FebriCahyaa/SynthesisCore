# Repository Data Inventory — SynthesisCore (future Aeyrin)

Required by the Repository Data Preservation Lock of the Zairenkai programme: nothing in this
repository may be moved, renamed, regenerated-over, truncated or deleted until it is listed here and
in `DELETION_MANIFEST.md`. Ecosystem-level baseline, migration matrix and agent state live in
`FebriCahyaa/Flux` under `docs/architecture/` and `docs/agent-state/`.

| | |
|---|---|
| Audited commit | `master` @ `7cbdda2d9961f207bcad2937f647b57605269dbc` |
| Audit branch | `ccr-0dc934d1-0a6zta` (no code changes) |
| Tracked files | 58 |
| License | Apache-2.0 |
| Latest release tags | `v2.1.0`, `v2.1.1` (Flux pins v2.1.1) |
| Audit date | 2026-09-29 |

Classes: AUTHORITATIVE, DEPENDENCY, HISTORICAL, TEST, FIXTURE, GENERATED, DERIVED, UNKNOWN
(preserved, never read as obsolete).

## Tracked content

| Path | Files | Role | Consumers | Class | CI | Runtime | Tests |
|---|---|---|---|---|---|---|---|
| `app/src/main/java/com/febricahyaa/synthesiscore/MainKt.kt` | 1 | CLI entry: monitor, `--resolve`, `--once`, `--capabilities`, `--version` | Flux `module/service.sh` invokes `com.febricahyaa.synthesiscore.MainKt` by name via `app_process` | AUTHORITATIVE (external contract) | yes | **yes (root)** | yes |
| `app/src/main/java/.../core/` | 8 | Engine, providers contract, Protocol (v3 field names), Binders, AtomicFile, Log, ListenerProxy, SystemEnvironment | app | AUTHORITATIVE | yes | yes | yes |
| `app/src/main/java/.../provider/` | 10 | Foreground (TaskResolver, ProcessTracker, PackageNames), Display, Power, Battery, Thermal, Audio, Kernel | app | AUTHORITATIVE | yes | yes | yes |
| `app/src/main/java/.../resolver/BinderResolver.kt` | 1 | Binder transaction code resolver | Flux `service.sh` → `binder_codes` → Flux NativeMonitor | AUTHORITATIVE (external contract) | yes | yes | yes |
| `app/src/main/AndroidManifest.xml`, `res/values/strings.xml` | 2 | APK manifest and resources (`applicationId com.febricahyaa.synthesiscore`, minSdk 28, targetSdk 37) | Gradle | AUTHORITATIVE | yes | packaged | no |
| `app/src/test/java/...` | 6 | Unit tests: MainKt, AtomicFile, Binders, Protocol, ProviderLogic, BinderResolver | `testDebugUnitTest`, `ci.yml`, `release.yml` | TEST | yes | no | yes |
| `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/.gitignore` | 3 | App build, R8 rules (only `MainKt.main` keeps its name), ABI splits | Gradle | AUTHORITATIVE | yes | shapes binary | no |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/gradle-daemon-jvm.properties` | 5 | Project build and version catalog | Gradle, Dependabot | AUTHORITATIVE / DEPENDENCY | yes | no | no |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | 4 | Gradle wrapper (jar is a pinned binary) | CI, developers | DEPENDENCY | yes | no | no |
| `.github/workflows/{ci,release,codeql,review,stale}.yml` | 5 | Test/build, signed release with checksum + certificate digest + provenance attestation, CodeQL, review, stale | GitHub Actions; Flux `sync_synthesiscore.yml` depends on release asset names | AUTHORITATIVE (CI + supply chain) | — | no | — |
| `.github/scripts/*` | 3 | Changelog, Telegram notify | workflows | AUTHORITATIVE | — | no | — |
| `.github/{CODEOWNERS,PULL_REQUEST_TEMPLATE.md,dependabot.yml,labels.yml}`, `ISSUE_TEMPLATE/*` | 7 | Repository governance | GitHub | AUTHORITATIVE | — | no | — |
| `README.md` | 1 | Protocol and field reference (the v3 contract), security, release verification | Flux and HiCo maintainers | AUTHORITATIVE | no | no | no |
| `LICENSE`, `.gitignore` | 2 | Legal, repository metadata | — | AUTHORITATIVE / DEPENDENCY | — | — | — |

## External contracts owned here

- Output file format `synthesis_version 3` (field names and meanings in `README.md`), read by
  Flux `SynthesisCoreReader`, the Flux WebUI monitor store and HiCo `FluxLink`.
- CLI flags `--resolve`, `--version`, `--once`, `--capabilities`, monitor mode argument order
  `<output> [lock]`.
- Release asset names `SynthesisCore-v<ver>.apk`, `.apk.sha256`, `.apk.cert.sha256`, the signing
  certificate (pinned in Flux `prebuilt/synthesiscore.cert.sha256`), and the provenance attestation.

## Unmerged work (not in this inventory's commit)

`claude/hico-thermal-game-9ree3l` (`5eb79f6`, +1/−1): canonical capability model, protocol 4,
`schema/capability_schema_v4.json`, 3 new test files. Flux `main` already mirrors that schema.
See Flux `docs/agent-state/BLOCKERS.md` B-03. Four `dependabot/*` branches are open dependency
updates.

## UNKNOWN entries

None at the audited commit.
