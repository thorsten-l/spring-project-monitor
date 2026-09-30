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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Sucht Spring-Boot-POMs und liest POM- und Dateisystemangaben
 * (entspricht Schritt 1 und 2 von rebuild-csv.py).
 */
@Slf4j
public class PomScanner
{
  /**
   * Ergebnis je POM: CSV-Spalten plus die Java-Version laut POM.
   */
  public record PomInfo(Map<String, String> values, String pomJava)
  {
  }

  private record BootParent(String version, Path pom)
  {
  }

  private static final Set<String> SKIP_DIRS = Set.of("target", "node_modules", "build", "dist");

  private static final Set<String> SKIP_MODIFIED_DIRS = Set.of("target", "node_modules");

  private static final List<String> JAVA_KEYS = List.of(
    "compiler-plugin.release", "maven.compiler.release", "java.version",
    "compiler-plugin.source", "maven.compiler.source", "maven.compiler.target");

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

  private static final DateTimeFormatter TIME_FORMAT =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

  private static final int MAX_DEPTH = 6;

  // Cache für die Parent-Ketten, jede POM wird pro Scan nur einmal geparst
  private final Map<Path, Optional<Pom>> poms = new HashMap<>();

  public List<PomInfo> scan(Path root, List<Pattern> excludes) throws IOException
  {
    List<PomInfo> result = new ArrayList<>();
    for (Path pomPath : findPoms(root, excludes))
    {
      read(pomPath).ifPresent(result::add);
    }
    return result;
  }

