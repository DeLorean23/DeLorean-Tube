# DeLorean Tube

**Download:** unter **[Releases](../../releases)**

YouTube-App für Android (ab Android 5.0), als zwei einzelne APKs: `DeLoreanTube-32bit.apk` (armeabi-v7a) und `DeLoreanTube-64bit.apk` (arm64-v8a).

Created by DeLorean23

## Funktionen

- **Mit Google anmelden** über die normale Google-Kontoauswahl (Konto antippen, fertig)
- Mit Konto: Abo-Feed, Liste deiner Abos, deine Playlists, „Mag ich“-Videos, Abonnieren/Deabonnieren, Liken
- Startseite mit Kategorien (Trends, Musik, Gaming, Live, Podcasts, Filme & Serien), Suche, Kanäle, Playlists
- **Keine Werbung**: Videos werden direkt geladen, es wird gar keine Werbung abgerufen
- **Hintergrund-Wiedergabe**: Ton läuft weiter, wenn die App im Hintergrund ist oder der Bildschirm aus ist
  (Steuerung über Benachrichtigung und Sperrbildschirm). Im Hintergrund wird nur der Ton geladen.
- Schalter **„Nur Ton“** (spart Daten), Qualitätsauswahl (getrennt für WLAN und mobile Daten)
- **Download** mit Auswahl:
  - **Audio (MP3)**, 192 kbit/s, mit Titel und Interpret → Ordner `Music/DeLorean Tube`
  - **Video (MP4)** mit Nachfrage nach der Auflösung → Ordner `Movies/DeLorean Tube`
- YouTube-Links aus anderen Apps öffnen oder über „Teilen“ an die App schicken
- Lokaler Verlauf, **Weiterschauen** mit rotem Fortschrittsbalken
- **Playlists** anlegen und verwalten (auf dem Handy oder im YouTube-Konto), **Später ansehen**,
  Warteschlange, Autoplay, Zufall und Wiederholen
- Mini-Player, Bild-im-Bild, Doppeltippen ±10 s, Geschwindigkeit, Untertitel, Kommentare,
  Suchvorschläge, Schlaf-Timer, Shorts
- **SponsorBlock** (Sponsor-Teile überspringen) und **Dislikes** (Return YouTube Dislike)
- **Downloads-Seite**, Cover-Bild in der MP3, ganze Playlist herunterladen, Kapitel als einzelne MP3s,
  nur einen Abschnitt (von–bis) herunterladen
- **Equalizer** mit Bass-Boost und gleicher Lautstärke für alle Videos, **A–B-Schleife**
- **Wecker**: mit einem Video oder einer Playlist aufwachen (wird langsam lauter, ohne Internet normaler Weckton)
- **Startbildschirm-Widget** mit Vorschaubild, Zurück / Play-Pause / Weiter
- **Chromecast**
- **Fernseher** (Android TV / Google TV): erscheint im TV-Startbildschirm, Bedienung mit der Fernbedienung
- **Android Auto** (nur Ton)

## Fernseher (z. B. TCL Android TV)

Die normale APK (meist `DeLoreanTube-32bit.apk`) auf dem Fernseher installieren, z. B. mit der App
„Downloader“ oder per USB-Stick und einem Dateimanager. Danach ist „DeLorean Tube“ im TV-Startbildschirm.

- Steuerkreuz: durch die Listen gehen, OK = öffnen
- Im Video: links/rechts = 10 s zurück/vor, runter oder Menü = Steuerleiste
- In der Steuerleiste blendet der Vollbild-Knopf die Infos und Knöpfe (Download, Untertitel, Warteschlange …) ein
- Zurück: erst Steuerleiste zu, dann Infos zu, dann zur Liste

## Android Auto

Android Auto zeigt Apps, die nicht aus dem Play Store kommen, erst nach einer einmaligen Einstellung:

1. Auf dem Handy die **Android Auto**-Einstellungen öffnen
   (Einstellungen → Verbundene Geräte → Verbindungseinstellungen → Android Auto).
2. Ganz unten **10× auf „Version“** tippen und den Entwicklermodus bestätigen.
3. Oben rechts auf **⋮ → Entwicklereinstellungen**.
4. **„Unbekannte Quellen“** einschalten.
5. Handy neu mit dem Auto verbinden. „DeLorean Tube“ ist jetzt bei den Medien-Apps.

