#!/bin/bash
# Emulator smoke test: install, launch, open every tab and match-page section, then repeat offline.
# Fails the job if the app crashes at any point.
set +e
PKG=com.matchday.app
APK=app/build/outputs/apk/debug/app-debug.apk
FAIL=0

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1; }

# tap_text <text> [nth]: taps the nth (default 1st) node whose text or content-desc equals <text>
tap_text() {
  dump
  python3 - "$1" "${2:-1}" <<'PY'
import re, sys, subprocess
t, nth = sys.argv[1], int(sys.argv[2])
x = open('ui.xml', encoding='utf-8', errors='ignore').read()
hits = []
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    txt = re.search(r' text="([^"]*)"', n); desc = re.search(r'content-desc="([^"]*)"', n)
    if (txt and txt.group(1) == t) or (desc and desc.group(1) == t):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b: hits.append(tuple(map(int, b.groups())))
if len(hits) >= nth:
    x1, y1, x2, y2 = hits[nth - 1]
    subprocess.run(['adb', 'shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2)])
    print('tapped', t)
else:
    print('NOT FOUND', t)
PY
}

screen_texts() {
  dump
  python3 -c "
import re;x=open('ui.xml',encoding='utf-8',errors='ignore').read()
print('   screen:', [t for t in re.findall(r' text=\"([^\"]+)\"',x)][:25])"
}

check() {
  if adb shell pidof $PKG >/dev/null; then echo "=== OK: $1"; else echo "=== DEAD: $1"; FAIL=1; fi
  if adb logcat -d -b crash | grep -q "FATAL EXCEPTION"; then echo "=== CRASH after: $1"; FAIL=1; fi
}

# Type in the Home search box, open the first team result, scroll its page, go back.
search_flow() {
  tap_text "Home"; sleep 3
  tap_text "Search teams or competitions"; sleep 2
  adb shell input text "madrid"; sleep 8; check "$1 search typed"; screen_texts
  adb shell input keyevent 111; sleep 1   # hide keyboard
  tap_text "Team"; sleep 20; check "$1 team page"; screen_texts
  adb shell input swipe 500 1600 500 400 300; sleep 3; screen_texts
  adb shell input swipe 500 1600 500 400 300; sleep 3; check "$1 team page scrolled"; screen_texts
  adb shell input keyevent 4; sleep 4; check "$1 back from team"
  tap_text "Clear search"; sleep 3; check "$1 search cleared"
}

run_pass() {
  label=$1
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.MainActivity >/dev/null
  sleep 25; check "$label launch"; screen_texts
  for tab in Predictions Live Favourites Settings Home; do
    tap_text "$tab"; sleep 6; check "$label tab $tab"
  done
  sleep 5
  # Filter to football so the team/table/H2H sections are exercised with real data.
  tap_text "BTTS Yes"; sleep 3; check "$label filter BTTS Yes"; screen_texts
  tap_text "BTTS Yes"; sleep 2
  tap_text "Football"; sleep 4; screen_texts
  # Open a match: tap the "X" (draw odds) box inside the first football card, else the first "1" box.
  tap_text "X"; sleep 2
  dump; grep -q '"Overview"' ui.xml || { tap_text "1"; sleep 2; }
  sleep 8; check "$label open match"; screen_texts
  tap_text "Insights"; sleep 20; check "$label match tab Insights"; screen_texts
  adb shell input swipe 500 1600 500 500 300; sleep 2; screen_texts
  for t in Teams Table Form H2H BTTS Squad Venue Odds Overview; do
    tap_text "$t"; sleep 6; check "$label match tab $t"; screen_texts
  done
  adb shell input swipe 500 1500 500 700 300; sleep 2; screen_texts
  adb shell input keyevent 4; sleep 4; check "$label back to list"
  search_flow "$label"
  # Rotate to landscape and back (configuration change)
  adb shell settings put system accelerometer_rotation 0
  adb shell settings put system user_rotation 1; sleep 5; check "$label landscape"
  adb shell settings put system user_rotation 0; sleep 5; check "$label portrait"
}

adb install -r "$APK" || { echo "INSTALL FAILED"; exit 1; }
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb logcat -c

echo "########## PASS 1: ONLINE ##########"
run_pass "online"

echo "########## PASS 2: OFFLINE (cached data) ##########"
adb shell svc wifi disable; adb shell svc data disable; adb shell cmd connectivity airplane-mode enable 2>/dev/null
sleep 5
run_pass "offline"
adb shell cmd connectivity airplane-mode disable 2>/dev/null; adb shell svc wifi enable; adb shell svc data enable

echo "########## PASS 3: FRESH INSTALL WITH NO INTERNET (empty data) ##########"
adb shell pm clear $PKG
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell svc wifi disable; adb shell svc data disable; adb shell cmd connectivity airplane-mode enable 2>/dev/null
sleep 3
adb shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 20; check "empty-offline launch"; screen_texts
for tab in Predictions Live Favourites Settings Home; do tap_text "$tab"; sleep 5; check "empty-offline tab $tab"; done
search_flow "empty-offline"
adb shell cmd connectivity airplane-mode disable 2>/dev/null; adb shell svc wifi enable; adb shell svc data enable

echo "########## MEMORY ##########"
adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS|TOTAL RSS|Java Heap|Native Heap" | head -5

echo "=================== CRASH BUFFER ==================="
adb logcat -d -b crash
echo "=================== APP WARNINGS/ERRORS ==================="
adb logcat -d | grep -E "MatchRepository|TeamRepository|OneXBet|HomeViewModel|AndroidRuntime: (FATAL|java|Caused)" | tail -60

if [ $FAIL -ne 0 ]; then echo "SMOKE TEST FAILED"; exit 1; fi
echo "SMOKE TEST PASSED"
