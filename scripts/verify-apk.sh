#!/usr/bin/env bash
set -euo pipefail
apk="app/build/outputs/apk/debug/app-debug.apk"
expected="24a83e6cae643a11b30e1102166ec4f8b4cecdd4614f7ba5a1e7cbebc16cde17"
build_tools="$(find "$ANDROID_SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
report="$("$build_tools/apksigner" verify --verbose --print-certs "$apk")"
printf '%s\n' "$report"
actual="$(printf '%s\n' "$report" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr '[:upper:]' '[:lower:]')"
test "$actual" = "$expected" || { echo "::error::Final APK has the wrong signing certificate"; exit 1; }
badging="$("$build_tools/aapt" dump badging "$apk")"
BADGING="$badging" python3 - <<'PY'
import json, os, re
from pathlib import Path
line = os.environ['BADGING'].splitlines()[0]
print(line)
values = dict(re.findall(r"(\w+)='([^']*)'", line))
metadata = json.loads(Path('app/build/outputs/apk/debug/output-metadata.json').read_text())
element = metadata['elements'][0]
assert values['name'] == metadata['applicationId'] == 'com.kura.aria'
assert int(values['versionCode']) == element['versionCode']
assert values['versionName'] == element['versionName']
print('APK identity and version agree with build metadata.')
PY
