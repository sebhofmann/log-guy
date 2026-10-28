# Rechtekonzept und Konfiguration

Anwendungen, Nutzer, Rechte und Tokens stehen in der Spring-Konfiguration application.yml. Eine Datenbank braucht log-guy nicht.

### Rechte

| Recht | erlaubt |
| --- | --- |
| `read` | schnelle Suche, Zeitleiste, Kontext in der GUI und über die API, mit Klarwerten |
| `read_pseudonymized` | wie `read`, aber nur pseudonymisiert, wie beim MCP |
| `archive` | Archivsuche, Archivtage temporär laden, Log-Browser: Dateien im Archiv ansehen, durchsuchen und neu indexieren, mit Klarwerten auch herunterladen |
| `delete` | Dateien aus `hot/`, Archiv und Index löschen und für die Agenten sperren, Sperren aufheben |
| `admin` | Solr-Befehle, Lifecycle-Status, Neuaufbau, Originale in `hot/` im Log-Browser, alle Anwendungen, schließt alle anderen Rechte ein |
| `scripts` | Parser, Meldungsregeln und Lifecycle-Filter der Anwendung bearbeiten, testen und aktivieren, setzt `read` voraus, `read_pseudonymized` genügt nicht |

Rechte werden pro Anwendung vergeben. Muster wie `digibib*` treffen `digibib` und `digibib-apache` zusammen.

### Anwendungsgruppen

Eine Anwendung kann unter `groups` zu Anwendungsgruppen gehören, z.B. `groups: [mir, produktiv]`. Überall, wo sonst Anwendungen oder Muster stehen, steht `@mir` für alle Anwendungen der Gruppe:

- `meta.apps` von Meldungsregeln, Lifecycle-Filtern und Pseudonymisierungsskripten, z.B. `apps: ["@mir"]`. Eine neue MIR-Anwendung bekommt dann nur `groups: [mir]` und ist sofort in allen Regeln.
- `apps` der Freigaben unter `grants` und von `api`- und `mcp`-Tokens.
- `apps` der Suche, in der GUI als eigene Einträge über den Anwendungen, siehe [Suche](Suche.md).

Gruppen werden bei jeder Verwendung aus der aktuellen Konfiguration aufgelöst und nicht indexiert. Ändert ein `log-guy reload` die Gruppen, gelten Regeln, Filter, Rechte und Suche sofort für die neuen Mitglieder, ohne Neuindexierung. Gruppennamen folgen denselben Regeln wie Anwendungsnamen. Ingest-Tokens nennen ihre Anwendungen weiter einzeln, weil der Agent pro Anwendung liefert. Skripte mit `*` oder einer Gruppe in `meta.apps` darf nur ein Administrator bearbeiten, weil sich die erfassten Anwendungen mit der Konfiguration ändern.

### Beispiel

```yaml
log-guy:
  apps:
    digibib:
      index-days: 7
      parser: mycore-log4j
      groups: [mir, produktiv]
    digibib-apache:
      index-days: 7
      parser: apache-combined
  users:
    - name: anna
      password-hash: "$argon2id$v=19$..."
      grants:
        - apps: ["*"]
          rights: [admin, read, archive]
    - name: ben
      password-hash: "$argon2id$v=19$..."
      grants:
        - apps: ["digibib*"]
          rights: [read, archive]
        - apps: ["leopoldina*"]
          rights: [read_pseudonymized]
        - apps: ["@produktiv"]
          rights: [read]
  tokens:
    - name: agent-app1
      kind: ingest
      host: app1
      apps: [digibib, digibib-apache]
      hash: "sha256:..."
    - name: ben-claude
      kind: mcp
      user: ben
      hash: "sha256:..."
      activate: false     # true: darf Parser und Meldungsregeln selbst aktivieren
```

### Tokens

