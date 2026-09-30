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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import l9g.webapp.springprojectmonitor.scan.CommandRunner.Result;

/**
 * Ermittelt Repository-Typ, URL und den Abgleich mit dem Remote
 * (entspricht Schritt 3 von rebuild-csv.py).
 */
public class RepositoryInspector
{
  public record Location(String type, Path root)
  {
    public static final Location NONE = new Location("none", null);
  }

  public record Info(String url, String lastCommit, String differs, String details, String lastTag)
  {
    public static final Info EMPTY = new Info("", "", "", "", "");
  }

  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(90);

  private final boolean fetch;

  public RepositoryInspector(boolean fetch)
  {
    this.fetch = fetch;
  }

  /**
   * Erstes Verzeichnis ab {@code directory} aufwärts mit .git, .hg oder .svn.
   */
  public static Location locate(Path directory)
  {
    for (Path p = directory.toAbsolutePath().normalize(); p != null; p = p.getParent())
    {
      if (Files.exists(p.resolve(".git")))
      {
        return new Location("git", p);
      }
      if (Files.exists(p.resolve(".hg")))
      {
        return new Location("hg", p);
      }
      if (Files.exists(p.resolve(".svn")))
      {
        return new Location("svn", p);
      }
    }
    return Location.NONE;
  }

  public Info inspect(Location location)
  {
    return switch (location.type())
    {
      case "git" -> git(location.root());
      case "svn" -> svn(location.root());
      case "hg" -> hg(location.root());
      default -> Info.EMPTY;
    };
  }

  private Info git(Path root)
  {
    String url = run(root, "git", "remote", "get-url", "origin").out();
    if (url.isEmpty())
    {
      String remotes = run(root, "git", "remote").out();
      if (!remotes.isEmpty())
      {
        url = run(root, "git", "remote", "get-url", remotes.split("\\s+")[0]).out();
      }
    }
    if (url.isEmpty())
    {
      return new Info("", "", "unbekannt", "kein Remote konfiguriert", lastGitTag(root));
    }

    List<String> notes = new ArrayList<>();
    boolean fetchFailed = false;
    if (fetch)
    {
      Result r = CommandRunner.run(root, FETCH_TIMEOUT, "git", "fetch", "--quiet", "--all", "--tags");
      if (!r.ok())
      {
        fetchFailed = true;
        notes.add("fetch fehlgeschlagen: " + r.lastErrorLine() + " (Datum = letzter bekannter Remote-Stand)");
      }
    }
    String tag = lastGitTag(root);

    Result upstream = run(root, "git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}");
    if (!upstream.ok())
    {
      upstream = run(root, "git", "rev-parse", "--abbrev-ref", "origin/HEAD");
      if (!upstream.ok())
      {
        notes.add("kein Upstream-Branch");
        return new Info(url, "", "unbekannt", String.join("; ", notes), tag);
      }
      notes.add("kein Upstream, verglichen mit " + upstream.out());
    }
    String ref = upstream.out();

    String date = run(root, "git", "log", "-1", "--format=%cd", "--date=format:%Y-%m-%d %H:%M:%S", ref).out();
    String[] counts = run(root, "git", "rev-list", "--left-right", "--count", "HEAD..." + ref).out().split("\\s+");
    int ahead = counts.length == 2 ? Integer.parseInt(counts[0]) : 0;
    int behind = counts.length == 2 ? Integer.parseInt(counts[1]) : 0;
    int dirty = lines(run(root, "git", "status", "--porcelain").out());

    List<String> parts = differences(ahead, behind, dirty);
    String differs = !parts.isEmpty() ? "ja" : fetchFailed ? "unbekannt" : "nein";
    parts.addAll(notes);
    return new Info(url, date, differs, parts.isEmpty() ? "identisch mit " + ref : String.join("; ", parts), tag);
  }

  private String lastGitTag(Path root)
  {
    String tags = run(root, "git", "tag", "--sort=-creatordate").out();
    return tags.isEmpty() ? "" : tags.split("\\s+")[0];
  }

