#!/usr/bin/env python3
"""Download the files that make the Android APK self-contained.

Run from the repository root (CI does this before the Gradle build):

    python3 agent_android/scripts/prepare_runtime_assets.py

The script is intentionally dependency-free.  It downloads the pinned Termux
bootstrap, resolves the Python/Git/ripgrep/archive dependency closure from the Termux
aarch64 repository, verifies every SHA256, and copies the local bridge source
into APK assets.
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "agent_android/app/src/main/assets"
DEBS = ASSETS / "runtime/debs"
BOOTSTRAP_VERSION = "bootstrap-2026.02.12-r1+apt.android-7"
BOOTSTRAP_URL = (
    "https://github.com/termux/termux-packages/releases/download/"
    f"{BOOTSTRAP_VERSION}/bootstrap-aarch64.zip"
)
BOOTSTRAP_MIRROR = (
    "https://sourceforge.net/projects/termux-packages.mirror/files/"
    f"{BOOTSTRAP_VERSION}/bootstrap-aarch64.zip/download"
)
BOOTSTRAP_SHA256 = "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3"
TERMUX_BASE = "https://packages.termux.dev/apt/termux-main"
TERMUX_INDEX = f"{TERMUX_BASE}/dists/stable/main/binary-aarch64/Packages"
PACKAGES = ("python", "python-pip", "git", "ripgrep", "zip", "unzip", "jq")
USER_AGENT = {"User-Agent": "ten-android-runtime/1.0"}


def fetch(url: str, destination: Path, minimum: int = 1024) -> None:
    if destination.exists() and destination.stat().st_size >= minimum:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".part")
    request = urllib.request.Request(url, headers=USER_AGENT)
    with urllib.request.urlopen(request, timeout=180) as response, temporary.open("wb") as out:
        while True:
            chunk = response.read(1024 * 256)
            if not chunk:
                break
            out.write(chunk)
    if temporary.stat().st_size < minimum:
        raise RuntimeError(f"Слишком маленький файл: {url}")
    temporary.replace(destination)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 256), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_index(text: str) -> dict[str, dict[str, str]]:
    packages: dict[str, dict[str, str]] = {}
    current: dict[str, str] = {}
    last = ""
    for line in text.splitlines():
        if line.startswith(" ") and last:
            current[last] += " " + line.strip()
            continue
        if not line.strip():
            if current.get("Package"):
                packages[current["Package"]] = current
            current = {}
            last = ""
            continue
        if ":" in line:
            key, value = line.split(":", 1)
            current[key.strip()] = value.strip()
            last = key.strip()
    if current.get("Package"):
        packages[current["Package"]] = current
    return packages


def dependencies(package: dict[str, str]) -> list[str]:
    result = []
    for field in ("Depends", "Pre-Depends"):
        for item in package.get(field, "").split(","):
            item = item.strip()
            if not item:
                continue
            name = re.split(r"[ (]", item.split("|")[0].strip())[0]
            if name:
                result.append(name)
    return result


def closure(packages: dict[str, dict[str, str]], roots: tuple[str, ...]) -> list[str]:
    seen: set[str] = set()
    stack = list(roots)
    while stack:
        name = stack.pop()
        if name in seen:
            continue
        package = packages.get(name)
        if package is None:
            print(f"warning: package not found in index: {name}", file=sys.stderr)
            continue
        seen.add(name)
        stack.extend(dependencies(package))
    return sorted(seen)


def safe_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._+-]", "_", value)


def main() -> int:
    ASSETS.mkdir(parents=True, exist_ok=True)
    bootstrap = ASSETS / "bootstrap-aarch64.zip"
    if not bootstrap.exists() or sha256(bootstrap) != BOOTSTRAP_SHA256:
        print("Downloading Termux bootstrap…")
        try:
            fetch(BOOTSTRAP_URL, bootstrap, 20_000_000)
        except Exception as first:
            print(f"GitHub download failed ({first}); trying SourceForge…", file=sys.stderr)
            fetch(BOOTSTRAP_MIRROR, bootstrap, 20_000_000)
    actual = sha256(bootstrap)
    if actual != BOOTSTRAP_SHA256:
        raise RuntimeError(f"Bootstrap SHA256 mismatch: {actual}")

    index_file = ROOT / "agent_android/build/termux-packages-aarch64.txt"
    fetch(TERMUX_INDEX, index_file, 500_000)
    packages = parse_index(index_file.read_text(encoding="utf-8", errors="replace"))
    names = closure(packages, PACKAGES)
    DEBS.mkdir(parents=True, exist_ok=True)
    manifest = {"bootstrap": {"version": BOOTSTRAP_VERSION, "sha256": BOOTSTRAP_SHA256}, "packages": {}}
    for name in names:
        package = packages[name]
        filename = package.get("Filename")
        expected = package.get("SHA256")
        if not filename or not expected:
            raise RuntimeError(f"Bad package metadata: {name}")
        destination = DEBS / f"{safe_name(name)}_{safe_name(package['Version'])}_aarch64.deb"
        url = f"{TERMUX_BASE}/{filename}"
        print(f"  {name} {package['Version']}")
        fetch(url, destination, 800)
        actual = sha256(destination)
        if actual != expected:
            raise RuntimeError(f"SHA256 mismatch for {name}: {actual}")
        manifest["packages"][name] = {
            "version": package["Version"],
            "filename": destination.name,
            "sha256": actual,
            "size": destination.stat().st_size,
        }

    runtime_dir = ASSETS / "runtime"
    runtime_dir.mkdir(parents=True, exist_ok=True)
    server = ROOT / "phone-runtime/server.py"
    (runtime_dir / "server.py").write_bytes(server.read_bytes())
    (runtime_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    total = sum(item["size"] for item in manifest["packages"].values())
    print(f"Runtime assets ready: {len(names)} packages, {total / 1_000_000:.1f} MB of .deb")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
