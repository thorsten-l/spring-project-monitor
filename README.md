# Spring Project Monitor

Web-Oberfläche, die alle lokalen Spring-Boot-Projekte findet und auf einen Blick zeigt,
mit welcher Spring-Boot- und JDK-Version sie laufen – als Grundlage für die Migration
auf Spring Boot 4.

Beim Start durchsucht die Anwendung ein Verzeichnis (Default `$HOME`) nach `pom.xml`
mit `spring-boot-starter-parent` (direkt oder über eine Parent-Kette) und ermittelt je Projekt:

- Maven-Koordinaten, Projektversion, Spring-Boot-Version, Java-Version aus dem POM
- Zeitstempel (POM und jüngste Datei im Projekt)
- Repository (git, svn, hg): URL, letztes Tag, Abweichung zum Remote
- Deployment: Docker-Services aus den compose-Dateien konfigurierter Hosts (per SSH)
  und die tatsächlich verwendete JDK-Version des Images

`/` zeigt die Projekte als sortierbare Tabelle, ein Klick auf eine Zeile öffnet alle
Angaben gruppiert in Kacheln in einem neuen Fenster. „neu scannen“ wiederholt den Scan.

## Voraussetzungen

- JDK 25, Maven 3.9
- `git` (und ggf. `svn`, `hg`) im PATH
- für Docker-Hosts: SSH-Zugang per Schlüssel; für Hardware-Security-Keys (FIDO, `*_sk`)
  ein OpenSSH mit FIDO-Unterstützung, auf macOS Homebrew-OpenSSH (`/opt/homebrew/bin/ssh`) –
  Apples `/usr/bin/ssh` kann diese Schlüssel nicht selbst nutzen

## Bauen und starten

```sh
mvn package
java -jar target/spring-project-monitor.jar     # http://localhost:8080
```

Aus dem Projektverzeichnis starten, relative Pfade (`data/…`) beziehen sich darauf.
Beim Start aus der IDE (Klassen aus `target/classes`) während der Laufzeit kein
`mvn clean` ausführen – sonst fehlen danach die Templates.

## Konfiguration

Voreinstellungen stehen in `src/main/resources/application.yaml`. Rechnerspezifische
Werte gehören nach `data/config.yaml` (wird automatisch geladen, ist per `.gitignore`
ausgeschlossen):

```yaml
app:
  scan-root: "/Users/me/Projects"
  scan-excludes:
    - "/src/test/resources/"
    - "/archetype-resources/"
    - "/00Archive/"
  git-fetch: false
  docker:
    hosts:
      - root@docker1.example.org:/srv/dockerdata
      - app@web.example.org:.?key=~/.ssh/id_ed25519_sk
    ssh: /opt/homebrew/bin/ssh
    security-key-on-startup: false
```

| Einstellung | Bedeutung |
|---|---|
| `app.scan-root` | Suchverzeichnis, Default `$HOME` |
| `app.scan-excludes` | Regex auf Verzeichnispfade; passende Verzeichnisse werden nicht betreten. `~/Library` ist per Default ausgeschlossen |
| `app.git-fetch` | `true` = vor dem Remote-Vergleich `git fetch` (Netzwerk, langsamer) |
| `app.docker.hosts` | `[user@]host[:pfad][?key=<datei>]`, `.` bzw. kein Pfad = Home-Verzeichnis |
| `?key=<datei>` | Host braucht einen Security-Key: ssh nutzt nur diese Identität und wird nur bei „neu scannen“ abgefragt |
| `app.docker.security-key-on-startup` | `true` = Security-Key-Hosts auch beim Start-Scan abfragen |
| `app.docker.ssh` | ssh-Programm; leer = Homebrew-OpenSSH, falls vorhanden, sonst `ssh` |
| `app.docker.fetch` | `false` = beim Start-Scan nicht per SSH holen, nur `app.docker.compose-cache` lesen. Bei „neu scannen“ entscheidet die Checkbox „fetch docker“ (Default aus) |
| `app.docker.compose-cache` | lokale Kopie der compose-Dateien, Default `./data/compose-cache` |

Jede Einstellung lässt sich auch per Kommandozeile (`--app.git-fetch=true`) oder
Umgebungsvariable (`APP_GITFETCH=true`) setzen.

## Sicherheit

- Von den Docker-Hosts werden **ausschließlich** `*compose*.yml`/`*.yaml` geholt,
  niemals `.env`-Dateien oder Volumes.
- SSH läuft mit `BatchMode=yes`: keine Passwortabfragen; ist ein Host nicht erreichbar,
  bleibt der bisherige Stand im Cache erhalten.
- Die Anwendung ist für den lokalen Gebrauch gedacht und hat keine Anmeldung.

## Lizenz

Apache License 2.0
