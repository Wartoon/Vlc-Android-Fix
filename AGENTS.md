# Entwicklungsanweisungen für VLC-Dual

Diese Regeln gelten für dieses Repository und alle darin
enthaltenen Projektdateien. Bestehende Paketnamen, Signaturen, Datenbanken und
die Backup-Kompatibilität müssen erhalten bleiben.

## Lokale Windows-Umgebung

- Für lokale Windows-Builds Microsoft JDK 17 und Gradle 9.3.1 verwenden.
  Unter Windows die lokale Konfiguration aus `.gradle/codex-local.md` einlesen,
  falls vorhanden.
- Unter Windows Gradle ausschließlich über den in `.gradle/codex-local.md` festgelegten Wrapper aufrufen.
- Für jeden lokalen Gradle-Aufruf `GRADLE_USER_HOME` ausschließlich auf
  `.gradle/codex-user-home` setzen. Der Cache gehört ausschließlich
  in dieses durch `.gitignore` ignorierte Verzeichnis innerhalb des Repositories.
- `GRADLE_USER_HOME` nur im ausführenden Prozess setzen und den vorherigen Wert
  mittels `try/finally` wiederherstellen; war die Variable vorher nicht gesetzt,
  muss sie danach wieder entfernt werden.
- Windows-PATH, JAVA_HOME, Registry und Dateiberechtigungen nicht dauerhaft
  verändern. Keine dauerhaften Windows-Umgebungsänderungen vornehmen.
- Diese Windows-Konfiguration nicht in Linux- oder Codex-Cloud-Umgebungen
  verwenden. Unter Linux/Cloud die vorhandene CI-Konfiguration des Repositories verwenden,
  insbesondere `.github/workflows/build-fixed-apk.yml`.

Das konkrete PowerShell-Beispiel für die lokale Versionsprüfung steht in
`.gradle/codex-local.md`.

## Fehleranalyse, Änderungen und Tests

- Vor Korrekturen immer die tatsächliche Fehlerursache anhand des Codes
  untersuchen; keine unbelegten Fehlerannahmen als Grundlage für Änderungen
  verwenden.
- Geeignete Regressionstests für Korrekturen und Verhaltensänderungen erstellen.
- Paketnamen, Signaturen, Datenbanken und Backup-Kompatibilität erhalten.
- Kodi nicht verändern.
- Bei relevanten Änderungen SMB, internen Speicher, Wiedergabelisten,
  Hintergrundwiedergabe und Backup/Restore mitprüfen.
- Routinearbeiten selbstständig durchführen. Keine destruktiven Änderungen
  oder produktiven Releases ohne ausdrückliche Freigabe.
- Tests, Builds und Korrekturen erst nach tatsächlicher Überprüfung als
  erfolgreich melden. Durchgeführte Prüfungen und ihre Ergebnisse nennen;
  nicht ausgeführte oder fehlgeschlagene Prüfungen ausdrücklich kennzeichnen.
