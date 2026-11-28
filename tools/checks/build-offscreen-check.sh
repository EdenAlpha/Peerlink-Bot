#!/bin/sh
# Build a single downloadable Prime Shell check from the diagnostic and its SDK helper.
set -eu
if [ "$#" -ne 3 ]; then
  echo 'Usage: build-offscreen-check.sh ANDROID_SDK JDK OUTPUT_SCRIPT' >&2
  exit 2
fi
sdk=$1
jdk=$2
output=$3
checks=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
android_jar="$sdk/platforms/android-36/android.jar"
d8="$sdk/build-tools/35.0.0/lib/d8.jar"
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
mkdir "$build/classes"
"$jdk/bin/javac" --release 11 -classpath "$android_jar" -d "$build/classes" \
  "$checks/../../app/src/main/java/com/peerlink/app/godmode/PeerLinkOffscreenDisplay.java" \
  "$checks/../../app/src/main/java/com/peerlink/app/bot/BotRenderSurface.java"
"$jdk/bin/jar" cf "$build/input.jar" -C "$build/classes" .
"$jdk/bin/java" -cp "$d8" com.android.tools.r8.D8 --min-api 34 --lib "$android_jar" \
  --output "$build/helper.jar" "$build/input.jar"
python3 - "$checks/PeerLink-priority.sh" "$build/helper.jar" "$output" <<'PY'
import base64
import hashlib
import sys
from pathlib import Path

source, binary, output = (Path(p) for p in sys.argv[1:])
script = source.read_text()
helper = binary.read_bytes()
payload = base64.b64encode(helper).decode("ascii")
values = {"offscreen_payload": payload, "offscreen_sha256": hashlib.sha256(helper).hexdigest()}
for name, value in values.items():
    marker = f"\n{name}=''\n"
    if script.count(marker) != 1:
        raise SystemExit(f"Missing unique {name} assignment in the diagnostic")
    script = script.replace(marker, f"\n{name}='{value}'\n")
output.write_text(script)
PY
