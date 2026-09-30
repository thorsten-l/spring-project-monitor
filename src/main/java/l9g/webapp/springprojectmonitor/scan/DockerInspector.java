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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import l9g.webapp.springprojectmonitor.config.DockerHost;
import l9g.webapp.springprojectmonitor.model.Project;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Ordnet Services aus den lokal gespiegelten compose-Dateien den Projekten zu
 * und bestimmt die Laufzeit-JDK (entspricht Schritt 4 von rebuild-csv.py).
 * Es wird nur der lokale Cache gelesen, kein SSH-Zugriff.
 */
@Slf4j
public class DockerInspector
{
  /**
   * Ein Service; host = Hostname (Cache-Verzeichnis), root = durchsuchtes Verzeichnis für die Anzeige.
   */
  public record ComposeService(String host, String root, String file, String dir, String name,
    String image, String build, List<String> jars)
  {
  }

  public record Match(String artifactId, String how, boolean guessed)
  {
  }

  private record Deployment(String host, String ref, String how, String image,
    String jdk, String jdkGuess)
  {
  }

  private static final Pattern JDK_IMAGE =
    Pattern.compile("(liberica|openjdk|temurin|corretto|zulu|jdk|jre|java)", Pattern.CASE_INSENSITIVE);

  private static final Pattern JAR = Pattern.compile("[\\w./\\-]+\\.jar");

  private static final Pattern VERSION = Pattern.compile("^(\\d+(?:\\.\\d+)*)");

  // -size -1024k statt -1M: GNU find rundet auf ganze MiB und fände nur leere Dateien
  private static final String FETCH_COMMAND = "cd %s && find . -type f \\( -name '*compose*.yml' -o -name '*compose*.yaml' \\)"
    + " -size -1024k -print0 2>/dev/null | tar --null -T - -cf -";

  private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(120);

  // Zeit, um den Security-Key zu berühren bzw. den Finger aufzulegen, plus Übertragung
  private static final Duration SECURITY_KEY_TIMEOUT = Duration.ofSeconds(180);

  private static final Set<String> CANONICAL =
    Set.of("docker-compose.yaml", "docker-compose.yml", "compose.yaml", "compose.yml");

  private final List<ComposeService> services;

  public DockerInspector(List<ComposeService> services)
  {
    this.services = services;
  }

  public static DockerInspector fromCache(Path cache, List<DockerHost> hosts)
  {
    return new DockerInspector(load(cache, hosts));
  }

  public List<ComposeService> getServices()
  {
    return services;
  }