  /**
   * Sucht pom.xml; ausgeschlossene Verzeichnisse werden gar nicht erst betreten
   * (wichtig bei scan-root = $HOME, z. B. ~/Library/CloudStorage).
   */
  static List<Path> findPoms(Path root, List<Pattern> excludes) throws IOException
  {
    List<Path> result = new ArrayList<>();
    Files.walkFileTree(root, new SimpleFileVisitor<>()
    {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
      {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        if (!dir.equals(root) && (name.startsWith(".") || SKIP_DIRS.contains(name)))
        {
          return FileVisitResult.SKIP_SUBTREE;
        }
        // mit abschließendem "/", damit Muster wie "/00Archive/" auch das Verzeichnis selbst treffen
        String dirPath = dir.toString() + "/";
        if (excludes.stream().anyMatch(p -> p.matcher(dirPath).find()))
        {
          return FileVisitResult.SKIP_SUBTREE;
        }
        Path pom = dir.resolve("pom.xml");
        if (Files.isRegularFile(pom))
        {
          result.add(pom);
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException e)
      {
        return FileVisitResult.CONTINUE;
      }
    });
    return result;
  }

  private Optional<PomInfo> read(Path pomPath)
  {
    Optional<BootParent> boot = bootParent(pomPath, 0);
    Optional<Pom> parsed = pom(pomPath);
    if (boot.isEmpty() || parsed.isEmpty())
    {
      return Optional.empty();
    }
    Pom pom = parsed.get();
    boolean direct = boot.get().pom().equals(pomPath);

    Map<String, String> values = new LinkedHashMap<>();
    values.put("pom_path", pomPath.toString());
    values.put("groupId", firstNonNull(pom.text("groupId"), pom.parentText("groupId"), ""));
    values.put("artifactId", firstNonNull(pom.text("artifactId"), ""));
    values.put("project_version", projectVersion(pom));
    values.put("spring_boot_parent_version", boot.get().version());
    values.put("bezug", direct ? "direkt" : "indirekt");
    values.put("spring_boot_parent_pom", direct ? "" : boot.get().pom().toString());

    try
    {
      BasicFileAttributes attrs = Files.readAttributes(pomPath, BasicFileAttributes.class);
      values.put("pom_last_access", format(attrs.lastAccessTime()));
      values.put("pom_last_modified", format(attrs.lastModifiedTime()));
      values.put("project_last_modified", format(projectLastModified(pomPath.getParent())));
    }
    catch (IOException e)
    {
      log.warn("Zeitstempel für {} nicht lesbar: {}", pomPath, e.getMessage());
    }

    return Optional.of(new PomInfo(values, javaVersion(pomPath, boot.get().version())));
  }

  private Optional<Pom> pom(Path path)
  {
    return poms.computeIfAbsent(path, Pom::parse);
  }

  /**
   * Spring-Boot-Version und die POM, die spring-boot-starter-parent als Parent hat.
   */
  private Optional<BootParent> bootParent(Path pomPath, int depth)
  {
    Optional<Pom> pom = pom(pomPath);
    if (pom.isEmpty() || depth > MAX_DEPTH || !pom.get().hasParent())
    {
      return Optional.empty();
    }
    if (pom.get().hasBootParent())
    {
      return Optional.of(new BootParent(pom.get().parentText("version"), pomPath));
    }
    return pom.get().parentPath().flatMap(p -> bootParent(p, depth + 1));
  }

  /**
   * Properties inklusive Parent-Kette, Compiler-Plugin-Konfiguration als compiler-plugin.*
   */
  private Map<String, String> properties(Path pomPath, int depth)
  {
    Optional<Pom> pom = pom(pomPath);
    if (pom.isEmpty() || depth > MAX_DEPTH)
    {
      return new HashMap<>();
    }
    Map<String, String> props = new HashMap<>();
    if (pom.get().hasParent() && !pom.get().hasBootParent())
    {
      pom.get().parentPath().ifPresent(p -> props.putAll(properties(p, depth + 1)));
    }
    props.putAll(pom.get().properties());
    pom.get().compilerPluginConfig().forEach((k, v) -> props.putIfAbsent("compiler-plugin." + k, v));
    return props;
  }

  private String projectVersion(Pom pom)
  {
    String version = pom.text("version");
    if (version == null && pom.hasParent() && !pom.hasBootParent())
    {
      version = pom.parentText("version") + " (vom Parent geerbt)";
    }
    return resolve(version == null ? "" : version, properties(pom.getPath(), 0));
  }

  private String javaVersion(Path pomPath, String bootVersion)
  {
    Map<String, String> props = properties(pomPath, 0);
    for (String key : JAVA_KEYS)
    {
      String value = props.get(key);
      if (value != null && !value.isBlank())
      {
        return resolve(value, props);
      }
    }
    // Default des spring-boot-starter-parent
    return bootVersion.startsWith("1.") || bootVersion.startsWith("2.") ? "1.8" : "17";
  }

  static String resolve(String value, Map<String, String> props)
  {
    for (int i = 0; i < MAX_DEPTH; i++)
    {
      Matcher m = PLACEHOLDER.matcher(value);
      if (!m.find() || !props.containsKey(m.group(1)))
      {
        break;
      }
      value = value.replace(m.group(), props.get(m.group(1)));
    }
    return value;
  }

  /**
   * Jüngste Datei im Projektverzeichnis. Ausgenommen: versteckte Verzeichnisse,
   * target/, node_modules/, .DS_Store sowie *.log und *.jar. Versteckte Dateien
   * wie .gitignore zählen mit (reproduziert die ursprüngliche CSV).
   */
  static FileTime projectLastModified(Path directory) throws IOException
  {
    FileTime[] newest = { FileTime.fromMillis(0) };
    Files.walkFileTree(directory, new SimpleFileVisitor<>()
    {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
      {
        String name = dir.getFileName().toString();
        if (!dir.equals(directory) && (name.startsWith(".") || SKIP_MODIFIED_DIRS.contains(name)))
        {
          return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
      {
        String name = file.getFileName().toString();
        if (name.equals(".DS_Store") || name.endsWith(".log") || name.endsWith(".jar"))
        {
          return FileVisitResult.CONTINUE;
        }
        try
        {
          // folgt Symlinks wie os.stat() im Python-Skript
          FileTime modified = Files.getLastModifiedTime(file);
          if (modified.compareTo(newest[0]) > 0)
          {
            newest[0] = modified;
          }
        }
        catch (IOException e)
        {
          // z. B. defekte Symlinks
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException e)
      {
        return FileVisitResult.CONTINUE;
      }
    });
    return newest[0].toMillis() == 0 ? Files.getLastModifiedTime(directory) : newest[0];
  }

  static String format(FileTime time)
  {
    return TIME_FORMAT.format(time.toInstant());
  }

  @SafeVarargs
  private static <T> T firstNonNull(T... values)
  {
    for (T value : values)
    {
      if (value != null)
      {
        return value;
      }
    }
    return null;
  }

}