| Art | gehört zu | darf |
| --- | --- | --- |
| `ingest` | einem Host | nur in die Anwendungen dieses Hosts schreiben |
| `api` | einem Nutzer | was der Nutzer darf, optional eingeschränkt |
| `mcp` | einem Nutzer | was der Nutzer darf, immer pseudonymisiert, `read_pseudonymized` genügt zum Lesen. Skripte nur mit `read` und `scripts`, nur als Entwurf, aktivieren nur mit activate: true |

- `log-guy token create --kind ingest --name agent-app1 --host app1 --apps digibib,digibib-apache` erzeugt ein Token, zeigt es einmal an und gibt den Eintrag für `application.yml` mit dem Hash aus. Eintragen muss ihn der Mensch selbst, denn ein Programm, das die YAML-Datei umschreibt, würde Kommentare und Reihenfolge zerstören.
- Tokens beginnen mit `lg_<art>_`, z.B. `lg_ingest_...`, danach 32 Zufallsbytes in Base64. Konfiguriert ist nur `sha256:<hex>` des ganzen Tokens.
- `log-guy check` prüft die Tokens: Name eindeutig, Hash im richtigen Format und eindeutig, Ingest-Tokens mit Host und Anwendungen, die unter `log-guy.apps` stehen. Jedes Muster in `apps` eines `api`- oder `mcp`-Tokens trifft mindestens eine Anwendung.
- Passwörter werden mit Argon2id gehasht, mit den Parametern von Spring Security. `log-guy password hash --name anna` liest das Passwort ohne Echo vom Terminal oder als erste Zeile von stdin und gibt den Eintrag für `log-guy.users` aus.
- `log-guy check` prüft auch die Nutzer: Name eindeutig, Hash im Argon2id-Format, jedes Muster und jede Gruppe einer Freigabe trifft mindestens eine Anwendung, Gruppennamen sind gültig, `api`- und `mcp`-Tokens gehören zu einem konfigurierten Nutzer.
- `admin` auf eine Anwendung schließt alle anderen Rechte für sie ein. Administrator von log-guy selbst, also Status, Server-Log und Log-Level, ist, wer `admin` mit `apps: ["*"]` hat.
- Ein `api`-Token kann mit `apps` auf einen Teil der Anwendungen seines Nutzers eingeschränkt werden. Ohne `apps` darf es alles, was der Nutzer darf.
- Jedes Token gilt nur auf seinem eigenen Endpunkt: `ingest` auf `/api/v1/streams`, `api` auf `/api/**` außer den Streams, `mcp` nur auf dem MCP-Pfad (`log-guy.mcp.path`). Ein Token am falschen Endpunkt behandelt log-guy wie ein unbekanntes, Antwort 401.
- `read_pseudonymized` gilt auch in GUI und API. Suche, Zeitleiste, Facetten und Kontext laufen dann durch dieselbe Pseudonymisierung wie beim MCP, mit den Pseudonymen der GUI-Session. Ein API-Token bekommt bei jeder Anfrage neue Pseudonyme. Wer für eine Anwendung `read` und für eine andere nur `read_pseudonymized` hat, sieht eine Suche über beide ganz pseudonymisiert und nur eine Suche über Anwendungen mit `read` im Klartext. Das ist einfacher und sicherer als eine Mischung pro Event, weil Facetten und Zeitleiste zu keiner einzelnen Anwendung gehören. `/api/v1/fields` nennt diese Anwendungen unter `pseudonymized`, die GUI markiert sie und die Suche mit „pseudonymisiert“.
- Skript-Tests gegen Daten einer Anwendung brauchen in GUI und API `read`, denn sie zeigen die Zeilen im Klartext.
- Änderungen an Anwendungen, Nutzern und Tokens übernimmt log-guy ohne Neustart nach `log-guy reload`. Alle anderen Änderungen prüft der Reload ebenfalls, sie wirken aber erst nach einem Neustart, siehe [Skripte](Skript-Verwaltung.md).
- Neue Rechte gelten ab der nächsten Anfrage, auch für Vorgänge, die schon laufen:
  - Ein offener Meldungs-Stream prüft spätestens mit jedem Ping (`stream-ping`, Vorgabe 15 s) den Nutzer und sein Token neu. Haben sich die Rechte geändert, endet er, und der Browser verbindet sich neu und bekommt die Meldungen, die er jetzt sehen darf, in der jetzt erlaubten Form. Ist der Nutzer oder das Token entfernt, scheitert die neue Verbindung.
  - Eine Archivsuche prüft vor jedem Frame und bei jedem Abruf, ob die aktuellen Rechte sie noch abdecken: `archive` und Lesen für alle ihre Anwendungen, und Lesen im Klartext, wenn ihre Treffer nicht pseudonymisiert sind. Sonst bricht sie ab und wird samt Treffern verworfen, der Abruf meldet, dass sich die Rechte geändert haben. Mehr Rechte lassen sie unberührt.
