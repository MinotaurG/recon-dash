#!/usr/bin/env bash
# Capture a nav ride's logs to a timestamped file that SURVIVES (unlike the rolling logcat
# buffer, which rolls over during a long ride and loses the nav lines).
#
# Usage:  ./capture-ride.sh
# Then ride. Ctrl-C when done. The file ~/ride-YYYYMMDD-HHMMSS.log has the full nav trace.
set -e
ADB="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb"
DEV="$("$ADB" devices | awk 'NR==2{print $1}')"
OUT="$HOME/ride-$(date +%Y%m%d-%H%M%S).log"

echo "Device: $DEV"
echo "Writing to: $OUT"
echo "Clearing buffer + streaming nav logs. Ride now; Ctrl-C when done."
"$ADB" -s "$DEV" logcat -c
# Stream only the nav-relevant tags to the file (keeps it small over a long ride).
"$ADB" -s "$DEV" logcat -v time \
  NavLog:D NavEngine:D ActiveNavVM:D NavSessionManager:D Router:I NavDashBridge:D \
  DashSession:I DashViewModel:I RideRecorder:I "*:S" | tee "$OUT"
