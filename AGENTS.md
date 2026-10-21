# Entwicklungsanweisungen für VLC-TV

Diese verbindlichen Regeln gelten für Arbeiten in diesem Repository.

## Lokale Windows-Entwicklung

Die folgenden Windows-Regeln gelten ausschließlich für lokale Windows-Umgebungen.
Unter Windows die repositoryrelative Datei
`.gradle/codex-local.md` vollständig einlesen, falls vorhanden. Sie enthält die
lokalen Werkzeugversionen, Pfade und das PowerShell-Verfahren. Fehlt sie, keine
maschinenspezifischen Werte annehmen.

1. Für lokale Windows-Builds die in der lokalen Konfiguration festgelegten
   JDK- und Gradle-Versionen verwenden.
2. Gradle ausschließlich über die in der lokalen Konfiguration festgelegte
   Startdatei starten.
3. Für jeden lokalen Gradle-Aufruf `GRADLE_USER_HOME` auf den in der lokalen
   Konfiguration festgelegten Gradle-Cache setzen.
4. `GRADLE_USER_HOME` nur im ausführenden Prozess setzen und den vorherigen Wert
   anschließend auch bei Fehlern wiederherstellen. War die Variable zuvor nicht
   gesetzt, sie anschließend wieder aus der Prozessumgebung entfernen.
5. Keine dauerhaften Änderungen an Windows-PATH, JAVA_HOME, Registry oder
   Dateiberechtigungen vornehmen.
6. Diese lokalen Windows-Anweisungen nicht in Linux- oder Codex-Cloud-Umgebungen
   anwenden. Dort die vorhandene Repository-/CI-Konfiguration nutzen.

## Vorgehen und Kompatibilität

7. Vor Änderungen immer die Fehlerursache anhand des tatsächlichen Codes untersuchen.
8. Für Code- und Verhaltensänderungen geeignete Regressionstests erstellen.
9. Bestehende Paketnamen, Signaturen und App-Daten-Kompatibilität schützen.
10. Kodi nicht verändern.
11. Änderungen möglichst klein halten und Auswirkungen auf andere Funktionen prüfen.
12. Bei normalen Entwicklungsarbeiten selbstständig vorgehen. Destruktive Änderungen
    und produktive Releases erfordern vorherige Freigabe.
13. Ergebnisse nur nach tatsächlicher Verifikation als erfolgreich melden.
