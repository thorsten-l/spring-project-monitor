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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerHostTest
{

  @Test
  void parsesUserHostAndAbsolutePath()
  {
    DockerHost h = DockerHost.parse("root@zserv16:/sonia/dockerdata");

    assertThat(h.user()).isEqualTo("root");
    assertThat(h.host()).isEqualTo("zserv16");
    assertThat(h.sshTarget()).isEqualTo("root@zserv16");
    assertThat(h.displayPath()).isEqualTo("/sonia/dockerdata");
    assertThat(h.shellPath()).isEqualTo("'/sonia/dockerdata'");
  }

  @Test
  void dotMeansHomeDirectory()
  {
    DockerHost h = DockerHost.parse("app@useradm.sonia.de:.");

    assertThat(h.host()).isEqualTo("useradm.sonia.de");
    assertThat(h.displayPath()).isEqualTo("~");
    assertThat(h.shellPath()).isEqualTo("'.'");
  }

  @Test
  void userAndPathAreOptional()
  {
    DockerHost h = DockerHost.parse("ztest6");

    assertThat(h.user()).isNull();
    assertThat(h.sshTarget()).isEqualTo("ztest6");
    assertThat(h.displayPath()).isEqualTo("~");
  }

  @Test
  void relativeAndTildePaths()
  {
    assertThat(DockerHost.parse("a@h:docker").displayPath()).isEqualTo("~/docker");
    assertThat(DockerHost.parse("a@h:~/my dir").shellPath()).isEqualTo("~/'my dir'");
    assertThat(DockerHost.parse("a@h:/x/it's").shellPath()).isEqualTo("'/x/it'\\''s'");
  }

  @Test
  void parsesSecurityKeyOption()
  {
    DockerHost h = DockerHost.parse("app@useradm.ostfalia.de:.?key=~/.ssh/id_ed25519_sk");

    assertThat(h.host()).isEqualTo("useradm.ostfalia.de");
    assertThat(h.path()).isEqualTo(".");
    assertThat(h.needsSecurityKey()).isTrue();
    assertThat(h.securityKey()).isEqualTo("~/.ssh/id_ed25519_sk");
    assertThat(h.securityKeyFile()).isEqualTo(System.getProperty("user.home") + "/.ssh/id_ed25519_sk");
    assertThat(h.toString()).isEqualTo("app@useradm.ostfalia.de:.?key=~/.ssh/id_ed25519_sk");

    assertThat(DockerHost.parse("root@zserv16:/sonia/dockerdata").needsSecurityKey()).isFalse();
  }

  @Test
  void rejectsUnknownOption()
  {
    assertThatThrownBy(() -> DockerHost.parse("a@h:.?sk"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("?key=");
  }

  @Test
  void rejectsMissingHost()
  {
    assertThatThrownBy(() -> DockerHost.parse("root@:/x")).isInstanceOf(IllegalArgumentException.class);
  }

}
