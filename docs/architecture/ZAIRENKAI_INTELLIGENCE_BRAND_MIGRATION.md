# Zairenkai Intelligence Brand Migration (Phase 4.5C)

**Status:** IMPLEMENTED in source.
**CI:** not checked.
**Device:** NOT_TESTED.
**Release artifact:** not rebuilt.

## Identity

| Name | Role |
|---|---|
| **Zairenkai Intelligence** | Public identity of this component: the system-observation layer of Zairenkai. |
| **SynthesisCore** | Technical backend identity, preserved: package, entry point, assets, protocol. |
| **Aeyrin** | Internal codename only. It never appears in public surfaces; `brand_migration_test.sh` checks this. |

Architecture (unchanged):

```
Zairenkai → Zairenkai Intelligence
              ↳ SynthesisCore technical backend
              ↳ internal codename Aeyrin
```

There is no second backend, no Aeyrin daemon and no duplicated functionality. A legacy identifier
does not mean the implementation is obsolete. No legal or trademark claim is made.

## Migration inventory

| Old identifier | Kind | Consumers | Public / technical | Decision | Compatibility requirement | Risk if renamed |
|---|---|---|---|---|---|---|
| `com.febricahyaa.synthesiscore` (namespace, `applicationId`, Kotlin packages) | package identity | `app/build.gradle.kts`, all sources and tests, `proguard-rules.pro`, Zairenkai `module/service.sh` (`app_process … com.febricahyaa.synthesiscore.MainKt`), Zairenkai `scripts/flux_utility.sh` | technical | **Frozen** | Zairenkai launches the class by name | The daemon no longer starts |
| `MainKt` entry point and modes (`--resolve`, `--once`, `--capabilities`, `--version`) | CLI / API | Zairenkai `service.sh` (`--version`, `--resolve`), `MainKtTest` | technical | Frozen; help text gains one banner line | `--version` output unchanged (Zairenkai parses `tail -n 1`) | Binder resolution and version checks break |
| Output contract `synthesis_version 3` and field names | IPC / data contract | Zairenkai `SynthesisCoreReader`, Zairenkai WebUI monitor store, Synrei (HiCo) `FluxLink` | technical | **Frozen** | Field names and semantics | Foreground and screen detection lost |
| `ListenerProxy` `toString` "SynthesisCore.<callback>" | runtime string | Binder proxies | technical | Frozen (runtime behavior) | none | Behavior change |
| Release assets `SynthesisCore-<tag>.apk`, `.apk.sha256`, `.apk.cert.sha256`; attestation repo | update / sync | `release.yml`, Zairenkai `sync_synthesiscore.yml/.sh`, `prebuilt/synthesiscore.*` | technical | **Frozen** | Zairenkai sync expects these names | Supply-chain sync breaks |
| `synthesiscore-release` dispatch event, `<!-- synthesiscore:auto -->` marker | workflow contract | `release.yml` → Zairenkai sync workflow; release-notes editor | technical | Frozen | The Zairenkai workflow listens for this event | No auto-sync |
| Signing certificate / pin | security | release signing secret; pinned in Zairenkai `prebuilt/synthesiscore.cert.sha256` | technical | **Frozen** | Same certificate digest | Install aborts on pin mismatch |
| `rootProject.name "Synthesis Core"` | build / module identifier | Gradle | technical | Frozen | Build identity | Build or cache differences |
| `app_name` "Synthesis Core" | APK label | APK resources (never installed; loaded by `app_process`) | public | **Rebranded** to Zairenkai Intelligence | Visible only after the next APK build | None (not installed) |
| README title and identity | docs | humans; the README is also the protocol reference | public | Title rebranded and identity section added; protocol text unchanged | Protocol reference intact | None |
| Usage help | CLI text | stderr help | public | Adds the banner "Zairenkai Intelligence (SynthesisCore backend)" | Usage lines unchanged | None |
| Release title "SynthesisCore $TAG" | release metadata | GitHub release | public | **Rebranded** | Asset names unchanged | None |
| Issue-template descriptions and labels | public | GitHub | public | Rebranded with "(SynthesisCore)"; field `id` unchanged | `id: synthesiscore_version` | None |
| CI names, telegram project, stale message, `labels.yml` | CI | GitHub Actions | technical / unrelated | Unchanged | none | n/a |
| `docs/architecture/*` (inventory, deletion manifest) | history | humans | historical | Unchanged | Preserve | n/a |

All remaining references are classified in `docs/architecture/synthesiscore_identifiers.tsv`.

## Changed files

- `README.md`: title "Zairenkai Intelligence" and an identity section. The protocol content is unchanged.
- `app/src/main/res/values/strings.xml`: `app_name` "Zairenkai Intelligence".
- `app/src/main/java/com/febricahyaa/synthesiscore/MainKt.kt`: one banner line in the usage text.
- `.github/workflows/release.yml`: release title only.
- `.github/ISSUE_TEMPLATE/bug_report.yml`, `feature_request.yml`: description and label text.
- `.github/workflows/ci.yml`: one added step that runs `tests/brand_migration_test.sh`.
- New: this document, `synthesiscore_identifiers.tsv`, `tests/brand_migration_test.sh`.

## Release-artifact requirement

The APK label and usage banner reach devices only after the existing release process rebuilds and
signs a new APK (`release.yml`, same certificate). Zairenkai's sync then updates
`prebuilt/synthesiscore.*`. Nothing was rebuilt in this phase. The tracked Zairenkai prebuilt APK
is unchanged and still carries the old label, which is invisible because the APK is never installed.

## Configuration

SynthesisCore has no config directory of its own. Output, lock and log paths are given by
Zairenkai (`/data/adb/.config/flux/…`). Nothing was moved; any future Zairenkai location is a
Zairenkai-side migration.