  /**
   * Holt NUR die compose-Dateien (keine .env, keine Volumes) per SSH in den Cache.
   * Schlägt ein Host fehl, bleibt sein bisheriger Cache-Stand erhalten.
   *
   * @param ssh           ssh-Programm (bei Security-Keys ein OpenSSH mit FIDO-Unterstützung)
   * @param waitingForKey wird vor einem Host mit Security-Key mit diesem Host und
   *                      danach mit null aufgerufen (Hinweis in der Oberfläche)
   */
  public static void fetch(List<DockerHost> hosts, Path cache, String ssh, Consumer<DockerHost> waitingForKey)
  {
    for (DockerHost dockerHost : hosts)
    {
      String host = dockerHost.host();
      Path target = cache.resolve(host);
      Path tmp = cache.resolve(host + ".new");
      Path sshErr = null;
      Duration timeout = dockerHost.needsSecurityKey() ? SECURITY_KEY_TIMEOUT : FETCH_TIMEOUT;
      try
      {
        deleteRecursively(tmp);
        Files.createDirectories(tmp);
        sshErr = Files.createTempFile("ssh-" + host, ".err");
        if (dockerHost.needsSecurityKey())
        {
          log.info("Hole compose-Dateien von {} ... bitte Security-Key bestätigen ({})",
            dockerHost, dockerHost.securityKey());
          waitingForKey.accept(dockerHost);
        }
        else
        {
          log.info("Hole compose-Dateien von {} ...", dockerHost);
        }

        List<Process> pipeline = ProcessBuilder.startPipeline(List.of(
          new ProcessBuilder(sshCommand(dockerHost, ssh))
            .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
            .redirectError(sshErr.toFile()),
          new ProcessBuilder("tar", "-xf", "-", "-C", tmp.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)));

        boolean ok = true;
        for (Process p : pipeline)
        {
          if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS))
          {
            p.destroyForcibly();
            ok = false;
          }
          else if (p.exitValue() != 0)
          {
            ok = false;
          }
        }

        if (ok)
        {
          deleteRecursively(target);
          Files.move(tmp, target);
        }
        else
        {
          log.warn("{} nicht abrufbar, verwende vorhandenen Cache. ssh: {}", dockerHost, lastLine(sshErr));
          deleteRecursively(tmp);
        }
      }
      catch (IOException e)
      {
        log.warn("{} nicht abrufbar ({}), verwende vorhandenen Cache", dockerHost, e.getMessage());
      }
      catch (InterruptedException e)
      {
        Thread.currentThread().interrupt();
        return;
      }
      finally
      {
        if (dockerHost.needsSecurityKey())
        {
          waitingForKey.accept(null);
        }
        if (sshErr != null)
        {
          try
          {
            Files.deleteIfExists(sshErr);
          }
          catch (IOException e)
          {
            // Temp-Datei, egal
          }
        }
      }
    }
  }

  /**
   * ssh-Aufruf; bei Security-Key nur die angegebene Identität verwenden, damit ssh
   * nicht vorher alle Default-Schlüssel durchprobiert.
   */
  static List<String> sshCommand(DockerHost host, String ssh)
  {
    List<String> command = new ArrayList<>(List.of(ssh, "-o", "BatchMode=yes", "-o", "ConnectTimeout=10"));
    if (host.needsSecurityKey())
    {
      command.addAll(List.of("-i", host.securityKeyFile(), "-o", "IdentitiesOnly=yes"));
    }
    command.add(host.sshTarget());
    command.add(FETCH_COMMAND.formatted(host.shellPath()));
    return command;
  }

  /**
   * Abruf-Reihenfolge: Hosts mit Security-Key zuletzt, damit die anderen nicht warten;
   * ohne {@code includeSecurityKeyHosts} werden sie ganz ausgelassen.
   */
  public static List<DockerHost> fetchOrder(List<DockerHost> hosts, boolean includeSecurityKeyHosts)
  {
    return hosts.stream()
      .filter(h -> includeSecurityKeyHosts || !h.needsSecurityKey())
      .sorted(Comparator.comparing(DockerHost::needsSecurityKey))
      .toList();
  }

  private static String lastLine(Path file)
  {
    try
    {
      List<String> lines = Files.readAllLines(file).stream().filter(l -> !l.isBlank()).toList();
      return lines.isEmpty() ? "(keine Meldung, ggf. Timeout)" : lines.getLast().strip();
    }
    catch (IOException e)
    {
      return "(keine Meldung)";
    }
  }

  private static void deleteRecursively(Path path) throws IOException
  {
    if (!Files.exists(path))
    {
      return;
    }
    try (Stream<Path> files = Files.walk(path))
    {
      for (Path p : files.sorted(Comparator.reverseOrder()).toList())
      {
        Files.delete(p);
      }
    }
  }

  /**
   * Alle Services aller compose-Dateien unter {@code cache/<host>/} der angegebenen Hosts.
   */
  static List<ComposeService> load(Path cache, List<DockerHost> hosts)
  {
    List<ComposeService> result = new ArrayList<>();
    if (!Files.isDirectory(cache))
    {
      log.warn("compose-Cache {} nicht vorhanden, keine Docker-Zuordnung", cache);
      return result;
    }
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

    for (DockerHost dockerHost : hosts)
    {
      Path hostDir = cache.resolve(dockerHost.host());
      if (!Files.isDirectory(hostDir))
      {
        log.warn("keine compose-Dateien für {} im Cache", dockerHost);
        continue;
      }
      try (Stream<Path> files = Files.walk(hostDir))
      {
        for (Path file : files.filter(DockerInspector::isComposeFile).sorted().toList())
        {
          result.addAll(parse(yaml, dockerHost, hostDir.relativize(file), file));
        }
      }
      catch (IOException e)
      {
        log.warn("compose-Cache {} nicht lesbar: {}", hostDir, e.getMessage());
      }
    }
    return result;
  }

  private static boolean isComposeFile(Path file)
  {
    String name = file.getFileName().toString();
    return Files.isRegularFile(file) && name.contains("compose")
      && (name.endsWith(".yml") || name.endsWith(".yaml"));
  }

  @SuppressWarnings("unchecked")
  private static List<ComposeService> parse(Yaml yaml, DockerHost host, Path relative, Path file)
  {
    List<ComposeService> result = new ArrayList<>();
    Object data;
    try
    {
      data = yaml.load(Files.readString(file));
    }
    catch (Exception e)
    {
      log.warn("compose-Datei {} nicht lesbar: {}", file, e.getMessage());
      return result;
    }
    if (!(data instanceof Map<?, ?> map) || !(map.get("services") instanceof Map<?, ?> svcs))
    {
      return result;
    }
    String dir = relative.getParent() == null ? "." : relative.getParent().toString();

    for (Map.Entry<?, ?> entry : svcs.entrySet())
    {
      Map<String, Object> s = entry.getValue() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
      List<String> jars = new ArrayList<>();
      for (String key : List.of("command", "entrypoint", "volumes"))
      {
        Object value = s.get(key);
        Collection<?> values = value instanceof Collection<?> c ? c : value == null ? List.of() : List.of(value);
        for (Object v : values)
        {
          Matcher m = JAR.matcher(String.valueOf(v));
          while (m.find())
          {
            String jar = Path.of(m.group()).getFileName().toString();
            jars.add(jar.substring(0, jar.length() - ".jar".length()));
          }
        }
      }
      Object build = s.get("build");
      String buildContext = build instanceof Map<?, ?> b ? String.valueOf(b.get("context")) : build == null ? null : build.toString();
      Object image = s.get("image");

      result.add(new ComposeService(host.host(), host.displayPath(), relative.toString(), dir, String.valueOf(entry.getKey()),
        image == null ? "" : image.toString(), buildContext, jars));
    }
    return result;
  }

  /**
   * "bellsoft/liberica-openjdk-alpine:21" -> "21", sonst null.
   */
  static String jdkOfImage(String image)
  {
    int colon = image == null ? -1 : image.lastIndexOf(':');
    if (colon < 0 || !JDK_IMAGE.matcher(image.substring(0, colon)).find())
    {
      return null;
    }
    Matcher m = VERSION.matcher(image.substring(colon + 1));
    return m.find() ? m.group(1) : null;
  }

  static Optional<Match> match(ComposeService svc, Set<String> artifactIds)
  {
    for (String jar : svc.jars())
    {
      for (String a : artifactIds)
      {
        if (jar.equals(a) || jar.matches(Pattern.quote(a) + "-\\d.*"))
        {
          return Optional.of(new Match(a, jar + ".jar", false));
        }
      }
    }
    String image = svc.image();
    if (!image.isEmpty() && jdkOfImage(image) == null && !image.contains("${"))
    {
      String last = image.substring(image.lastIndexOf('/') + 1);
      String name = last.split(":")[0];
      if (artifactIds.contains(name))
      {
        return Optional.of(new Match(name, (svc.build() != null ? "Build aus Quellcode, " : "") + "Image " + last, false));
      }
    }
    if (svc.build() != null && image.isEmpty() && artifactIds.contains(svc.name()))
    {
      return Optional.of(new Match(svc.name(), "vermutet: Service-Name, Build " + svc.build(), true));
    }
    return Optional.empty();
  }

  /**
   * Setzt docker_* und jdk_* in den Projektzeilen.
   */
  public void assign(List<PomScanner.PomInfo> infos)
  {
    Set<String> artifactIds = infos.stream().map(i -> i.values().get("artifactId")).collect(Collectors.toSet());

    // Gruppen je (artifactId, host, Verzeichnis, Service): Varianten zusammenfassen
    Map<List<String>, List<ComposeService>> groups = new TreeMap<>(DockerInspector::compareKeys);
    Map<ComposeService, Match> matches = new LinkedHashMap<>();
    for (ComposeService svc : services)
    {
      match(svc, artifactIds).ifPresent(m ->
      {
        matches.put(svc, m);
        groups.computeIfAbsent(List.of(m.artifactId(), svc.host(), svc.dir(), svc.name()), k -> new ArrayList<>()).add(svc);
      });
    }

    Map<String, List<Deployment>> byArtifact = new LinkedHashMap<>();
    groups.forEach((key, entries) ->
    {
      entries.sort(Comparator
        .comparing((ComposeService e) -> !CANONICAL.contains(Path.of(e.file()).getFileName().toString()))
        .thenComparing(ComposeService::file));
      ComposeService svc = entries.getFirst();
      String how = matches.get(svc).how();
      if (entries.size() > 1)
      {
        how += " (Varianten: " + entries.subList(1, entries.size()).stream()
          .map(e -> Path.of(e.file()).getFileName().toString()).collect(Collectors.joining(", ")) + ")";
      }

      String jdk = jdkOfImage(svc.image());
      String jdkGuess = null;
      if (jdk == null)
      {
        // eigenes Image: JDK aus einer anderen compose-Datei im selben Verzeichnis
        for (ComposeService other : services)
        {
          if (other.host().equals(svc.host()) && other.dir().equals(svc.dir())
            && other.name().equals(svc.name()) && jdkOfImage(other.image()) != null)
          {
            jdk = jdkOfImage(other.image());
            String stem = Path.of(other.file()).getFileName().toString().replaceFirst("\\.ya?ml$", "");
            jdkGuess = stem + ": " + other.image();
            break;
          }
        }
      }
      String ref = svc.host() + ":" + svc.root() + "/" + svc.file() + "#" + svc.name();
      byArtifact.computeIfAbsent(key.getFirst(), k -> new ArrayList<>())
        .add(new Deployment(svc.host(), ref, how, svc.image(), jdk, jdkGuess));
    });

    for (PomScanner.PomInfo info : infos)
    {
      apply(info, byArtifact.getOrDefault(info.values().get("artifactId"), List.of()));
    }
  }

  private static int compareKeys(List<String> a, List<String> b)
  {
    for (int i = 0; i < Math.min(a.size(), b.size()); i++)
    {
      int result = a.get(i).compareTo(b.get(i));
      if (result != 0)
      {
        return result;
      }
    }
    return Integer.compare(a.size(), b.size());
  }

  private static void apply(PomScanner.PomInfo info, List<Deployment> entries)
  {
    Map<String, String> v = info.values();
    String pomJava = info.pomJava();
    String hosts = entries.stream().map(Deployment::host).distinct().collect(Collectors.joining(" "));

    v.put("docker_hosts", hosts);
    v.put("docker_services", entries.stream().map(Deployment::ref).collect(Collectors.joining("; ")));
    v.put("docker_match", entries.stream().map(Deployment::how).distinct().collect(Collectors.joining("; ")));

    List<Deployment> known = entries.stream().filter(e -> e.jdk() != null).toList();
    if (!known.isEmpty())
    {
      Map<String, Set<String>> perImage = new LinkedHashMap<>();
      for (Deployment e : known)
      {
        String label = e.jdkGuess() == null
          ? e.image()
          : "eigenes Image %s, vermutet %s (%s)".formatted(
            e.image().substring(e.image().lastIndexOf('/') + 1), e.jdk(), e.jdkGuess());
        perImage.computeIfAbsent(label, k -> new LinkedHashSet<>()).add(e.host());
      }
      String jdk = known.getFirst().jdk();
      String source = perImage.entrySet().stream()
        .map(e -> "Docker " + String.join("+", e.getValue()) + ": " + e.getKey())
        .collect(Collectors.joining("; ")) + "; pom: " + pomJava;
      if (Project.jdkMajor(jdk) != Project.jdkMajor(pomJava))
      {
        source += " (ABWEICHUNG)";
      }
      v.put("jdk_version", jdk);
      v.put("jdk_source", source);
    }
    else if (!entries.isEmpty())
    {
      v.put("jdk_version", pomJava);
      v.put("jdk_source", "pom (Docker-Laufzeit " + hosts.replace(' ', '/') + " unbekannt, Image aus eigenem Dockerfile)");
    }
    else
    {
      v.put("jdk_version", pomJava);
      v.put("jdk_source", "pom (nicht deployt / Laufzeit unbekannt)");
    }
  }

}
