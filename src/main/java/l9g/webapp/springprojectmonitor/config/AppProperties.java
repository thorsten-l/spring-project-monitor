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
package l9g.webapp.springprojectmonitor.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Einstellungen unter {@code app.*} in application.yaml.
 */
@ConfigurationProperties("app")
public record AppProperties(
  String scanRoot,
  List<String> scanExcludes,
  boolean gitFetch,
  @DefaultValue Docker docker)
{
  /**
   * Einstellungen unter {@code app.docker.*}; hosts im Format {@code user@host:pfad}.
   */
  public record Docker(
    List<String> hosts,
    @DefaultValue("../compose-cache") String composeCache,
    @DefaultValue("true") boolean fetch,
    @DefaultValue("false") boolean securityKeyOnStartup,
    String ssh)
  {
    // macOS: /usr/bin/ssh (Apple) kann FIDO-Keys (*_sk) nicht selbst nutzen, Homebrew-OpenSSH schon.
    // Aus NetBeans/launchd gestartet fehlt /opt/homebrew/bin im PATH, daher explizit suchen.
    private static final List<String> SSH_CANDIDATES = List.of("/opt/homebrew/bin/ssh", "/usr/local/bin/ssh");

    /**
     * ssh-Programm: app.docker.ssh, sonst Homebrew-OpenSSH, sonst "ssh" aus dem PATH.
     */
    public String sshExecutable()
    {
      if (ssh != null && !ssh.isBlank())
      {
        return ssh;
      }
      return SSH_CANDIDATES.stream()
        .filter(p -> Files.isExecutable(Path.of(p)))
        .findFirst()
        .orElse("ssh");
    }

    /**
     * Ob Hosts mit Security-Key bei diesem Scan abgefragt werden: immer beim manuellen
     * Scan, beim automatischen Start-Scan nur mit security-key-on-startup: true.
     */
    public boolean includeSecurityKeyHosts(boolean manual)
    {
      return manual || securityKeyOnStartup;
    }

    public Docker
    {
      hosts = hosts == null ? List.of() : List.copyOf(hosts);
    }

    public List<DockerHost> targets()
    {
      return hosts.stream().map(DockerHost::parse).toList();
    }
  }

  public AppProperties
  {
    scanExcludes = scanExcludes == null ? List.of() : List.copyOf(scanExcludes);
  }

}
