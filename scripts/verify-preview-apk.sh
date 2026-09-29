#!/usr/bin/env bash
set -euo pipefail
apk="app/build/outputs/apk/debug/app-debug.apk"
build_tools="$(find "$ANDROID_SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
"$build_tools/apksigner" verify --verbose --print-certs "$apk" >/dev/null
badging="$("$build_tools/aapt" dump badging "$apk")"
BADGING="$badging" python3 - <<'PY'
import json, os, re
from pathlib import Path
line = os.environ['BADGING'].splitlines()[0]
values = dict(re.findall(r"(\w+)='([^']*)'", line))
metadata = json.loads(Path('app/build/outputs/apk/debug/output-metadata.json').read_text())
element = metadata['elements'][0]
assert values['name'] == metadata['applicationId'] == 'com.kura.aria.b2preview'
assert values['versionName'] == element['versionName'] == '0.2.37-B2-preview'
assert int(values['versionCode']) == element['versionCode'] == 1
print('ARIA B2 preview APK is signed and has an isolated application ID.')
PY
