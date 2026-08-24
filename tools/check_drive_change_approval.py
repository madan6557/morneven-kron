#!/usr/bin/env python3
"""Fail CI when protected production files lack an approval trailer."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import PurePosixPath


APPROVAL_RE = re.compile(r"^Drive-Change-Approval:\s*\S+\s*$", re.MULTILINE)
PROTECTED_PREFIXES = (
    "app/src/main/java/com/morneven/kron/sync/",
    "app/src/main/java/com/morneven/kron/team/",
    "app/src/main/java/com/morneven/kron/capsule/",
    "app/src/main/java/com/morneven/kron/backup/",
    "app/src/main/java/com/morneven/kron/security/",
    "app/src/main/java/com/morneven/kron/data/",
    "app/src/main/java/com/morneven/kron/di/",
)
PROTECTED_FILES = {
    "app/src/main/java/com/morneven/kron/MainActivity.kt",
    "app/src/main/java/com/morneven/kron/KronApplication.kt",
    "app/src/main/java/com/morneven/kron/ui/KronApp.kt",
    "app/src/main/java/com/morneven/kron/ui/MainViewModel.kt",
    "app/src/main/java/com/morneven/kron/ui/components/Components.kt",
    "app/src/main/java/com/morneven/kron/ui/dialogs/Dialogs.kt",
    "app/src/main/java/com/morneven/kron/ui/screens/SettingsScreen.kt",
    "app/build.gradle.kts",
    "gradle/libs.versions.toml",
    "settings.gradle.kts",
}


def git(*args: str) -> str:
    result = subprocess.run(["git", *args], check=True, text=True, capture_output=True)
    return result.stdout


def is_protected(path: str) -> bool:
    normalized = PurePosixPath(path).as_posix()
    return normalized in PROTECTED_FILES or normalized.startswith(PROTECTED_PREFIXES)


def commit_files(commit: str) -> list[str]:
    return [line for line in git("diff-tree", "--no-commit-id", "--name-only", "-r", commit).splitlines() if line]


def commit_message(commit: str) -> str:
    return git("show", "-s", "--format=%B", commit)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", default="HEAD")
    args = parser.parse_args()

    changed = [line for line in git("diff", "--name-only", f"{args.base}...{args.head}").splitlines() if line]
    protected_changed = [path for path in changed if is_protected(path)]
    if not protected_changed:
        print("Drive change control: PASS, no protected production path changed.")
        return 0

    commits = [line for line in git("rev-list", "--reverse", f"{args.base}..{args.head}").splitlines() if line]
    violations: list[tuple[str, list[str]]] = []
    for commit in commits:
        touched = [path for path in commit_files(commit) if is_protected(path)]
        if touched and not APPROVAL_RE.search(commit_message(commit)):
            violations.append((commit, touched))

    if violations:
        print("Drive change control: FAIL", file=sys.stderr)
        print("Protected production changes require explicit user approval and:", file=sys.stderr)
        print("  Drive-Change-Approval: <approval-reference>", file=sys.stderr)
        for commit, paths in violations:
            print(f"\n{commit}", file=sys.stderr)
            for path in paths:
                print(f"  {path}", file=sys.stderr)
        return 1

    print(f"Drive change control: PASS, {len(protected_changed)} protected path(s) have approval trailers.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
