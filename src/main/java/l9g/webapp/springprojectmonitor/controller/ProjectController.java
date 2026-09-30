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
package l9g.webapp.springprojectmonitor.controller;

import java.util.List;
import l9g.webapp.springprojectmonitor.model.Project;
import l9g.webapp.springprojectmonitor.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequiredArgsConstructor
public class ProjectController
{
  public record Column(String key, String label)
  {
  }

  static final List<Column> COLUMNS = List.of(
    new Column("groupId", "Group-ID"),
    new Column("artifactId", "Artifact-ID"),
    new Column("project_version", "Projektversion"),
    new Column("spring_boot_parent_version", "Spring Boot"),
    new Column("jdk_version", "JDK"),
    new Column("pom_last_modified", "POM zuletzt geändert"));

  private static final String DEFAULT_SORT = "pom_last_modified";

  private final ProjectService projectService;

  @GetMapping("/")
  public String index(
    @RequestParam(name = "sort", defaultValue = DEFAULT_SORT) String requestedSort,
    @RequestParam(name = "dir", defaultValue = "desc") String dir,
    Model model)
  {
    String sort = COLUMNS.stream().anyMatch(c -> c.key().equals(requestedSort))
      ? requestedSort
      : DEFAULT_SORT;
    boolean ascending = "asc".equalsIgnoreCase(dir);

    model.addAttribute("columns", COLUMNS);
    model.addAttribute("projects", projectService.findAll(sort, ascending));
    model.addAttribute("sort", sort);
    model.addAttribute("dir", ascending ? "asc" : "desc");
    model.addAttribute("scanRoot", projectService.getScanRoot());
    model.addAttribute("status", projectService.getStatus());
    model.addAttribute("securityKeyHosts", projectService.getSecurityKeyHosts());
    return "index";
  }

  @PostMapping("/rescan")
  public String rescan()
  {
    projectService.startScan(true);
    return "redirect:/";
  }

  @GetMapping("/project/{id}")
  public String project(@PathVariable String id, Model model)
  {
    Project project = projectService.findById(id)
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Projekt nicht gefunden"));

    model.addAttribute("project", project);
    model.addAttribute("tiles", projectService.tiles(project));
    return "project";
  }

}