  private Info svn(Path root)
  {
    String url = run(root, "svn", "info", "--show-item", "url").out();
    String localRev = run(root, "svn", "info", "--show-item", "revision").out();
    int dirty = lines(run(root, "svn", "status", "-q").out());
    if (!fetch)
    {
      return new Info(url, "", "unbekannt", "Remote nicht abgefragt (app.git-fetch: false)", "");
    }
    Result remoteRev = CommandRunner.run(root, FETCH_TIMEOUT,
      "svn", "info", "-r", "HEAD", "--show-item", "last-changed-revision", "--non-interactive", url);
    if (!remoteRev.ok())
    {
      return new Info(url, "", "unbekannt", "Remote nicht erreichbar: " + remoteRev.lastErrorLine(), "");
    }
    String date = CommandRunner.run(root, FETCH_TIMEOUT,
      "svn", "info", "-r", "HEAD", "--show-item", "last-changed-date", "--non-interactive", url).out();
    date = date.replace('T', ' ');
    date = date.length() > 19 ? date.substring(0, 19) : date;

    List<String> parts = new ArrayList<>();
    if (!localRev.isEmpty() && !remoteRev.out().isEmpty()
      && Long.parseLong(remoteRev.out()) > Long.parseLong(localRev))
    {
      parts.add("lokal r" + localRev + ", remote r" + remoteRev.out());
    }
    if (dirty > 0)
    {
      parts.add(dirty + " uncommitted Dateien");
    }
    return new Info(url, date, parts.isEmpty() ? "nein" : "ja",
      parts.isEmpty() ? "identisch (r" + localRev + ")" : String.join("; ", parts), "");
  }

  private Info hg(Path root)
  {
    String url = run(root, "hg", "paths", "default").out();
    String tags = run(root, "hg", "log", "-r", "max(tag() - tag(tip))", "--template", "{tags}").out();
    String tag = tags.isEmpty() ? "" : tags.split("\\s+")[0];
    if (url.isEmpty())
    {
      return new Info("", "", "unbekannt", "kein Remote konfiguriert", tag);
    }
    if (!fetch)
    {
      return new Info(url, "", "unbekannt", "Remote nicht abgefragt (app.git-fetch: false)", tag);
    }
    Result incoming = CommandRunner.run(root, FETCH_TIMEOUT, "hg", "incoming", "-q", "--template", "{node}\n");
    if (incoming.exitCode() != 0 && incoming.exitCode() != 1)
    {
      return new Info(url, "", "unbekannt", "Remote nicht erreichbar: " + incoming.lastErrorLine(), tag);
    }
    int behind = incoming.exitCode() == 0 ? lines(incoming.out()) : 0;
    int ahead = lines(CommandRunner.run(root, FETCH_TIMEOUT, "hg", "outgoing", "-q", "--template", "{node}\n").out());
    String date;
    if (behind > 0)
    {
      date = CommandRunner.run(root, FETCH_TIMEOUT, "hg", "incoming", "-q", "--template", "{date|isodatesec}\n")
        .out().lines().max(String::compareTo).orElse("");
    }
    else
    {
      date = run(root, "hg", "log", "-r", "max(all() - outgoing())", "--template", "{date|isodatesec}").out();
    }
    date = date.length() > 19 ? date.substring(0, 19) : date;
    int dirty = lines(run(root, "hg", "status", "-mard").out());

    List<String> parts = differences(ahead, behind, dirty);
    return new Info(url, date, parts.isEmpty() ? "nein" : "ja",
      parts.isEmpty() ? "identisch" : String.join("; ", parts), tag);
  }

  private static List<String> differences(int ahead, int behind, int dirty)
  {
    List<String> parts = new ArrayList<>();
    if (ahead > 0)
    {
      parts.add(ahead + " lokal voraus");
    }
    if (behind > 0)
    {
      parts.add(behind + " remote voraus");
    }
    if (dirty > 0)
    {
      parts.add(dirty + " uncommitted Dateien");
    }
    return parts;
  }

  private static int lines(String text)
  {
    return text.isBlank() ? 0 : (int) text.lines().count();
  }

  private static Result run(Path root, String... command)
  {
    return CommandRunner.run(root, TIMEOUT, command);
  }

}
