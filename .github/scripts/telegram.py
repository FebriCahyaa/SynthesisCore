#!/usr/bin/env python3
"""
telegram.py — Telegram notifications for SynthesisCore CI.

Usage:
  telegram.py review <changed_files> <added_lines> <removed_lines> [diff_report]
  telegram.py release <success|failure|cancelled>

Environment:
  TG_BOT_TOKEN, TG_CHANNEL_ID   required
  TG_DEV_ID                     optional, pinged on failures
  GITHUB_*                      provided by GitHub Actions
  release only: TAG, APK_PATH, APK_SHA256, CERT_SHA256, NOTES_FILE, PRERELEASE

Messages use Telegram HTML with an expandable quote for long content and inline
buttons for links. Sending is best effort: errors are logged, never fatal.
"""

import html
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

PROJECT = "SynthesisCore"
TAGLINE = "Event-driven Android system monitor"
MESSAGE_LIMIT = 4096
CAPTION_LIMIT = 1024

ENV = os.environ
REPO = ENV.get("GITHUB_REPOSITORY", "FebriCahyaa/SynthesisCore")
SERVER = ENV.get("GITHUB_SERVER_URL", "https://github.com")
REPO_URL = f"{SERVER}/{REPO}"
SHA = ENV.get("GITHUB_SHA", "")
RUN_URL = f"{REPO_URL}/actions/runs/{ENV.get('GITHUB_RUN_ID', '')}"
ACTOR = ENV.get("GITHUB_ACTOR", "unknown")


# ── helpers ──────────────────────────────────────────────────────────────────

def esc(text) -> str:
    return html.escape(str(text), quote=False)


def link(url: str, label: str) -> str:
    return f'<a href="{html.escape(url, quote=True)}">{esc(label)}</a>'


def code(text) -> str:
    return f"<code>{esc(text)}</code>"


def tree(rows) -> str:
    """Render (label, value_html) rows as a ├/└ tree."""
    rows = [r for r in rows if r is not None]
    out = []
    for i, (label, value) in enumerate(rows):
        branch = "└" if i == len(rows) - 1 else "├"
        out.append(f"{branch} {esc(label)}: {value}")
    return "\n".join(out)


def git(*args) -> str:
    try:
        return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def human_size(num_bytes: int) -> str:
    size = float(num_bytes)
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return f"{num_bytes} B"


def now_utc() -> str:
    return datetime.now(timezone.utc).strftime("%d %b %Y · %H:%M UTC")


def hashtag(text: str) -> str:
    return "#" + re.sub(r"[^0-9A-Za-z_]", "_", text)


def protocol_version() -> str:
    source = Path("app/src/main/java/com/febricahyaa/synthesiscore/core/Protocol.kt")
    try:
        match = re.search(r"const val VERSION = (\d+)", source.read_text())
        return match.group(1) if match else "?"
    except OSError:
        return "?"


def dev_ping() -> str:
    dev = ENV.get("TG_DEV_ID", "").strip()
    return f'\n\n⚠️ <a href="tg://user?id={esc(dev)}">Developer</a>, please take a look.' if dev.isdigit() else ""


def fit(text: str, limit: int) -> str:
    """Hard safety net; callers already budget their content."""
    return text if len(text) <= limit else text[: limit - 1] + "…"


# ── Telegram API ─────────────────────────────────────────────────────────────

def api(method: str, fields: dict, file_field=None):
    if ENV.get("TG_DRY_RUN"):
        # Preview mode: print the payload instead of calling Telegram.
        print(f"--- {method}{' + ' + str(file_field[1]) if file_field else ''}")
        print(fields.get("text") or fields.get("caption"))
        if "reply_markup" in fields:
            print("buttons:", [[b["text"] for b in row] for row in fields["reply_markup"]["inline_keyboard"]])
        return {"message_id": 1}

    token = ENV["TG_BOT_TOKEN"]
    url = f"https://api.telegram.org/bot{token}/{method}"
    if file_field is None:
        body = json.dumps(fields).encode()
        headers = {"Content-Type": "application/json"}
    else:
        name, path = file_field
        boundary = uuid.uuid4().hex
        parts = []
        for key, value in fields.items():
            value = json.dumps(value) if isinstance(value, (dict, list)) else str(value)
            parts.append(
                f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode()
            )
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{Path(path).name}"\r\n'
            f"Content-Type: application/octet-stream\r\n\r\n".encode()
            + Path(path).read_bytes()
            + b"\r\n"
        )
        parts.append(f"--{boundary}--\r\n".encode())
        body = b"".join(parts)
        headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}

    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        result = {"ok": False, "description": error.read().decode(errors="replace")}
    except (urllib.error.URLError, OSError) as error:
        result = {"ok": False, "description": str(error)}

    if result.get("ok"):
        print(f"Telegram {method}: sent")
        return result.get("result", {})
    print(f"WARNING: Telegram {method} failed: {result.get('description')}", file=sys.stderr)
    return None


def buttons(*rows):
    """rows of (label, url) tuples -> inline keyboard."""
    return {"inline_keyboard": [[{"text": t, "url": u} for t, u in row if u] for row in rows]}


