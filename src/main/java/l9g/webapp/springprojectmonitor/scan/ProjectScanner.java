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
package l9g.webapp.springprojectmonitor.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import l9g.webapp.springprojectmonitor.config.AppProperties;
import l9g.webapp.springprojectmonitor.config.DockerHost;
import l9g.webapp.springprojectmonitor.model.Project;
import l9g.webapp.springprojectmonitor.scan.RepositoryInspector.Info;
import l9g.webapp.springprojectmonitor.scan.RepositoryInspector.Location;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Java-Variante von rebuild-csv.py --scan: POMs suchen, Repositories prüfen,
 * Docker-Deployments zuordnen. Liefert dieselben Spalten wie die CSV.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectScanner
{
  public static final List<String> FIELDS = List.of(
    "pom_path", "groupId", "artifactId", "project_version", "last_git_tag",
    "spring_boot_parent_version", "bezug", "spring_boot_parent_pom",
    "pom_last_access", "pom_last_modified", "project_last_modified",
    "repo_type", "repo_root", "repo_url", "remote_last_commit", "remote_differs",
    "remote_diff_details", "docker_hosts", "docker_services", "docker_match",
    "jdk_version", "jdk_source");

  private final AppProperties properties;

  public List<Project> scan() throws IOException, InterruptedException
  {
    return scan(false, host -> {});
  }

  /**
   * @param manual        true = vom Nutzer ausgelöst; nur dann werden Hosts mit Security-Key abgefragt
   * @param waitingForKey Hinweis "bitte Security-Key bestätigen" (Host bzw. null danach)
   */
  public List<Project> scan(boolean manual, Consumer<DockerHost> waitingForKey)
    throws IOException, InterruptedException
  {
    Path root = Path.of(properties.scanRoot()).toAbsolutePath().normalize();
    List<Pattern> excludes = properties.scanExcludes().stream().map(Pattern::compile).toList();

    log.info("Suche Spring-Boot-POMs unter {} ...", root);
    List<PomScanner.PomInfo> infos = new PomScanner().scan(root, excludes);
    log.info("{} Spring-Boot-POMs gefunden", infos.size());

    inspectRepositories(infos);

    AppProperties.Docker dockerProperties = properties.docker();
    Path cache = Path.of(dockerProperties.composeCache()).toAbsolutePath().normalize();
    List<DockerHost> hosts = dockerProperties.targets();
    if (dockerProperties.fetch())
    {
      List<DockerHost> toFetch = DockerInspector.fetchOrder(hosts, dockerProperties.includeSecurityKeyHosts(manual));
      hosts.stream()
        .filter(h -> !toFetch.contains(h))
        .forEach(h -> log.info("{} braucht einen Security-Key, beim automatischen Scan nur Cache "
          + "(app.docker.security-key-on-startup: false)", h));
      String ssh = dockerProperties.sshExecutable();
      log.info("ssh-Programm: {}", ssh);
      DockerInspector.fetch(toFetch, cache, ssh, waitingForKey);
    }
    DockerInspector docker = DockerInspector.fromCache(cache, hosts);
    log.info("{} Services von {} aus compose-Cache {} gelesen",
      docker.getServices().size(), dockerProperties.hosts(), cache);
    docker.assign(infos);

    return infos.stream()
      .map(i -> ordered(i.values()))
      .sorted(Comparator.comparing((Map<String, String> v) -> v.getOrDefault("project_last_modified", "")).reversed())
      .map(v -> new Project(id(v.get("pom_path")), v))
      .toList();
  }

  /**
   * Jedes Repository nur einmal prüfen, parallel auf virtuellen Threads.
   */
  private void inspectRepositories(List<PomScanner.PomInfo> infos) throws InterruptedException
  {
    Map<Path, Location> locations = new LinkedHashMap<>();
    for (PomScanner.PomInfo info : infos)
    {
      Location location = RepositoryInspector.locate(Path.of(info.values().get("pom_path")).getParent());
      info.values().put("repo_type", location.type());
      info.values().put("repo_root", location.root() == null ? "" : location.root().toString());
      if (location.root() != null)
      {
        locations.putIfAbsent(location.root(), location);
      }
    }

    RepositoryInspector inspector = new RepositoryInspector(properties.gitFetch());
    log.info("Prüfe {} Repositories{} ...", locations.size(), properties.gitFetch() ? " (mit git fetch)" : "");

    Map<Path, Future<Info>> results = new LinkedHashMap<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor())
    {
      locations.forEach((path, location) -> results.put(path, executor.submit(() -> inspector.inspect(location))));
    }

    for (PomScanner.PomInfo info : infos)
    {
      String repoRoot = info.values().get("repo_root");
      Info repo = Info.EMPTY;
      if (!repoRoot.isEmpty())
      {
        try
        {
          repo = results.get(Path.of(repoRoot)).get();
        }
        catch (Exception e)
        {
          log.warn("Repository {} nicht prüfbar: {}", repoRoot, e.getMessage());
        }
      }
      info.values().put("repo_url", repo.url());
      info.values().put("remote_last_commit", repo.lastCommit());
      info.values().put("remote_differs", repo.differs());
      info.values().put("remote_diff_details", repo.details());
      info.values().put("last_git_tag", repo.lastTag());
    }
  }

  private static Map<String, String> ordered(Map<String, String> values)
  {
    Map<String, String> result = new LinkedHashMap<>();
    FIELDS.forEach(f -> result.put(f, values.getOrDefault(f, "")));
    return result;
  }

  /**
   * Stabile ID aus dem POM-Pfad.
   */
  public static String id(String pomPath)
  {
    try
    {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(pomPath.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 8);
    }
    catch (NoSuchAlgorithmException e)
    {
      throw new IllegalStateException(e);
    }
  }

}
