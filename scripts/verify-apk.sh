#!/usr/bin/env bash
set -euo pipefail
apk="app/build/outputs/apk/debug/app-debug.apk"
expected="2321b43077cd25d11d9d78312e9edb920441fd4847b406dd6e30774815e59921"
build_tools="$(find "$ANDROID_SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
report="$("$build_tools/apksigner" verify --verbose --print-certs "$apk")"
printf '%s\n' "$report"
actual="$(printf '%s\n' "$report" | sed -n -e 's/^V2 Signer: certificate SHA-256 digest: //p' -e 's/^Signer #1 certificate SHA-256 digest: //p' | tr '[:upper:]' '[:lower:]')"
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
assert int(values['versionCode']) == 68
assert values['versionName'] == '0.2.53'
print('APK identity and version agree with build metadata.')
PY

contents="$(unzip -l "$apk")"
grep -q 'lib/arm64-v8a/libpockettts_jni.so' <<<"$contents" || {
  echo "::error::Pocket TTS JNI library missing for arm64-v8a"; exit 1;
}
grep -q 'lib/arm64-v8a/libonnxruntime.so' <<<"$contents" || {
  echo "::error::ONNX Runtime missing for arm64-v8a"; exit 1;
}
if grep -Eq 'flow_lm_(main|flow)|mimi_(encoder|decoder)\.onnx|text_conditioner\.onnx' <<<"$contents"; then
  echo "::error::Pocket model weights must not be packaged in the APK"; exit 1
fi
apk_bytes="$(stat -c '%s' "$apk")"
test "$apk_bytes" -lt $((200 * 1024 * 1024)) || {
  echo "::error::APK unexpectedly contains a large model payload"; exit 1;
}
echo "Pocket runtime present for arm64-v8a; Pocket model weights are outside the APK."
