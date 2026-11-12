#!/usr/bin/env bash
# Writes the GitHub release notes (Markdown) for one build to stdout.
#   release-notes.sh <version-name> <kind: release|test> <apk-file-name> <commit-sha>
# The "What's new" section is the matching "## <x.y.z>" section of CHANGELOG.md.
set -euo pipefail

VERSION_NAME="$1"   # e.g. 1.0.0-build.46 or 1.0.0
KIND="$2"
APK="$3"
SHA="$4"
BASE="${VERSION_NAME%%-*}"            # 1.0.0
# Changelog heading for this build: 1.0.0-beta.53 -> 1.0.0-beta, 1.0.0-build.46 -> 1.0.0
CHAN="$(echo "$VERSION_NAME" | sed -E 's/-build\.[0-9]+$//; s/(-beta)\.[0-9]+$/\1/')"
BETA=no; case "$VERSION_NAME" in *-beta*) BETA=yes ;; esac
# Automatic releases (v1.0.1, v1.0.2…) are the public beta build.
[ "${CHANNEL:-}" = "beta" ] && BETA=yes
REPO="${GITHUB_REPOSITORY:-Hm589158/ps4-SmartCheat}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

changes="$(awk -v v="## ${BASE}" -v full="## ${VERSION_NAME}" -v chan="## ${CHAN}" '
  $0 == v || index($0, v " ") == 1 || $0 == full || index($0, full " ") == 1 || $0 == chan || index($0, chan " ") == 1 { on = 1; next }
  on && /^## / { exit }
  on { print }
' "$ROOT/CHANGELOG.md")"
# No section for this exact version: use the newest section (the current changes).
[ -n "${changes//[[:space:]]/}" ] || { changes="$(awk '/^## / { n++; if (n == 2) exit; if (n == 1) { next } } n == 1 { print }' "$ROOT/CHANGELOG.md")"; CHAN="$(grep -m1 '^## ' "$ROOT/CHANGELOG.md" | sed 's/^## //')"; }
[ -n "${changes//[[:space:]]/}" ] || changes="See [CHANGELOG.md](https://github.com/${REPO}/blob/main/CHANGELOG.md)."

if [ "$BETA" = yes ]; then
  if [ "$KIND" = release ]; then SIGN="Signed with the release key (package \`com.ps4smartcheat.app\`)."
  else SIGN="Debug-signed by CI (package \`com.ps4smartcheat.app.debug\`); if Android reports a signature conflict, uninstall the previous build first."; fi
  cat <<EOF
> [!NOTE]
> **Beta** — feature-complete and passing every automated check; still being verified on real consoles. ${SIGN}
> Please report what works and what does not: [hardware test report](https://github.com/${REPO}/issues/new?template=hardware_report.yml).
EOF
elif [ "$KIND" = "release" ]; then
  cat <<EOF
**Signed release** of PS4 SmartCheat ${BASE} (package \`com.ps4smartcheat.app\`). Updates an earlier signed release in place.
EOF
else
  cat <<EOF
> [!NOTE]
> **Test build** — debug-signed, built by GitHub Actions after all checks passed. Installs as
> \`com.ps4smartcheat.app.debug\` next to a signed release; uninstall the previous test build first if Android
> reports a signature conflict.
EOF
fi

cat <<EOF

## Download

| File | What it is |
|---|---|
| [\`${APK}\`](https://github.com/${REPO}/releases/download/v${VERSION_NAME}/${APK}) | Android app (Android 8.0+, phones and tablets) |

## Install

1. Download the APK on the phone and allow *Install unknown apps* for the browser or file manager.
2. On the PS4: GoldHEN → load **[ps4debug-NG](https://github.com/Pharaoh2k/ps4debug-NG/releases)** (required) → start the game.
3. In the app: enter the PS4's local IP (port 744) → **Connect**. The running game is selected automatically.

The in-app guide (ⓘ) walks through scanning, safe editing, the address list and pointer scans.

## What's new in ${CHAN}

${changes}

## Compatibility

Supports every firmware ps4debug-NG supports (39 versions, 5.05–14.02): the app uses only ps4debug-NG's commands and has
no firmware-specific code. The core flow is reported working on a real PS4 by the project owner (FW 11.00, GoldHEN,
ps4debug-NG 1.3.3). Features marked as new are verified end to end against the simulated ps4debug-NG server — see
[docs/COMPATIBILITY.md](https://github.com/${REPO}/blob/main/docs/COMPATIBILITY.md).
For offline single-player use on your own console. ps4debug has no authentication: trusted local network only.

---
Built from [\`${SHA:0:7}\`](https://github.com/${REPO}/commit/${SHA}) by GitHub Actions.
EOF