- Dieselbe Prüfung läuft beim Start des Servers. Findet sie Fehler, bricht der Start mit allen Fehlern im Log ab, wie bei fehlerhaften Parsern. Ein Tippfehler fällt so sofort auf und nicht erst als 500 oder 401 im Betrieb.
- Der Reload prüft zusätzlich, ob die neue Konfiguration plausibel ist. Er lehnt sie ab, wenn sie keine Anwendungen, keine Nutzer oder keine Tokens mehr enthält, die laufende aber welche hat, und wenn kein Administrator (`admin` für `apps: ["*"]`) übrig bliebe, die laufende aber einen hat. Das schützt vor einer kurz geleerten oder halb geschriebenen `application.yml`. Wie jeder fehlgeschlagene Reload antwortet er mit 422 und übernimmt nichts. Wer wirklich alle Nutzer oder Tokens entfernen will, startet den Server mit der neuen Konfiguration neu.

### GUI-Anmeldung

Name und Passwort, danach ein Session-Cookie mit `HttpOnly`, `Secure` und `SameSite=Strict`, gültig 8 Stunden ohne Aktivität. Die GUI ruft die API mit dieser Session und einem CSRF-Token auf, API-Tokens brauchen kein CSRF-Token. Ohne Anmeldung antwortet `/api/**` mit 401, die Seiten leiten auf `/login` um. Sessions liegen im RAM, nach einem Neustart meldet man sich neu an. Die Anmeldung steckt hinter einer Schnittstelle, sodass LDAP später als zweite Umsetzung dazukommen kann.

### Zustand ohne Datenbank

| Was | Wo |
| --- | --- |
| Anwendungen, Nutzer, Rechte, Tokens, Solr, Ziele für Mail und Webhook | `/etc/log-guy/application.yml` |
| alle Skripte, frühere Fassungen in .history/, Entwürfe in .drafts/ | `/etc/log-guy/scripts/` |
| Logs im Original | Archiv |
| Einsammel-Stand pro Logdatei | `.meta.json` im Archiv |
| Indexer-Fortschritt | `state/index/<fingerprint>` |
| Steuer-Token für `log-guy reload`, `log-guy archive refilter`, `log-guy archive pseudonymize` und `log-guy reindex` | `state/control.token`, bei jedem Start neu, wird nicht gesichert |
| Meldungen | `state/alerts.ndjson` |
| gelöschte und gesperrte Dateien | `state/deleted.json` |
| Audit-Log | `audit/*.ndjson`, Skript-Änderungen, Reloads, MCP-Aufrufe, Löschen, Sperren aufheben, Herunterladen und Neuindexieren |
| Sessions, Pseudonym-Zuordnungen, Suchjobs | nur im RAM |
| eigenes Log von log-guy | `/var/lib/log-guy/logs/`, täglich rotiert, die letzten Einträge zusätzlich im RAM |

[Zur Übersicht](README.md)
