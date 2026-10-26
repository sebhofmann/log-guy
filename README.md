# log-guy
Zentrales Log-Management für MyCoRe/MIR-Anwendungen mit Solr, Archiv, JavaScript-Parsern und MCP

Das Konzept steht unter [wiki/](wiki/README.md), der Fortschritt unter [wiki/Milestones.md](wiki/Milestones.md).

## Bauen

Java 25 und Maven 3.10. Die Integrationstests starten `solr:9.10.1` per Testcontainers, dafür muss Docker laufen.

```bash
mvn clean install
docker build -t log-guy-server log-guy-server
docker build -t log-guy-agent log-guy-agent
```

| Modul | Inhalt |
| --- | --- |
| `log-guy-common` | Protokoll zwischen Agent und Server, Fingerabdruck |
| `log-guy-server` | Server mit Stream-API, `hot/`, Parser-Skripten, Indexer und Kommandozeile |
| `log-guy-agent` | Agent, der Logdateien mitliest und an den Server schickt |

## Starten

Der Server liest `/etc/log-guy/application.yml`, die Vorgaben stehen in
`log-guy-server/src/main/resources/application.yml`. Ein kleines Beispiel:

```yaml
log-guy:
  apps:
    digibib:
      parser: mycore-log4j
  tokens:
    - name: agent-app1
      kind: ingest
      host: app1
      apps: [digibib]
      hash: "sha256:..."
  solr:
    urls: [http://solr1:8983/solr, http://solr2:8983/solr]
```

```bash
# Token erzeugen, den ausgegebenen Eintrag in application.yml übernehmen
docker run --rm log-guy-server token create --kind ingest --name agent-app1 --host app1 --apps digibib

# Konfiguration und Skripte prüfen, Configset, Collection und Alias anlegen
docker run --rm -v /etc/log-guy:/etc/log-guy:ro log-guy-server check
docker run --rm -v /etc/log-guy:/etc/log-guy:ro log-guy-server solr init

# Server
docker run -d -p 8080:8080 -v /etc/log-guy:/etc/log-guy:ro -v /var/lib/log-guy:/var/lib/log-guy log-guy-server

# Agent neben einer MIR-Anwendung, mit der UID der Logdateien im Container
docker run -d --user 501 -v ./mir-logs:/logs/digibib:ro -v /etc/log-guy-agent:/etc/log-guy-agent:ro \
  log-guy-agent

# oder ohne agent.yml, nur über die Umgebung (siehe wiki/Einsammeln.md)
docker run -d --group-add 501 -v ./mir-logs:/logs/digibib:ro -e LOG_GUY_SERVER=https://log-guy.example.org \
  -e LOG_GUY_TOKEN=lg_ingest_... -e LOG_GUY_HOST=app1 -e LOG_GUY_SOURCES_0_APP=digibib \
  -e LOG_GUY_SOURCES_0_LIVE=/logs/digibib/mycore-app.log \
  -e 'LOG_GUY_SOURCES_0_ROTATED=/logs/digibib/*/mycore-app-*.log.gz' log-guy-agent

# vorhandene Dateien nachliefern
java -jar log-guy-agent.jar backfill --app digibib --parallel 4 /logs/digibib/*/*.log.gz
```

Die Konfiguration des Agenten steht in [wiki/Einsammeln.md](wiki/Einsammeln.md).

## Docker Compose

`compose.yml` startet log-guy mit einer kleinen SolrCloud zum Ausprobieren. Agenten sind nicht dabei, sie laufen im
Stack der jeweiligen Anwendung.

| Dienst | Aufgabe |
| --- | --- |
| `zookeeper` | ZooKeeper der SolrCloud |
| `solr1`, `solr2` | zwei Solr-Knoten `solr:9.10.1`, die Collection `logs_v1` hat auf jedem ein Replikat |
| `log-guy-init` | legt beim ersten Start `application.yml` an, dann `log-guy solr init`, überspringt Vorhandenes |
| `log-guy` | der Server, startet erst nach erfolgreichem `log-guy-init` |

```bash
mvn clean install                # das Image wird aus log-guy-server/target gebaut
docker compose up -d             # nach Änderungen am Code: mvn clean install und docker compose up -d --build
docker compose logs log-guy-init # Admin-Passwort und Token für den Agenten, nur nach dem ersten Start
```

- log-guy läuft auf http://localhost:28080 mit dem Nutzer `admin`, die Solr-Oberfläche auf http://localhost:28983/solr,
  nur auf localhost. Die Ports lassen sich mit `LOG_GUY_PORT` und `SOLR_PORT` ändern.
- Daten und Konfiguration liegen unter `docker-data/`: `etc/` ist `/etc/log-guy` mit der erzeugten
  `application.yml`, `log-guy/` ist `/var/lib/log-guy`, dazu `solr1/`, `solr2/` und `zookeeper/`. Die Container
  starten als root, geben ihre Verzeichnisse dort ihrem Nutzer und laufen dann unter diesem. Beim Server schaltet das
  wie bei MIR `FIX_FILE_SYSTEM_RIGHTS=true` ein.
- Fehlt `docker-data/etc/application.yml`, legt `log-guy-init` sie an (`docker/log-guy-init.sh`): den Nutzer
  `admin`, die Anwendung `mir` mit dem Parser `mycore-log4j` und einen Ingest-Token für den Host `mir`. Passwort und
  Token stehen nur im Log dieses ersten Laufs, nach `docker compose down` sind sie weg. Das Passwort lässt sich
  vorher mit `LOG_GUY_ADMIN_PASSWORD` festlegen, sonst ist es zufällig. Neu erzeugen: `application.yml` löschen und
  `docker compose up -d` aufrufen. Der Token gehört in die `token-file` des Agenten im MIR-Stack.
- Weitere Anwendungen, Nutzer und Tokens kommen nach `docker-data/etc/application.yml`, danach
  `docker compose exec log-guy log-guy reload`, ohne Neustart. Die Solr-URLs setzt `compose.yml` über die Umgebung.
- Abweichungen vom Betrieb: Solr läuft ohne Anmeldung (eigener Solr-Nutzer siehe
  [wiki/Solr-Verwaltung.md](wiki/Solr-Verwaltung.md)), ein einzelner ZooKeeper ist kein Quorum, und ohne TLS-Proxy
  davor akzeptieren Browser das `secure` Session-Cookie über HTTP nur auf `localhost`.

Alles löschen, auch mit Rootless Docker, wo die Dateien nicht dem eigenen Nutzer gehören:

```bash
docker compose down
docker run --rm --user root --entrypoint rm -v "$PWD:/w" solr:9.10.1 -rf /w/docker-data
```
