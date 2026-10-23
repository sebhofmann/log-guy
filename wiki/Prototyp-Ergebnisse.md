# Ergebnisse des Prototyps

Der klassische Solr-Index mit gespeicherter Message braucht 0,42 Byte pro Rohbyte. Ein eigenes Archiv mit Pointern spart nichts, deshalb speichert Solr den Text selbst (Variante A).

Stichprobe: eine der größten Anwendungen, September und Oktober 2026, 53 Tage, 4,9 GB roh, 22,5 Mio. Events. Gemessen nach Force-Merge auf ein Segment mit `BEST_COMPRESSION`.

| Variante | Index | Archiv | Gesamt | × Roh |
| --- | --- | --- | --- | --- |
| A: Message gespeichert und indexiert | 2.061 MB |  | 2.061 MB | 0,42 |
| B: nur indexiert, Text über Pointer aus dem Archiv | 1.501 MB | 665 MB | 2.166 MB | 0,44 |
| C: wie B, ohne Positionen | 1.190 MB | 665 MB | 1.855 MB | 0,38 |
| D: Volltext einmal pro Archivblock | 987 MB | 665 MB | 1.652 MB | 0,34 |

Solr komprimiert die gespeicherte Message auf 797 MB, fast so klein wie das gzip-Archiv. D spart nur 20 % und kostet ein eigenes Archivformat mit Grep im Nachgang. Den größten Anteil haben die Terms mit ca. 360 MB, vermutlich durch eindeutige Werte wie `id`, Session-UUIDs und IPs.

Volumen über 22 Monate (Januar 2025 bis Oktober 2026, 5,0 GB gzip, 43,9 GB roh): im Mittel 68 MB roh pro Tag, Median 51 MB, Spitzentag 1,2 GB. Normale Tage komprimieren mit gzip etwa 1:7, Spam-Tage bis 1:45.

Hochrechnung für 35 Anwendungen, die übrigen halb so groß oder kleiner:

| Aufbewahrung im Index | Index pro Knoten bei Replikationsfaktor 2 |
| --- | --- |
| 7 Tage | 2 bis 3,5 GB |
| 14 Tage | 4 bis 7 GB |
| 30 Tage | 10 bis 15 GB |

Die MIR-Collections im Cluster belegen zusammen 5,4 GB, davon 4,9 GB `digibib-main`. Bei 30 GB RAM pro Knoten bleiben nach Heap und Betriebssystem ca. 20 GB Page Cache, also etwa 14 GB für Logs. 7 bis 14 Tage passen bequem, sobald jeder Knoten ein eigener Server ist. Solange beide Knoten auf einem Server laufen, gilt höchstens 7 Tage.

Nicht gemessen: Suchgeschwindigkeit unter Last. Ein laufender Index ist durch ungemergte Segmente etwa 10 bis 20 % größer.

[Zur Übersicht](README.md)
