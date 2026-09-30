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

import java.util.Arrays;
import java.util.List;

/**
 * Ein Feld einer Kachel. Die Darstellung im Template richtet sich nach {@link Kind}.
 */
public record TileField(String key, String label, String value, Kind kind)
{
  public enum Kind
  {
    TEXT, CODE, LINK, YESNO, LIST, BADGES
  }

  public boolean isEmpty()
  {
    return value == null || value.isBlank();
  }

  /**
   * Einzelwerte für LIST (Trenner ";") und BADGES (Trenner Leerzeichen).
   */
  public List<String> getItems()
  {
    if (isEmpty())
    {
      return List.of();
    }
    String separator = kind == Kind.BADGES ? "\\s+" : ";";
    return Arrays.stream(value.split(separator))
      .map(String::trim)
      .filter(s -> !s.isEmpty())
      .toList();
  }

}
