#!/usr/bin/env bash
# =============================================================================
# changelog.sh — Generate a Markdown changelog from Conventional Commits
#
# Usage:
#   changelog.sh [target-ref] [previous-ref]
#
#   target-ref    Tag or commit the changelog ends at (default: HEAD)
#   previous-ref  Where it starts (default: the tag before target-ref, or the
#                 first commit when there is no earlier tag)
#
# Commits are grouped by their Conventional Commit type
# (`type(scope)!: description`). `!` or a "BREAKING CHANGE" footer lists the
# commit under Breaking Changes as well. Merge commits are skipped.
#
# Links point at $GITHUB_SERVER_URL/$GITHUB_REPOSITORY when those are set
# (always the case in GitHub Actions).
# =============================================================================

set -euo pipefail

target="${1:-HEAD}"
previous="${2:-$(git describe --tags --abbrev=0 "${target}^" 2>/dev/null || true)}"
range="${previous:+${previous}..}${target}"

repo_url=""
if [[ -n "${GITHUB_SERVER_URL:-}" && -n "${GITHUB_REPOSITORY:-}" ]]; then
    repo_url="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}"
fi

declare -A titles=(
    [breaking]="🚨 Breaking Changes"
    [feat]="✨ Features"
    [fix]="🐛 Bug Fixes"
    [perf]="⚡ Performance"
    [refactor]="♻️ Refactoring"
    [docs]="📚 Documentation"
    [test]="🧪 Tests"
    [build]="📦 Build & Dependencies"
    [ci]="🔧 CI"
    [chore]="🧹 Maintenance"
    [other]="📝 Other Changes"
)
order=(breaking feat fix perf refactor docs test build ci chore other)
declare -A sections=()

conventional='^([a-zA-Z]+)(\(([^)]+)\))?(!)?: (.+)$'

# One record per commit: hash, subject and body separated by US, records by RS.
while IFS=$'\x1f' read -r -d $'\x1e' hash subject body; do
    hash="${hash//$'\n'/}"
    [[ -z "$hash" ]] && continue

    type="other" scope="" breaking=0 description="$subject"
    if [[ "$subject" =~ $conventional ]]; then
        type="${BASH_REMATCH[1],,}"
        scope="${BASH_REMATCH[3]}"
        [[ -n "${BASH_REMATCH[4]}" ]] && breaking=1
        description="${BASH_REMATCH[5]}"
    elif [[ "$subject" =~ ^[Bb]ump\  ]]; then
        type="build" # Dependabot
    fi
    [[ "$body" == *"BREAKING CHANGE"* ]] && breaking=1
    [[ -z "${titles[$type]:-}" ]] && type="other"

    short="${hash:0:7}"
    ref="\`${short}\`"
    [[ -n "$repo_url" ]] && ref="[\`${short}\`](${repo_url}/commit/${hash})"

    line="- ${scope:+**${scope}:** }${description} (${ref})"
    sections[$type]+="${line}"$'\n'
    [[ $breaking -eq 1 ]] && sections[breaking]+="${line}"$'\n'
done < <(git log --no-merges --format='%H%x1f%s%x1f%b%x1e' "$range")

printed=0
for type in "${order[@]}"; do
    [[ -z "${sections[$type]:-}" ]] && continue
    printf '### %s\n\n%s\n' "${titles[$type]}" "${sections[$type]}"
    printed=1
done
[[ $printed -eq 0 ]] && printf 'No changes since %s.\n\n' "${previous:-the first commit}"

if [[ -n "$repo_url" && -n "$previous" ]]; then
    printf '**Full Changelog**: %s/compare/%s...%s\n' "$repo_url" "$previous" "$target"
fi
