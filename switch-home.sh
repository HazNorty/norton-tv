#!/bin/bash
# Switches the TV's home screen.
#   ./switch-home.sh talkies          Talkies
#   ./switch-home.sh stock            the TV's own home screen (Google TV, Android TV or the TV maker's)
#   ./switch-home.sh <package>        any other launcher you installed, e.g. com.spocky.projengmenu (Projectivy)
#   ./switch-home.sh list             the home screens on the TV
# Optional second argument: the TV's address (default: TALKIES_TV or the .tv file, see tv-address.sh).
set -euo pipefail
. "$(dirname "$0")/tv-address.sh"
DEV="${2:-$(tv_default)}"; need_tv
adb connect "$DEV" >/dev/null 2>&1 || true
sh() { adb -s "$DEV" shell -n "$@" | tr -d '\r'; }
homes() { sh cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep / ; }
case "${1:-}" in
  talkies|glass) HOME_ACT=com.techpp.glass/.HomeActivity ;;
  stock)
    # The first home screen that came with the TV (a system app), not Talkies or a fallback page.
    SYSTEM="$(sh pm list packages -s)"
    HOME_ACT=""
    for a in $(homes); do
      case "$a" in com.techpp.glass/*|*FallbackHome*|*setupwraith*|*Settings*) continue ;; esac
      if printf '%s\n' "$SYSTEM" | grep -qx "package:${a%%/*}"; then HOME_ACT="$a"; break; fi
    done
    [ -n "$HOME_ACT" ] || { echo "No built-in home screen found. Home screens on the TV:"; homes; exit 1; } ;;
  list) homes; exit 0 ;;
  "") echo "Usage: $0 talkies|stock|<package>|list [tv-ip:port]"; exit 1 ;;
  *)
    HOME_ACT="$(homes | grep -m1 "^ *$1/" | tr -d ' ' || true)"
    [ -n "$HOME_ACT" ] || { echo "$1 isn't a home screen on this TV. These are:"; homes; exit 1; } ;;
esac
sh cmd package set-home-activity --user 0 "$HOME_ACT"
NOW="$(sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d ' ')"
echo "Home screen: $NOW"
if [ "${NOW%%/*}" != "${HOME_ACT%%/*}" ] && [ "${HOME_ACT%%/*}" = com.techpp.glass ]; then
  echo "Google TV keeps its own launcher as the home app; Talkies takes the Home button through"
  echo "Settings → Device Preferences → Accessibility → Talkies, which needs to be on."
elif [ "${NOW%%/*}" != "${HOME_ACT%%/*}" ]; then
  # Google TV keeps its own launcher as the home app whatever you choose; launchers take Home with an
  # accessibility service instead (Talkies' is Settings → Accessibility → Talkies).
  echo "!! The TV kept ${NOW%%/*} as its home app (Google TV does this). To use ${HOME_ACT%%/*}, turn off"
  echo "   Settings → Device Preferences → Accessibility → Talkies and turn on that launcher's own Home option."
fi
sh input keyevent KEYCODE_HOME
