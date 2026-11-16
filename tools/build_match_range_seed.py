#!/usr/bin/env python3
"""Build or extract the measured 11.1.1 asset-range research fixture.

This is not a replacement for the APK's full-resource manifest. Unobserved
rosters or match events may need additional ranges.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import zipfile

ENGINE = "2cea0c85344d7d7f295aea1c2353621f02db78dbccd888a0a21a0508ac330659"
FORMAT = "peerlink-match-range-seed-v1"
PINNED = Path(__file__).resolve().parents[1] / "app/src/main/assets/game-host/match-resources-11.1.1.tsv"
NAME = re.compile(r"pc[0-9]{4}_mobile_and\.(pak|ucas|utoc)")
BUFFER = 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def pinned_files():
    result = {}
    for line in PINNED.read_text().splitlines():
        if line and not line.startswith("#"):
            name, size, sha = line.split("\t")
            result[name] = (int(size), sha)
    return result


def segments(stream, size):
    while size:
        block = stream.read(min(size, BUFFER))
        require(bool(block), "Truncated resource payload")
        size -= len(block)
        yield block


def validate_files(files):
    pinned = pinned_files()
    require(len(files) == len(pinned), "Resource file count differs from pinned build")
    names = [entry["name"] for entry in files]
    require(len(set(names)) == len(names) and set(names) == set(pinned),
            "Duplicate or unsupported resource name")
    for entry in files:
        name = entry["name"]
        require(NAME.fullmatch(name), "Invalid resource filename")
        require((entry["logicalBytes"], entry["originalSha256"]) == pinned[name],
                f"Original resource fingerprint differs: {name}")
        stop = 0
        payload_offset = 0
        for part in entry["ranges"]:
            offset, length = part["offset"], part["length"]
            require(type(offset) is int and type(length) is int and
                    offset >= stop and length > 0 and
                    offset + length <= entry["logicalBytes"],
                    f"Invalid source ranges: {name}")
            require(part["payloadOffset"] == payload_offset,
                    f"Invalid payload offsets: {name}")
            require(re.fullmatch(r"[0-9a-f]{64}", part["sha256"]),
                    f"Invalid range fingerprint: {name}")
            stop = offset + length
            payload_offset += length
        require(payload_offset == entry["payloadBytes"], f"Payload size differs: {name}")
        require(entry["payloadEntry"] == "payload/" + name, "Invalid payload entry")


def build(args):
    profile = json.loads(args.profile.read_text())
    require(profile.get("engineSha256") == ENGINE, "Unsupported engine profile")
    files = profile["profiles"][args.role]["files"]
    inventory = json.loads(args.sources.read_text())
    require(not inventory.get("missing"), "Original source inventory is incomplete")
    sources = {entry["name"]: Path(entry["path"]) for entry in inventory["files"]}
    manifest = {"format": FORMAT, "engineSha256": ENGINE,
                "scope": "Measured extra-resource ranges for one controlled match; "
                         "other rosters/events and phone installation are unverified",
                "files": []}
    for entry in files:
        require(entry.get("unknownReadCount", 0) == 0, "Unknown-offset reads in profile")
        source = sources[entry["name"]]
        require(source.stat().st_size == entry["bytes"], f"Wrong source size: {source.name}")
        with source.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        require(digest == entry["sha256"], f"Wrong source hash: {source.name}")
        parts = []
        payload_size = 0
        with source.open("rb") as stream:
            for start, stop in entry["ranges"]:
                require(type(start) is int and type(stop) is int and
                        0 <= start < stop <= entry["bytes"], "Invalid profile range")
                stream.seek(start)
                digest = hashlib.sha256()
                for block in segments(stream, stop - start):
                    digest.update(block)
                parts.append({"offset": start, "length": stop - start,
                              "payloadOffset": payload_size, "sha256": digest.hexdigest()})
                payload_size += stop - start
        manifest["files"].append({"name": entry["name"], "logicalBytes": entry["bytes"],
                                  "originalSha256": entry["sha256"], "ranges": parts,
                                  "payloadBytes": payload_size,
                                  "payloadEntry": "payload/" + entry["name"]})
    validate_files(manifest["files"])
    manifest["seedBytes"] = sum(entry["payloadBytes"] for entry in manifest["files"])
    manifest["originalBytes"] = sum(entry["logicalBytes"] for entry in manifest["files"])
    require(not args.output.exists(), "Output already exists")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".range-seed-", dir=args.output.parent)
    os.close(fd)
    try:
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            archive.writestr("manifest.json", json.dumps(manifest, indent=2) + "\n")
            for entry in manifest["files"]:
                with sources[entry["name"]].open("rb") as source, \
                        archive.open(entry["payloadEntry"], "w", force_zip64=True) as payload:
                    for part in entry["ranges"]:
                        source.seek(part["offset"])
                        digest = hashlib.sha256()
                        for block in segments(source, part["length"]):
                            payload.write(block)
                            digest.update(block)
                        require(digest.hexdigest() == part["sha256"],
                                f"Source changed while packing: {entry['name']}")
        # link() fails atomically if another process created the requested output.
        os.link(temporary, args.output)
    finally:
        Path(temporary).unlink(missing_ok=True)
    with args.output.open("rb") as stream:
        sha = hashlib.file_digest(stream, "sha256").hexdigest()
    print(json.dumps({"seedBytes": manifest["seedBytes"],
                      "originalBytes": manifest["originalBytes"],
                      "archiveBytes": args.output.stat().st_size, "sha256": sha}))


def extract(args):
    require(not args.output.exists(), "Output directory already exists")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".range-seed-", dir=args.output.parent))
    try:
        with zipfile.ZipFile(args.archive) as archive:
            require(archive.getinfo("manifest.json").file_size <= 1024 * 1024,
                    "Manifest is too large")
            manifest = json.loads(archive.read("manifest.json"))
            require(manifest["format"] == FORMAT and manifest["engineSha256"] == ENGINE,
                    "Unsupported seed format or engine")
            validate_files(manifest["files"])
            entries = ["manifest.json"] + [entry["payloadEntry"] for entry in manifest["files"]]
            require(len(archive.namelist()) == len(entries) and
                    set(archive.namelist()) == set(entries), "Unexpected archive entries")
            for entry in manifest["files"]:
                require(archive.getinfo(entry["payloadEntry"]).file_size == entry["payloadBytes"],
                        f"Payload size differs: {entry['name']}")
                with archive.open(entry["payloadEntry"]) as payload, \
                        (temporary / entry["name"]).open("xb") as target:
                    target.truncate(entry["logicalBytes"])
                    for part in entry["ranges"]:
                        target.seek(part["offset"])
                        digest = hashlib.sha256()
                        for block in segments(payload, part["length"]):
                            target.write(block)
                            digest.update(block)
                        require(digest.hexdigest() == part["sha256"],
                                f"Corrupt range: {entry['name']} at {part['offset']}")
                    require(not payload.read(1), "Trailing resource payload")
        # Preserve the verified manifest beside the sparse files.
        (temporary / "range-seed-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        temporary.rename(args.output)
    except BaseException:
        shutil.rmtree(temporary)
        raise
    print(json.dumps({"files": len(manifest["files"]),
                      "seedBytes": sum(entry["payloadBytes"] for entry in manifest["files"]),
                      "logicalBytes": sum(entry["logicalBytes"] for entry in manifest["files"])}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("build")
    create.add_argument("--profile", required=True, type=Path)
    create.add_argument("--sources", required=True, type=Path)
    create.add_argument("--role", choices=["A", "B"], default="A")
    create.add_argument("--output", required=True, type=Path)
    restore = commands.add_parser("extract")
    restore.add_argument("--archive", required=True, type=Path)
    restore.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    (build if args.command == "build" else extract)(args)


if __name__ == "__main__":
    main()
