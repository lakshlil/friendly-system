#!/usr/bin/env bash
# Publishes a release that installed apps pick up by themselves.
# Builds the signed release APK, then commits medreminder.apk + update.json to the `medreminder-dist`
# branch of lakshlil/friendly-system (served at raw.githubusercontent.com, see updateUrl in gradle.properties).
#
# Usage: scripts/publish.sh [notes]     (bump versionCode/versionName in app/build.gradle.kts first)
# Needs the signing key: KEYSTORE_FILE (+ KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD), or ~/.android/debug.keystore.
# It must be the key installed copies were signed with (cert SHA-256 775a293f…), or updates won't install.
set -euo pipefail
cd "$(dirname "$0")/.."
NOTES="${1:-$(git log -1 --pretty=%s)}"
DIST="${DIST_DIR:-/tmp/medreminder-dist}"
REMOTE="${DIST_REMOTE:-https://github.com/lakshlil/friendly-system}"

gradle --no-daemon -q testDebugUnitTest assembleRelease
read CODE NAME < <(gradle --no-daemon -q :app:printVersion | tail -1)
APK=app/build/outputs/apk/release/app-release.apk

rm -rf "$DIST"
git clone -q --depth 1 --branch medreminder-dist "$REMOTE" "$DIST" 2>/dev/null || {
  mkdir -p "$DIST"; git -C "$DIST" init -q -b medreminder-dist; git -C "$DIST" remote add origin "$REMOTE"; }
cp "$APK" "$DIST/medreminder.apk"
SHA=$(sha256sum "$DIST/medreminder.apk" | cut -d' ' -f1)
BASE="${REMOTE/github.com/raw.githubusercontent.com}/medreminder-dist"
python3 - "$DIST/update.json" "$CODE" "$NAME" "$BASE/medreminder.apk?v=$CODE" "$SHA" "$NOTES" <<'PY'
import json, sys
p, code, name, apk, sha, notes = sys.argv[1:]
json.dump({"versionCode": int(code), "versionName": name, "apk": apk, "sha256": sha, "notes": notes}, open(p, "w"), indent=2)
PY
cat > "$DIST/README.md" <<MD
# Med Reminder downloads

Latest: **$NAME** (build $CODE). [Download the APK](medreminder.apk).
Installed copies update themselves from \`update.json\`. Source: branch \`medreminder\`.
MD
git -C "$DIST" add -A
git -C "$DIST" commit -qm "Med Reminder $NAME (build $CODE)"
git -C "$DIST" push -u origin medreminder-dist
echo "Published $NAME ($CODE): $BASE/update.json"
