# log-guy

Stand: 26.10.2026

## Ziel und Rahmen

log-guy ist ein zentraler Dienst in Java mit Spring Boot, der die Logs aller unserer Anwendungen dauerhaft einsammelt und die letzten 7 Tage per Solr schnell durchsuchbar macht. Ältere Logs bleiben im Archiv und sind über eine langsamere Suche weiterhin abrufbar.

Ausgangslage: 30 bis 40 Anwendungen, überwiegend MyCoRe/MIR mit Log4j und davor jeweils ein Apache. Jede Log-Quelle wird als eigene Anwendung geführt, z.B. `digibib` und `digibib-apache` oder `leopoldina` und `leopoldina-apache`.

log-guy bietet:

- einen eigenen Agenten pro Host, der die Logdateien live mitliest und mit Token an log-guy schickt
- die Originale der letzten 7 Tage und ein gesichertes Archiv mit gekürzten IP-Adressen
- eine Solr-Collection für das aktuelle Zeitfenster, Länge konfigurierbar, mit Feldern aus Parser-Skripten pro Anwendung
- eine Such-API mit schneller Solr-Suche und langsamer Archivsuche mit regulären Ausdrücken
- Lifecycle-Filter, die das Archiv mit gekürzten IP-Adressen schreiben. Dazu Meldungen per Mail, Webhook oder API, wenn bestimmte Events auftreten
- einen MCP-Endpunkt für LLMs mit regelbasierter Pseudonymisierung
- ein Rechtesystem pro Anwendung, konfiguriert über eine Datei
- eine Web-Oberfläche mit Logfenster und Zeitleiste, dazu einen Log-Browser, der ganze Dateien zeigt, durchsucht, herunterlädt und löscht
- die Einrichtung von Configset, Collections und Alias in der bestehenden SolrCloud

Nicht im Umfang: LDAP-Anbindung, Löschfristen für das Archiv, mehrere log-guy-Instanzen für Hochverfügbarkeit. Die Architektur hält diese Erweiterungen offen.

## Inhalt

- [Ergebnisse des Prototyps](Prototyp-Ergebnisse.md)
- [Architekturüberblick](Architektur.md)
- [Einsammeln: log-guy-agent und Ingest-API](Einsammeln.md)
- [Speicherung](Speicherung.md)
- [Parser-Skripte](Parser-Skripte.md)
- [Solr-Verwaltung](Solr-Verwaltung.md)
- [Suche](Suche.md)
- [Meldungen](Meldungen.md)
- [Skripte: Verzeichnis, Reload, GUI und MCP](Skript-Verwaltung.md)
- [Lifecycle-Filter](Lifecycle-Filter.md)
- [MCP-Endpunkt und Pseudonymisierung](MCP-und-Pseudonymisierung.md)
- [Rechtekonzept und Konfiguration](Rechte-und-Konfiguration.md)
- [GUI](GUI.md)
- [Technologie](Technologie.md)
- [Milestones und offene Fragen](Milestones.md)
