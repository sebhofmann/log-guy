# GUI

Die Hauptansicht besteht aus einer Filterleiste oben, links den Facetten und rechts der Zeitleiste über dem Logfenster. Die Zeitleiste steht oben, weil sie den Überblick gibt und das Logfenster den restlichen Platz nach unten ausfüllen soll. Ein in der Zeitleiste markierter Bereich wird zum neuen Suchzeitraum.

### Hauptansicht

- **Filterleiste:** Anwendungen als Mehrfachauswahl, nur die erlaubten. Filter für die Felder der gewählten Anwendungen, z.B. Level oder Import-ID, Suchfeld in Solr-Syntax, Zeitraum als Auswahl mit 15 Minuten, 1 Stunde, 24 Stunden, 7 Tagen oder frei.
- **Logfenster:** Zeit, Anwendung, wählbare Felder wie Level oder Logger, erste Zeile der Message. Ein Klick klappt das ganze Event mit Stacktrace auf. Weitere Aktionen: Kontext davor und danach, Link kopieren. Die Liste scrollt virtuell und lädt per `cursorMark` nach, auch bei Millionen Treffern.
- **Zeitleiste:** Histogramm über den Zeitraum, gestapelt nach Level, sofern die Anwendungen ein Level-Feld haben, `ERROR` und `WARN` hervorgehoben. Viele Logs ergeben einen hohen Ausschlag, wenige einen niedrigen. Markiert man mit der Maus einen Bereich, werden Logfenster und Zeitleiste für diesen Bereich neu geladen. Ein Klick auf einen Balken springt im Logfenster an diese Stelle.
- **Facetten:** die häufigsten Werte der Facettenfelder der gewählten Anwendungen mit Anzahl. Ein Klick setzt den Wert als Filter oder nimmt ihn wieder heraus. Die Facette eines Feldes ignoriert den eigenen Filter, damit man weitere Werte dazunehmen kann.
- **Teilbare Links:** Der ganze Zustand steht in der URL, also Anwendungen, Zeitraum, Query, Feldfilter (`f.<feld>`), Spalten (`cols`) und das aufgeklappte Event (`at`). „Link kopieren“ erzeugt einen Link auf die fünf Minuten vor dem Event mit dem Event aufgeklappt.

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
- Status und Server-Log liegen unter `/admin/` und holen ihre Daten als JSON von `/admin/api/status` und `/admin/api/log`. Die Status-Seite lädt alle 15 Sekunden neu, das Server-Log fragt alle 2 Sekunden nach neuen Einträgen.
- Das Log-Level ändert die Seite über den Actuator-Endpunkt `/actuator/loggers`. Änderungen gelten bis zum Neustart.
- Den Rückstand eines Agenten kennt der Server nicht, weil der Agent nur Daten schickt und keinen Stand meldet. Die Status-Seite zeigt deshalb pro Agent, wann er zuletzt gesehen wurde (seit dem Start des Servers), wann zuletzt Daten seines Hosts ankamen und wie viele seiner Dateien noch offen sind. Ein Rückstand pro Agent wäre eine Erweiterung des Protokolls.
- Die Content-Security-Policy verbietet Inline-Skripte und `style`-Attribute. Styles setzt JavaScript nur über das CSSOM.
- Später möglich: Live-Modus, der neue Events per Server-Sent Events oben ins Logfenster schiebt.

[Zur Übersicht](README.md)