Im Auto: Später ansehen, Downloads, Verlauf, Playlists, Abos, Mag ich und Suche (auch per Sprache:
„Spiele … auf DeLorean Tube“). Es läuft nur Ton, ein Video spielt die ganze Liste ab dort.

## Google-Login einrichten (einmalig, ca. 10 Minuten)

Google erlaubt die Anmeldung nur für Apps, die in der Google Cloud Console eingetragen sind.
Das ist kostenlos.

1. https://console.cloud.google.com öffnen und mit deinem Google-Konto anmelden.
2. Oben **Projekt auswählen → Neues Projekt**, Name z. B. `DeLorean Tube`, **Erstellen**.
3. Links **APIs & Dienste → Bibliothek**, nach **YouTube Data API v3** suchen, **Aktivieren**.
4. Links **APIs & Dienste → OAuth-Zustimmungsbildschirm** (bzw. **Google Auth Platform**):
   - App-Name `DeLorean Tube`, deine E-Mail als Support- und Entwickler-Mail, Zielgruppe **Extern**.
   - Unter **Zielgruppe / Testnutzer** deine Google-Mail-Adresse hinzufügen.
   - Danach **App veröffentlichen** („In Produktion“), sonst musst du dich alle 7 Tage neu anmelden.
     Beim Anmelden zeigt Google dann „Google hat diese App nicht überprüft“ → **Erweitert → Weiter zu DeLorean Tube**.
5. Links **Clients** (bzw. **Anmeldedaten → Anmeldedaten erstellen → OAuth-Client-ID**):
   - Anwendungstyp: **Android**
   - Paketname: `de.delorean23.tube`
   - SHA-1-Zertifikatsfingerabdruck:
     ```
     38:A3:F8:6F:E6:57:CE:9B:F9:F8:0F:49:A9:BF:C1:4F:80:F6:38:64
     ```
   - **Erstellen**. Eine Client-ID musst du nirgends eintragen, Google erkennt die App an Paketname + SHA-1.
6. App öffnen → oben rechts aufs Konto-Symbol → Google-Konto auswählen → Zugriff erlauben.

Der SHA-1 gehört zum Schlüssel `youtube/signing.jks` im Repo. Wird der Schlüssel ausgetauscht,
muss der neue SHA-1 in Schritt 5 eingetragen werden.

## APK bauen

Bei jedem Push baut GitHub Actions (Workflow **YouTube-App bauen**) automatisch die APK und
veröffentlicht unter **Releases** die beiden einzelnen Dateien `DeLoreanTube-32bit.apk` und `DeLoreanTube-64bit.apk`.

Android Studio: `gradlew :youtube:assembleRelease` → `youtube/build/outputs/apk/release/youtube-armeabi-v7a-release.apk` und `youtube-arm64-v8a-release.apk`

## Grenzen

- Die Startseiten-Empfehlungen und der Wiedergabeverlauf deines Kontos sind über die offizielle
  YouTube-Schnittstelle nicht abrufbar. Statt „Empfohlen“ gibt es die Kategorien und den Abo-Feed,
  der Verlauf wird in der App gespeichert.
- Die MP3-Umwandlung läuft auf dem Handy; auf alten Geräten dauert ein 4-Minuten-Lied ca. 1–3 Minuten.
- MP4 gibt es bis zur höchsten H.264-Qualität, die YouTube anbietet (meist 1080p). 1440p/4K liefert
  YouTube nur als VP9/AV1, das passt nicht in eine normale MP4-Datei für alte Handys.
- Ändert YouTube etwas an der Seite, muss die NewPipeExtractor-Version in `youtube/build.gradle`
  aktualisiert werden (gleiche Version wie die aktuelle NewPipe-App).
- Downloads nur für den privaten Gebrauch; YouTubes Nutzungsbedingungen erlauben Downloads eigentlich nicht.

## Bibliotheken

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0)
- jump3r / LAME (Java-MP3-Encoder, LGPL, siehe `src/main/java/de/delorean23/tube/lame/COPYING-LGPL.txt`)
- AndroidX Media3, Glide, OkHttp, Google Play Services Auth / Cast
- [SponsorBlock](https://sponsor.ajay.app) und [Return YouTube Dislike](https://returnyoutubedislike.com) (Daten über deren freie Schnittstellen)


## Weitere Apps von DeLorean23

- [AdBlock Browser](https://github.com/DeLorean23/Browser-)
- [DeLorean Cloud – Xbox Cloud Gaming Android App](https://github.com/DeLorean23/Xbox-Cloude-Gaming-Android-App-32bit)
