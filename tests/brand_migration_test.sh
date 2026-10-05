#!/bin/sh
# Phase 4.5C (SynthesisCore -> Zairenkai Intelligence): public labels rebranded; package, signing,
# build, update/sync and protocol identifiers frozen; runtime code unchanged apart from the help
# banner; every remaining SynthesisCore reference classified. Read-only and rerunnable.
root=${1:-.}
fail=0
bad() { echo "FAIL: $*"; fail=1; }
has() { grep -qF -- "$2" "$root/$1" || bad "$1 lacks: $2"; }
hasnt() { if grep -qF -- "$2" "$root/$1"; then bad "$1 has: $2"; fi; }

# 1, 18. public name
has app/src/main/res/values/strings.xml '<string name="app_name">Zairenkai Intelligence</string>'
has README.md '# Zairenkai Intelligence'
has app/src/main/java/com/febricahyaa/synthesiscore/MainKt.kt 'Zairenkai Intelligence (SynthesisCore backend)'
has .github/workflows/release.yml '--title "Zairenkai Intelligence $TAG"'
# 2. Aeyrin stays internal: never in public surfaces.
for f in README.md app/src/main/res/values/strings.xml app/src/main/java/com/febricahyaa/synthesiscore/MainKt.kt .github/workflows/release.yml app/build.gradle.kts settings.gradle.kts; do
	hasnt "$f" Aeyrin
done
# 3, 4, 17. package and application identity
has app/build.gradle.kts 'namespace = "com.febricahyaa.synthesiscore"'
has app/build.gradle.kts 'applicationId = "com.febricahyaa.synthesiscore"'
has app/proguard-rules.pro '-keep class com.febricahyaa.synthesiscore.MainKt {'
has app/src/main/java/com/febricahyaa/synthesiscore/MainKt.kt 'package com.febricahyaa.synthesiscore'
# 6. build module identifiers
has settings.gradle.kts 'rootProject.name = "Synthesis Core"'
# 8. update / sync identifiers
has .github/workflows/release.yml 'echo "APK_NAME=SynthesisCore-${TAG}.apk" >>"$GITHUB_ENV"'
has .github/workflows/release.yml '-f event_type=synthesiscore-release'
has .github/workflows/release.yml '<!-- synthesiscore:auto -->'
# 15, 16. no second backend or daemon
[ "$(cd "$root" && git ls-files 'app/src/main/java/*' | grep -ciE 'aeyrin|zairenkai')" = 0 ] || bad "new backend/daemon source"
[ "$(grep -c 'fun main' "$root/app/src/main/java/com/febricahyaa/synthesiscore/MainKt.kt")" = 1 ] || bad "entry point count"

# 5, 7, 9-14, 20. everything except the label files is unchanged since the pre-migration base.
base=7b6e1cd
if git -C "$root" cat-file -e "$base^{commit}" 2>/dev/null; then
	others=$(cd "$root" && git ls-files | grep -vxE 'README.md|app/src/main/res/values/strings.xml|app/src/main/java/com/febricahyaa/synthesiscore/MainKt.kt|\.github/workflows/release.yml|\.github/workflows/ci.yml|\.github/ISSUE_TEMPLATE/(bug_report|feature_request).yml|docs/architecture/ZAIRENKAI_INTELLIGENCE_BRAND_MIGRATION.md|docs/architecture/synthesiscore_identifiers.tsv|tests/brand_migration_test.sh')
	# shellcheck disable=SC2086
	git -C "$root" diff --quiet "$base" -- $others || bad "files outside the label set changed since $base"
	[ -z "$(git -C "$root" diff --name-only --diff-filter=DR "$base")" ] || bad "files deleted or renamed"
	# Runtime code: only the usage banner line was added.
	code=$(git -C "$root" diff -U0 "$base" -- app/src/main/java | grep -E '^[-+][^-+]')
	[ "$code" = "+            Zairenkai Intelligence (SynthesisCore backend)" ] || bad "unexpected runtime code change: $code"
	ci=$(git -C "$root" diff -U0 "$base" -- .github/workflows/ci.yml | grep -E '^[-+][^-+]' | grep -v 'brand_migration_test\|Brand migration')
	[ -z "$ci" ] || bad "unexpected CI change: $ci"
else
	echo "note: base $base not available (shallow clone); history checks skipped"
fi

# 21. every remaining reference classified
reg="$root/docs/architecture/synthesiscore_identifiers.tsv"
files=$(cd "$root" && { git grep -lIiE 'synthesis ?core'; git ls-files | grep -i 'synthesiscore'; } | sort -u)
for f in $files; do
	best=""
	while IFS='	' read -r prefix class reason; do
		case "$prefix" in ''|'#'*) continue ;; esac
		case "$f" in "$prefix"*) [ ${#prefix} -gt ${#best} ] && best=$prefix ;; esac
	done <"$reg"
	[ -n "$best" ] || bad "unclassified: $f"
done
badclass=$(grep -v '^#' "$reg" | awk -F'\t' 'NF{print $2}' | grep -vxE 'technical compatibility|package identity|historical documentation|legal attribution|test fixture|migration documentation|generated artifact|build/module identifier|intentional legacy reference')
[ -z "$badclass" ] || bad "invalid class: $badclass"

[ $fail -eq 0 ] && echo "brand_migration_test: passed"
exit $fail
