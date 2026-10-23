# GUI

Die Hauptansicht besteht aus einer Filterleiste oben, dem Logfenster in der Mitte und einer Zeitleiste unten. Ein in der Zeitleiste markierter Bereich wird zum neuen Suchzeitraum.

### Hauptansicht

- **Filterleiste:** Anwendungen als Mehrfachauswahl, nur die erlaubten. Filter für die Felder der gewählten Anwendungen, z.B. Level oder Import-ID, Suchfeld in Solr-Syntax, Zeitraum als Auswahl mit 15 Minuten, 1 Stunde, 24 Stunden, 7 Tagen oder frei.
- **Logfenster:** Zeit, Anwendung, wählbare Felder wie Level oder Logger, erste Zeile der Message. Ein Klick klappt das ganze Event mit Stacktrace auf. Weitere Aktionen: Kontext davor und danach, Link kopieren. Die Liste scrollt virtuell und lädt per `cursorMark` nach, auch bei Millionen Treffern.
- **Zeitleiste:** Histogramm über den Zeitraum, gestapelt nach Level, sofern die Anwendungen ein Level-Feld haben, `ERROR` und `WARN` hervorgehoben. Viele Logs ergeben einen hohen Ausschlag, wenige einen niedrigen. Markiert man mit der Maus einen Bereich, werden Logfenster und Zeitleiste für diesen Bereich neu geladen. Ein Klick auf einen Balken springt im Logfenster an diese Stelle.
- **Teilbare Links:** Der ganze Zustand steht in der URL, also Anwendungen, Zeitraum, Query und Level.

### Weitere Seiten

| Seite | Inhalt |
| --- | --- |
| Archivsuche | Ausdruck, Zeitraum, Anwendungen. Fortschrittsbalken, Treffer laufen live ein, Abbrechen jederzeit. |
| Status | Übersicht wie im Dashboard der Solr-Admin-Oberfläche. System: Version, Laufzeit, CPU, Speicher, Platz in hot/ und archive/. Pro Agent: zuletzt gesehen, Rückstand. Pro Anwendung: letztes Event, Events pro Stunde, Indexer-Rückstand, Parser- und Filterfehler. Solr: erreichbar, Größe der Collection, Anzahl Dokumente. |
| Server-Log | das eigene Log von log-guy, live mitlaufend, filterbar nach Level und Komponente. Das Log-Level einzelner Komponenten lässt sich zur Laufzeit ändern, wie im Logging-Bereich von Solr. Nur mit Recht admin. |
| Anmeldung | Name und Passwort |
| Skript-Editor | Parser, Meldungsregeln, Lifecycle-Filter und Pseudonymisierung bearbeiten. Testquelle wählen: Anwendung, Logdatei im Archiv oder eingefügte Zeilen. JavaScript-Modul im Editor ändern und ausführen. Ergebnis als Tabelle: ein Event pro Zeile, eine Spalte pro Feld. Markiert werden Zeilen ohne Event-Anfang, Skriptfehler und nicht deklarierte Felder, dazu Events pro Sekunde. Speichern und Aktivieren mit Recht scripts, frühere Fassungen vergleichen und zurückrollen, Entwürfe aus MCP prüfen und freigeben, danach Neuindexierung anbieten. |
| Meldungen | offene und quittierte Meldungen, quittieren, Sprung zu den auslösenden Events. Regeln als JavaScript bearbeiten und gegen die letzten 24 Stunden testen, bevor sie scharf geschaltet werden. |

### Umsetzung

- Seiten rendert der Server mit JTE-Templates.
- Suche, Logfenster und Zeitleiste sind JavaScript-Module, die die Such-API per `fetch` aufrufen.
- Die Zeitleiste zeichnet uPlot. Es ist klein, schnell bei Zeitreihen und kann Bereiche auswählen.
- Es gibt keinen Build-Schritt für JavaScript: einfache ES-Module, eingebettet ins JAR.
- Später möglich: Live-Modus, der neue Events per Server-Sent Events oben ins Logfenster schiebt.

[Zur Übersicht](README.md)