def send_message(text: str, keyboard=None, reply_to=None):
    fields = {
        "chat_id": ENV["TG_CHANNEL_ID"],
        "text": fit(text, MESSAGE_LIMIT),
        "parse_mode": "HTML",
        "link_preview_options": {"is_disabled": True},
    }
    if keyboard:
        fields["reply_markup"] = keyboard
    if reply_to:
        fields["reply_parameters"] = {"message_id": reply_to, "allow_sending_without_reply": True}
    return api("sendMessage", fields)


def send_document(path: str, caption: str, reply_to=None):
    fields = {"chat_id": ENV["TG_CHANNEL_ID"], "caption": fit(caption, CAPTION_LIMIT), "parse_mode": "HTML"}
    if reply_to:
        fields["reply_parameters"] = json.dumps({"message_id": reply_to, "allow_sending_without_reply": True})
    return api("sendDocument", fields, ("document", path))


# ── changelog (Markdown from changelog.sh -> Telegram HTML) ──────────────────

# The trailing "(ref)" is only the commit reference, so descriptions may contain parentheses.
ITEM = re.compile(
    r"^- (?:\*\*(?P<scope>[^*]+):\*\* )?(?P<desc>.*?)"
    r"(?: \((?P<ref>\[`[0-9a-f]+`\]\([^)\s]+\)|`[0-9a-f]+`)\))?$"
)
REF = re.compile(r"\[`(?P<sha>[0-9a-f]+)`\]\((?P<url>[^)]+)\)|`(?P<plain>[0-9a-f]+)`")


def parse_changelog(markdown: str):
    """Returns [(section_title, [item_html, ...]), ...]."""
    sections, current = [], None
    for line in markdown.splitlines():
        if line.startswith("### "):
            current = (line[4:].strip(), [])
            sections.append(current)
        elif line.startswith("- ") and current is not None:
            match = ITEM.match(line)
            if not match:
                continue
            item = ""
            if match["scope"]:
                item += f"<b>{esc(match['scope'])}:</b> "
            item += esc(match["desc"].replace("`", ""))
            ref = REF.search(match["ref"] or "")
            if ref and ref["sha"]:
                item += " " + link(ref["url"], ref["sha"])
            elif ref and ref["plain"]:
                item += " " + code(ref["plain"])
            current[1].append(item)
    return sections


def render_changelog(sections, budget: int) -> str:
    lines, used, shown, total = [], 0, 0, sum(len(items) for _, items in sections)
    for title, items in sections:
        header = f"<b>{esc(title)}</b>"
        if used + len(header) > budget:
            break
        lines.append(("\n" if lines else "") + header)
        used += len(header) + 1
        for item in items:
            entry = f"• {item}"
            if used + len(entry) > budget:
                break
            lines.append(entry)
            used += len(entry) + 1
            shown += 1
    if shown < total:
        lines.append(f"\n<i>…and {total - shown} more in the release notes</i>")
    return "\n".join(lines)


def section_counts(sections) -> str:
    return " · ".join(f"{title.split(' ', 1)[0]} {len(items)}" for title, items in sections if items)


# ── commands ─────────────────────────────────────────────────────────────────

