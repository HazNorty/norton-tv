#!/bin/bash
# Sets the area for Talkies' weather line (top left). The launcher shows the nearest airport's current
# observation, or a forecast-model reading when no airport nearby has one.
#   ./set-location.sh "Indiranagar, Bengaluru"    a place name (looked up on the TV)
#   ./set-location.sh "12.97,77.64"               or latitude,longitude
#   ./set-location.sh --remove                    back to the greeting
# Optional last argument: the TV's address (default: TALKIES_TV or the .tv file, see tv-address.sh).
set -euo pipefail
AREA="${1:?Usage: $0 \"place\" | lat,lon | --remove [tv-ip:port]}"
. "$(dirname "$0")/tv-address.sh"
DEV="${2:-$(tv_default)}"; need_tv
F=/sdcard/Android/data/com.haznorty.nortontv/files/location.txt
adb connect "$DEV" >/dev/null 2>&1 || true
if [ "$AREA" = "--remove" ]; then
  adb -s "$DEV" shell -n rm -f "$F"
  echo "Location removed; Talkies shows the greeting again."
else
  TMP="$(mktemp)"; printf '%s' "$AREA" > "$TMP"
  adb -s "$DEV" push "$TMP" "$F" >/dev/null; rm -f "$TMP"
  echo "Saved \"$AREA\". Talkies looks it up and shows the weather the next time it opens."
fi
