# Rechtekonzept und Konfiguration

Anwendungen, Nutzer, Rechte und Tokens stehen in der Spring-Konfiguration application.yml. Eine Datenbank braucht log-guy nicht.

### Rechte

| Recht | erlaubt |
| --- | --- |
| `read` | schnelle Suche, Zeitleiste, Kontext in der GUI und über die API, mit Klarwerten |
| `read_pseudonymized` | wie `read`, aber nur pseudonymisiert, wie beim MCP |
| `archive` | Archivsuche und Archivtage temporär laden |
| `admin` | Solr-Befehle, Lifecycle-Status, Neuaufbau, alle Anwendungen |
| `scripts` | Parser, Meldungsregeln und Lifecycle-Filter der Anwendung bearbeiten, testen und aktivieren, setzt read voraus |

Rechte werden pro Anwendung vergeben. Muster wie `digibib*` treffen `digibib` und `digibib-apache` zusammen.

### Beispiel

```yaml
log-guy:
  apps:
    digibib:
      index-days: 7
    digibib-apache:
      index-days: 7
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
```

### Tokens

| Art | gehört zu | darf |
| --- | --- | --- |
| `ingest` | einem Host | nur in die Anwendungen dieses Hosts schreiben |
| `api` | einem Nutzer | was der Nutzer darf, optional eingeschränkt |
| `mcp` | einem Nutzer | was der Nutzer darf, immer pseudonymisiert. Skripte nur als Entwurf, aktivieren nur mit activate: true |

- `log-guy token create --kind ingest --host app1 --apps digibib,digibib-apache` erzeugt ein Token, zeigt es einmal an und schreibt nur den Hash in die Konfiguration.
- Passwörter werden mit Argon2 gehasht.
- Änderungen an der Datei übernimmt log-guy ohne Neustart nach `log-guy reload`.

### GUI-Anmeldung

Name und Passwort, danach ein Session-Cookie mit `HttpOnly`, `Secure` und `SameSite=Strict`. Sessions liegen im RAM, nach einem Neustart meldet man sich neu an. Die Anmeldung steckt hinter einer Schnittstelle, sodass LDAP später als zweite Umsetzung dazukommen kann.

### Zustand ohne Datenbank

| Was | Wo |
| --- | --- |
| Anwendungen, Nutzer, Rechte, Tokens, Solr, Ziele für Mail und Webhook | `/etc/log-guy/application.yml` |
| alle Skripte, frühere Fassungen in .history/, Entwürfe in .drafts/ | `/etc/log-guy/scripts/` |
| Logs im Original | Archiv |
| Einsammel-Stand pro Logdatei | `.meta.json` im Archiv |
| Indexer-Fortschritt | `state/index/<fingerprint>` |
| Meldungen | `state/alerts.ndjson` |
| Audit-Log | `audit/*.ndjson`, wer wann was gesucht hat |
| Sessions, Pseudonym-Zuordnungen, Suchjobs | nur im RAM |
| eigenes Log von log-guy | `/var/lib/log-guy/logs/`, täglich rotiert, die letzten Einträge zusätzlich im RAM |

[Zur Übersicht](README.md)
