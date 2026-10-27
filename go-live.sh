#!/bin/bash
# Norton TV: builds, installs on the Bravia and (with --home) makes Norton TV the home screen.
#   ./go-live.sh                 build and install only; Projectivy stays the home screen
#   ./go-live.sh --home          ...and make Norton TV the home screen
#   ./go-live.sh --projectivy    put Projectivy back as the home screen (nothing is uninstalled)
# Optional last argument: the TV's address (default: TALKIES_TV or the .tv file, see tv-address.sh).
#
# Unlike Talkies' build-and-install.sh this leaves Google's setup wizard alone (switching it off can delete the TV's
# saved Wi-Fi) and grants no location: on this TV the stock launcher is already switched off by tv-debloat, so
# Android's normal "preferred home" setting is all it takes.
set -euo pipefail
. "$(dirname "$0")/tv-address.sh"
MODE=install; ARGS=()
for a in "$@"; do case "$a" in --home) MODE=home ;; --projectivy) MODE=projectivy ;; *) ARGS+=("$a") ;; esac; done
DEV="${ARGS[0]:-$(tv_default)}"; need_tv
PKG=com.haznorty.nortontv
NORTON_HOME=$PKG/com.techpp.glass.HomeActivity
PROJECTIVY_HOME=com.spocky.projengmenu/.ui.home.MainActivity
HERE="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-$LOCALAPPDATA/Programs/Eclipse Adoptium/jdk-17.0.20.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
export MSYS_NO_PATHCONV=1   # Git Bash: don't turn /sdcard/... into C:/Program Files/Git/sdcard/...

sh() { adb -s "$DEV" shell -n "$@" | tr -d '\r'; }
home() { sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1; }

adb connect "$DEV" >/dev/null || true
if [ "$(adb -s "$DEV" get-state 2>/dev/null)" != device ]; then
  echo "!! Can't reach the TV at $DEV. Is Network debugging on, and did you press OK on the TV?" >&2; exit 1
fi

if [ "$MODE" = projectivy ]; then
  sh cmd package set-home-activity "$PROJECTIVY_HOME" >/dev/null
  echo "Home screen: $(home)"; sh input keyevent KEYCODE_HOME; exit 0
fi

echo "== Building"
(cd "$HERE" && ./gradlew -q assembleRelease)
APK="$HERE/app/build/outputs/apk/release/app-release.apk"
case "$(uname -s)" in MINGW*|MSYS*) APK="$(cygpath -m "$APK")" ;; esac   # adb.exe wants C:/... paths

echo "== Installing on $DEV"
adb -s "$DEV" install -r "$APK"
# Continue watching reads Android TV's Watch Next list.
sh pm grant $PKG android.permission.READ_TV_LISTINGS || true
# Personal settings saved next to these scripts (all git-ignored), pushed again after a fresh install.
for f in tmdb_key.txt calendars.txt location.txt; do
  if [ -f "$HERE/$f" ]; then
    sh mkdir -p /sdcard/Android/data/$PKG/files
    SRC="$HERE/$f"; case "$(uname -s)" in MINGW*|MSYS*) SRC="$(cygpath -m "$SRC")" ;; esac
    adb -s "$DEV" push "$SRC" /sdcard/Android/data/$PKG/files/$f >/dev/null && echo "   pushed $f"
  fi
done

if [ "$MODE" = home ]; then
  echo "== Making Norton TV the home screen (Projectivy stays installed as the fallback)"
  sh cmd package set-home-activity "$NORTON_HOME" >/dev/null
else
  # Installing a second home app can make Android ask which one to use: keep whichever was chosen before.
  case "$(home)" in $PKG/*) ;; *) sh cmd package set-home-activity "$PROJECTIVY_HOME" >/dev/null ;; esac
fi
echo "Home screen: $(home)"
sh input keyevent KEYCODE_HOME
