# Changelog

Versions are numbered *major.minor.fix*. While Talkies is in beta they start with 0: a new minor number brings new
features, a new fix number only fixes. The version shows in Talkies settings → About.

## Unreleased

### Changed
- A new logo: a cinema ticket with a play button, on a warm dark background, for the app icon and the TV banner
  (it replaces the two glass cards from when Talkies was called Glass Home).

## 0.2.0 — 28 October 2026

### New
- **Live football scores.** A Football row and score line like cricket's, from ESPN's public scoreboard, with no
  key needed: Premier League, Champions League, LaLiga, Serie A, Bundesliga, ISL, MLS and more. Each country starts
  with its own leagues; change them in Talkies settings → Football leagues. Updates every minute while a match is on.
- **Live scores setting:** cricket, football, both or off.
- **Pin and hide apps.** Hold OK on an app: **Add to dock**, **Remove from dock**, or **Hide** (it stays installed).
  Talkies settings → Hidden apps brings them back.
- **Keys and location on the TV.** Type or paste your TMDB key, CricketData key and weather location in Talkies
  settings → Keys and location; no computer needed. Keys are checked with the service before they're saved.
- **Talkies settings,** separate from Control Center. Control Center keeps the quick actions (sleep timer, turn off,
  memory, network, speed test, sound, screensaver); everything you set once moved to Talkies settings, opened from
  Control Center's last row or by holding OK on the ⚙ icon.

### Changed
- A Test match at stumps, lunch, tea or a rain delay shows as paused, not live; a live football match then takes
  the score line, and its row moves above Cricket.
- The score line shows the football league: "● LIVE  Serie A · 54' · SAS 2–4 LAZ".
- New installs start with Watch Next and each app's own home-screen row instead of the author's channel choice
  (TVs that had Talkies before keep theirs).
- On Android TV, Home goes to a launcher you picked as the home app; the README explains switching launchers on
  Google TV, which keeps its own launcher as the home app.
- `switch-home.sh` takes `talkies`, `stock`, any launcher's package name, or `list`.
- Transparent images (team badges) are cached as PNG, so they don't get black backgrounds.

### Fixed
- Score rows didn't update while focus rested on them, so a live match could show in the score line but not in
  the rows.

## 0.1.0 — 25 October 2026

First public version: a small, ad-free home screen for Android TV and Google TV. Weather, clock and date; For You
picks from TMDB with Continue, Trending and New in your country; a seven-app dock; channel rows; live cricket
scores; Control Center with sleep timer, turn off, speed test and HDMI-CEC inputs; remote key remapping; a
background that glows in the focused app's colour; light by day and dark at night. About 200 KB, no libraries,
MIT licensed.
