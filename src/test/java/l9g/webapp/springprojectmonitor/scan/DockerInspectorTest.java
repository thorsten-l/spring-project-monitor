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

import java.util.List;
import l9g.webapp.springprojectmonitor.config.DockerHost;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DockerInspectorTest
{
  private static final DockerHost KEY = DockerHost.parse("app@useradm.ostfalia.de:.?key=~/.ssh/id_ed25519_sk");

  private static final DockerHost A = DockerHost.parse("root@zserv15:/sonia/dockerdata");

  private static final DockerHost B = DockerHost.parse("root@zserv16:/sonia/dockerdata");

  @Test
  void automaticScanSkipsSecurityKeyHosts()
  {
    assertThat(DockerInspector.fetchOrder(List.of(KEY, A, B), false)).containsExactly(A, B);
  }

  @Test
  void manualScanFetchesSecurityKeyHostsLast()
  {
    assertThat(DockerInspector.fetchOrder(List.of(KEY, A, B), true)).containsExactly(A, B, KEY);
  }

  @Test
  void sshUsesOnlyTheConfiguredSecurityKey()
  {
    List<String> command = DockerInspector.sshCommand(KEY, "ssh");

    assertThat(command).containsSubsequence("-i", System.getProperty("user.home") + "/.ssh/id_ed25519_sk",
      "-o", "IdentitiesOnly=yes", "app@useradm.ostfalia.de");
    assertThat(command.getLast()).startsWith("cd '.' && find . -type f");
    assertThat(DockerInspector.sshCommand(A, "ssh")).doesNotContain("-i", "IdentitiesOnly=yes");
  }

  @Test
  void sshOverridesInteractiveSettingsFromSshConfig()
  {
    // ~/.ssh/config mit RemoteCommand bzw. RequestTTY yes darf den Abruf nicht stören
    assertThat(DockerInspector.sshCommand(A, "ssh"))
      .containsSubsequence("ssh", "-T", "-o", "RequestTTY=no", "-o", "RemoteCommand=none", "root@zserv15");
  }

}
