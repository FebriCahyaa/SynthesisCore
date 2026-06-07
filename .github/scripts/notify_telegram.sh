#!/usr/bin/env bash
# =============================================================================
# notify_telegram.sh — Send a Telegram notification from GitHub Actions
#
# Usage:
#   notify_telegram.sh <type> [extra args...]
#
# Types:
#   review   — Source-review / diff report
#   build    — Build result (success or failure)
#
# Required environment variables (set as GitHub Actions secrets):
#   TG_BOT_TOKEN   — Telegram bot token  (e.g. 123456:ABCdef...)
#   TG_CHANNEL_ID  — Target channel/chat  (e.g. @mychannel or -100123456789)
#   TG_DEV_ID      — Developer user ID for @mention in critical failures
#
# All other context is read from standard GITHUB_* environment variables
# that Actions provides automatically.
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

require_env() {
    local var="$1"
    if [[ -z "${!var:-}" ]]; then
        echo "ERROR: required environment variable \$$var is not set." >&2
        exit 1
    fi
}

send_message() {
    local text="$1"
    local response
    response=$(curl -fsSL \
        -X POST "https://api.telegram.org/bot${TG_BOT_TOKEN}/sendMessage" \
        -H "Content-Type: application/json" \
        -d "{
              \"chat_id\": \"${TG_CHANNEL_ID}\",
              \"text\": $(echo "$text" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'),
              \"parse_mode\": \"HTML\",
              \"disable_web_page_preview\": true
            }" 2>&1) || true

    if echo "$response" | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d.get('ok') else 1)" 2>/dev/null; then
        echo "Telegram notification sent successfully."
    else
        echo "WARNING: Telegram API returned an error. Response:" >&2
        echo "$response" >&2
        # Do not fail the CI step — notification is best-effort
    fi
}

send_document() {
    local caption="$1"
    local filepath="$2"
    local response
    response=$(curl -fsSL \
        -X POST "https://api.telegram.org/bot${TG_BOT_TOKEN}/sendDocument" \
        -F "chat_id=${TG_CHANNEL_ID}" \
        -F "caption=$caption" \
        -F "parse_mode=HTML" \
        -F "document=@${filepath}" 2>&1) || true

    if echo "$response" | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d.get('ok') else 1)" 2>/dev/null; then
        echo "Telegram document sent successfully."
    else
        echo "WARNING: Telegram sendDocument failed. Response:" >&2
        echo "$response" >&2
    fi
}

short_sha() {
    echo "${GITHUB_SHA:0:7}"
}

repo_url() {
    echo "https://github.com/${GITHUB_REPOSITORY}"
}

commit_url() {
    echo "$(repo_url)/commit/${GITHUB_SHA}"
}

run_url() {
    echo "$(repo_url)/actions/run/${GITHUB_RUN_ID}"
}

actor_link() {
    echo "<a href=\"https://github.com/${GITHUB_ACTOR}\">${GITHUB_ACTOR}</a>"
}

divider() {
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
}

# ---------------------------------------------------------------------------
# Sub-command: review
# ---------------------------------------------------------------------------
# Usage: notify_telegram.sh review <changed_files_count> <added_lines>
#                                   <removed_lines> <report_file>
#
# report_file: path to a plain-text diff summary (will be sent as document)
# ---------------------------------------------------------------------------
cmd_review() {
    local changed_files="$1"
    local added_lines="$2"
    local removed_lines="$3"
    local report_file="${4:-}"

    local branch="${GITHUB_REF_NAME:-unknown}"
    local sha
    sha=$(short_sha)
    local event="${GITHUB_EVENT_NAME:-push}"

    # Build commit message (first line only, JSON-safe)
    local commit_msg
    commit_msg=$(git log -1 --pretty=format:"%s" 2>/dev/null | head -c 200 || echo "(unknown)")

    local text
    text=$(cat <<EOF
🔍 <b>Source Review — SynthesisCore</b>
$(divider)

📦 <b>Repository:</b> <a href="$(repo_url)">${GITHUB_REPOSITORY}</a>
🌿 <b>Branch:</b> <code>${branch}</code>
🔖 <b>Commit:</b> <a href="$(commit_url)"><code>${sha}</code></a>
💬 <b>Message:</b> <i>${commit_msg}</i>
👤 <b>Author:</b> $(actor_link)
🎯 <b>Event:</b> <code>${event}</code>

$(divider)
📊 <b>Change Summary</b>

📁 Files changed : <b>${changed_files}</b>
➕ Lines added   : <b>+${added_lines}</b>
➖ Lines removed : <b>-${removed_lines}</b>

$(divider)
🔗 <a href="$(run_url)">View Full Workflow Run</a>
EOF
)

    send_message "$text"

    # If a report file was provided and exists, send it as a document attachment
    if [[ -n "$report_file" && -f "$report_file" ]]; then
        local caption="📋 <b>Full diff report</b> — <code>$(short_sha)</code>"
        send_document "$caption" "$report_file"
    fi
}

# ---------------------------------------------------------------------------
# Sub-command: build
# ---------------------------------------------------------------------------
# Usage: notify_telegram.sh build <success|failure> [artifact_url]
# ---------------------------------------------------------------------------
cmd_build() {
    local result="$1"            # "success" or "failure"
    local artifact_url="${2:-}"  # optional direct download URL

    local branch="${GITHUB_REF_NAME:-unknown}"
    local sha
    sha=$(short_sha)

    local commit_msg
    commit_msg=$(git log -1 --pretty=format:"%s" 2>/dev/null | head -c 200 || echo "(unknown)")

    local status_icon status_label
    if [[ "$result" == "success" ]]; then
        status_icon="✅"
        status_label="SUCCESS"
    else
        status_icon="❌"
        status_label="FAILURE"
    fi

    local artifact_line=""
    if [[ -n "$artifact_url" ]]; then
        artifact_line="
📥 <b>Artifact:</b> <a href=\"${artifact_url}\">Download APK</a>"
    fi

    # Ping developer on failure
    local dev_ping=""
    if [[ "$result" != "success" && -n "${TG_DEV_ID:-}" ]]; then
        dev_ping="
⚠️ <a href=\"tg://user?id=${TG_DEV_ID}\">Developer</a> — please check the build failure."
    fi

    local text
    text=$(cat <<EOF
${status_icon} <b>Build ${status_label} — SynthesisCore</b>
$(divider)

📦 <b>Repository:</b> <a href="$(repo_url)">${GITHUB_REPOSITORY}</a>
🌿 <b>Branch:</b> <code>${branch}</code>
🔖 <b>Commit:</b> <a href="$(commit_url)"><code>${sha}</code></a>
💬 <b>Message:</b> <i>${commit_msg}</i>
👤 <b>Author:</b> $(actor_link)
🔢 <b>Run #:</b> <code>${GITHUB_RUN_NUMBER}</code>${artifact_line}

$(divider)
🔗 <a href="$(run_url)">View Workflow Run</a>${dev_ping}
EOF
)

    send_message "$text"
}

# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

require_env TG_BOT_TOKEN
require_env TG_CHANNEL_ID
# TG_DEV_ID is optional — only used for failure pings

SUBCOMMAND="${1:-}"
shift || true

case "$SUBCOMMAND" in
    review) cmd_review "$@" ;;
    build)  cmd_build  "$@" ;;
    *)
        echo "ERROR: unknown sub-command '${SUBCOMMAND}'. Use: review | build" >&2
        exit 1
        ;;
esac
