#!/usr/bin/env bash
# Cognitive-load probe: counts interactive targets (choices) and text blocks (information
# chunks) per screen from the device's accessibility tree — a real measurement rather than a
# read of the source. Works on FLAG_SECURE surfaces too (uiautomator reads the a11y tree, not
# the framebuffer), which is why Recovery can be measured when it cannot be screenshotted.
#
# Usage: ui-cognitive-load-probe.sh <serial> <tab> [<tab> ...]
set -u

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
SERIAL="$1"; shift
PKG=com.example.myexodus

for TAB in "$@"; do
  "$ADB" -s "$SERIAL" shell "am start -n $PKG/.MainActivity --es extra_start_tab $TAB" >/dev/null 2>&1
  sleep 2.5
  "$ADB" -s "$SERIAL" shell "uiautomator dump /data/local/tmp/cl-$TAB.xml" >/dev/null 2>&1
  "$ADB" -s "$SERIAL" pull "/data/local/tmp/cl-$TAB.xml" "/tmp/cl-$TAB.xml" >/dev/null 2>&1
  python3 - "$TAB" "/tmp/cl-$TAB.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
tab, path = sys.argv[1], sys.argv[2]
try:
    root = ET.parse(path).getroot()
except Exception as e:
    print(f"{tab:<10} PARSE FAIL {e}"); sys.exit()
clickable, texts, screen_h = 0, [], 0
for n in root.iter('node'):
    if n.get('clickable') == 'true' and n.get('enabled') != 'false':
        clickable += 1
    # Count leaf text only — a container repeating its child's text is not a second chunk.
    if not list(n):
        t = (n.get('text') or '').strip()
        if t:
            texts.append(t)
    b = n.get('bounds') or ''
    if b:
        try: screen_h = max(screen_h, int(b.replace('][', ',').strip('[]').split(',')[3]))
        except (ValueError, IndexError): pass
# Hick's law: decision time grows with log2(choices). Reported, not judged.
import math
bits = round(math.log2(clickable), 2) if clickable > 1 else 0.0
print(f"{tab:<10} choices={clickable:<3} text_blocks={len(texts):<3} hick_bits={bits:<5} viewport_h={screen_h}dp")
PY
done
