/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package l9g.webapp.springprojectmonitor.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import l9g.webapp.springprojectmonitor.config.AppProperties;
import l9g.webapp.springprojectmonitor.config.DockerHost;
import l9g.webapp.springprojectmonitor.model.Project;
import l9g.webapp.springprojectmonitor.model.Tile;
import l9g.webapp.springprojectmonitor.model.TileField;
import l9g.webapp.springprojectmonitor.model.TileField.Kind;
import l9g.webapp.springprojectmonitor.scan.ProjectScanner;
import l9g.webapp.springprojectmonitor.scan.ScanStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ProjectService
{
  private record FieldDef(String key, String label, Kind kind)
  {
  }

  private record TileDef(String title, String icon, List<FieldDef> fields)
  {
  }

  private static final Set<String> VERSION_COLUMNS = Set.of(
    "project_version", "spring_boot_parent_version", "jdk_version");

  private static final List<TileDef> TILES = List.of(
    new TileDef("Maven-Projekt", "fa-solid fa-cube", List.of(
      new FieldDef("groupId", "Group-ID", Kind.CODE),
      new FieldDef("artifactId", "Artifact-ID", Kind.CODE),
      new FieldDef("project_version", "Projektversion", Kind.TEXT),
      new FieldDef("pom_path", "POM", Kind.CODE))),
    new TileDef("Spring Boot & JDK", "fa-solid fa-leaf", List.of(
      new FieldDef("spring_boot_parent_version", "Spring-Boot-Version", Kind.TEXT),
      new FieldDef("bezug", "Bezug des Parents", Kind.TEXT),
      new FieldDef("spring_boot_parent_pom", "Parent-POM", Kind.CODE),
      new FieldDef("jdk_version", "JDK-Version", Kind.TEXT),
      new FieldDef("jdk_source", "Quelle der JDK-Version", Kind.TEXT))),
    new TileDef("Zeitstempel", "fa-regular fa-clock", List.of(
      new FieldDef("pom_last_modified", "POM zuletzt geändert", Kind.TEXT),
      new FieldDef("pom_last_access", "POM zuletzt gelesen", Kind.TEXT),
      new FieldDef("project_last_modified", "Projekt zuletzt geändert", Kind.TEXT))),
    new TileDef("Repository", "fa-solid fa-code-branch", List.of(
      new FieldDef("repo_type", "Typ", Kind.TEXT),
      new FieldDef("repo_url", "URL", Kind.LINK),
      new FieldDef("repo_root", "Lokales Verzeichnis", Kind.CODE),
      new FieldDef("last_git_tag", "Letztes Git-Tag", Kind.TEXT))),
    new TileDef("Remote-Abgleich", "fa-solid fa-arrows-rotate", List.of(
      new FieldDef("remote_last_commit", "Letzter Remote-Commit", Kind.TEXT),
      new FieldDef("remote_differs", "Lokal weicht ab", Kind.YESNO),
      new FieldDef("remote_diff_details", "Details", Kind.TEXT))),
    new TileDef("Deployment (Docker)", "fa-brands fa-docker", List.of(
      new FieldDef("docker_hosts", "Hosts", Kind.BADGES),
      new FieldDef("docker_services", "Services", Kind.LIST),
      new FieldDef("docker_match", "Zuordnung", Kind.TEXT)))
  );

  private final ProjectScanner scanner;

  private final AppProperties properties;

  private final AtomicBoolean running = new AtomicBoolean();

  private volatile List<Project> projects = List.of();

  private volatile ScanStatus status = ScanStatus.NEVER;

  public ProjectService(ProjectScanner scanner, AppProperties properties)
  {
    this.scanner = scanner;
    this.properties = properties;
  }

  /**
   * Startet den Scan automatisch, sobald die Anwendung bereit ist.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady()
  {
    startScan(false, properties.docker().fetch());
  }

  /**
   * Startet einen Scan im Hintergrund; false, wenn bereits einer läuft.
   *
   * @param manual      true = vom Nutzer ausgelöst, dann auch Hosts mit Security-Key abfragen
   * @param fetchDocker compose-Dateien per SSH holen; beim Start app.docker.fetch,
   *                    bei "neu scannen" die Checkbox "fetch docker"
   */
  public boolean startScan(boolean manual, boolean fetchDocker)
  {
    if (!running.compareAndSet(false, true))
    {
      return false;
    }
    status = status.start();
    Thread.ofVirtual().name("project-scan").start(() -> doScan(manual, fetchDocker));
    return true;
  }

  private void doScan(boolean manual, boolean fetchDocker)
  {
    Instant start = Instant.now();
    try
    {
      projects = scanner.scan(manual, fetchDocker, host -> status = status.waitingFor(host));
      status = new ScanStatus(false, LocalDateTime.now(), Duration.between(start, Instant.now()), null, null);
      log.info("Scan abgeschlossen: {} Projekte in {} s", projects.size(), status.getSeconds());
    }
    catch (Exception e)
    {
      log.error("Scan fehlgeschlagen", e);
      status = new ScanStatus(false, LocalDateTime.now(), Duration.between(start, Instant.now()), e.toString(), null);
      if (e instanceof InterruptedException)
      {
        Thread.currentThread().interrupt();
      }
    }
    finally
    {
      running.set(false);
    }
  }

  public ScanStatus getStatus()
  {
    return status;
  }

  public String getScanRoot()
  {
    return properties.scanRoot();
  }

  /**
   * Hosts mit Security-Key, die beim automatischen Scan übersprungen werden
   * (leer bei app.docker.security-key-on-startup: true).
   */
  public List<DockerHost> getSecurityKeyHosts()
  {
    if (properties.docker().includeSecurityKeyHosts(false))
    {
      return List.of();
    }
    return properties.docker().targets().stream().filter(DockerHost::needsSecurityKey).toList();
  }

  public List<Project> findAll()
  {
    return projects;
  }

  public List<Project> findAll(String sortKey, boolean ascending)
  {
    Comparator<String> valueComparator = VERSION_COLUMNS.contains(sortKey)
      ? VersionComparator.INSTANCE
      : String.CASE_INSENSITIVE_ORDER;

    Comparator<Project> comparator = Comparator.comparing(
      p -> sortValue(p, sortKey), valueComparator);

    if (!ascending)
    {
      comparator = comparator.reversed();
    }

    // leere Werte immer ans Ende
    Comparator<Project> emptyLast = Comparator.comparing(p -> p.get(sortKey).isBlank());

    return findAll().stream()
      .sorted(emptyLast.thenComparing(comparator))
      .toList();
  }

  public Optional<Project> findById(String id)
  {
    return findAll().stream().filter(p -> p.getId().equals(id)).findFirst();
  }

  /**
   * Gruppiert alle Werte eines Projekts in Kacheln. Unbekannte Spalten landen
   * in der Kachel "Weitere Angaben", damit neue CSV-Spalten nicht verloren gehen.
   */
  public List<Tile> tiles(Project project)
  {
    List<Tile> tiles = new ArrayList<>();
    Set<String> known = new LinkedHashSet<>();

    for (TileDef def : TILES)
    {
      List<TileField> fields = def.fields().stream()
        .map(f ->
        {
          known.add(f.key());
          return new TileField(f.key(), f.label(), project.get(f.key()), f.kind());
        })
        .toList();
      tiles.add(new Tile(def.title(), def.icon(), fields));
    }

    List<TileField> other = project.getValues().keySet().stream()
      .filter(key -> !known.contains(key))
      .map(key -> new TileField(key, key, project.get(key), Kind.TEXT))
      .toList();

    if (!other.isEmpty())
    {
      tiles.add(new Tile("Weitere Angaben", "fa-solid fa-circle-info", other));
    }

    return tiles;
  }

  private static String sortValue(Project project, String key)
  {
    String value = project.get(key);
    if ("jdk_version".equals(key) && value.startsWith("1."))
    {
      // 1.8 wie 8 behandeln, damit es vor 11 einsortiert wird
      return value.substring(2);
    }
    return value;
  }

}
