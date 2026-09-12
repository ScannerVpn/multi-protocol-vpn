#!/usr/bin/env python3
"""Fetch the pinned Android AAR on Linux/macOS/Windows, verifying before install."""
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent
NAME = "hiddify-core-4.1.0.aar"
URL = "https://github.com/hiddify/hiddify-core/releases/download/v4.1.0/hiddify-lib-android.tar.gz"


def main():
    expected = json.loads((ROOT / "core-hashes.json").read_text())[NAME]
    dest = ROOT / "app" / "libs" / NAME
    if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest() == expected:
        print("Core already present; SHA256 verified.")
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(URL, headers={"User-Agent": "MultiVPN-build"})
    with urllib.request.urlopen(request, timeout=120) as response:
        archive = response.read()
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as tar:
        members = [m for m in tar.getmembers() if m.isfile() and Path(m.name).name == "hiddify-core.aar"]
        if len(members) != 1:
            raise RuntimeError("Expected exactly one hiddify-core.aar in archive")
        data = tar.extractfile(members[0]).read()
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise RuntimeError(f"Core SHA256 mismatch: expected {expected}, got {actual}")
    with tempfile.NamedTemporaryFile(dir=dest.parent, delete=False) as output:
        pending = Path(output.name)
        output.write(data)
    try:
        pending.replace(dest)
    finally:
        pending.unlink(missing_ok=True)
    print("Core installed; SHA256 verified.")


if __name__ == "__main__":
    main()
