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
package l9g.webapp.springprojectmonitor;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import l9g.webapp.springprojectmonitor.model.Project;
import l9g.webapp.springprojectmonitor.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
  "app.scan-root=src/test/resources/scan-root",
  "app.scan-excludes=/archive/",
  "app.git-fetch=false",
  "app.docker.hosts=root@zserv16:/sonia/dockerdata",
  "app.docker.compose-cache=src/test/resources/compose-cache",
  "app.docker.fetch=false"
})
class SpringProjectMonitorApplicationTests
{
  @Autowired
  private WebApplicationContext context;

  @Autowired
  private ProjectService projectService;

  private MockMvc mvc;

  private Map<String, Project> projects;

  @BeforeEach
  void setUp() throws InterruptedException
  {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();

    // der Scan startet automatisch beim Hochfahren im Hintergrund
    for (int i = 0; i < 100 && projectService.getStatus().running(); i++)
    {
      Thread.sleep(100);
    }
    assertThat(projectService.getStatus().running()).isFalse();
    assertThat(projectService.getStatus().finished()).isNotNull();

    projects = projectService.findAll().stream()
      .collect(Collectors.toMap(p -> p.get("artifactId"), Function.identity()));
  }

  @Test
  void scanFindsOnlySpringBootPomsOutsideExcludesAndTarget()
  {
    assertThat(projects).containsOnlyKeys("alpha-app", "multi-parent", "module-a", "legacy");
  }

  @Test
  void readsDirectAndIndirectBootParent()
  {
    Project parent = projects.get("multi-parent");
    assertThat(parent.get("bezug")).isEqualTo("direkt");
    assertThat(parent.get("project_version")).isEqualTo("2.0.0");

    Project module = projects.get("module-a");
    assertThat(module.get("bezug")).isEqualTo("indirekt");
    assertThat(module.get("spring_boot_parent_version")).isEqualTo("3.5.6");
    assertThat(module.get("spring_boot_parent_pom")).endsWith("scan-root/multi/pom.xml");
    assertThat(module.get("groupId")).isEqualTo("l9g.multi");
    assertThat(module.get("project_version")).isEqualTo("2.0.0 (vom Parent geerbt)");
    // maven.compiler.release=${app.java} aus dem Parent
    assertThat(module.get("jdk_version")).isEqualTo("21");
  }

  @Test
  void javaVersionFallsBackToBootDefault()
  {
    assertThat(projects.get("legacy").get("jdk_version")).isEqualTo("1.8");
  }

  @Test
  void dockerDeploymentAndRuntimeJdk()
  {
    Project alpha = projects.get("alpha-app");
    assertThat(alpha.get("docker_hosts")).isEqualTo("zserv16");
    assertThat(alpha.get("docker_services"))
      .isEqualTo("zserv16:/sonia/dockerdata/alpha/docker-compose.yaml#alpha");
    assertThat(alpha.get("docker_match")).isEqualTo("alpha-app.jar (Varianten: docker-compose-nosec.yaml)");
    assertThat(alpha.get("jdk_version")).isEqualTo("21");
    assertThat(alpha.get("jdk_source"))
      .isEqualTo("Docker zserv16: bellsoft/liberica-openjdk-alpine:21; pom: 25 (ABWEICHUNG)");

    Project legacy = projects.get("legacy");
    assertThat(legacy.get("docker_match")).isEqualTo("vermutet: Service-Name, Build ./");
    assertThat(legacy.get("jdk_source")).startsWith("pom (Docker-Laufzeit zserv16 unbekannt");
  }

  @Test
  void sortsByJdkVersionTreatingOneDotEightAsEight()
  {
    List<String> jdks = projectService.findAll("jdk_version", true).stream()
      .map(p -> p.get("jdk_version"))
      .toList();

    assertThat(jdks).containsExactly("1.8", "21", "21", "21");
  }

  @Test
  void indexShowsTableWithSortArrows() throws Exception
  {
    mvc.perform(get("/").param("sort", "artifactId").param("dir", "asc"))
      .andExpect(status().isOk())
      .andExpect(content().string(containsString("table-striped")))
      .andExpect(content().string(containsString("alpha-app")))
      .andExpect(content().string(containsString("fa-arrow-up")))
      .andExpect(content().string(containsString("neu scannen")))
      // Checkbox "fetch docker" vorhanden und standardmäßig nicht angehakt
      .andExpect(content().string(containsString("name=\"fetchDocker\" value=\"true\"")))
      .andExpect(content().string(not(containsString("checked"))));
  }

  @Test
  void projectPageShowsTiles() throws Exception
  {
    mvc.perform(get("/project/" + projects.get("alpha-app").getId()))
      .andExpect(status().isOk())
      .andExpect(content().string(containsString("Repository")))
      .andExpect(content().string(containsString("Deployment (Docker)")))
      .andExpect(content().string(containsString("zserv16:/sonia/dockerdata/alpha/docker-compose.yaml#alpha")));
  }

  @Test
  void rescanRedirectsToIndex() throws Exception
  {
    mvc.perform(post("/rescan")).andExpect(redirectedUrl("/"));
  }

  @Test
  void unknownProjectIsNotFound() throws Exception
  {
    mvc.perform(get("/project/doesnotexist")).andExpect(status().isNotFound());
  }

}
