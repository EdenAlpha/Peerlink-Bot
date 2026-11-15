"""Restore public game resources only; archive URLs stay in an Actions secret.

The committed allowlist prevents device/account files from being published.
Every output must match that allowlist before the upload step can run.
"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import sys
import urllib.request


def main():
    from owner_resource_input import receive
    config = receive()
    allowed = {}
    for line in Path("app/src/main/assets/game-host/match-resources-11.1.1.tsv").read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        name, size, digest = line.split("\t")
        allowed[name] = (int(size), digest)
    entries = config["entries"]
    assert len(entries) == len(allowed)
    assert {item["name"] for item in entries} == set(allowed)
    assert all((item["size"], item["sha256"]) == allowed[item["name"]] for item in entries)
    output = Path("match-resource-output")
    output.mkdir()

    def restore(item):
        name = item["name"]
        position, remaining = item["offset"], item["size"]
        digest = hashlib.sha256()
        target = output / name
        with target.open("wb") as stream:
            while remaining:
                first_size = config["parts"][0]
                part = 0 if position < first_size else 1
                start = 0 if part == 0 else first_size
                offset = position - start
                count = min(remaining, config["parts"][part] - offset)
                end = offset + count - 1
                request = urllib.request.Request(config["urls"][part], headers={"Range": f"bytes={offset}-{end}"})
                with urllib.request.urlopen(request, timeout=120) as response:
                    assert response.status == 206
                    assert response.headers["Content-Range"].startswith(f"bytes {offset}-{end}/")
                    unread = count
                    while unread:
                        block = response.read(min(unread, 1024 * 1024))
                        if not block:
                            raise RuntimeError("Truncated resource download")
                        stream.write(block)
                        digest.update(block)
                        unread -= len(block)
                        remaining -= len(block)
                        position += len(block)
        if target.stat().st_size != item["size"] or digest.hexdigest() != item["sha256"]:
            raise RuntimeError("Original resource checksum mismatch: " + name)
        print("VERIFIED_ORIGINAL_MATCH_RESOURCE", name, item["size"], flush=True)

    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(restore, sorted(entries, key=lambda item: item["size"])))
    assert len(list(output.iterdir())) == len(allowed)
    print("ALL_ORIGINAL_RESOURCES_VERIFIED", len(allowed), flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # HTTP exceptions may contain temporary private archive URLs.
        print("Resource restore failed:", type(error).__name__, file=sys.stderr)
        sys.exit(1)
