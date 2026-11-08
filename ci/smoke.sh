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

dump_diag() { echo "--- $1" >> diag.log; adb logcat -d | grep -E "needed repair|skipped|could be listed|Skipping match|Scoreboard" | head -20 >> diag.log; }

# Open the first match of a sport and check its Markets and Odds tabs.
sport_flow() {
  adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 3
  tap_text "Home"; sleep 3
  tap_text "$2"; sleep 4; check "$1 filter $2"; screen_texts
  open_card; sleep 10; check "$1 open $2 match"; screen_texts
  tap_text "Markets"; sleep 8; check "$1 $2 Markets"; screen_texts
  adb shell input swipe 500 1600 500 500 300; sleep 2; screen_texts
  adb shell input keyevent 4; sleep 4
  tap_text "$2"; sleep 2
}

tap_prefix() {
  dump
  python3 - "$1" <<'PY'
import re, sys, subprocess
t = sys.argv[1]
x = open('ui.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    txt = re.search(r' text="([^"]*)"', n)
    if txt and txt.group(1).startswith(t):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            subprocess.run(['adb', 'shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2)])
            print('tapped', txt.group(1)); break
else:
    print('NOT FOUND prefix', t)
PY
}

open_card() {
  tap_prefix "Today "; sleep 3
  dump; grep -q '"Overview"' ui.xml || { tap_prefix "Tomorrow "; sleep 3; }
  dump; grep -q '"Overview"' ui.xml || { tap_prefix "Next "; sleep 3; }
  dump; grep -q '"Overview"' ui.xml && echo "match page opened" || echo "MATCH PAGE NOT OPENED"
}

# Open the first match in play and read its Live tab (stats, intelligence, live markets).
live_flow() {
  adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 3
  tap_text "Live"; sleep 5
  tap_prefix "LIVE"; sleep 25; check "$1 live match page"; screen_texts
  adb shell input swipe 500 1600 500 500 300; sleep 2; screen_texts
  adb shell input swipe 500 1600 500 500 300; sleep 2; screen_texts
  adb shell input swipe 500 1600 500 500 300; sleep 2; screen_texts
  adb shell input keyevent 4; sleep 4; check "$1 back from live match"
}

run_pass() {
  label=$1
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.MainActivity >/dev/null
  sleep 25; check "$label launch"; screen_texts
  dump_diag "$label launch"
  for tab in Predictions Live Favourites Settings Home; do
    tap_text "$tab"; sleep 6; check "$label tab $tab"
    if [ "$tab" = "Live" ]; then screen_texts; fi
  done
  sleep 70; dump_diag "$label after live poll"; screen_texts
  sleep 5
  # Filter to football so the team/table/H2H sections are exercised with real data.
  tap_text "BTTS Yes"; sleep 3; check "$label filter BTTS Yes"; screen_texts
  tap_text "BTTS Yes"; sleep 2
  tap_text "Football"; sleep 4; screen_texts
  # Open a match: tap the kick-off label of the first card ("Today 22:45" / "Tomorrow 01:00").
  open_card
  sleep 8; check "$label open match"; screen_texts
  tap_text "Markets"; sleep 10; check "$label match tab Markets"; screen_texts
  adb shell input swipe 500 1600 500 500 300; sleep 2; screen_texts
  adb shell input swipe 500 1600 500 500 300; sleep 2; screen_texts
  tap_text "Insights"; sleep 20; check "$label match tab Insights"; screen_texts
  adb shell input swipe 500 1600 500 500 300; sleep 2; screen_texts
  for t in Teams Table Form H2H BTTS Squad Venue Odds; do
    tap_text "$t"; sleep 6; check "$label match tab $t"; screen_texts
  done
  # Enter two 1xBet prices by hand and save (no API involved).
  tap_text "Delete"; sleep 2
  tap_text "Enter 1xBet odds"; sleep 3; check "$label 1xBet form"
  tap_text "Draw"; sleep 1; adb shell input text "3.40"; sleep 1
  adb shell input keyevent 111; sleep 1
  tap_text "Save & analyse"; sleep 4; check "$label 1xBet saved"; screen_texts
  tap_text "Overview"; sleep 4; check "$label match tab Overview"; screen_texts
  adb shell input swipe 500 1500 500 700 300; sleep 2; screen_texts
  adb shell input keyevent 4; sleep 4; check "$label back to list"
  search_flow "$label"
  live_flow "$label"
  sport_flow "$label" "Basketball"
  sport_flow "$label" "Tennis"
  # Rotate to landscape and back (configuration change)
  adb shell settings put system accelerometer_rotation 0
  adb shell settings put system user_rotation 1; sleep 5; check "$label landscape"
  adb shell settings put system user_rotation 0; sleep 5; check "$label portrait"
}

# ---- Update test: install the currently published release, use it, then install this build over it ----
if [ -s prev.apk ] && adb install -r prev.apk; then
  echo "Previous release installed"
  adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
  adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 30
  adb shell am force-stop $PKG
  if adb install -r "$APK" 2>&1 | tee upgrade.txt | grep -q Success; then
    echo "UPGRADE OVER EXISTING APP: OK (no uninstall needed)"
    adb shell svc wifi disable; adb shell svc data disable; adb shell cmd connectivity airplane-mode enable 2>/dev/null; sleep 3
    adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 15
    check "after upgrade (offline, data kept)"; screen_texts
    adb shell cmd connectivity airplane-mode disable 2>/dev/null; adb shell svc wifi enable; adb shell svc data enable; sleep 3
  else
    echo "UPGRADE OVER EXISTING APP: REFUSED ($(cat upgrade.txt | tail -1)) - previous release used another signing key"
    adb uninstall $PKG >/dev/null
    adb install -r "$APK" || { echo "INSTALL FAILED"; exit 1; }
  fi
else
  echo "No previous release to upgrade from"
  adb install -r "$APK" || { echo "INSTALL FAILED"; exit 1; }
fi
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
echo "=================== DIAG ==================="
cat diag.log 2>/dev/null | head -80

if [ $FAIL -ne 0 ]; then echo "SMOKE TEST FAILED"; exit 1; fi
echo "SMOKE TEST PASSED"
