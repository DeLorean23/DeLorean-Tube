#!/bin/bash
# Fernseher-Test (Android TV Emulator): Start über den TV-Launcher, Bedienung nur mit der Fernbedienung.
set -x
OUT=emu-out-tv
mkdir -p $OUT
APK=${1:-DeLoreanTube-Test-32bit.apk}
adb install -r -g $APK > $OUT/install.txt 2>&1
adb logcat -c
alive() { adb shell pidof de.delorean23.tube > $OUT/pid-$1.txt || echo "APP LAEUFT NICHT MEHR" > $OUT/pid-$1.txt; }
shot() { adb exec-out screencap -p > $OUT/$1.png; }
key() { adb shell input keyevent $1; sleep ${2:-1}; }

# Ist die App im TV-Launcher (LEANBACK_LAUNCHER) sichtbar?
adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER > $OUT/tv-launcher.txt 2>&1

adb shell monkey -p de.delorean23.tube -c android.intent.category.LEANBACK_LAUNCHER 1
sleep 30
shot 01-start
alive start

# mit dem Steuerkreuz durch die Startseite
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_DOWN
shot 02-fokus
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_RIGHT
shot 03-fokus2
alive fokus

# Suche öffnen und tippen
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_UP
shot 04-oben
key KEYCODE_DPAD_CENTER 2
adb shell input text "delorean"
sleep 2
key KEYCODE_ENTER 20
shot 05-suche
alive suche
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_DOWN
shot 06-suche-fokus

# Video öffnen (OK), dann Steuerleiste und Infos
key KEYCODE_DPAD_CENTER 30
shot 07-video
alive video
key KEYCODE_DPAD_RIGHT 2
key KEYCODE_DPAD_DOWN 2
shot 08-steuerleiste
key KEYCODE_BACK 2
key KEYCODE_BACK 3
shot 09-zurueck
alive zurueck

# Shorts direkt öffnen (braucht root, weil nicht von außen startbar), hoch/runter
adb root > /dev/null 2>&1; sleep 4; adb wait-for-device
adb shell am start -n de.delorean23.tube/.ShortsActivity
sleep 30
shot 10-shorts
key KEYCODE_DPAD_DOWN 15
shot 11-shorts-weiter
alive shorts
key KEYCODE_BACK 2

adb logcat -d -b crash > $OUT/crash.txt 2>&1
adb logcat -d -v time > $OUT/logcat-alles.txt 2>&1
grep -E "AndroidRuntime|FATAL|de.delorean23|ActivityManager: (Start|Process|Force)" $OUT/logcat-alles.txt | tail -500 > $OUT/logcat-app.txt
exit 0
