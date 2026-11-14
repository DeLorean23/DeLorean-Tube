#!/bin/bash
# Emulator-Test für DeLorean Tube. Aufruf: emu-test.sh <apk>
set -x
OUT=emu-out
mkdir -p $OUT
APK=${1:-DeLoreanTube-32bit.apk}
adb install -r -g $APK > $OUT/install.txt 2>&1
adb shell settings put global window_animation_scale 0
adb logcat -c
alive() { adb shell pidof de.delorean23.tube > $OUT/pid-$1.txt || echo "APP LAEUFT NICHT MEHR" > $OUT/pid-$1.txt; }
shot() { adb exec-out screencap -p > $OUT/$1.png; }
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
DENS=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
dp() { echo $(( $1 * DENS / 160 )); }
# Auf einen sichtbaren Text tippen (über die Bildschirm-Beschreibung)
tapText() {
  local NAME="$1"
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  B=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -F "text=\"$1\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
  set -- $B
  if [ -n "$4" ]; then adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); return 0; fi
  echo "nicht gefunden: $NAME" >> $OUT/nicht-gefunden.txt; return 1
}

adb shell am start -W -n de.delorean23.tube/.MainActivity
sleep 30
shot 01-start
alive start

# Suche: Lupe oben rechts (zweites Symbol von rechts), Text tippen -> Vorschläge
adb shell input tap $((W - $(dp 76))) $(dp 26)
sleep 2
adb shell input text "delorean"
sleep 6
shot 02-vorschlaege
adb shell input keyevent KEYCODE_ENTER
sleep 15
shot 03-suche
alive suche

# langes Drücken auf das erste Ergebnis -> Menü
adb shell input swipe $((W/2)) $((H*35/100)) $((W/2)) $((H*35/100)) 900
sleep 3
shot 04-menue
alive menue
adb shell input keyevent KEYCODE_BACK
sleep 2

# erstes Ergebnis öffnen -> Videoseite
adb shell input tap $((W/2)) $((H*35/100))
sleep 30
shot 05-video
alive video
# Kopfteil-Knöpfe nach links wischen (weitere Knöpfe sichtbar)
adb shell input swipe $((W*85/100)) $((H*58/100)) $((W*10/100)) $((H*58/100)) 300
sleep 2
shot 06-knoepfe
# Warteschlange antippen ist schwer zu treffen: Video verkleinern
adb shell input keyevent KEYCODE_BACK
sleep 6
shot 07-mini
alive mini
adb shell input keyevent KEYCODE_BACK
sleep 2

# Bibliothek (4. Tab)
adb shell input tap $((W*7/8)) $((H - $(dp 28)))
sleep 6
shot 08-bibliothek
alive bibliothek

# Bibliothek: Downloads (3. Zeile) öffnen
adb shell input tap $((W/2)) $(dp 227)
sleep 4
shot 08b-downloads
alive downloads
adb shell input keyevent KEYCODE_BACK
sleep 2
# Bibliothek: Wecker
tapText "Wecker" || adb shell input tap $((W/2)) $(dp 367)
sleep 3
shot 08e-wecker
alive wecker
# neuer Wecker (+ oben rechts)
adb shell input tap $((W - $(dp 28))) $(dp 26)
sleep 2
shot 08f-wecker-neu
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell input keyevent KEYCODE_BACK
sleep 2
# Bibliothek: Einstellungen
tapText "Einstellungen" || adb shell input tap $((W/2)) $(dp 437)
sleep 3
shot 08c-einstellungen
alive einstellungen
adb shell input swipe $((W/2)) $((H*80/100)) $((W/2)) $((H*30/100)) 300
sleep 2
shot 08d-einstellungen2
adb shell input keyevent KEYCODE_BACK
sleep 2

# Kontomenü (oben rechts) -> Einstellungen
adb shell input tap $((W - $(dp 28))) $(dp 26)
sleep 2
shot 09-kontomenue
adb shell input keyevent KEYCODE_BACK
sleep 1

# Shorts (2. Tab)
adb shell input tap $((W*3/8)) $((H - $(dp 28)))
sleep 40
shot 10-shorts
alive shorts
adb shell input keyevent KEYCODE_BACK
sleep 3

# Video direkt per Link öffnen, dann Home (Bild-im-Bild)
adb shell am start -W -a android.intent.action.VIEW -d "https://www.youtube.com/watch?v=jNQXAC9IVRw" -n de.delorean23.tube/.PlayerActivity
sleep 30
shot 11-link
alive link
adb shell input keyevent KEYCODE_HOME
sleep 5
shot 12-home
alive home

# Wecker klingeln lassen (Später ansehen ist leer -> Weckton), dann Stopp
adb shell am broadcast -a de.delorean23.tube.ALARM_RING -n de.delorean23.tube/.AlarmReceiver --ei id 99
sleep 8
shot 13-wecker-klingelt
alive wecker-klingelt
tapText "Stopp"
sleep 3
shot 14-wecker-stopp
adb shell dumpsys notification --noredact | grep -A3 "de.delorean23.tube" | head -40 > $OUT/benachrichtigungen.txt

# Widget und Android Auto: sind sie beim System angemeldet?
adb shell dumpsys appwidget | grep -i -A3 "delorean" > $OUT/widget.txt 2>&1
adb shell cmd package query-services -a android.media.browse.MediaBrowserService > $OUT/auto-dienst.txt 2>&1
adb shell dumpsys package de.delorean23.tube | grep -i -A2 "MediaBrowserService\|MediaLibraryService\|LEANBACK" >> $OUT/auto-dienst.txt 2>&1

adb logcat -d -b crash > $OUT/crash.txt 2>&1
adb logcat -d -v time > $OUT/logcat-alles.txt 2>&1
grep -E "AndroidRuntime|FATAL|de.delorean23|ExoPlayer|MediaSession|tube-io|System.err|ActivityManager: (Start|Process|Force)" $OUT/logcat-alles.txt | tail -800 > $OUT/logcat-app.txt
exit 0
