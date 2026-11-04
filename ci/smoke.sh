#!/bin/bash
# Emulator smoke test: install, launch, open every tab and a match, report crashes.
set +e
PKG=com.matchday.app
APK=app/build/outputs/apk/debug/app-debug.apk

alive() { adb shell pidof $PKG >/dev/null && echo "ALIVE" || echo "DEAD"; }

tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  python3 - "$1" <<'PY'
import re, sys, subprocess
t = sys.argv[1]
x = open('ui.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    txt = re.search(r' text="([^"]*)"', n); desc = re.search(r'content-desc="([^"]*)"', n)
    if (txt and txt.group(1) == t) or (desc and desc.group(1) == t):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            subprocess.run(['adb', 'shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)])
            print('tapped', t); sys.exit(0)
print('NOT FOUND', t)
PY
}

step() { echo "=== STEP: $1 -> $(alive)"; }

adb install -r "$APK" || { echo "INSTALL FAILED"; exit 1; }
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity
sleep 20; step "launch (20s)"
sleep 20; step "launch (40s)"
for tab in Predictions Live Favourites Settings Home; do
  tap_text "$tab"; sleep 8; step "tab $tab"
done
# open first match card: tap the first 1/X/2 odds label or a team row in the list area
adb shell input tap 540 900; sleep 10; step "tap list item (match page?)"
for t in Odds Teams Squad Conditions Overview; do tap_text "$t"; sleep 4; step "detail tab $t"; done
adb shell input keyevent 4; sleep 4; step "back"

echo "=================== CRASH BUFFER ==================="
adb logcat -d -b crash
echo "=================== APP ERRORS ==================="
adb logcat -d | grep -E "AndroidRuntime|FATAL EXCEPTION|E/.*(matchday|Compose|Room|WM-)" | tail -150
echo "=================== UI DUMP (last screen) ==================="
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
python3 -c "
import re;x=open('ui.xml',encoding='utf-8',errors='ignore').read()
print([t for t in re.findall(r' text=\"([^\"]+)\"',x)][:80])"
