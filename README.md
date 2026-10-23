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

# vorhandene Dateien nachliefern
java -jar log-guy-agent.jar backfill --app digibib --parallel 4 /logs/digibib/*/*.log.gz
```

Die Konfiguration des Agenten steht in [wiki/Einsammeln.md](wiki/Einsammeln.md).
