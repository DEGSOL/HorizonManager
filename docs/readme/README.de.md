# Hovq 0.6.3

[Русский](../../README.md) · [English](README.en.md) · **Deutsch** · [Français](README.fr.md) · [Italiano](README.it.md) · [Español](README.es.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [简体中文](README.zh.md) · [Қазақша](README.kk.md) · [Татарча](README.tt.md)

Hovq ist eine Android-App, mit der Sie [Horizon](https://github.com/Thysrael/Horizon) auf Ihrem eigenen VPS installieren und verwalten sowie Nachrichtenausgaben lesen können. Horizon sammelt Artikel auf dem Server, entfernt Dubletten und nutzt das gewählte KI-Modell für Bewertung, Übersetzung und Erstellung der Ausgabe.

[Signierte APK herunterladen und Versionshinweise lesen](https://github.com/DEGSOL/HorizonManager/releases/latest)

## Funktionen

- RSS, Telegram, Reddit und Hacker News, Bewertungsprofile, Titelbilder und zusätzliche Artikelinformationen auf Abruf.
- Manueller Start, Start nach Wochentagen oder in festen Intervallen ab einer Uhrzeit. Der Zeitplan läuft auf dem VPS.
- Feed mit Suche, Filtern und ungelesenen Artikeln, Favoriten, Archiv und heruntergeladenen Ausgaben zum Offline-Lesen.
- Aktivitätsprotokoll und verschlüsselte Sicherungen der Telefon- und Servereinstellungen.

## Installation und erste Ausgabe

**Zur Installation genügt ein eigener VPS mit Ubuntu Server 24.04 LTS oder Debian 12 und SSH-Zugang.** Verbinden Sie sich in Hovq mit dem VPS und bestätigen Sie die Installation. Die App installiert Horizon, den Agenten und die benötigten Abhängigkeiten automatisch.

1. Installieren Sie die APK auf Android 8.0 oder neuer. Bereiten Sie einen VPS mit Ubuntu Server 24.04 LTS oder Debian 12, systemd, SSH und Zugriff auf Paketquellen des Betriebssystems, GitHub und PyPI vor. Das Installationsprogramm prüft die Architektur sowie mindestens 432 MiB RAM und 1,5 GiB freien Speicherplatz. Die Verarbeitung kann mehr Ressourcen benötigen.
2. Fügen Sie den Server in Hovq hinzu, geben Sie ein SSH-Passwort ein oder wählen Sie einen privaten Schlüssel. Prüfen Sie den Fingerabdruck im Kundenportal Ihres Anbieters. SSH muss die Weiterleitung zu `127.0.0.1:18777` auf dem VPS erlauben. Bestätigen Sie die Installation und geben Sie bei Bedarf das sudo-Passwort ein. Eine Verbindung zu einem vorhandenen Agenten ist ebenfalls möglich.
3. Legen Sie KI-Anbieter, Modell und API-Schlüssel fest. Für OpenRouter und UnoRouter lässt sich der Modellkatalog in der App laden. Fügen Sie eine Quelle hinzu, wählen Sie ein Verarbeitungsprofil und starten Sie die Sammlung.
4. Öffnen Sie die Ausgabe im Feed. Den Zeitplan legen Sie in den Nachrichteneinstellungen fest, die Ausgabesprache in den KI-Einstellungen. Oberfläche und Ausgaben unterstützen alle oben aufgeführten Sprachen.

## Benachrichtigungen und Updates

Aktivieren Sie Benachrichtigungen unter **Einstellungen → Oberfläche → Benachrichtigungen** und erlauben Sie sie in Android. Hovq hält eine direkte Verbindung zum VPS über SSH oder das eingerichtete HTTPS aufrecht. Android zeigt während der Überwachung eine stille Dienstbenachrichtigung. Für fertige Ausgaben erscheint eine eigene Meldung. Nach einem Verbindungsabbruch verbindet sich Hovq erneut und prüft auf neue Ausgaben.

Die APK lässt sich über Hovq 0.4.5–0.6.1 mit demselben Zertifikat installieren. Für 0.4.4 und älter ist eine Neuinstallation erforderlich. Aktualisieren Sie auf einem vorhandenen VPS den Agenten in Hovq auf 0.1.21. Er erkennt unterschiedlich formulierte Berichte über dasselbe Ereignis auch bei Anreicherung auf Abruf. Die Einstellung zur thematischen Deduplizierung des Profils gilt weiterhin.

## Aus dem Quellcode bauen

Verwenden Sie JDK 17 oder 21, Android SDK 36 und Build Tools 36.0.0. Setzen Sie den SDK-Pfad über `ANDROID_HOME` oder `android/local.properties`.

```bash
cd android
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

Für einen signierten Build setzen Sie die Variablen

`HM_RELEASE_KEYSTORE`, `HM_RELEASE_KEY_ALIAS`, `HM_RELEASE_STORE_PASSWORD`, `HM_RELEASE_KEY_PASSWORD`

[Quellcode der App und des Agenten für diese Version](https://github.com/DEGSOL/HorizonManager/tree/v0.6.3) · [Architektur und API](https://github.com/DEGSOL/HorizonManager/blob/v0.6.3/docs/ARCHITECTURE.md) · [Tests](https://github.com/DEGSOL/HorizonManager/blob/v0.6.3/docs/TESTING.md)
