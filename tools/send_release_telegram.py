#!/usr/bin/env python3
"""
send_release_telegram.py
Automates sending KRON release APK and patch notes to Telegram group topic.

Usage:
    python tools/send_release_telegram.py
    python tools/send_release_telegram.py --dry-run
    python tools/send_release_telegram.py --apk releases/1.7.27/KRON-1.7.27.apk
    python tools/send_release_telegram.py --no-patch-notes
"""

import argparse
import hashlib
import html
import mimetypes
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

# Fix Windows console unicode printing
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def load_env(env_path: Path) -> dict:
    """Parse a simple .env file without external dependencies."""
    config = {}
    if not env_path.is_file():
        return config

    with open(env_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                k, v = line.split("=", 1)
                config[k.strip()] = v.strip().strip("'\"")
    return config


def compute_sha256(file_path: Path) -> str:
    """Compute SHA-256 hash of a file."""
    hasher = hashlib.sha256()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def find_latest_release(repo_root: Path) -> tuple[Path, str]:
    """Find the latest release APK and version in releases/ or app/build/outputs/apk/release/."""
    releases_dir = repo_root / "releases"
    if releases_dir.is_dir():
        # Sort subdirectories by version components
        version_dirs = []
        for d in releases_dir.iterdir():
            if d.is_dir():
                m = re.match(r"^(\d+)\.(\d+)\.(\d+)$", d.name)
                if m:
                    version_dirs.append(((int(m.group(1)), int(m.group(2)), int(m.group(3))), d))

        if version_dirs:
            version_dirs.sort(key=lambda x: x[0], reverse=True)
            for _, vdir in version_dirs:
                apks = list(vdir.glob("*.apk"))
                if apks:
                    return apks[0], vdir.name

    # Fallback to build output directory
    build_dir = repo_root / "app" / "build" / "outputs" / "apk" / "release"
    if build_dir.is_dir():
        apks = sorted(build_dir.glob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
        if apks:
            m = re.search(r"(\d+\.\d+\.\d+)", apks[0].name)
            ver = m.group(1) if m else "latest"
            return apks[0], ver

    raise FileNotFoundError("Tidak ditemukan file APK rilis di releases/ atau app/build/outputs/apk/release/")


def extract_changelog(repo_root: Path, version: str) -> list[str]:
    """Extract changelog bullet points for the given version from CHANGELOG.md."""
    changelog_path = repo_root / "CHANGELOG.md"
    if not changelog_path.is_file():
        return []

    lines = []
    in_target_section = False
    with open(changelog_path, "r", encoding="utf-8") as f:
        for line in f:
            line_str = line.strip()
            if line_str.startswith("## "):
                # e.g. ## 1.7.27 - 2026-10-16
                m = re.match(r"^##\s+(\d+\.\d+\.\d+)", line_str)
                if m:
                    current_ver = m.group(1)
                    if current_ver == version:
                        in_target_section = True
                        continue
                    elif in_target_section:
                        # Reached next version section
                        break
            elif in_target_section:
                if line_str.startswith("- ") or line_str.startswith("* "):
                    bullet = line_str[2:].strip()
                    if bullet and not bullet.lower().startswith("bump version"):
                        lines.append(bullet)

    return lines


def build_caption(version: str, apk_path: Path, sha256_hash: str) -> str:
    """Build a concise HTML-formatted caption for the APK document."""
    size_mb = apk_path.stat().st_size / (1024 * 1024)
    caption = (
        f"🚀 <b>KRON v{html.escape(version)} Release</b>\n\n"
        f"📦 <b>File:</b> <code>{html.escape(apk_path.name)}</code> ({size_mb:.2f} MB)\n"
        f"🔒 <b>SHA-256:</b> <code>{sha256_hash}</code>\n"
    )
    return caption.strip()


def build_patch_notes_messages(version: str, changelog_items: list[str], custom_notes: str | None = None) -> list[str]:
    """Build full patch notes messages, splitting into chunks if necessary (max 4000 chars per message)."""
    if custom_notes:
        raw_text = custom_notes.strip()
        body = html.escape(raw_text)
    elif changelog_items:
        bullet_lines = [f"• {html.escape(item)}" for item in changelog_items]
        body = "\n".join(bullet_lines)
    else:
        body = "<i>Pembaruan performa dan stabilitas aplikasi.</i>"

    header = f"📋 <b>Patch Notes KRON v{html.escape(version)}:</b>\n\n"
    max_chunk_len = 3800

    # If within limit, return single message
    full_message = header + body
    if len(full_message) <= max_chunk_len:
        return [full_message]

    # Split body into chunks
    chunks = []
    current_chunk = header
    paragraphs = body.split("\n\n")

    for p in paragraphs:
        if len(current_chunk) + len(p) + 2 > max_chunk_len:
            chunks.append(current_chunk.strip())
            current_chunk = f"📋 <b>Patch Notes v{html.escape(version)} (lanjutan):</b>\n\n{p}\n\n"
        else:
            current_chunk += p + "\n\n"

    if current_chunk.strip():
        chunks.append(current_chunk.strip())

    return chunks


def send_telegram_document(
    bot_token: str,
    chat_id: str,
    topic_id: str,
    file_path: Path,
    caption: str,
    dry_run: bool = False
) -> dict:
    """Send a document to a Telegram group topic using standard library urllib."""
    url = f"https://api.telegram.org/bot{bot_token}/sendDocument"

    if dry_run:
        print("[DRY-RUN] URL:", url.replace(bot_token, "BOT_TOKEN_HIDDEN"))
        print(f"[DRY-RUN] chat_id: {chat_id}, message_thread_id: {topic_id}")
        print(f"[DRY-RUN] file: {file_path} ({file_path.stat().st_size} bytes)")
        print("[DRY-RUN] caption:\n" + caption)
        return {"ok": True, "result": {"message_id": 999999}, "dry_run": True}

    boundary = f"----WebKitFormBoundary{uuid.uuid4().hex}"
    crlf = b"\r\n"

    # Read file content
    with open(file_path, "rb") as f:
        file_bytes = f.read()

    mime_type, _ = mimetypes.guess_type(str(file_path))
    if not mime_type:
        mime_type = "application/vnd.android.package-archive"

    # Build multipart body
    parts = []

    fields = {
        "chat_id": str(chat_id),
        "caption": caption,
        "parse_mode": "HTML"
    }
    if topic_id:
        fields["message_thread_id"] = str(topic_id)

    for name, value in fields.items():
        parts.append(f"--{boundary}".encode("utf-8"))
        parts.append(f'Content-Disposition: form-data; name="{name}"'.encode("utf-8"))
        parts.append(b"")
        parts.append(value.encode("utf-8"))

    # File field
    parts.append(f"--{boundary}".encode("utf-8"))
    parts.append(
        f'Content-Disposition: form-data; name="document"; filename="{file_path.name}"'.encode("utf-8")
    )
    parts.append(f"Content-Type: {mime_type}".encode("utf-8"))
    parts.append(b"")
    parts.append(file_bytes)

    # Closing boundary
    parts.append(f"--{boundary}--".encode("utf-8"))
    parts.append(b"")

    body = crlf.join(parts)

    req = urllib.request.Request(url, data=body)
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Content-Length", str(len(body)))

    print(f"Mengirim {file_path.name} ({len(file_bytes) / (1024*1024):.2f} MB) ke Telegram...")
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            import json
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        error_msg = e.read().decode("utf-8", errors="replace")
        print(f"Error HTTP {e.code}: {error_msg}", file=sys.stderr)
        raise
    except Exception as e:
        print(f"Error saat mengirim: {e}", file=sys.stderr)
        raise


def send_telegram_message(
    bot_token: str,
    chat_id: str,
    topic_id: str,
    text: str,
    reply_to_message_id: int | None = None,
    dry_run: bool = False
) -> dict:
    """Send a text message (patch notes) to a Telegram group topic."""
    url = f"https://api.telegram.org/bot{bot_token}/sendMessage"

    if dry_run:
        print(f"[DRY-RUN] Send Message to chat_id={chat_id}, topic={topic_id}, reply_to={reply_to_message_id}:")
        print(text)
        return {"ok": True, "dry_run": True}

    params = {
        "chat_id": str(chat_id),
        "text": text,
        "parse_mode": "HTML"
    }
    if topic_id:
        params["message_thread_id"] = str(topic_id)
    if reply_to_message_id:
        params["reply_to_message_id"] = str(reply_to_message_id)

    data = urllib.parse.urlencode(params).encode("utf-8")
    req = urllib.request.Request(url, data=data)
    req.add_header("Content-Type", "application/x-www-form-urlencoded")

    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            import json
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        error_msg = e.read().decode("utf-8", errors="replace")
        print(f"Error HTTP saat kirim patch notes {e.code}: {error_msg}", file=sys.stderr)
        raise
    except Exception as e:
        print(f"Error saat kirim patch notes: {e}", file=sys.stderr)
        raise


def main():
    parser = argparse.ArgumentParser(description="Kirim APK rilis dan Patch Notes KRON ke topik Telegram.")
    parser.add_argument("--apk", type=str, help="Path ke file APK rilis (opsional, default: deteksi versi terbaru)")
    parser.add_argument("--publish", action="store_true", help="Kirim rilis ke target publik (Federasi INF - topic 6151)")
    parser.add_argument("--notes-file", type=str, help="Path ke file catatan rilis khusus (misal release-notes.md)")
    parser.add_argument("--no-patch-notes", action="store_true", help="Kirim APK saja tanpa pesan catatan rilis")
    parser.add_argument("--token", type=str, help="Telegram Bot Token (opsional, default dari .env atau env var)")
    parser.add_argument("--chat-id", type=str, help="Telegram Chat ID (opsional, default dari .env atau env var)")
    parser.add_argument("--topic-id", type=str, help="Telegram Topic ID / Thread ID (opsional, default dari .env)")
    parser.add_argument("--dry-run", action="store_true", help="Simulasi pengiriman tanpa mengunggah ke Telegram")

    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent

    # Load configuration
    env_config = load_env(repo_root / ".env")

    bot_token = args.token or os.environ.get("TELEGRAM_BOT_TOKEN") or env_config.get("TELEGRAM_BOT_TOKEN")

    if args.publish:
        chat_id = args.chat_id or os.environ.get("TELEGRAM_PUBLISH_CHAT_ID") or env_config.get("TELEGRAM_PUBLISH_CHAT_ID") or "-1003602779585"
        topic_id = args.topic_id or os.environ.get("TELEGRAM_PUBLISH_TOPIC_ID") or env_config.get("TELEGRAM_PUBLISH_TOPIC_ID") or "6151"
        target_name = "Publik (Federasi INF - Topic 6151)"
    else:
        chat_id = args.chat_id or os.environ.get("TELEGRAM_CHAT_ID") or env_config.get("TELEGRAM_CHAT_ID") or "-1003950002621"
        topic_id = args.topic_id or os.environ.get("TELEGRAM_TOPIC_ID") or env_config.get("TELEGRAM_TOPIC_ID") or "249"
        target_name = "Internal (Morneven Playground - Topic KRON)"

    if not bot_token:
        print("Error: TELEGRAM_BOT_TOKEN belum diset di .env, environment variable, atau argumen --token", file=sys.stderr)
        sys.exit(1)

    if not chat_id:
        print("Error: Target chat_id belum ditentukan", file=sys.stderr)
        sys.exit(1)

    print(f"Target Pengiriman: {target_name}")

    # Resolve APK
    if args.apk:
        apk_path = Path(args.apk).resolve()
        if not apk_path.is_file():
            print(f"Error: File APK {apk_path} tidak ditemukan", file=sys.stderr)
            sys.exit(1)
        m = re.search(r"(\d+\.\d+\.\d+)", apk_path.name)
        version = m.group(1) if m else "unknown"
    else:
        apk_path, version = find_latest_release(repo_root)

    print(f"Versi terdeteksi: {version}")
    print(f"File APK: {apk_path}")

    # Resolve SHA-256
    sha_file = apk_path.parent / f"{apk_path.stem}-sha256.txt"
    if sha_file.is_file():
        content = sha_file.read_text(encoding="utf-8-sig").strip()
        sha256_hash = content.split()[0].strip()
    else:
        print("Menghitung SHA-256...")
        sha256_hash = compute_sha256(apk_path)

    # Build concise caption for APK
    caption = build_caption(version, apk_path, sha256_hash)

    # Send document
    doc_res = send_telegram_document(
        bot_token=bot_token,
        chat_id=chat_id,
        topic_id=topic_id,
        file_path=apk_path,
        caption=caption,
        dry_run=args.dry_run
    )

    if not doc_res.get("ok"):
        print(f"\nGagal mengirim APK: {doc_res}", file=sys.stderr)
        sys.exit(1)

    print("File APK berhasil terkirim.")

    # Send full patch notes if requested
    if not args.no_patch_notes:
        custom_notes = None
        if args.notes_file:
            notes_p = Path(args.notes_file).resolve()
            if notes_p.is_file():
                custom_notes = notes_p.read_text(encoding="utf-8-sig")
        elif (apk_path.parent / "release-notes.md").is_file():
            custom_notes = (apk_path.parent / "release-notes.md").read_text(encoding="utf-8-sig")

        changelog_items = extract_changelog(repo_root, version)
        patch_messages = build_patch_notes_messages(version, changelog_items, custom_notes)

        reply_id = None
        if not args.dry_run:
            reply_id = doc_res.get("result", {}).get("message_id")

        print(f"Mengirim {len(patch_messages)} pesan patch notes...")
        for msg_text in patch_messages:
            send_telegram_message(
                bot_token=bot_token,
                chat_id=chat_id,
                topic_id=topic_id,
                text=msg_text,
                reply_to_message_id=reply_id,
                dry_run=args.dry_run
            )

    print("\nSelesai! Seluruh rilis dan patch notes berhasil dipublikasikan ke Telegram.")


if __name__ == "__main__":
    main()
