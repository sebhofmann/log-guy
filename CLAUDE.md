# log-guy

Zentrales Log-Management für unsere 30 bis 40 MyCoRe/MIR-Anwendungen: Ein Agent pro Host liefert die Logdateien,
der Server bewahrt sie auf, parst sie mit JavaScript-Skripten in Felder, indexiert die letzten 7 Tage in Solr und
bietet GUI, API und einen MCP-Endpunkt mit Pseudonymisierung.

## Konzept

- `wiki/` ist das verbindliche Konzept, Einstieg ist `wiki/README.md`. Vor jeder Arbeit an einem Bereich die
  passende Seite lesen.
- Weicht die Umsetzung vom Konzept ab, wird `wiki/` in derselben Änderung angepasst. Das Konzept bleibt immer der
  aktuelle Stand, nicht der ursprüngliche Plan.
- Fortschritt wird in `wiki/Milestones.md` abgehakt. Die Reihenfolge M1 bis M6 einhalten, weil spätere Teile auf
  früheren aufbauen. Innerhalb eines Milestones ist die Reihenfolge frei.

## Stack

Java 25, Spring Boot 4, Maven als Multi-Modul-Projekt:

- `log-guy-common`: Protokoll zwischen Agent und Server, Fingerabdruck, gemeinsame Modelle
- `log-guy-server`: Spring Boot mit GUI, API, Indexer, Archiv, Skripten, MCP und Spring Shell
- `log-guy-agent`: schlanke Java-Anwendung ohne Spring

Bibliotheken laut `wiki/Technologie.md`: JTE, Spring Security, SolrJ, GraalJS über `org.graalvm.polyglot`,
zstd-jni, RE2/J, Spring AI MCP Server, Spring Shell, Log4j 2, im Browser uPlot und CodeMirror. Versionen vor dem
Einbinden auf Maven Central bzw. npm prüfen und die aktuelle stabile Version nehmen, keine aus dem Gedächtnis.

Die Major-Version der Produktiv-SolrCloud ist noch nicht geklärt. Bis dahin gegen `solr:9.10.1` entwickeln und
SolrJ passend dazu wählen.

## Arbeitsweise

- In kleinen, durchgehenden Schritten arbeiten: Jeder Schritt baut, hat Tests und läuft. Lieber früh einen
  dünnen Durchstich vom Agenten bis Solr als viele halbfertige Schichten.
- Nach jeder Änderung an Java-Dateien formatieren und `mvn clean install` ausführen. Ein Schritt ist erst fertig,
  wenn der Build grün ist.
- Tests: JUnit 5 für Logik, Integrationstests gegen ein echtes Solr per Testcontainers. Für Parser, Fingerabdruck,
  Duplikatschutz und Rotation Tests mit echten Logzeilen aus `prod-logs/`.
- Gegen echte Daten prüfen, nicht nur gegen Tests: Backfill von `prod-logs/`, Live-Datei einer lokalen MIR-Instanz.
- Unabhängige Teile wie Agent und Server dürfen parallel von Subagenten gebaut werden, wenn die Schnittstelle in
  `log-guy-common` vorher feststeht.
- Messwerte, die im Konzept als „nicht gemessen“ oder „hochgerechnet“ markiert sind, beim Umsetzen messen und im
  Wiki nachtragen.
- Nur bei Entscheidungen zurückfragen, die das Konzept nicht abdeckt, oder wenn eine Messung eine Annahme des
  Konzepts widerlegt. Sonst selbstständig weiterarbeiten.
- Commits klein, Messages auf Englisch, erst nach grünem Build. Gepusht wird nur nach Absprache.

## Code-Stil

- Java mit dem Eclipse-Formatter und dem MyCoRe-Stil formatieren:
  ```bash
  java --enable-preview --class-path "$(find /usr/lib/eclipse/plugins -name '*.jar' | tr '\n' ':')" \
    ~/opt/EclipseFormat.java ~/mycore-javastyle.xml $(git diff --name-only --diff-filter=ACMR -- '*.java')
  ```
- Code, Kommentare, JavaDoc und Commit-Messages auf Englisch. Wiki, GUI-Texte und Fehlermeldungen für Nutzer auf
  Deutsch.
- JavaDoc für public und protected. Logging mit Log4j 2.
- XML, HTML, CSS mit 2 Leerzeichen, JavaScript mit 4 Leerzeichen, maximal 120 Zeichen pro Zeile.
- Konfiguration nur über `application.yml` mit `@ConfigurationProperties`, keine fest eingebauten Werte im Code.

## Daten und Umgebung

- `prod-logs/`: 22 Monate Log4j-Logs von digibib, 691 `.gz`-Dateien, 5,1 GB, 43,9 GB entpackt. Nur lesen, nie
  committen, steht in `.gitignore`.
- Log-Format der MIR-Anwendungen: `%d{ISO8601} %-5p %X{loginId} %c{1}: %m%n%ex` mit ANSI-Farben, siehe
  `~/workspace/mir/docker-log4j2.xml`. Rotation täglich und bei 250 MB.
- Lokale MIR-Instanzen mit Live-Logs unter `~/workspace/*/docker/mir-logs/`. Die Dateien gehören UID 100500 und
  sind teils nicht lesbar für den eigenen Nutzer.
- Docker ist verfügbar, lokal liegen die Images `solr:9.10.1` und `solr:10.0.0`. Test-Solr auf Port 18983
  starten, 8983 ist von den MIR-Instanzen belegt.
- Java 25 und Maven 3.10 sind installiert.