def cmd_release(status: str):
    tag = ENV.get("TAG", "v?")
    version = tag.lstrip("v")
    prerelease = ENV.get("PRERELEASE", "false").lower() == "true"
    release_url = f"{REPO_URL}/releases/tag/{tag}"
    tags = f"{hashtag(PROJECT)} #release {hashtag(tag)}"

    if status != "success":
        icon, word = ("⏹", "cancelled") if status == "cancelled" else ("❌", "failed")
        text = (
            f"{icon} <b>{PROJECT} {esc(tag)}</b>: release {word}\n"
            f"<i>{esc(TAGLINE)}</i>\n\n"
            f"🧾 <b>Details</b>\n"
            + tree([
                ("Commit", link(f"{REPO_URL}/commit/{SHA}", SHA[:7])),
                ("Triggered by", link(f"{SERVER}/{ACTOR}", ACTOR)),
                ("Time", esc(now_utc())),
            ])
            + f"\n\nNothing was published. Check the workflow log for the failing step."
            + dev_ping()
            + f"\n\n{tags} #failed"
        )
        send_message(text, buttons([("⚙️ Workflow run", RUN_URL)]))
        return

    apk = Path(ENV.get("APK_PATH", ""))
    apk_sha = ENV.get("APK_SHA256", "")
    cert = ENV.get("CERT_SHA256", "")
    notes = Path(ENV.get("NOTES_FILE", ""))
    sections = parse_changelog(notes.read_text()) if notes.is_file() else []
    build = git("rev-list", "--count", "HEAD") or "?"
    previous = git("describe", "--tags", "--abbrev=0", f"{tag}^") if tag != "v?" else ""

    head = (
        f"🚀 <b>{PROJECT} {esc(tag)}</b> is out{' (pre-release)' if prerelease else ''}\n"
        f"<i>{esc(TAGLINE)}</i>\n\n"
        f"📦 <b>Release</b>\n"
        + tree([
            ("Version", f"{code(version)} · build {code(build)}"),
            ("Channel", "🧪 Pre-release" if prerelease else "✅ Stable"),
            ("Android", "9 – 17 (API 28 – 37)"),
            ("Protocol", code(f"v{protocol_version()}")),
            ("Previous", code(previous)) if previous else None,
            ("Published", esc(now_utc())),
        ])
        + "\n\n🔐 <b>Integrity</b>\n"
        + tree([
            ("APK", f"{code(apk.name)} · {human_size(apk.stat().st_size)}" if apk.is_file() else code(apk.name)),
            ("SHA-256", code(apk_sha)),
            ("Signer", code(cert)),
            ("Provenance", "Sigstore build attestation"),
        ])
    )
    tail = (
        "\n\n🛡 <b>Verify</b>\n"
        f"<pre>sha256sum -c {esc(apk.name)}.sha256\n"
        f"gh attestation verify {esc(apk.name)} --repo {esc(REPO)}</pre>"
        f"\n\n{tags}"
    )

    counts = section_counts(sections)
    changes_header = f"\n\n📝 <b>What's changed</b>{'  ' + esc(counts) if counts else ''}\n"
    budget = MESSAGE_LIMIT - len(head) - len(tail) - len(changes_header) - 80
    body = render_changelog(sections, budget) if sections else "<i>No changes listed.</i>"
    text = head + changes_header + f"<blockquote expandable>{body}</blockquote>" + tail

    message = send_message(text, buttons(
        [("📥 Download APK", f"{REPO_URL}/releases/download/{tag}/{apk.name}"), ("📝 Release notes", release_url)],
        [("🔀 Compare", f"{REPO_URL}/compare/{previous}...{tag}" if previous else None), ("⚙️ Workflow run", RUN_URL)],
    ))

    if apk.is_file():
        caption = (
            f"📦 <b>{PROJECT} {esc(tag)}</b>\n"
            f"SHA-256: {code(apk_sha)}\n"
            f"<i>Runs via app_process; verify before use.</i>"
        )
        send_document(str(apk), caption, reply_to=(message or {}).get("message_id"))


def cmd_review(changed_files: str, added: str, removed: str, report: str = ""):
    branch = ENV.get("GITHUB_REF_NAME", "unknown")
    event = ENV.get("GITHUB_EVENT_NAME", "push")
    subject = git("log", "-1", "--format=%s") or "(unknown)"
    # Drop git trailers (Co-Authored-By, Signed-off-by, ...): noise in a channel post.
    body = "\n".join(
        line for line in git("log", "-1", "--format=%b").splitlines()
        if not re.match(r"^[A-Za-z][\w-]*: \S", line)
    ).strip()
    author = git("log", "-1", "--format=%an") or ACTOR

    try:
        plus, minus = int(added or 0), int(removed or 0)
    except ValueError:
        plus = minus = 0
    total = plus + minus
    filled = round(10 * plus / total) if total else 0
    bar = "🟩" * filled + "🟥" * (10 - filled) if total else "⬜" * 10

    top_files = ""
    if report and Path(report).is_file():
        stat_lines = [l for l in Path(report).read_text().splitlines() if "|" in l][:8]
        if stat_lines:
            top_files = "\n\n📁 <b>Files</b>\n<blockquote expandable>" + esc("\n".join(
                l.strip() for l in stat_lines)) + "</blockquote>"

    message_block = esc(subject[:200])
    if body:
        message_block += "\n\n" + esc(body[:600])

    text = (
        f"🔍 <b>{PROJECT}</b>: new {esc(event.replace('_', ' '))}\n"
        f"<i>{esc(TAGLINE)}</i>\n\n"
        f"🧾 <b>Commit</b>\n"
        + tree([
            ("Branch", code(branch)),
            ("Commit", link(f"{REPO_URL}/commit/{SHA}", SHA[:7])),
            ("Author", link(f"{SERVER}/{ACTOR}", author)),
            ("Time", esc(now_utc())),
        ])
        + f"\n\n💬 <b>Message</b>\n<blockquote expandable>{message_block}</blockquote>\n\n"
        f"📊 <b>Changes</b>\n"
        + tree([
            ("Files", f"<b>{esc(changed_files)}</b>"),
            ("Lines", f"<b>+{plus}</b> / <b>−{minus}</b>"),
            ("Balance", bar),
        ])
        + top_files
        + f"\n\n{hashtag(PROJECT)} #review {hashtag(branch)}"
    )
    send_message(text, buttons([("🔖 Commit", f"{REPO_URL}/commit/{SHA}"), ("⚙️ Workflow run", RUN_URL)]))


def main(argv):
    for var in ("TG_BOT_TOKEN", "TG_CHANNEL_ID"):
        if not ENV.get(var):
            print(f"{var} is not set, skipping Telegram notification")
            return 0
    if len(argv) >= 2 and argv[1] == "release":
        cmd_release(argv[2] if len(argv) > 2 else "failure")
    elif len(argv) >= 5 and argv[1] == "review":
        cmd_review(*argv[2:6])
    else:
        print(__doc__, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
