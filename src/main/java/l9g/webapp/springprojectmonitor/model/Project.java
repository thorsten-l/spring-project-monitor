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
package l9g.webapp.springprojectmonitor.model;

import java.util.Collections;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Eine Zeile der CSV-Datei, die Spaltennamen sind die Schlüssel.
 */
@Getter
@RequiredArgsConstructor
public class Project
{
  private final String id;

  private final Map<String, String> values;

  public String get(String key)
  {
    String value = values.get(key);
    return value == null ? "" : value;
  }

  public Map<String, String> getValues()
  {
    return Collections.unmodifiableMap(values);
  }

  /**
   * Bootstrap-Farbe für die Spring-Boot-Version: 4.x grün, 3.x gelb, älter rot.
   */
  public String getSpringBootBadge()
  {
    return switch (major(get("spring_boot_parent_version")))
    {
      case 4 -> "text-bg-success";
      case 3 -> "text-bg-warning";
      default -> "text-bg-danger";
    };
  }

  /**
   * Bootstrap-Farbe für die JDK-Version: ab 25 grün, 21 blau, 17 gelb, älter rot.
   */
  public String getJdkBadge()
  {
    int major = jdkMajor(get("jdk_version"));
    if (major >= 25)
    {
      return "text-bg-success";
    }
    if (major >= 21)
    {
      return "text-bg-primary";
    }
    if (major >= 17)
    {
      return "text-bg-warning";
    }
    return "text-bg-danger";
  }

  public static int jdkMajor(String version)
  {
    // "1.8" -> 8
    if (version.startsWith("1."))
    {
      version = version.substring(2);
    }
    return major(version);
  }

  private static int major(String version)
  {
    int end = 0;
    while (end < version.length() && Character.isDigit(version.charAt(end)))
    {
      end++;
    }
    return end == 0 ? 0 : Integer.parseInt(version.substring(0, end));
  }

}
